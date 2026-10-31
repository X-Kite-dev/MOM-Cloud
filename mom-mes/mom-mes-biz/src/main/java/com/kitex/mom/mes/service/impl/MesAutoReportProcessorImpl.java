package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.AutoReportEventStatusEnum;
import com.kitex.mom.mes.enums.AutoReportQuantityModeEnum;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.mapper.MesAutoReportEventMapper;
import com.kitex.mom.mes.mapper.MesAutoReportRuleMapper;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesWorksiteTriggerProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesAutoReportProcessorImpl implements MesAutoReportProcessor {

    private final MesAutoReportEventMapper eventMapper;
    private final MesAutoReportRuleMapper ruleMapper;
    private final MesTaskArrangeService arrangeService;
    private final MesTaskReportService reportService;
    private final com.kitex.mom.mes.mapper.MesCounterBaselineCaseMapper baselines;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;
    private final MesAutoReportCaptureService captures;
    private final MesExecutionTemplateService templates;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void process(Long eventId) {
        MesAutoReportEventEntity event = eventMapper.selectOne(new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesAutoReportEventEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesAutoReportEventEntity::getId, eventId).last("FOR UPDATE"));
        if (event == null) {
            throw new CheckedException("自动报工事件不存在");
        }
        boolean captured = event.getCaptureVersion() != null;
        if (captured) captures.authorizeProcessing(event);
        if (AutoReportEventStatusEnum.SUCCESS.name().equals(event.getStatus())
                || AutoReportEventStatusEnum.IGNORED.name().equals(event.getStatus())) {
            return;
        }
        List<MesAutoReportRuleEntity> candidates = ruleMapper.selectList(
                new LambdaQueryWrapper<MesAutoReportRuleEntity>()
                        .eq(MesAutoReportRuleEntity::getDeviceCode, event.getDeviceCode())
                        .eq(MesAutoReportRuleEntity::getFactoryCode, event.getFactoryCode())
                        .eq(MesAutoReportRuleEntity::getEventIdentifier, event.getEventIdentifier())
                        .eq(captured, MesAutoReportRuleEntity::getId, event.getCaptureRuleId())
                        .eq(MesAutoReportRuleEntity::getEnabled, true));
        if (candidates.isEmpty()) {
            if (captured) throw new CheckedException("原采集规则已停用或缺失，不改取当前新规则；保留原事件待核");
            markIgnored(event, "未匹配到启用的设备自动报工规则");
            return;
        }
        if (candidates.size() > 1) {
            throw new CheckedException("同一设备和事件标识存在多条启用规则，请先处理配置冲突");
        }

        MesAutoReportRuleEntity candidate = candidates.get(0);
        ruleMapper.lockRule(candidate.getId());
        sqlSession.clearCache();
        MesAutoReportRuleEntity rule = ruleMapper.selectById(candidate.getId());
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())) {
            if (captured) throw new CheckedException("原采集规则已停用，保留原事件待核，不更换口径");
            markIgnored(event, "自动报工规则已停用");
            return;
        }
        if (!Objects.equals(rule.getTenantId(), event.getTenantId()) || !Objects.equals(rule.getFactoryCode(), event.getFactoryCode())
                || !Objects.equals(rule.getDeviceCode(), event.getDeviceCode()) || !Objects.equals(rule.getEventIdentifier(), event.getEventIdentifier()))
            throw new CheckedException("等待处理期间规则设备/工厂事实变化，须重新核对事件映射");
        if (captured && !Objects.equals(event.getCaptureRuleSnapshot(), MesAutoReportCaptureEvidence.ruleSnapshot(rule)))
            throw new CheckedException("原单件换算/规则范围已变化，不按当前口径重新解释原事件");
        validateEventTime(event, rule);
        event.setRuleId(rule.getId());
        event.setRuleCode(rule.getRuleCode());

        MesTaskArrangeEntity arrange = null;
        if ("CUMULATIVE".equals(rule.getQuantityMode())) {
            var baseline = rule.getCounterBaselineCaseId() == null ? null : baselines.selectById(rule.getCounterBaselineCaseId());
            if (baseline == null || !"APPROVED".equals(baseline.getStatus()) || !Objects.equals(baseline.getRuleId(), rule.getId())
                    || !Objects.equals(baseline.getTenantId(), rule.getTenantId()) || !Objects.equals(baseline.getFactoryCode(), rule.getFactoryCode())
                    || !Objects.equals(baseline.getArrangeCode(), rule.getCounterArrangeCode()) || rule.getLastCounterValue() == null || rule.getLastEventTime() == null)
                throw new CheckedException("累计计数缺少已批准的派工基线；请停用规则并完成真实事件基线交接，不能自动补记历史量");
            if (event.getSourceEventTime().isAfter(rule.getLastEventTime())) {
                arrange = resolveArrange(event, rule);
                if (!Objects.equals(rule.getCounterArrangeCode(), arrange.getCode()))
                    throw new CheckedException("设备已切换派工，旧计数不能记到新工单；须停用并独立批准新基线");
            }
        }
        QuantityResolution resolution = resolveQuantity(event, rule);
        if (resolution.ignored()) {
            if (rule.getLastEventTime() == null || event.getSourceEventTime().isAfter(rule.getLastEventTime())) {
                updateRuleState(rule, event);
            }
            markIgnored(event, resolution.message());
            return;
        }
        BigDecimal quantity = resolution.quantity();
        var exactQuantity = quantity.stripTrailingZeros();
        if (exactQuantity.scale() > 6 || exactQuantity.precision() - exactQuantity.scale() > 14)
            throw new CheckedException("折算产量超出20位/6位小数容量，须明确换算规则，禁止静默舍入导致计数与产量不守恒");
        if (rule.getMaxSingleQuantity() != null && quantity.compareTo(rule.getMaxSingleQuantity()) > 0) {
            throw new CheckedException("设备事件折算数量[" + quantity + "]超过规则单次上限["
                    + rule.getMaxSingleQuantity() + "]");
        }

        if (arrange == null) arrange = resolveArrange(event, rule);
        if (captured) {
            if (!Objects.equals(event.getCapturePolicySnapshot(), cn.hutool.json.JSONUtil.toJsonStr(
                    templates.contract(arrange).getIdentifierNodePolicy())))
                throw new CheckedException("原采集冻结节点已变化，不按新模板解释旧事件");
            MesAutoReportCaptureEvidence.singleQuantity(MesAutoReportCaptureEvidence.originalInput(event), rule);
            if (BigDecimal.ONE.compareTo(quantity) != 0)
                throw new CheckedException("动态原单件事件实际折算量不是一件，禁止执行");
        } else if (!"NONE".equals(templates.contract(arrange).getIdentifierNodePolicy().getAction())) {
            throw new CheckedException("旧事件没有动态采集实例证据，须人工核对；不能按当前外码补报");
        }
        String conversionSnapshot = cn.hutool.json.JSONUtil.toJsonStr(java.util.Map.of("version", "MES_AUTO_CONVERSION_V1",
                "rule", rule, "eventId", event.getId(), "sourceEventId", event.getSourceEventId(), "arrangeCode", arrange.getCode(), "quantity", quantity));
        MesTaskReportVO report = new MesTaskReportVO();
        report.setRequestId("AUTO_EVENT:" + event.getId());
        report.setArrangeCode(arrange.getCode());
        report.setPassNum(quantity);
        report.setTransferCode(event.getTransferCode());
        report.setDeviceCode(event.getDeviceCode());
        report.setDeviceName(arrange.getPlannedDeviceName());
        report.setSourceEventId(event.getSourceEventId());
        report.setSourceEventTime(event.getSourceEventTime());
        report.setExternalIdentifier(event.getExternalIdentifier());
        report.setExpectedBindingId(event.getExpectedBindingId() == null ? null : event.getExpectedBindingId().toString());
        report.setExpectedBindingVersion(event.getExpectedBindingVersion());
        // V2精确派工已冻结原规则工位；旧NONE命令保持原正文，不追加新值。
        if (captured) report.setWorksiteCode(rule.getWorksiteCode());
        report.setOperatorCode("DEVICE:" + event.getDeviceCode());
        report.setOperatorName("设备自动报工[" + event.getDeviceCode() + "]");
        reportService.reportAuto(report);

        MesTaskReportEntity savedReport = reportService.getOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, event.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, event.getFactoryCode())
                .eq(MesTaskReportEntity::getRequestId, report.getRequestId()), false);
        if (savedReport == null) {
            throw new CheckedException("自动报工事务已执行但未找到报工主记录");
        }
        if (!Objects.equals(savedReport.getTenantId(), event.getTenantId()) || !Objects.equals(savedReport.getFactoryCode(), event.getFactoryCode())
                || !Objects.equals(savedReport.getArrangeCode(), arrange.getCode()) || !Objects.equals(savedReport.getSourceEventId(), event.getSourceEventId())
                || savedReport.getReportNum() == null || savedReport.getReportNum().compareTo(quantity) != 0 || !"EFFECTIVE".equals(savedReport.getStatus()))
            throw new CheckedException("设备事件与实际报工的派工、来源、数量或状态不一致，拒绝推进计数基线");
        updateRuleState(rule, event);
        event.setResolvedQuantity(quantity);
        event.setArrangeCode(arrange.getCode());
        event.setReportId(savedReport.getId());
        event.setConversionSnapshot(conversionSnapshot);
        event.setStatus(AutoReportEventStatusEnum.SUCCESS.name());
        event.setProcessMessage("设备事件已生成报工，数量：" + quantity);
        event.setLastProcessTime(LocalDateTime.now());
        if (eventMapper.updateById(event) != 1)
            throw new CheckedException("设备原事件成功回执保存失败，报工与原号一起回滚");
    }

    private void validateEventTime(MesAutoReportEventEntity event, MesAutoReportRuleEntity rule) {
        LocalDateTime now = LocalDateTime.now();
        if (event.getSourceEventTime().isAfter(now.plusMinutes(5))) {
            throw new CheckedException("设备事件时间超过当前时间5分钟，请校准设备时钟");
        }
        long delay = Duration.between(event.getSourceEventTime(), now).toMinutes();
        if (delay > Objects.requireNonNullElse(rule.getMaxEventDelayMinutes(), 1440)) {
            throw new CheckedException("设备事件已超过规则允许的延迟时间，禁止自动补记产量");
        }
    }

    private QuantityResolution resolveQuantity(MesAutoReportEventEntity event, MesAutoReportRuleEntity rule) {
        AutoReportQuantityModeEnum mode = AutoReportQuantityModeEnum.fromCode(rule.getQuantityMode());
        BigDecimal multiplier = Objects.requireNonNullElse(rule.getQuantityMultiplier(), BigDecimal.ONE);
        BigDecimal value = event.getRawValue();
        if (mode == AutoReportQuantityModeEnum.PULSE) {
            BigDecimal pulseCount = value == null ? BigDecimal.ONE : value;
            if (pulseCount.compareTo(BigDecimal.ZERO) <= 0) {
                return QuantityResolution.ignore("脉冲数不大于0，不产生报工");
            }
            return QuantityResolution.success(pulseCount
                    .multiply(Objects.requireNonNullElse(rule.getPulseQuantity(), BigDecimal.ONE))
                    .multiply(multiplier));
        }
        if (value == null) {
            throw new CheckedException("当前自动报工规则要求设备事件提供数值");
        }
        if (mode == AutoReportQuantityModeEnum.DELTA) {
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                return QuantityResolution.ignore("增量值不大于0，不产生报工");
            }
            return QuantityResolution.success(value.multiply(multiplier));
        }

        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new CheckedException("设备累计计数不能为负数");
        }
        if (rule.getLastEventTime() != null && !event.getSourceEventTime().isAfter(rule.getLastEventTime())) {
            return QuantityResolution.ignore("累计计数事件早于或等于已处理事件，按乱序事件忽略");
        }
        if (rule.getLastCounterValue() == null) {
            throw new CheckedException("累计计数须先批准基线，不能自动建立无派工归属的历史基线");
        }
        BigDecimal delta = value.subtract(rule.getLastCounterValue());
        if (delta.compareTo(BigDecimal.ZERO) < 0) {
            throw new CheckedException("设备累计计数发生回退；旧清零开关不再直接生成产量，须停用并独立批准新基线");
        }
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            return QuantityResolution.ignore("累计计数未增加，不产生报工");
        }
        return QuantityResolution.success(delta.multiply(multiplier));
    }

    private MesTaskArrangeEntity resolveArrange(MesAutoReportEventEntity event, MesAutoReportRuleEntity rule) {
        if (StrUtil.isNotBlank(event.getRequestedArrangeCode())) {
            List<MesTaskArrangeEntity> requested = arrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, event.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, event.getFactoryCode())
                    .eq(MesTaskArrangeEntity::getCode, event.getRequestedArrangeCode()).last("LIMIT 2"));
            if (requested.size() != 1)
                throw new CheckedException("原指定派工未唯一归属事件租户/工厂，禁止取第一条猜归属");
            MesTaskArrangeEntity arrange = requested.get(0);
            validateArrange(arrange, event, rule);
            return arrange;
        }
        List<MesTaskArrangeEntity> matches = arrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTenantId, event.getTenantId())
                        .eq(MesTaskArrangeEntity::getFactoryCode, event.getFactoryCode())
                        .eq(MesTaskArrangeEntity::getPlannedDeviceCode, event.getDeviceCode())
                        .eq(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name()))
                .stream().filter(item -> matchesRuleScope(item, rule)).toList();
        if (matches.isEmpty()) {
            throw new CheckedException("设备没有匹配到生产中的自动报工派工任务");
        }
        if (matches.size() > 1) {
            throw new CheckedException("设备同时匹配到多个生产中派工任务，请在事件或规则中限定派工/工序/工位");
        }
        MesTaskArrangeEntity arrange = matches.get(0);
        validateArrange(arrange, event, rule);
        return arrange;
    }

    private void validateArrange(MesTaskArrangeEntity arrange, MesAutoReportEventEntity event,
                                 MesAutoReportRuleEntity rule) {
        if (arrange == null) {
            throw new CheckedException("指定的派工任务不存在");
        }
        if (!Objects.equals(arrange.getTenantId(), event.getTenantId()) || !Objects.equals(arrange.getFactoryCode(), event.getFactoryCode()))
            throw new CheckedException("设备事件与派工须属于同一实际租户和工厂");
        if (!MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus())) {
            throw new CheckedException("派工任务未处于生产中，禁止设备自动报工");
        }
        if (!Objects.equals(event.getDeviceCode(), arrange.getPlannedDeviceCode())) {
            throw new CheckedException("事件设备与派工任务的APS锁定设备不一致");
        }
        if (MesWorksiteTriggerProjection.forExecution(null, arrange) != WorksiteTypeEnum.AUTO) {
            throw new CheckedException("派工任务不是自动报工工位，禁止设备事件驱动报工");
        }
        if (!matchesRuleScope(arrange, rule)) {
            throw new CheckedException("派工任务不在自动报工规则限定的工序或工位范围内");
        }
    }

    private boolean matchesRuleScope(MesTaskArrangeEntity arrange, MesAutoReportRuleEntity rule) {
        if (StrUtil.isNotBlank(rule.getRouteCode()) && !Objects.equals(rule.getRouteCode(), arrange.getRouteCode())) {
            return false;
        }
        return StrUtil.isBlank(rule.getWorksiteCode()) || splitCodes(arrange.getWorksiteCodes()).contains(rule.getWorksiteCode());
    }

    private List<String> splitCodes(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private void updateRuleState(MesAutoReportRuleEntity rule, MesAutoReportEventEntity event) {
        if (!"CUMULATIVE".equals(rule.getQuantityMode())) return;
        rule.setLastCounterValue(event.getRawValue());
        rule.setLastEventTime(event.getSourceEventTime());
        rule.setLastSourceEventId(event.getSourceEventId());
        rule.setLastCounterEventId(event.getId());
        if (ruleMapper.updateById(rule) != 1)
            throw new CheckedException("累计计数状态写入失败，报工与基线必须同事务提交");
    }

    private void markIgnored(MesAutoReportEventEntity event, String message) {
        event.setStatus(AutoReportEventStatusEnum.IGNORED.name());
        event.setProcessMessage(message);
        event.setLastProcessTime(LocalDateTime.now());
        eventMapper.updateById(event);
    }

    private record QuantityResolution(BigDecimal quantity, boolean ignored, String message) {
        static QuantityResolution success(BigDecimal quantity) {
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("设备事件折算出的报工数量必须大于0");
            }
            return new QuantityResolution(quantity, false, null);
        }

        static QuantityResolution ignore(String message) {
            return new QuantityResolution(null, true, message);
        }
    }
}
