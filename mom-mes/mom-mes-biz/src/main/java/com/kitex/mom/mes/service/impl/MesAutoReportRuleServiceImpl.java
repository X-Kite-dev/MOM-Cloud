package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import com.kitex.mom.mes.enums.AutoReportQuantityModeEnum;
import com.kitex.mom.mes.mapper.MesAutoReportEventMapper;
import com.kitex.mom.mes.mapper.MesAutoReportRuleMapper;
import com.kitex.mom.mes.service.MesAutoReportRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesAutoReportRuleServiceImpl extends ServiceImpl<MesAutoReportRuleMapper, MesAutoReportRuleEntity>
        implements MesAutoReportRuleService {

    private final MesAutoReportEventMapper eventMapper;
    private final MesActualFactoryAccessService access;
    private final com.kitex.mom.mes.mapper.MesCounterBaselineCaseMapper baselines;

    @Override
    public IPage<MesAutoReportRuleEntity> queryPage(Page<MesAutoReportRuleEntity> page,
                                                    MesAutoReportRuleEntity query) {
        String factory = access.require("read");
        LambdaQueryWrapper<MesAutoReportRuleEntity> wrapper = new LambdaQueryWrapper<MesAutoReportRuleEntity>()
                .eq(MesAutoReportRuleEntity::getFactoryCode, factory);
        if (query != null) {
            wrapper.like(StrUtil.isNotBlank(query.getRuleCode()), MesAutoReportRuleEntity::getRuleCode, query.getRuleCode())
                    .like(StrUtil.isNotBlank(query.getRuleName()), MesAutoReportRuleEntity::getRuleName, query.getRuleName())
                    .eq(StrUtil.isNotBlank(query.getDeviceCode()), MesAutoReportRuleEntity::getDeviceCode, query.getDeviceCode())
                    .eq(StrUtil.isNotBlank(query.getEventIdentifier()), MesAutoReportRuleEntity::getEventIdentifier,
                            query.getEventIdentifier())
                    .eq(query.getEnabled() != null, MesAutoReportRuleEntity::getEnabled, query.getEnabled());
        }
        wrapper.orderByDesc(MesAutoReportRuleEntity::getEnabled)
                .orderByAsc(MesAutoReportRuleEntity::getDeviceCode)
                .orderByAsc(MesAutoReportRuleEntity::getEventIdentifier);
        return page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesAutoReportRuleEntity saveRule(MesAutoReportRuleEntity input) {
        String factory = access.require("write");
        validate(input);
        MesAutoReportRuleEntity existing = input.getId() == null ? null : locked(input.getId(), factory);
        if (input.getId() != null && existing == null) {
            throw new CheckedException("自动报工规则不存在");
        }
        long sameCode = count(new LambdaQueryWrapper<MesAutoReportRuleEntity>()
                .eq(MesAutoReportRuleEntity::getRuleCode, input.getRuleCode().trim())
                .ne(input.getId() != null, MesAutoReportRuleEntity::getId, input.getId()));
        if (sameCode > 0) {
            throw new CheckedException("自动报工规则编码已存在");
        }
        if (existing != null && Boolean.TRUE.equals(existing.getEnabled()) && identityChanged(existing, input)) {
            throw new CheckedException("启用中的规则不能修改设备、事件、数量模式或限定工序，请先停用");
        }
        normalize(input);
        input.setTenantId(com.kitex.common.data.tenant.TenantContextHolder.getTenantId());
        input.setFactoryCode(factory);
        input.setCreateBy(existing == null ? null : existing.getCreateBy());
        input.setCreateTime(existing == null ? null : existing.getCreateTime());
        input.setDelFlag(existing == null ? "0" : existing.getDelFlag());
        input.setUpdateBy(null);
        input.setUpdateTime(null);
        if (existing != null) {
            ensureNoPending(existing.getId());
            if (!Objects.equals(existing.getRuleCode(), input.getRuleCode()))
                throw new CheckedException("既有规则编码不可改写");
            if (Boolean.TRUE.equals(existing.getEnabled()) && conversionChanged(existing, input))
                throw new CheckedException("启用中不能改换算规则，请先停用");
            boolean used = existing.getLastEventTime() != null || existing.getCounterBaselineCaseId() != null
                    || eventMapper.selectCount(new LambdaQueryWrapper<MesAutoReportEventEntity>().eq(MesAutoReportEventEntity::getRuleId, existing.getId())) > 0;
            if (used && conversionChanged(existing, input))
                throw new CheckedException("已有事件/基线的设备、范围、计数方式或倍率不可覆盖，请另建规则并重新交接基线");
        }
        input.setLastCounterValue(existing == null ? null : existing.getLastCounterValue());
        input.setLastEventTime(existing == null ? null : existing.getLastEventTime());
        input.setLastSourceEventId(existing == null ? null : existing.getLastSourceEventId());
        input.setCounterArrangeCode(existing == null ? null : existing.getCounterArrangeCode());
        input.setCounterBaselineCaseId(existing == null ? null : existing.getCounterBaselineCaseId());
        input.setLastCounterEventId(existing == null ? null : existing.getLastCounterEventId());
        if (Boolean.TRUE.equals(input.getEnabled())) {
            assertBaseline(input);
            ensureNoActiveConflict(input);
        }
        saveOrUpdate(input);
        return getById(input.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesAutoReportRuleEntity changeStatus(Long id, boolean enabled) {
        String factory = access.require("write");
        MesAutoReportRuleEntity rule = id == null ? null : locked(id, factory);
        if (rule == null) {
            throw new CheckedException("自动报工规则不存在");
        }
        if (enabled) {
            ensureNoPending(id);
            assertBaseline(rule);
            validate(rule);
            ensureNoActiveConflict(rule);
        }
        rule.setEnabled(enabled);
        if (!updateById(rule)) {
            throw new CheckedException("设备规则启停未保存，请按原规则核对状态");
        }
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        String factory = access.require("write");
        MesAutoReportRuleEntity rule = id == null ? null : locked(id, factory);
        if (rule == null) {
            return;
        }
        if (Boolean.TRUE.equals(rule.getEnabled())) {
            throw new CheckedException("启用中的自动报工规则不能删除，请先停用");
        }
        if (baselines.selectCount(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesCounterBaselineCase>().eq(com.kitex.mom.mes.entity.MesCounterBaselineCase::getRuleId, id)) > 0)
            throw new CheckedException("规则已有基线交接历史，不可删除");
        Long successCount = eventMapper.selectCount(new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getRuleId, id));
        if (successCount != null && successCount > 0) {
            throw new CheckedException("规则已有处理事件，为保证追溯不能删除，可保持停用");
        }
        removeById(id);
    }

    private void validate(MesAutoReportRuleEntity rule) {
        if (rule == null || StrUtil.isBlank(rule.getRuleCode()) || StrUtil.isBlank(rule.getRuleName())) {
            throw new CheckedException("规则编码和名称不能为空");
        }
        if (StrUtil.isBlank(rule.getDeviceCode()) || StrUtil.isBlank(rule.getEventIdentifier())) {
            throw new CheckedException("设备编码和事件标识不能为空");
        }
        AutoReportQuantityModeEnum mode;
        try {
            mode = AutoReportQuantityModeEnum.fromCode(rule.getQuantityMode());
        } catch (IllegalArgumentException exception) {
            throw new CheckedException(exception.getMessage());
        }
        BigDecimal multiplier = Objects.requireNonNullElse(rule.getQuantityMultiplier(), BigDecimal.ONE);
        if (multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("数量换算倍率必须大于0");
        }
        if (mode == AutoReportQuantityModeEnum.PULSE
                && (rule.getPulseQuantity() == null || rule.getPulseQuantity().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new CheckedException("脉冲模式的单脉冲数量必须大于0");
        }
        if (rule.getMaxSingleQuantity() != null && rule.getMaxSingleQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CheckedException("单次最大报工量必须大于0");
        }
        int delay = Objects.requireNonNullElse(rule.getMaxEventDelayMinutes(), 1440);
        if (delay < 1 || delay > 10080) {
            throw new CheckedException("允许事件延迟必须在1至10080分钟之间");
        }
    }

    private void normalize(MesAutoReportRuleEntity rule) {
        rule.setRuleCode(rule.getRuleCode().trim());
        rule.setRuleName(rule.getRuleName().trim());
        rule.setDeviceCode(rule.getDeviceCode().trim());
        rule.setEventIdentifier(rule.getEventIdentifier().trim());
        rule.setQuantityMode(AutoReportQuantityModeEnum.fromCode(rule.getQuantityMode()).name());
        rule.setQuantityMultiplier(Objects.requireNonNullElse(rule.getQuantityMultiplier(), BigDecimal.ONE));
        rule.setCounterResetAllowed(Boolean.TRUE.equals(rule.getCounterResetAllowed()));
        rule.setMaxEventDelayMinutes(Objects.requireNonNullElse(rule.getMaxEventDelayMinutes(), 1440));
        rule.setRouteCode(StrUtil.blankToDefault(StrUtil.trim(rule.getRouteCode()), null));
        rule.setWorksiteCode(StrUtil.blankToDefault(StrUtil.trim(rule.getWorksiteCode()), null));
        rule.setEnabled(Boolean.TRUE.equals(rule.getEnabled()));
        if (!AutoReportQuantityModeEnum.PULSE.name().equals(rule.getQuantityMode())) {
            rule.setPulseQuantity(null);
        }
    }

    private boolean identityChanged(MesAutoReportRuleEntity before, MesAutoReportRuleEntity after) {
        return !Objects.equals(before.getDeviceCode(), StrUtil.trim(after.getDeviceCode()))
                || !Objects.equals(before.getEventIdentifier(), StrUtil.trim(after.getEventIdentifier()))
                || !Objects.equals(before.getQuantityMode(), AutoReportQuantityModeEnum.fromCode(after.getQuantityMode()).name())
                || !Objects.equals(StrUtil.blankToDefault(before.getRouteCode(), null),
                StrUtil.blankToDefault(StrUtil.trim(after.getRouteCode()), null))
                || !Objects.equals(StrUtil.blankToDefault(before.getWorksiteCode(), null),
                StrUtil.blankToDefault(StrUtil.trim(after.getWorksiteCode()), null));
    }

    private MesAutoReportRuleEntity locked(Long id, String factory) {
        baseMapper.lockRule(id);
        return getOne(new LambdaQueryWrapper<MesAutoReportRuleEntity>().eq(MesAutoReportRuleEntity::getId, id).eq(MesAutoReportRuleEntity::getFactoryCode, factory).last("FOR UPDATE"));
    }

    private void ensureNoPending(Long id) {
        if (baselines.selectCount(new LambdaQueryWrapper<com.kitex.mom.mes.entity.MesCounterBaselineCase>().eq(com.kitex.mom.mes.entity.MesCounterBaselineCase::getRuleId, id).eq(com.kitex.mom.mes.entity.MesCounterBaselineCase::getStatus, "PENDING")) > 0)
            throw new CheckedException("基线正在复核，须先批准、驳回或撤回，不能编辑或启用规则");
    }

    private boolean conversionChanged(MesAutoReportRuleEntity a, MesAutoReportRuleEntity b) {
        return identityChanged(a, b)
                || !sameNumber(a.getQuantityMultiplier(), b.getQuantityMultiplier()) || !sameNumber(a.getPulseQuantity(), b.getPulseQuantity());
    }

    private boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void assertBaseline(MesAutoReportRuleEntity rule) {
        if (!"CUMULATIVE".equals(rule.getQuantityMode())) return;
        var c = rule.getCounterBaselineCaseId() == null ? null : baselines.selectById(rule.getCounterBaselineCaseId());
        if (c == null || !"APPROVED".equals(c.getStatus()) || !Objects.equals(c.getRuleId(), rule.getId()) || !Objects.equals(c.getArrangeCode(), rule.getCounterArrangeCode())
                || rule.getLastCounterValue() == null || rule.getLastEventTime() == null || rule.getLastCounterEventId() == null)
            throw new CheckedException("累计计数启用前须以真实事件和在产派工申请并独立批准基线；历史旧值不能自动认定归属");
    }

    private void ensureNoActiveConflict(MesAutoReportRuleEntity rule) {
        long conflicts = count(new LambdaQueryWrapper<MesAutoReportRuleEntity>()
                .eq(MesAutoReportRuleEntity::getDeviceCode, rule.getDeviceCode())
                .eq(MesAutoReportRuleEntity::getEventIdentifier, rule.getEventIdentifier())
                .eq(MesAutoReportRuleEntity::getEnabled, true)
                .ne(rule.getId() != null, MesAutoReportRuleEntity::getId, rule.getId()));
        if (conflicts > 0) {
            throw new CheckedException("同一设备和事件标识只能启用一条自动报工规则");
        }
    }
}
