package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 排产引擎服务
 */
public interface PlnSchedulingEngineService {

    /**
     * 执行排产
     *
     * @param runDTO 运行参数
     * @return 是否成功
     */
    Boolean run(PlnSchedulingRunDTO runDTO);

    com.kitex.mom.plan.dto.PlnSchedulingRunInput captureRun(PlnSchedulingRunDTO runDTO);

    PlnSchedulingScenarioDTO runScenario(PlnSchedulingScenarioRunDTO runDTO);

    /**
     * 只规范化原Agent运行参数并计算原领域永久指纹，不查询当前来源、不求解、不写业务。
     */
    String agentPermanentRequestFingerprint(PlnSchedulingScenarioRunDTO runDTO);

    com.kitex.mom.plan.dto.PlnSchedulingScenarioInput captureScenario(PlnSchedulingScenarioRunDTO runDTO);

    PlnSchedulingScenarioDTO getScenario(String scenarioNo);

    PlnSchedulingScenarioDTO getScenarioById(Long scenarioId);

    /**
     * 同一情景内复制父候选、应用专业调整并重验硬约束；失败时整笔事务回滚。
     */
    PlnSchedulingScenarioDTO deriveScenarioCandidate(Long caseId, PlnApsDecisionDTO.DeriveCommand command);

    PlnSchedulingScenarioDTO selectScenarioCandidate(String scenarioNo, PlnSchedulingScenarioSelectDTO selectDTO);

    /**
     * 确认正式排产并执行锁定
     *
     * @param taskId 任务ID
     * @return 是否成功
     */
    Boolean confirmOfficialTaskLock(Long taskId);

    PlnSchedulingBatchLedgerDTO configureBatches(PlnSchedulingBatchConfigureDTO configureDTO);

    /**
     * 按状态分层执行重排
     *
     * @param replanDTO 重排参数
     * @return 是否成功
     */
    Boolean replan(PlnSchedulingReplanDTO replanDTO);

    /**
     * 兼容旧入口，下发到各模块
     *
     * @param plnIssueModuleDTO 下发参数
     * @return 是否成功
     */
    Boolean issueToModule(PlnIssueModuleDTO plnIssueModuleDTO);

    /**
     * 根据产品产率和工作日历，计算从指定开始日起的排产结束日期。
     * 无工艺路线或产率为 0 时降级返回 startDate（单日兜底）。
     *
     * @param productCode 产品编码
     * @param num         订单数量
     * @param startDate   排产开始日期
     * @return 排产结束日期
     */
    LocalDate calcScheduledEndDate(String productCode, BigDecimal num, LocalDate startDate);

    /**
     * 更新排产日期
     *
     * @param list 产品编码
     * @return
     */
    boolean savePlanDateBatch(List<PlnSchedulingTaskDetailEntity> list);
}
