package com.kitex.mom.mdm.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmUserFactoryModelBindEntity;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.mapper.MdmUserFactoryModelBindMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 工艺Agent真实身份租户及MDM用户工厂权限，不接受任意tenant/factory请求头跳过权限。
 */
@Service
public class MdmAgentActualScopeAccess {
    private final MdmFactoryModelMapper factories;
    private final MdmUserFactoryModelBindMapper bindings;

    public MdmAgentActualScopeAccess(MdmFactoryModelMapper factories, MdmUserFactoryModelBindMapper bindings) {
        this.factories = factories;
        this.bindings = bindings;
    }

    public void require(String factory, String access) {
        var user = SecurityUtils.getUser();
        if (user == null || user.getId() == null || user.getTenantId() == null
                || !Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId())
                || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || factory == null || factory.isBlank() || !Objects.equals(factory, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("工艺Agent必须使用实名实际身份租户和授权工厂");
        }
        List<MdmFactoryModelEntity> matched = factories.selectList(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getCode, factory).eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
                .eq(MdmFactoryModelEntity::getStatus, true).last("LIMIT 2"));
        if (matched.size() != 1) {
            throw new CheckedException("实际租户工厂不存在、停用或不唯一");
        }
        List<MdmUserFactoryModelBindEntity> permissions = bindings.selectList(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>()
                .eq(MdmUserFactoryModelBindEntity::getUserId, user.getId())
                .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, matched.get(0).getId())
                .eq(MdmUserFactoryModelBindEntity::getStatus, true));
        FactoryModelPermissionEnum required = FactoryModelPermissionEnum.getByCode(access);
        boolean allowed = required != null && permissions.stream().anyMatch(binding -> {
            FactoryModelPermissionEnum granted = FactoryModelPermissionEnum.getByCode(binding.getPermissionType());
            return granted != null && granted.hasPermission(required);
        });
        if (!allowed) {
            throw new CheckedException("当前用户没有该工厂" + access + "权限，菜单或请求头不能替代用户工厂授权");
        }
    }
}
