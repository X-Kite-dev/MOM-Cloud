package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.mom.plan.dto.PlanInsertionLocalReplanDTO;
import com.kitex.mom.plan.entity.PlnInsertionReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnInsertionReplanPolicyEntity;

/**
 * 紧急插单局部重排领域服务。
 *
 * <p>负责策略版本管理、插单影响评估、多候选选案、APS/MES 正式发布和受控回退。该服务跨越策略、
 * 情景、排程工序和现场执行多个聚合，不对应单一实体，因此不继承 {@code IService}。</p>
 */
public interface PlnInsertionLocalReplanService {

    /**
     * 分页查询插单重排策略。
     */
    Page<PlnInsertionReplanPolicyEntity> policyPage(SearchPage page, PlanInsertionLocalReplanDTO.PolicyQuery query);

    /**
     * 保存策略草稿；草稿不会参与自动评估。
     */
    PlnInsertionReplanPolicyEntity savePolicyDraft(PlanInsertionLocalReplanDTO.PolicyDraftCommand command);

    /**
     * 激活指定策略版本，并结束同范围内冲突的活动版本。
     */
    PlnInsertionReplanPolicyEntity activatePolicy(PlanInsertionLocalReplanDTO.PolicyActionCommand command);

    /**
     * 退役指定策略版本，保留其历史评估证据。
     */
    PlnInsertionReplanPolicyEntity retirePolicy(PlanInsertionLocalReplanDTO.PolicyActionCommand command);

    /**
     * 分页查询紧急插单重排方案。
     */
    Page<PlnInsertionReplanCaseEntity> casePage(SearchPage page, PlanInsertionLocalReplanDTO.CaseQuery query);

    /**
     * 查询插单方案、被挤占计划和工序移动明细。
     */
    PlanInsertionLocalReplanDTO.CaseDetail detail(Long caseId);

    /**
     * 查询一次插单评估情景及按排名排列的全部候选。
     */
    PlanInsertionLocalReplanDTO.Scenario scenario(Long scenarioId);

    /**
     * 校验插单需求、冻结区和 MES 执行事实后生成有限产能候选。
     */
    PlanInsertionLocalReplanDTO.Scenario assess(PlanInsertionLocalReplanDTO.AssessCommand command);

    /**
     * 选择唯一可发布候选，其他候选转为已淘汰。
     */
    PlanInsertionLocalReplanDTO.Scenario select(PlanInsertionLocalReplanDTO.SelectCommand command);

    /**
     * 复核证据指纹后将已选方案发布到 APS 和 MES。
     */
    PlanInsertionLocalReplanDTO.PublishReceipt publish(PlanInsertionLocalReplanDTO.PublishCommand command);

    /**
     * 取消尚未发布的插单评估情景。
     */
    boolean cancel(PlanInsertionLocalReplanDTO.CancelCommand command);

    /**
     * 在正式排程和 MES 未发生后续变化时回退已发布方案。
     */
    PlanInsertionLocalReplanDTO.PublishReceipt rollback(PlanInsertionLocalReplanDTO.RollbackCommand command);
}
