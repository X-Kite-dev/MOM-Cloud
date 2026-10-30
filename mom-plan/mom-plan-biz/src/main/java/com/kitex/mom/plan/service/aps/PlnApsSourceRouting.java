package com.kitex.mom.plan.service.aps;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;

import java.util.Set;

/** 区分未下达计划求解与已有正式排程的局部重排，禁止相互套用发布流程。 */
public final class PlnApsSourceRouting {
    private static final Set<String> LOCAL_TYPES = Set.of("SHORTAGE_REPLAN", "RESOURCE_EXCEPTION_REPLAN",
            "QUALITY_EXCEPTION_REPLAN", "INSERTION_REPLAN", "EXECUTION_DEVIATION_REPLAN");

    private PlnApsSourceRouting() {
    }

    public static boolean isLocal(PlnApsCaseDTO apsCase) {
        return apsCase != null && LOCAL_TYPES.contains(apsCase.getCaseType());
    }

    /** 通用求解、派生调整及通用审批不能替代原局部重排领域的执行事实核验。 */
    public static void requireStandard(PlnApsCaseDTO apsCase) {
        if (isLocal(apsCase)) throw new CheckedException("此案件请在局部重排工作区按原业务候选和异岗审批流程办理");
    }
}
