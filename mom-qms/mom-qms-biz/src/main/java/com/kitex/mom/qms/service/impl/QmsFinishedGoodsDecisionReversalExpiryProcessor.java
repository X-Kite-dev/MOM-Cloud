package com.kitex.mom.qms.service.impl;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalEntity;
import com.kitex.mom.qms.service.QmsFinishedGoodsDecisionReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 成品质量逆向授权到期处理器。
 *
 * <p>候选扫描可跨租户，但每张授权都必须重新进入原租户和原工厂上下文，
 * 由业务服务加锁复验后独立提交；单张失败不会阻塞其他授权。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QmsFinishedGoodsDecisionReversalExpiryProcessor {

    private final QmsFinishedGoodsDecisionReversalService service;

    public int expireDue(int limit) {
        LocalDateTime cutoff = LocalDateTime.now();
        int expired = 0;
        for (QmsFinishedGoodsDecisionReversalEntity candidate
                : service.expiredCandidates(cutoff, limit)) {
            String previousFactory = FactoryContextHolder.getFactoryCode();
            try {
                FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                boolean changed = TenantBroker.applyAs(candidate.getTenantId(), ignored ->
                        service.expireApprovedAuthorization(candidate.getId(), cutoff));
                if (changed) expired++;
            } catch (RuntimeException exception) {
                log.warn("质量逆向授权到期关闭失败，reversalId={}，原因={}",
                        candidate.getId(), safeMessage(exception));
            } finally {
                FactoryContextHolder.setFactoryCode(previousFactory);
            }
        }
        return expired;
    }

    private String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
