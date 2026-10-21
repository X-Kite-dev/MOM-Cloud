package com.kitex.mom.plan.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** 新Agent领域接口核验实际身份租户和MDM用户工厂授权，不把可伪造请求头当成权限。 */
@Service
public class PlanAgentActualScopeAccess {
    private final RemoteMdmFactoryAccessService factories;

    public PlanAgentActualScopeAccess(RemoteMdmFactoryAccessService factories) {
        this.factories = factories;
    }

    public void require(String requestedFactory, String access) {
        var user = SecurityUtils.getUser();
        if (user == null || user.getId() == null || user.getTenantId() == null
                || !Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId())
                || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || !StringUtils.hasText(requestedFactory) || !Objects.equals(requestedFactory, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("Agent领域接口必须实名、实际身份租户及当前授权工厂，不允许跳过隔离");
        }
        R<Boolean> response = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(requestedFactory).setRequiredPermission(access));
        if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
            throw new CheckedException("当前用户尚无该工厂" + access + "授权，或工厂权限服务不可用");
        }
    }
}
