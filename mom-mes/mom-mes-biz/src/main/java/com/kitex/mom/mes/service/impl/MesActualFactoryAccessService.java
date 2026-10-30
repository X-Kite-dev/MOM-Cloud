package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * MES业务查询与命令共用的实名、租户和实际工厂上下文校验。
 *
 * <p>模块菜单权限由控制器上的{@code @PreAuthorize}校验；工厂上下文来自请求，
 * 必须在实际身份和租户一致的前提下，由MDM核验所需工厂权限。</p>
 */
@Service
@RequiredArgsConstructor
public class MesActualFactoryAccessService {

    private static final Set<String> ACCESS_LEVELS = Set.of("read", "write", "admin");

    private final RemoteMdmFactoryAccessService factories;

    public String require(String accessLevel) {
        var authentication = SecurityUtils.getAuthentication();
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (authentication == null || !authentication.isAuthenticated()
                || user == null || user.getId() == null || user.getId() <= 0
                || tenant == null || tenant <= 0 || !Objects.equals(user.getTenantId(), tenant)
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(factory)) {
            throw new CheckedException("请以当前租户身份登录并选择实际工厂，禁止跳过隔离");
        }
        if (accessLevel == null || !ACCESS_LEVELS.contains(accessLevel)) {
            throw new CheckedException("工厂访问权限类型无效");
        }
        R<Boolean> authorization;
        try {
            authorization = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                    .setFactoryCode(factory).setRequiredPermission(accessLevel));
        } catch (RuntimeException failure) {
            throw new CheckedException("工厂访问授权暂时无法核验，请稍后重试", failure);
        }
        if (authorization == null || !authorization.isOk() || !Boolean.TRUE.equals(authorization.getData())) {
            throw new CheckedException("当前用户没有所需工厂访问权限，或授权结果不可核验");
        }
        return factory;
    }
}
