package com.kitex.mom.pms.service;

import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOperationEntity;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;

import java.util.List;

public interface PmsAgentPurchaseStatusOutboxService {
    /**
     * 与采购订单驳回事务同箱保存，禁止因PLAN暂时不可用而丢失状态变化。
     */
    void enqueueRejected(PmsOrderEntity order, String reason);

    /**
     * Agent采购订单审批后取消剩余供给时可靠回写PLAN。
     */
    void enqueueCancelled(PmsOrderEntity order, String reason);

    int dispatchDue(int limit);

    PmsAgentPurchaseStatusOutboxEntity retry(Long outboxId, String requestId, String reason);

    PmsAgentPurchaseStatusOutboxEntity findByOrderId(Long orderId);

    List<PmsAgentPurchaseStatusOperationEntity> operations(Long outboxId);
}
