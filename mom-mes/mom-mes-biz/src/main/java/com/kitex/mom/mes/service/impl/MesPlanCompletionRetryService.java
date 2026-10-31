package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.mapper.MesPlanCompletionOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 失败账在独立事务保留原事件，不删除后换号发送。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionRetryService {
    private final MesPlanCompletionOutboxMapper outboxes;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void mark(Long id, String factory, RuntimeException failure) {
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        var row = outboxes.locked(tenant, factory, id);
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())) return;
        require(
                row.getAttemptCount() != null
                        && row.getAttemptCount() >= 0
                        && row.getAttemptCount() <= 1_000_000,
                "完工通知失败次数无效");
        int count = Math.min(1_000_000, row.getAttemptCount() + 1);
        long delay = Math.min(1800, 5L * (1L << Math.min(count, 9)));
        String reason =
                failure instanceof CheckedException
                        ? failure.getMessage()
                        : "远端连接或处理异常：" + failure.getClass().getSimpleName();
        String error =
                reason == null ? "完工通知结果未知" : reason.substring(0, Math.min(1000, reason.length()));
        require(
                outboxes.retry(
                        tenant,
                        factory,
                        id,
                        count,
                        LocalDateTime.now().plusSeconds(delay),
                        error)
                        == 1,
                "完工通知失败账保存失败");
    }
}
