package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutConfigMapper;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import com.kitex.mom.mes.service.MesWipTimeoutConfigService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MesWipTimeoutConfigServiceImpl
        extends ServiceImpl<MesWipTimeoutConfigMapper, MesWipTimeoutConfigEntity>
        implements MesWipTimeoutConfigService {

    private static final Set<String> CHANNELS = Set.of("SYSTEM", "DINGDING", "WECHAT");

    private final MesWipTimeoutHandleMapper handleMapper;
    private final MesActualFactoryAccessService access;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesWipTimeoutConfigEntity saveData(MesWipTimeoutConfigEntity config) {
        require(config != null, "在制超时配置不能为空");
        String factory = scope("write");
        bindScope(config, factory);
        config.setId(null);
        config.setCode("WIP-" + IdUtil.getSnowflakeNextIdStr());
        normalizeAndValidate(config);
        stampOperator(config);
        require(save(config), "配置保存失败，未完成新增");
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateData(MesWipTimeoutConfigEntity config) {
        require(config != null && config.getId() != null, "在制超时配置不能为空");
        String factory = scope("write");
        bindScope(config, factory);
        MesWipTimeoutConfigEntity persisted = scoped(config.getId(), factory);
        config.setCode(persisted.getCode());
        config.setFactoryCode(persisted.getFactoryCode());
        normalizeAndValidate(config);
        stampOperator(config);
        // Optional ranges and escalation levels are replaceable, including explicit SQL NULL.
        // An entity update would silently skip cleared fields under the NOT_NULL strategy.
        var update =
                new LambdaUpdateWrapper<MesWipTimeoutConfigEntity>()
                        .eq(MesWipTimeoutConfigEntity::getId, persisted.getId())
                        .eq(MesWipTimeoutConfigEntity::getTenantId, persisted.getTenantId())
                        .eq(MesWipTimeoutConfigEntity::getFactoryCode, persisted.getFactoryCode())
                        .set(MesWipTimeoutConfigEntity::getName, config.getName())
                        .set(MesWipTimeoutConfigEntity::getLineCode, config.getLineCode())
                        .set(MesWipTimeoutConfigEntity::getRouteCode, config.getRouteCode())
                        .set(MesWipTimeoutConfigEntity::getProductCode, config.getProductCode())
                        .set(MesWipTimeoutConfigEntity::getTimeoutSec, config.getTimeoutSec())
                        .set(MesWipTimeoutConfigEntity::getLevel2Sec, config.getLevel2Sec())
                        .set(MesWipTimeoutConfigEntity::getLevel3Sec, config.getLevel3Sec())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel1NotifyRoles,
                                config.getLevel1NotifyRoles())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel2NotifyRoles,
                                config.getLevel2NotifyRoles())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel3NotifyRoles,
                                config.getLevel3NotifyRoles())
                        .set(MesWipTimeoutConfigEntity::getVerifyRoles, config.getVerifyRoles())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel1NotifyChannels,
                                config.getLevel1NotifyChannels())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel2NotifyChannels,
                                config.getLevel2NotifyChannels())
                        .set(
                                MesWipTimeoutConfigEntity::getLevel3NotifyChannels,
                                config.getLevel3NotifyChannels())
                        .set(
                                MesWipTimeoutConfigEntity::getNotifyCooldownMin,
                                config.getNotifyCooldownMin())
                        .set(MesWipTimeoutConfigEntity::getEnabled, config.getEnabled())
                        .set(MesWipTimeoutConfigEntity::getSetPersonCode, config.getSetPersonCode())
                        .set(MesWipTimeoutConfigEntity::getSetPersonName, config.getSetPersonName())
                        .set(
                                MesWipTimeoutConfigEntity::getUpdateBy,
                                SecurityUtils.getUser().getUsername())
                        .set(MesWipTimeoutConfigEntity::getUpdateTime, LocalDateTime.now());
        require(getBaseMapper().update(null, update) == 1, "配置更新失败或已变化");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUnused(Collection<Long> ids) {
        require(ids != null && !ids.isEmpty(), "请选择配置");
        String factory = scope("write");
        java.util.List<Long> found = new java.util.ArrayList<>();
        for (Long id : ids) {
            require(id != null && id > 0, "请选择有效配置主键");
            MesWipTimeoutConfigEntity config = getById(id);
            if (config == null) {
                continue;
            }
            checkOwner(config, factory);
            found.add(id);
            long used =
                    handleMapper.selectCount(
                            new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                                    .eq(
                                            MesWipTimeoutHandleEntity::getTenantId,
                                            TenantContextHolder.getTenantId())
                                    .eq(MesWipTimeoutHandleEntity::getFactoryCode, factory)
                                    .eq(
                                            MesWipTimeoutHandleEntity::getConfigCode,
                                            config.getCode()));
            require(used == 0, "配置[" + config.getName() + "]已产生超时事件，只能停用，不能删除");
        }
        if (!found.isEmpty()) require(removeBatchByIds(found), "配置删除未完成，请重新读取");
    }

    @Override
    public MesWipTimeoutConfigEntity readData(Long id) {
        return scoped(id, scope("read"));
    }

    private String scope(String permission) {
        String factory = access.require(permission);
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && user != null
                        && user.getId() != null
                        && user.getId() > 0
                        && java.util.Objects.equals(tenant, user.getTenantId()),
                "配置维护缺少实名组织范围");
        return factory;
    }

    private void bindScope(MesWipTimeoutConfigEntity c, String factory) {
        require(
                (c.getTenantId() == null
                                || java.util.Objects.equals(
                                        c.getTenantId(), TenantContextHolder.getTenantId()))
                        && (StrUtil.isBlank(c.getFactoryCode())
                                || factory.equals(c.getFactoryCode())),
                "配置与当前租户或实际工厂不一致");
        c.setTenantId(TenantContextHolder.getTenantId());
        c.setFactoryCode(factory);
    }

    private MesWipTimeoutConfigEntity scoped(Long id, String factory) {
        require(id != null && id > 0, "请选择有效配置主键");
        var row = getById(id);
        require(row != null, "配置不存在或不可读取");
        checkOwner(row, factory);
        return row;
    }

    private void checkOwner(MesWipTimeoutConfigEntity row, String factory) {
        require(
                java.util.Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                        && factory.equals(row.getFactoryCode()),
                "配置不属于当前租户/工厂");
    }

    private void normalizeAndValidate(MesWipTimeoutConfigEntity config) {
        config.setName(StrUtil.trim(config.getName()));
        config.setLineCode(StrUtil.blankToDefault(StrUtil.trim(config.getLineCode()), null));
        config.setRouteCode(StrUtil.blankToDefault(StrUtil.trim(config.getRouteCode()), null));
        config.setProductCode(StrUtil.blankToDefault(StrUtil.trim(config.getProductCode()), null));
        config.setLevel1NotifyRoles(normalizeCsv(config.getLevel1NotifyRoles()));
        config.setLevel2NotifyRoles(normalizeCsv(config.getLevel2NotifyRoles()));
        config.setLevel3NotifyRoles(normalizeCsv(config.getLevel3NotifyRoles()));
        config.setVerifyRoles(normalizeCsv(config.getVerifyRoles()));
        config.setLevel1NotifyChannels(normalizeChannels(config.getLevel1NotifyChannels()));
        config.setLevel2NotifyChannels(normalizeChannels(config.getLevel2NotifyChannels()));
        config.setLevel3NotifyChannels(normalizeChannels(config.getLevel3NotifyChannels()));
        config.setNotifyCooldownMin(
                config.getNotifyCooldownMin() == null ? 30 : config.getNotifyCooldownMin());
        config.setEnabled(!Boolean.FALSE.equals(config.getEnabled()));

        require(StrUtil.isNotBlank(config.getName()), "配置名称不能为空");
        require(config.getTimeoutSec() != null && config.getTimeoutSec() > 0, "L1阈值必须大于0秒");
        require(
                config.getLevel2Sec() == null || config.getLevel2Sec() > config.getTimeoutSec(),
                "L2阈值必须大于L1阈值");
        require(config.getLevel3Sec() == null || config.getLevel2Sec() != null, "配置L3阈值前必须配置L2阈值");
        require(
                config.getLevel3Sec() == null || config.getLevel3Sec() > config.getLevel2Sec(),
                "L3阈值必须大于L2阈值");
        require(StrUtil.isNotBlank(config.getLevel1NotifyRoles()), "L1通知角色不能为空");
        require(StrUtil.isNotBlank(config.getVerifyRoles()), "处置验证角色不能为空");
        require(
                config.getLevel2Sec() == null || StrUtil.isNotBlank(config.getLevel2NotifyRoles()),
                "启用L2阈值时必须配置L2通知角色");
        require(
                config.getLevel3Sec() == null || StrUtil.isNotBlank(config.getLevel3NotifyRoles()),
                "启用L3阈值时必须配置L3通知角色");
        require(
                config.getNotifyCooldownMin() >= 1 && config.getNotifyCooldownMin() <= 1440,
                "通知冷却时间必须在1～1440分钟之间");
        LambdaQueryWrapper<MesWipTimeoutConfigEntity> duplicateQuery =
                new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                        .ne(
                                config.getId() != null,
                                MesWipTimeoutConfigEntity::getId,
                                config.getId())
                        .eq(
                                MesWipTimeoutConfigEntity::getTenantId,
                                TenantContextHolder.getTenantId())
                        .eq(MesWipTimeoutConfigEntity::getFactoryCode, config.getFactoryCode())
                        .eq(MesWipTimeoutConfigEntity::getEnabled, Boolean.TRUE);
        equalOrNull(duplicateQuery, MesWipTimeoutConfigEntity::getLineCode, config.getLineCode());
        equalOrNull(duplicateQuery, MesWipTimeoutConfigEntity::getRouteCode, config.getRouteCode());
        equalOrNull(
                duplicateQuery, MesWipTimeoutConfigEntity::getProductCode, config.getProductCode());
        long duplicate = count(duplicateQuery);
        require(
                !Boolean.TRUE.equals(config.getEnabled()) || duplicate == 0,
                "同一产线/工作中心、工序、产品范围只能存在一条启用配置");
    }

    private String normalizeChannels(String value) {
        LinkedHashSet<String> values =
                Arrays.stream(StrUtil.blankToDefault(value, "SYSTEM").split(","))
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        require(CHANNELS.containsAll(values), "通知通道仅支持SYSTEM、DINGDING、WECHAT");
        values.add("SYSTEM");
        long externalCount = values.stream().filter(item -> !"SYSTEM".equals(item)).count();
        require(externalCount <= 1, "每个等级最多选择一个外部通道；站内信固定发送");
        return String.join(",", values);
    }

    private String normalizeCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private void stampOperator(MesWipTimeoutConfigEntity config) {
        MomUser user = SecurityUtils.getUser();
        config.setSetPersonCode(user.getUsername());
        config.setSetPersonName(user.getName());
    }

    private void equalOrNull(
            LambdaQueryWrapper<MesWipTimeoutConfigEntity> query,
            com.baomidou.mybatisplus.core.toolkit.support.SFunction<MesWipTimeoutConfigEntity, ?>
                    column,
            String value) {
        if (value == null) {
            query.isNull(column);
        } else {
            query.eq(column, value);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
