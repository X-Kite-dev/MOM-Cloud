package com.kitex.mom.wms.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * WMS 智能体查询统一的实名、租户和实际工厂权限闸门。
 */
@Service
public class WmsAgentFactoryAccessService {

    public Scope requireRead(String requestedFactory) {
        var user = SecurityUtils.getUser();
        Long tenantId = TenantContextHolder.getTenantId();
        String actualFactory = FactoryContextHolder.getFactoryCode();
        boolean validContext = user != null && user.getId() != null && tenantId != null
                && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StrUtil.isNotBlank(actualFactory);
        if (!validContext) {
            throw new CheckedException("WMS智能体查询须实名登录并选择实际工厂，不能跳过租户或工厂隔离");
        }
        if (!actualFactory.equals(requestedFactory)) {
            throw new CheckedException("WMS智能体查询工厂与当前实际工厂不一致");
        }
        return new Scope(tenantId, actualFactory);
    }

    public record Scope(Long tenantId, String factoryCode) {
    }
}
