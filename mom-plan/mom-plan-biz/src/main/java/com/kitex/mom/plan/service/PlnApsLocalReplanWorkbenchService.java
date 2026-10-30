package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnApsLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;

/** 统一展示原领域重排候选与冻结工序，不另建排程或审批事实。 */
public interface PlnApsLocalReplanWorkbenchService {
    /** 核验统一案件、原来源及原岗位权限后，读取原业务情景和完整差异。 */
    PlnApsLocalReplanDTO.Workbench detail(Long apsCaseId);

    /** 明确选择一张计划，复用原资源领域的偏差评估，生成候选后进入其统一案件。 */
    PlnApsCaseDTO assessEffect(Long apsCaseId, PlnApsLocalReplanDTO.EffectAssessCommand command);
}
