package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * MES业务查询与命令共用的实名、租户和实际工厂上下文校验。
 *
 * <p>模块菜单权限由控制器上的{@code @PreAuthorize}校验；这里不再重复访问MDM用户—工厂绑定，
 * {@code factory-code}即认证链路确定并透传的当前授权工厂。</p>
 */
@Service
public class MesActualFactoryAccessService {

    public String require(String accessLevel) {
        var user = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        if (user == null || user.getId() == null || TenantContextHolder.getTenantId() == null
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(factory)) {
            throw new CheckedException("请登录并选择实际工厂，禁止跳过隔离");
        }
        return factory;
    }
}
