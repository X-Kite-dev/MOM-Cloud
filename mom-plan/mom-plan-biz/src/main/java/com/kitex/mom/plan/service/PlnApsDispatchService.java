package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnApsMesDispatchDTO;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;

import java.util.List;

/**
 * 智能 APS 正式排程到 MES 的事务 Outbox 服务。
 */
public interface PlnApsDispatchService extends IService<PlnApsMesDispatchOutboxEntity> {

    /**
     * 在 A3 本地事务内冻结载荷并幂等入账，不同步等待 MES。
     */
    PlnApsMesDispatchDTO.DispatchView enqueue(Long caseId, Long selectedTaskId,
                                              String publicationFingerprint);

    /**
     * 查询当前租户工厂内的下达状态与逐项回执。
     */
    PlnApsMesDispatchDTO.DispatchView detail(Long caseId);

    /**
     * 由有权限的计划员将人工处理或死信项重新置为待发送。
     */
    PlnApsMesDispatchDTO.DispatchView retry(Long caseId, String reason);

    /**
     * 在当前组织校验原发布下达单、永久请求号及失败轮次，重新入队后返回原单。
     * 当前正式候选、发送次数或状态发生变化时，要求计划员重新读取。
     */
    PlnApsMesDispatchDTO.DispatchView retryConfirmed(Long caseId, Long outboxId,
            PlnApsMesDispatchDTO.ConfirmedRetryCommand command);

    /**
     * 读取该下达单已落账的逐项回执。快照损坏、成功数矛盾时拒绝展示及续发。
     * 首次投递尚无回执时返回空列表。
     */
    List<PlnApsMesDispatchDTO.ItemReceipt> readReceipts(PlnApsMesDispatchOutboxEntity row);

    /**
     * 在发送事务内锁定并复核发布时冻结的计划、批次和工序联合证据。
     */
    void verifyFrozenItem(Long selectedTaskId, PlnApsMesDispatchDTO.PlanItem item);
}
