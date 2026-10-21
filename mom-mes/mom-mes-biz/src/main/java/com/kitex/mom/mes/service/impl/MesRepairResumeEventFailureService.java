package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.entity.MesRepairResumeOutbox;
import com.kitex.mom.mes.mapper.MesRepairResumeDeliveryOperationMapper;
import com.kitex.mom.mes.mapper.MesRepairResumeOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesRepairResumeEventFailureService {
    private final MesRepairResumeOutboxMapper outboxes;
    private final MesRepairResumeDeliveryOperationMapper operations;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void mark(Long id, RuntimeException error) {
        MesRepairResumeOutbox row = outboxes.selectOne(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getId, id).last("FOR UPDATE"));
        if (row == null || !java.util.Set.of("PENDING", "RETRY_WAIT").contains(row.getStatus())) return;
        int attempts = Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1;
        String before = row.getStatus();
        row.setAttemptCount(attempts);
        row.setStatus(attempts >= 5 ? "DEAD_LETTER" : "RETRY_WAIT");
        row.setNextAttemptTime(attempts >= 5 ? null : LocalDateTime.now().plusSeconds(Math.min(3600, 30L * (1L << Math.min(6, attempts - 1)))));
        row.setLastError(StrUtil.sub(error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "失败"), 0, 500));
        outboxes.updateById(row);
        MesRepairResumeEventService.record(operations, row, "FAILED", before, row.getStatus(), attempts, null, row.getLastError());
    }
}
