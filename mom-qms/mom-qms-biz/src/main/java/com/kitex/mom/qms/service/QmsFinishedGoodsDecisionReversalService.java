package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成品质量判定逆向授权的申请、异岗复核、证据复验和WMS幂等消费。
 */
public interface QmsFinishedGoodsDecisionReversalService
        extends IService<QmsFinishedGoodsDecisionReversalEntity> {
    QmsFinishedGoodsDecisionReversalEntity apply(QmsFinishedGoodsDecisionReversalDTO.ApplyCommand command);

    QmsFinishedGoodsDecisionReversalEntity review(QmsFinishedGoodsDecisionReversalDTO.ReviewCommand command);

    QmsFinishedGoodsDecisionReversalEntity withdraw(Long id,
                                                    QmsFinishedGoodsDecisionReversalDTO.WithdrawCommand command);

    QmsFinishedGoodsDecisionReversalDTO.Detail detail(Long id);

    QmsFinishedGoodsDecisionReversalDTO.Authorization authorization(
            QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query);

    QmsFinishedGoodsDecisionReversalDTO.Authorization consume(
            QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand command);

    QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt receiveExecution(
            QmsFinishedGoodsDecisionReversalExecutionEvent event);

    QmsFinishedGoodsDecisionReversalExecutionEvent.Receipt executionReceipt(String eventId);

    /**
     * 跨租户调度只读取候选身份，不在跳过隔离的上下文中执行状态变更。
     */
    List<QmsFinishedGoodsDecisionReversalEntity> expiredCandidates(LocalDateTime cutoff, int limit);

    /**
     * 在候选所属租户和工厂上下文中锁定并关闭一张已到期授权。
     */
    boolean expireApprovedAuthorization(Long reversalId, LocalDateTime cutoff);
}
