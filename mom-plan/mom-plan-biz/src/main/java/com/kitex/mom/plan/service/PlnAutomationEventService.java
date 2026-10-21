package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventPolicyEntity;
import com.kitex.mom.plan.entity.PlnIssueCallbackReceiptEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;

import java.time.LocalDate;

/**
 * 制造自动化事件收件与策略管理服务。
 *
 * <p>该接口只负责将外部变化可靠地转换为事件收件记录，以及维护事件路由策略；事件的异步领取、执行、
 * 重试和失败落账由独立处理器承担，以保持依赖方向单一并避免业务服务循环引用。</p>
 */
public interface PlnAutomationEventService {

    /**
     * 接收通用制造事件；通过来源事件号和请求指纹保证幂等。
     */
    PlanAutomationEventDTO.ReceiveReceipt receive(PlanAutomationEventDTO.ReceiveCommand command);

    /**
     * 将 MES 质量回传转换为质量偏差自动化事件。
     */
    PlanAutomationEventDTO.ReceiveReceipt receiveQualityCallback(PlnIssueCallbackReceiptEntity receipt, PlnIssueEntity issue);

    /**
     * 将 MRP 齐套状态变化转换为指定计划周期的自动化事件。
     */
    PlanAutomationEventDTO.ReceiveReceipt receiveMrpReady(String factoryCode, String aggregateId,
                                                          String cycleType, LocalDate scheduleDate, String scene);

    /**
     * 分页查询事件收件箱及处理状态。
     */
    Page<PlnAutomationEventEntity> eventPage(SearchPage page, PlanAutomationEventDTO.EventQuery query);

    /**
     * 查询事件载荷、处理结果和全部操作履历。
     */
    PlanAutomationEventDTO.EventDetail detail(Long eventInboxId);

    /**
     * 分页查询事件匹配与自动处置策略。
     */
    Page<PlnAutomationEventPolicyEntity> policyPage(SearchPage page, PlanAutomationEventDTO.PolicyQuery query);

    /**
     * 保存事件处置策略草稿。
     */
    PlnAutomationEventPolicyEntity savePolicyDraft(PlanAutomationEventDTO.PolicyDraftCommand command);

    /**
     * 激活事件策略版本，使后续新事件按该版本路由。
     */
    PlnAutomationEventPolicyEntity activatePolicy(PlanAutomationEventDTO.PolicyActionCommand command);

    /**
     * 退役事件策略版本，不影响已固化策略快照的历史事件。
     */
    PlnAutomationEventPolicyEntity retirePolicy(PlanAutomationEventDTO.PolicyActionCommand command);

    /**
     * 将失败或等待重试事件重新置为可处理状态。
     */
    boolean retry(PlanAutomationEventDTO.ManualActionCommand command);

    /**
     * 人工忽略不可再处理的事件，并保存原因与操作者审计。
     */
    boolean ignore(PlanAutomationEventDTO.ManualActionCommand command);
}
