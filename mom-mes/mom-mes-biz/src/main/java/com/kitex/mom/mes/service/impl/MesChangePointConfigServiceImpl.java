package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.mapper.MesChangePointConfigMapper;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.service.MesChangePointConfigService;
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
public class MesChangePointConfigServiceImpl extends ServiceImpl<MesChangePointConfigMapper, MesChangePointConfigEntity>
        implements MesChangePointConfigService {

    private static final Set<String> CATEGORIES = Set.of("MAN", "MACHINE", "MATERIAL", "METHOD", "ENV");
    private static final Set<String> CONTROL_MODES = Set.of("NOTICE", "HOLD_START", "HOLD_REPORT", "HOLD_ALL");
    private static final Set<String> CHANNELS = Set.of("SYSTEM", "DINGDING", "WECHAT");
    private final MesChangePointHandleMapper handleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesChangePointConfigEntity saveData(MesChangePointConfigEntity config) {
        require(config != null, "变化点配置不能为空");
        config.setId(null);
        config.setConfigCode("CP-CFG-" + IdUtil.getSnowflakeNextIdStr());
        normalizeAndValidate(config);
        stampOperator(config);
        save(config);
        return config;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateData(MesChangePointConfigEntity config) {
        require(config != null && config.getId() != null, "变化点配置不能为空");
        MesChangePointConfigEntity persisted = getById(config.getId());
        require(persisted != null, "变化点配置不存在");
        config.setConfigCode(persisted.getConfigCode());
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
            MesChangePointConfigEntity config = getById(id);
            if (config == null) continue;
            long used = handleMapper.selectCount(new LambdaQueryWrapper<MesChangePointHandleEntity>()
                    .eq(MesChangePointHandleEntity::getConfigCode, config.getConfigCode()));
            require(used == 0, "配置[" + config.getName() + "]已产生变化点事件，只能停用，不能删除");
        }
        removeBatchByIds(ids);
    }

    private void normalizeAndValidate(MesChangePointConfigEntity config) {
        config.setName(StrUtil.trim(config.getName()));
        config.setCategory(upper(config.getCategory()));
        config.setCode(upper(config.getCode()));
        config.setContent(StrUtil.trim(config.getContent()));
        config.setLineCode(scope(config.getLineCode()));
        config.setRouteCode(scope(config.getRouteCode()));
        config.setProductCode(scope(config.getProductCode()));
        config.setMonitorField(upper(config.getMonitorField()));
        config.setNotifyRoles(normalizeCsv(config.getNotifyRoles()));
        config.setVerifyRole(normalizeCsv(config.getVerifyRole()));
        config.setControlMode(StrUtil.blankToDefault(upper(config.getControlMode()), "NOTICE"));
        config.setInspectionRequired(Boolean.TRUE.equals(config.getInspectionRequired()));
        config.setResponseSlaMin(config.getResponseSlaMin() == null ? 30 : config.getResponseSlaMin());
        config.setHandleSlaMin(config.getHandleSlaMin() == null ? 240 : config.getHandleSlaMin());
        config.setNotifyChannels(normalizeChannels(config.getNotifyChannels()));
        config.setEnabled(StrUtil.blankToDefault(config.getEnabled(), "1"));
        if (!"NOTICE".equals(config.getControlMode()) || Boolean.TRUE.equals(config.getInspectionRequired())) {
            config.setIsVerify(true);
        }
        config.setIsVerify(Boolean.TRUE.equals(config.getIsVerify()));

        require(StrUtil.isNotBlank(config.getName()), "变化点名称不能为空");
        require(CATEGORIES.contains(config.getCategory()), "4M1E大类无效");
        require(StrUtil.isNotBlank(config.getCode()), "变化点类型不能为空");
        require(StrUtil.isNotBlank(config.getContent()), "变化点内容不能为空");
        require(CONTROL_MODES.contains(config.getControlMode()), "生产控制模式无效");
        require(config.getResponseSlaMin() >= 1 && config.getResponseSlaMin() <= 10080,
                "接收SLA必须在1～10080分钟之间");
        require(config.getHandleSlaMin() > config.getResponseSlaMin() && config.getHandleSlaMin() <= 43200,
                "处置SLA必须大于接收SLA且不超过43200分钟");
        require(StrUtil.isNotBlank(config.getNotifyRoles()), "通知角色不能为空");
        require(!Boolean.TRUE.equals(config.getIsVerify()) || StrUtil.isNotBlank(config.getVerifyRole()),
                "需要验证时必须配置验证角色");
        require(!Boolean.TRUE.equals(config.getInspectionRequired()) || StrUtil.isNotBlank(config.getRouteCode()),
                "要求变化点首检时必须限定工序编码");
        if (config.getUpperLimit() != null || config.getLowerLimit() != null) {
            require(StrUtil.isNotBlank(config.getMonitorField()), "数值型变化点必须配置监控字段");
            require(config.getUpperLimit() != null && config.getLowerLimit() != null
                            && config.getUpperLimit().compareTo(config.getLowerLimit()) > 0,
                    "数值型变化点上限必须大于下限");
        }
        require("0".equals(config.getEnabled()) || "1".equals(config.getEnabled()), "启用状态无效");

        LambdaQueryWrapper<MesChangePointConfigEntity> duplicate = new LambdaQueryWrapper<MesChangePointConfigEntity>()
                .ne(config.getId() != null, MesChangePointConfigEntity::getId, config.getId())
                .eq(MesChangePointConfigEntity::getEnabled, "1")
                .eq(MesChangePointConfigEntity::getCategory, config.getCategory())
                .eq(MesChangePointConfigEntity::getCode, config.getCode());
        equalOrNull(duplicate, MesChangePointConfigEntity::getLineCode, config.getLineCode());
        equalOrNull(duplicate, MesChangePointConfigEntity::getRouteCode, config.getRouteCode());
        equalOrNull(duplicate, MesChangePointConfigEntity::getProductCode, config.getProductCode());
        equalOrNull(duplicate, MesChangePointConfigEntity::getMonitorField, config.getMonitorField());
        require(!"1".equals(config.getEnabled()) || count(duplicate) == 0,
                "同一4M1E类型、产线、工序、产品和监控字段范围只能有一条启用配置");
    }

    private String normalizeChannels(String value) {
        LinkedHashSet<String> channels = Arrays.stream(StrUtil.blankToDefault(value, "SYSTEM").split(","))
                .map(String::trim).map(String::toUpperCase).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(CHANNELS.containsAll(channels), "通知通道仅支持SYSTEM、DINGDING、WECHAT");
        channels.add("SYSTEM");
        require(channels.stream().filter(item -> !"SYSTEM".equals(item)).count() <= 1,
                "最多选择一个外部通道，站内信固定发送");
        return String.join(",", channels);
    }

    private String normalizeCsv(String value) {
        if (StrUtil.isBlank(value)) return null;
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                .distinct().collect(Collectors.joining(","));
    }

    private String scope(String value) {
        String trimmed = StrUtil.trim(value);
        return StrUtil.isBlank(trimmed) || "*".equals(trimmed) ? null : trimmed;
    }

    private String upper(String value) {
        return StrUtil.isBlank(value) ? null : value.trim().toUpperCase();
    }

    private void stampOperator(MesChangePointConfigEntity config) {
        MomUser user = SecurityUtils.getUser();
        config.setSetPersonCode(user.getUsername());
        config.setSetPersonName(user.getName());
    }

    private void equalOrNull(LambdaQueryWrapper<MesChangePointConfigEntity> query,
                             com.baomidou.mybatisplus.core.toolkit.support.SFunction<MesChangePointConfigEntity, ?> column,
                             String value) {
        if (value == null) query.isNull(column); else query.eq(column, value);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
