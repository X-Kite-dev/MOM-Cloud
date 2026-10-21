package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnMpsPublicationRecoveryDTO;
import com.kitex.mom.plan.entity.PlnRepairResumeObservation;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanOperationEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanPolicyEntity;

import java.util.List;

/**
 * 设备、人员、工装等资源异常的局部重排领域服务。
 *
 * <p>覆盖异常影响评估、维修恢复时间观测、恢复后优化、人工复核、多候选发布及回退。该服务编排多个
 * 业务聚合和外部执行事实，不继承面向单实体 CRUD 的 {@code IService}。</p>
 */
public interface PlnResourceLocalReplanService {

    /**
     * 分页查询资源异常重排策略。
     */
    Page<PlnResourceReplanPolicyEntity> policyPage(SearchPage page, PlanResourceLocalReplanDTO.PolicyQuery query);

    /**
     * 保存资源异常重排策略草稿。
     */
    PlnResourceReplanPolicyEntity savePolicyDraft(PlanResourceLocalReplanDTO.PolicyDraftCommand command);

    /**
     * 激活指定资源异常重排策略版本。
     */
    PlnResourceReplanPolicyEntity activatePolicy(PlanResourceLocalReplanDTO.PolicyActionCommand command);

    /**
     * 退役指定资源异常重排策略版本。
     */
    PlnResourceReplanPolicyEntity retirePolicy(PlanResourceLocalReplanDTO.PolicyActionCommand command);

    /**
     * 分页查询资源异常局部重排方案。
     */
    Page<PlnResourceReplanCaseEntity> casePage(SearchPage page, PlanResourceLocalReplanDTO.CaseQuery query);

    /**
     * 查询资源异常方案、候选指标和工序差异。
     */
    PlanResourceLocalReplanDTO.CaseDetail detail(Long caseId);

    /**
     * 查询一次资源异常重排情景及其全部候选。
     */
    PlanResourceLocalReplanDTO.Scenario scenario(Long scenarioId);

    /**
     * 根据异常资源、影响时窗和 MES 执行事实生成重排候选。
     */
    PlanResourceLocalReplanDTO.Scenario assess(PlanResourceLocalReplanDTO.AssessCommand command);

    /**
     * 根据已发布排程的MES执行偏差效果证据生成局部重排候选。
     *
     * <p>只接受效果回评中被确定性归类为 {@code LOCAL_REPLAN_CANDIDATE} 的未开工可移动工序，
     * 已开工、已完工、映射缺失或现场阻断工序均不能通过该入口改写。</p>
     */
    PlanResourceLocalReplanDTO.Scenario assessExecutionDeviation(
            PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command);

    /** 业务MPS来源采用全部工序实时证据；原始数量不改，保留既有三候选和A3发布链。 */
    PlanResourceLocalReplanDTO.Scenario assessMpsRemaining(
            PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command, int toleranceMinutes);

    /**
     * 根据维修恢复证据重新优化尚未发布的资源异常候选。
     */
    PlanResourceLocalReplanDTO.Scenario assessRecovery(PlanResourceLocalReplanDTO.RecoveryOptimizeCommand command);

    /**
     * 选择唯一可发布的资源异常重排候选。
     */
    PlanResourceLocalReplanDTO.Scenario select(PlanResourceLocalReplanDTO.SelectCommand command);

    /**
     * 复核资源、排程和 MES 指纹后正式发布已选方案。
     */
    PlanResourceLocalReplanDTO.PublishReceipt publish(PlanResourceLocalReplanDTO.PublishCommand command);

    /** 只读核对MPS原发布链；恢复仍须原号、原实名发布人和独立A3权限。 */
    PlnMpsPublicationRecoveryDTO mpsPublicationRecovery(Long caseId);

    /**
     * 取消尚未发布的资源异常重排情景。
     */
    boolean cancel(PlanResourceLocalReplanDTO.CancelCommand command);

    /**
     * 在现场执行未继续推进时受控回退已发布方案。
     */
    PlanResourceLocalReplanDTO.PublishReceipt rollback(PlanResourceLocalReplanDTO.RollbackCommand command);

    /**
     * 生成维修恢复人工复核预览，不改变正式方案状态。
     */
    PlanResourceLocalReplanDTO.RecoveryReviewPreview recoveryReviewPreview(Long caseId);

    /**
     * 查询方案关联的维修恢复时间观测记录。
     */
    List<PlnRepairResumeObservation> resumeObservations(Long caseId);

    /**
     * 记录人工恢复复核结论，并保留操作者、原因和证据快照。
     */
    PlnResourceReplanOperationEntity decideRecoveryReview(PlanResourceLocalReplanDTO.RecoveryReviewCommand command);
}
