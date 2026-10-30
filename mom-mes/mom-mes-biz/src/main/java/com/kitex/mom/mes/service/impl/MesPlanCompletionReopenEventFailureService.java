package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOperationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 单独事务保存原事件失败账，不删除或改变原载荷。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventFailureService {
    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void mark(Long id, RuntimeException error) {
        String factory = FactoryContextHolder.getFactoryCode();
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(id != null && id > 0, "反向完成原件主键无效");
        var row = outboxMapper.locked(tenant, factory, id);
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())) return;
        require(
                row.getAttemptCount() != null
                        && row.getAttemptCount() >= 0
                        && row.getAttemptCount() < 100,
                "反向完成失败次数无效");
        int attempt = row.getAttemptCount() + 1;
        String before = row.getStatus();
        String state = attempt >= 5 ? "DEAD_LETTER" : "RETRY_WAIT";
        LocalDateTime next =
                attempt >= 5
                        ? null
                        : LocalDateTime.now()
                        .plusSeconds(
                                Math.min(3600, 30L * (1L << Math.min(6, attempt - 1))));
        String message =
                error instanceof CheckedException
                        ? error.getMessage()
                        : "远端连接或处理异常：" + error.getClass().getSimpleName();
        if (message == null) message = "PLAN反向完成收件结果未知";
        message = message.substring(0, Math.min(500, message.length()));
        require(
                outboxMapper.state(
                        tenant,
                        factory,
                        id,
                        before,
                        state,
                        attempt,
                        next,
                        row.getDeliveredTime(),
                        row.getPlanInboxId(),
                        row.getPlanEventNo(),
                        row.getPlanEventStatus(),
                        message,
                        row.getRetryRequestId())
                        == 1,
                "反向完成失败账保存失败");
        MesPlanCompletionReopenEventService.record(
                operationMapper, row, "FAILED", before, state, attempt, null, message);
    }
}
