package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryPlanningPolicyService;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 工厂计划权威解析实现；所有异常均按正式计划故障关闭处理。 */
@Service
@RequiredArgsConstructor
public class PlnFactoryPlanningAuthorityServiceImpl implements PlnFactoryPlanningAuthorityService {

    private final RemoteMdmFactoryPlanningPolicyService remotePolicyService;

    @Override
    public MdmFactoryPlanningPolicyDTO requireCurrentPolicy(String requestedFactoryCode) {
        Long tenantId = TenantContextHolder.getTenantId();
        String contextFactoryCode = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        String factoryCode = StrUtil.trim(requestedFactoryCode);
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip(),
                "正式计划动作必须在实际租户上下文执行");
        require(!Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(contextFactoryCode),
                "正式计划动作必须选择实际工厂");
        require(StrUtil.isBlank(factoryCode) || Objects.equals(contextFactoryCode, factoryCode),
                "计划业务工厂与当前工厂上下文不一致");

        R<MdmFactoryPlanningPolicyDTO> response;
        try {
            response = remotePolicyService.active(contextFactoryCode);
        } catch (RuntimeException ex) {
            throw new CheckedException("MDM计划权威不可读取，禁止执行正式计划写动作");
        }
        require(response != null && response.isOk() && response.getData() != null,
                "当前工厂尚未发布可核验的计划协同策略");
        MdmFactoryPlanningPolicyDTO policy = response.getData();
        require(Objects.equals(contextFactoryCode, policy.getFactoryCode()),
                "MDM返回的计划权威不属于当前工厂");
        require("ACTIVE".equals(policy.getStatus()) && policy.getVersionNo() != null
                        && policy.getVersionNo() > 0 && isSha256(policy.getContentFingerprint()),
                "当前工厂计划权威状态、版本或摘要不合法");
        return policy;
    }

    @Override
    public MdmFactoryPlanningPolicyDTO revalidate(MdmFactoryPlanningPolicyDTO expected) {
        require(expected != null, "缺少计划写动作冻结的工厂权威");
        MdmFactoryPlanningPolicyDTO current = requireCurrentPolicy(expected.getFactoryCode());
        require(Objects.equals(current.getId(), expected.getId())
                        && Objects.equals(current.getVersionNo(), expected.getVersionNo())
                        && Objects.equals(current.getContentFingerprint(), expected.getContentFingerprint()),
                "工厂计划权威已变化，本次正式写动作失效，请刷新后重试");
        return current;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
