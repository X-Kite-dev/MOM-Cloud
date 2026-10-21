package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.enums.FactoryModelPermissionEnum;
import com.kitex.mom.mdm.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 工厂目录锁是BOM所有修改入口的共同边界；ECN引用的整棵版本图不可原地覆盖。
 */
@Service
@RequiredArgsConstructor
public class MdmBomGovernance {
    private final MdmFactoryModelMapper factoryMapper;
    private final MdmUserFactoryModelBindMapper bindMapper;
    private final MdmBomMapper bomMapper;
    private final MdmBomEcnMapper ecnMapper;
    private final MdmBomEcnBindingMapper bindingMapper;

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public Long actor() {
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null, "BOM工程变更操作须登录");
        return user.getId();
    }

    public String factory(String supplied) {
        String selected = FactoryContextHolder.getFactoryCode();
        String factory = StrUtil.blankToDefault(supplied, selected);
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(factory, selected)), "BOM工厂与当前范围不一致");
        return factory;
    }

    public void access(String factory, String permission, boolean lock) {
        factory = factory(factory);
        Long userId = actor();
        var query = new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getCode, factory).eq(MdmFactoryModelEntity::getType, "FACTORY")
                .eq(MdmFactoryModelEntity::getStatus, true);
        if (lock) {
            require(TransactionSynchronizationManager.isActualTransactionActive(), "BOM目录修改须处于事务中");
            query.last("FOR UPDATE");
        }
        List<MdmFactoryModelEntity> factories = factoryMapper.selectList(query);
        List<MdmUserFactoryModelBindEntity> bindings = null;
        require(factories.size() == 1, "工厂不存在、未启用或编码不唯一");
        try {
            bindings = bindMapper.selectList(new LambdaQueryWrapper<MdmUserFactoryModelBindEntity>()
                    .eq(MdmUserFactoryModelBindEntity::getUserId, userId)
                    .eq(MdmUserFactoryModelBindEntity::getFactoryModelId, factories.get(0).getId())
                    .eq(MdmUserFactoryModelBindEntity::getStatus, true));
        } catch (Exception e) {
            System.out.println("Error occurred while fetching bindings: " + e.getMessage());
        }
        var required = FactoryModelPermissionEnum.getByCode(permission);
        require(required != null && bindings.stream().anyMatch(b -> {
            var granted = FactoryModelPermissionEnum.getByCode(StrUtil.blankToDefault(b.getPermissionType(), "").toLowerCase(Locale.ROOT));
            return granted != null && granted.hasPermission(required);
        }), "缺少该工厂直接绑定的" + permission + "权限");
    }

    public MdmBomEntity editable(Long id) {
        MdmBomEntity before = bomMapper.selectById(id);
        require(before != null, "BOM不存在");
        access(before.getFactoryCode(), "write", true);
        MdmBomEntity row = bomMapper.selectById(id);
        require(row != null && Objects.equals(row.getFactoryCode(), before.getFactoryCode()), "BOM已变化，请刷新");
        require(!Boolean.TRUE.equals(row.getEnabled()) && !Boolean.TRUE.equals(row.getIsDefault())
                && !"RELEASED".equals(row.getReleaseState()), "启用、默认或已发布BOM不可原地修改/删除，请复制新版本走工程变更");
        require(!frozen(id), "BOM已被待审或已发布工程变更引用，不可覆盖版本图");
        return row;
    }

    public boolean frozen(Long bomId) {
        List<Long> ids = bindingMapper.selectList(new LambdaQueryWrapper<MdmBomEcnBindingEntity>()
                .eq(MdmBomEcnBindingEntity::getBomId, bomId)).stream().map(MdmBomEcnBindingEntity::getEcnId).distinct().toList();
        return !ids.isEmpty() && ecnMapper.selectCount(new LambdaQueryWrapper<MdmBomEcnEntity>()
                .in(MdmBomEcnEntity::getId, ids).in(MdmBomEcnEntity::getStatus, "PENDING", "PUBLISHED")) > 0;
    }
}
