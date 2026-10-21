package com.kitex.mom.plan.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PLAN 正式计划写动作门禁。
 *
 * <p>仿真、查询和诊断不得调用本门禁冒充正式结果；MRP、替代批准、合同发布等写动作
 * 必须先冻结策略，并在提交前调用 {@link #revalidate(MdmFactoryPlanningPolicyDTO)}。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnFormalPlanningGate {

    private final PlnFactoryPlanningAuthorityService authorityService;

    /** MOM 本地正式 MRP 只允许 MOM_MRP 工厂执行。 */
    public MdmFactoryPlanningPolicyDTO requireMomMrp(String factoryCode) {
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(factoryCode);
        require("MOM_MRP".equals(policy.getMrpAuthority()),
                "当前工厂以ERP为正式MRP权威；请导入ERP已发布计划，MOM仅可执行仿真与齐套诊断");
        return policy;
    }

    /** MOM 正式替代料批准只允许 MOM_APPROVAL 边界。 */
    public MdmFactoryPlanningPolicyDTO requireMomSubstituteApproval(String factoryCode) {
        MdmFactoryPlanningPolicyDTO policy = authorityService.requireCurrentPolicy(factoryCode);
        require("MOM_APPROVAL".equals(policy.getSubstituteGovernanceMode()),
                "当前工厂替代料由ERP冻结或授权包治理，MOM不能形成新的正式替代料批准");
        return policy;
    }

    /** 正式写动作提交前重新核对同一策略版本和摘要。 */
    public void revalidate(MdmFactoryPlanningPolicyDTO expected) {
        authorityService.revalidate(expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
