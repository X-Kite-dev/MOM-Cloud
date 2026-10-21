package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOperationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/** 在独立事务中保存完工重新打开事件的退避或死信结果。 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventFailureService {

    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void mark(Long id, RuntimeException error) {
        MesPlanCompletionReopenOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getId, id).last("FOR UPDATE"));
        if (row == null || !Set.of("PENDING", "RETRY_WAIT").contains(row.getStatus())) return;
        int attempts = Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1;
        String before = row.getStatus();
        row.setAttemptCount(attempts).setStatus(attempts >= 5 ? "DEAD_LETTER" : "RETRY_WAIT")
                .setNextAttemptTime(attempts >= 5 ? null : LocalDateTime.now()
                        .plusSeconds(Math.min(3600, 30L * (1L << Math.min(6, attempts - 1)))))
                .setLastError(StrUtil.sub(error.getClass().getSimpleName() + ": "
                        + Objects.toString(error.getMessage(), "失败"), 0, 500));
        if (outboxMapper.updateById(row) != 1) {
            throw new IllegalStateException("MES完工重新打开事件失败状态保存失败");
        }
        MesPlanCompletionReopenEventService.record(operationMapper, row, "FAILED", before,
                row.getStatus(), attempts, null, row.getLastError());
    }
}
