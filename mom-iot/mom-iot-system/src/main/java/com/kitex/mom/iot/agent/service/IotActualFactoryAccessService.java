package com.kitex.mom.iot.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import org.springframework.stereotype.Service;

/** IoT Agent 查询共用的实名、租户和实际工厂上下文校验。 */
@Service
public class IotActualFactoryAccessService {

    /**
     * 返回认证链路确定并透传的当前工厂。
     *
     * <p>菜单权限由控制器校验；这里验证用户、租户和 {@code factory-code} 实际范围，
     * 不把额外的 MDM 直接绑定查询作为第二套授权来源。</p>
     */
    public String require(String accessLevel) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        if (user == null || user.getId() == null || TenantContextHolder.getTenantId() == null
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(factory)) {
            throw new CheckedException("请登录并选择实际工厂，禁止跳过IoT数据隔离");
        }
        return factory;
    }

    public Long tenantId() {
        require("read");
        return TenantContextHolder.getTenantId();
    }
}
