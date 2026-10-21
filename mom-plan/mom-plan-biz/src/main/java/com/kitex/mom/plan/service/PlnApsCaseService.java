package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.dto.PlnApsCaseCommand;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnFormalPlanContractDTO;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;

/**
 * 智能 APS 案件领域服务。
 *
 * <p>通用 CRUD 不用于改变案件状态；所有生命周期变化必须通过显式领域方法完成。</p>
 */
public interface PlnApsCaseService extends IService<PlnApsCaseEntity> {

    /** 创建或幂等复用案件。 */
    PlnApsCaseDTO launch(PlnApsCaseCommand.Launch command);

    /** 查询当前工厂内的案件详情。 */
    PlnApsCaseDTO detail(Long caseId);

    /** 分页查询当前工厂案件。 */
    Page<PlnApsCaseDTO> page(SearchPage page, PlnApsCaseCommand.Query query);

    /** 按显式动作推进案件。 */
    PlnApsCaseDTO advance(Long caseId, PlnApsCaseCommand.Advance command);

    /** 取消尚未产生正式发布结果的案件。 */
    PlnApsCaseDTO cancel(Long caseId, PlnApsCaseCommand.Cancel command);

    /**
     * 绑定服务端生成的资源快照，并依据预检结论推进准备阶段。
     * 该入口仅供 APS 领域服务调用，不接受浏览器提交的状态或资源指纹。
     */
    PlnApsCaseDTO bindResourceSnapshot(Long caseId, Long snapshotId, String resourceFingerprint,
                                      String readinessStatus, String requestId,
                                      String expectedCaseFingerprint);

    /** 将现有 APS 求解产生的唯一情景绑定案件，并完成计算阶段。 */
    PlnApsCaseDTO bindScenarioResult(Long caseId, Long scenarioId, String requestId,
                                    String expectedCaseFingerprint, String reason);

    /** 将经过现有 APS 可行性复核的 A2 候选绑定案件。 */
    PlnApsCaseDTO bindSelectedCandidate(Long caseId, Long selectedTaskId, String requestId,
                                       String expectedCaseFingerprint, String reason);

    /** 在 A3 前冻结服务端复核过的当前工厂策略与唯一正式计划合同。 */
    PlnApsCaseDTO bindFormalPlanContract(Long caseId, MdmFactoryPlanningPolicyDTO policy,
                                         PlnFormalPlanContractDTO contract, String requestId,
                                         String expectedCaseFingerprint, String reason);

    /** 在现有 APS 正式锁定成功后完成案件 A3 发布。 */
    PlnApsCaseDTO completePublish(Long caseId, String requestId,
                                 String expectedCaseFingerprint, String reason);

    /**
     * 记录事务 Outbox 的下达汇总状态并推进执行阶段。
     *
     * <p>该方法只供 PLAN 模块内的下达服务和工作器调用，不对浏览器暴露。调用方必须已经建立明确的
     * 租户与工厂上下文；状态由服务端 Outbox 回执推导，不能接收前端自报状态。</p>
     */
    PlnApsCaseDTO recordDispatchStatus(Long caseId, String dispatchStatus,
                                       String requestId, String reason);
}
