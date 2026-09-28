package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
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

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MesWipTimeoutConfigServiceImpl extends ServiceImpl<MesWipTimeoutConfigMapper, MesWipTimeoutConfigEntity>
        implements MesWipTimeoutConfigService {

    private static final Set<String> CHANNELS = Set.of("SYSTEM", "DINGDING", "WECHAT");

    private final MesWipTimeoutHandleMapper handleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesWipTimeoutConfigEntity saveData(MesWipTimeoutConfigEntity config) {
        require(config != null, "在制超时配置不能为空");
        config.setId(null);
        config.setCode("WIP-" + IdUtil.getSnowflakeNextIdStr());
        normalizeAndValidate(config);
        stampOperator(config);
        save(config);
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateData(MesWipTimeoutConfigEntity config) {
        require(config != null && config.getId() != null, "在制超时配置不能为空");
        MesWipTimeoutConfigEntity persisted = getById(config.getId());
        require(persisted != null, "在制超时配置不存在");
        config.setCode(persisted.getCode());
        config.setFactoryCode(persisted.getFactoryCode());
        normalizeAndValidate(config);
        stampOperator(config);
        updateById(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUnused(Collection<Long> ids) {
        require(ids != null && !ids.isEmpty(), "请选择配置");
        for (Long id : ids) {
            MesWipTimeoutConfigEntity config = getById(id);
            if (config == null) {
                continue;
            }
            long used = handleMapper.selectCount(new LambdaQueryWrapper<MesWipTimeoutHandleEntity>()
                    .eq(MesWipTimeoutHandleEntity::getConfigCode, config.getCode()));
            require(used == 0, "配置[" + config.getName() + "]已产生超时事件，只能停用，不能删除");
        }
        removeBatchByIds(ids);
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
        config.setNotifyCooldownMin(config.getNotifyCooldownMin() == null ? 30 : config.getNotifyCooldownMin());
        config.setEnabled(!Boolean.FALSE.equals(config.getEnabled()));

        require(StrUtil.isNotBlank(config.getName()), "配置名称不能为空");
        require(config.getTimeoutSec() != null && config.getTimeoutSec() > 0, "L1阈值必须大于0秒");
        require(config.getLevel2Sec() == null || config.getLevel2Sec() > config.getTimeoutSec(),
                "L2阈值必须大于L1阈值");
        require(config.getLevel3Sec() == null || config.getLevel2Sec() != null,
                "配置L3阈值前必须配置L2阈值");
        require(config.getLevel3Sec() == null || config.getLevel3Sec() > config.getLevel2Sec(),
                "L3阈值必须大于L2阈值");
        require(StrUtil.isNotBlank(config.getLevel1NotifyRoles()), "L1通知角色不能为空");
        require(StrUtil.isNotBlank(config.getVerifyRoles()), "处置验证角色不能为空");
        require(config.getLevel2Sec() == null || StrUtil.isNotBlank(config.getLevel2NotifyRoles()),
                "启用L2阈值时必须配置L2通知角色");
        require(config.getLevel3Sec() == null || StrUtil.isNotBlank(config.getLevel3NotifyRoles()),
                "启用L3阈值时必须配置L3通知角色");
        require(config.getNotifyCooldownMin() >= 1 && config.getNotifyCooldownMin() <= 1440,
                "通知冷却时间必须在1～1440分钟之间");
        LambdaQueryWrapper<MesWipTimeoutConfigEntity> duplicateQuery =
                new LambdaQueryWrapper<MesWipTimeoutConfigEntity>()
                        .ne(config.getId() != null, MesWipTimeoutConfigEntity::getId, config.getId())
                        .eq(MesWipTimeoutConfigEntity::getEnabled, Boolean.TRUE);
        equalOrNull(duplicateQuery, MesWipTimeoutConfigEntity::getLineCode, config.getLineCode());
        equalOrNull(duplicateQuery, MesWipTimeoutConfigEntity::getRouteCode, config.getRouteCode());
        equalOrNull(duplicateQuery, MesWipTimeoutConfigEntity::getProductCode, config.getProductCode());
        long duplicate = count(duplicateQuery);
        require(!Boolean.TRUE.equals(config.getEnabled()) || duplicate == 0,
                "同一产线/工作中心、工序、产品范围只能存在一条启用配置");
    }

    private String normalizeChannels(String value) {
        LinkedHashSet<String> values = Arrays.stream(StrUtil.blankToDefault(value, "SYSTEM").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank)
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
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                .distinct().collect(Collectors.joining(","));
    }

    private void stampOperator(MesWipTimeoutConfigEntity config) {
        MomUser user = SecurityUtils.getUser();
        config.setSetPersonCode(user.getUsername());
        config.setSetPersonName(user.getName());
    }

    private void equalOrNull(LambdaQueryWrapper<MesWipTimeoutConfigEntity> query,
                             com.baomidou.mybatisplus.core.toolkit.support.SFunction<MesWipTimeoutConfigEntity, ?> column,
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
