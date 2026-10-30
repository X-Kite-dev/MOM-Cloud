package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.entity.*;

/**
 * 质量异常局部重排领域服务。
 *
 * <p>将 MES 报工/检验回传形成的质量偏差转化为可评估事件，覆盖补产需求、重排策略、多候选方案、
 * 正式发布与回退。该服务是跨聚合编排器，不对应单表 CRUD。</p>
 */
public interface PlnQualityLocalReplanService {

    /**
     * 接收计划回传并固化质量偏差事实；该入口需保持幂等。
     */
    void captureCallback(PlnIssueCallbackReceiptEntity receipt, PlnIssueEntity issue);

    /**
     * 使用最新回传事实重新计算质量偏差及重排触发结论。
     */
    PlnQualityDeviationEvaluationEntity reevaluate(PlanQualityLocalReplanDTO.ReevaluateCommand command);

    /**
     * 分页查询质量重排策略。
     */
    Page<PlnQualityReplanPolicyEntity> policyPage(SearchPage page, PlanQualityLocalReplanDTO.PolicyQuery query);

    /**
     * 保存质量重排策略草稿。
     */
    PlnQualityReplanPolicyEntity savePolicyDraft(PlanQualityLocalReplanDTO.PolicyDraftCommand command);

    /**
     * 激活指定质量重排策略版本。
     */
    PlnQualityReplanPolicyEntity activatePolicy(PlanQualityLocalReplanDTO.PolicyActionCommand command);

    /**
     * 退役指定质量重排策略版本。
     */
    PlnQualityReplanPolicyEntity retirePolicy(PlanQualityLocalReplanDTO.PolicyActionCommand command);

    /**
     * 分页查询质量偏差评估记录。
     */
    Page<PlnQualityDeviationEvaluationEntity> evaluationPage(SearchPage page, PlanQualityLocalReplanDTO.EvaluationQuery query);

    /**
     * 分页查询质量局部重排方案。
     */
    Page<PlnQualityReplanCaseEntity> casePage(SearchPage page, PlanQualityLocalReplanDTO.CaseQuery query);

    /**
     * 查询质量重排方案、候选指标和工序差异。
     */
    PlanQualityLocalReplanDTO.CaseDetail detail(Long caseId);

    /**
     * 分页查询质量补产需求及其审批状态。
     */
    Page<PlnQualitySupplementDemandEntity> supplementPage(SearchPage page,
                                                          PlanQualityLocalReplanDTO.SupplementQuery query);

    /**
     * 提交补产需求，并固化不合格数量和来源证据。
     */
    PlnQualitySupplementDemandEntity submitSupplement(PlanQualityLocalReplanDTO.SupplementSubmitCommand command);

    /**
     * 审核补产需求；审核结论进入后续重排触发依据。
     */
    PlnQualitySupplementDemandEntity reviewSupplement(PlanQualityLocalReplanDTO.SupplementReviewCommand command);

    PlnQualitySupplementDemandEntity planSupplement(PlanQualityLocalReplanDTO.SupplementPlanCommand command);

    /**
     * 取消尚未形成正式排程影响的补产需求。
     */
    PlnQualitySupplementDemandEntity cancelSupplement(PlanQualityLocalReplanDTO.SupplementCancelCommand command);

    /**
     * 查询一次质量重排情景及其全部候选。
     */
    PlanQualityLocalReplanDTO.Scenario scenario(Long scenarioId);

    /**
     * 根据质量偏差、补产需求和现有执行事实生成重排候选。
     */
    PlanQualityLocalReplanDTO.Scenario assess(PlanQualityLocalReplanDTO.AssessCommand command);

    /**
     * 选择唯一可发布的质量重排候选。
     */
    PlanQualityLocalReplanDTO.Scenario select(PlanQualityLocalReplanDTO.SelectCommand command);

    /**
     * 复核证据指纹后将已选方案发布到 APS 和 MES。
     */
    PlanQualityLocalReplanDTO.PublishReceipt publish(PlanQualityLocalReplanDTO.PublishCommand command);

    /**
     * 取消尚未发布的质量重排情景。
     */
    boolean cancel(PlanQualityLocalReplanDTO.CancelCommand command);

    /**
     * 在无后续执行冲突时受控回退已发布的质量重排方案。
     */
    PlanQualityLocalReplanDTO.PublishReceipt rollback(PlanQualityLocalReplanDTO.RollbackCommand command);
}
