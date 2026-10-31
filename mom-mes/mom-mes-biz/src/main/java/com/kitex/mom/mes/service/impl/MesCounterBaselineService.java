package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesCounterBaselineCommand;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.util.MesWorksiteTriggerProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 累计计数换派工/清零/初始接入：只建立新的计数边界，不生成或冲销产量。
 */
@Service
@RequiredArgsConstructor
public class MesCounterBaselineService {
    private final MesActualFactoryAccessService access;
    private final MesAutoReportRuleMapper rules;
    private final MesAutoReportEventMapper events;
    private final MesTaskArrangeMapper arranges;
    private final MesCounterBaselineCaseMapper cases;
    private final MesCounterBaselineOperationMapper operations;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public Map<String, Object> context(Long ruleId, long page) {
        String factory = access.require("read");
        require(page >= 1 && page <= 100000, "页码无效");
        var rule = rule(ruleId, factory, false);
        require(rule != null, "当前工厂规则不存在");
        var result = new LinkedHashMap<String, Object>();
        result.put("rule", rule);
        result.put("cases", cases.selectPage(new Page<>(page, 20), caseScope(factory).eq(MesCounterBaselineCase::getRuleId, ruleId).orderByDesc(MesCounterBaselineCase::getId)));
        result.put("operations", operations.selectPage(new Page<>(page, 20), opScope(factory).eq(MesCounterBaselineOperation::getRuleId, ruleId).orderByDesc(MesCounterBaselineOperation::getId)));
        result.put("events", events.selectPage(new Page<>(page, 20), new LambdaQueryWrapper<MesAutoReportEventEntity>()
                .eq(MesAutoReportEventEntity::getTenantId, tenant()).eq(MesAutoReportEventEntity::getFactoryCode, factory)
                .eq(MesAutoReportEventEntity::getDeviceCode, rule.getDeviceCode()).eq(MesAutoReportEventEntity::getEventIdentifier, rule.getEventIdentifier())
                .orderByDesc(MesAutoReportEventEntity::getSourceEventTime).orderByDesc(MesAutoReportEventEntity::getId)));
        result.put("arranges", arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getPlannedDeviceCode, rule.getDeviceCode()).eq(MesTaskArrangeEntity::getStatus, "PRODUCT")
                .orderByAsc(MesTaskArrangeEntity::getId).last("LIMIT 101")));
        result.put("notice", "须先停用累计规则。选取真实事件计数与唯一在产派工，人工核对未记产量处置；批准只建立边界，不补记或冲销产量，之后仍须显式启用规则。");
        return result;
    }

    public Map<String, Object> preview(MesCounterBaselineCommand command) {
        var c = capture(command, access.require("read"), false);
        return Map.of("rule", c.rule(), "event", c.event(), "arrange", c.arrange(), "fingerprint", c.fingerprint(),
                "counterValueText", c.event().getRawValue().toPlainString(), "previousCounterText", c.rule().getLastCounterValue() == null ? "未建立" : c.rule().getLastCounterValue().toPlainString(),
                "notice", "所选原始事件值将成为新基线；截至该事件的未记产量不会自动补记，必须按现场实际核对处置并留证。");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> execute(MesCounterBaselineCommand command) {
        String factory = access.require("write");
        var actor = SecurityUtils.getUser();
        require(command != null && command.getRuleId() != null && command.getRequestId() != null
                && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}"), "须提供规则及16至64位永久请求号");
        require(Set.of("PROPOSE", "APPROVE", "REJECT", "WITHDRAW").contains(Objects.toString(command.getOperation(), "")), "基线操作无效");
        require(StrUtil.isNotBlank(command.getReason()) && command.getReason().length() <= 500, "须填写500字以内实际原因");
        command.setReason(command.getReason().trim());
        String hash = hash(JSONUtil.toJsonStr(Arrays.asList(command, actor.getId())));
        rules.lockRequest("MES_COUNTER_BASELINE:" + tenant() + ":" + factory + ":" + command.getRequestId());
        var old = operations.selectOne(opScope(factory).eq(MesCounterBaselineOperation::getRequestId, command.getRequestId()));
        if (old != null) {
            require(hash.equals(old.getFingerprint()), "请求号已用于不同内容或人员");
            return Map.of("case", cases.selectById(old.getCaseId()), "operation", old, "replayed", true);
        }
        rules.lockRule(command.getRuleId());
        sqlSession.clearCache();
        MesCounterBaselineCase row;
        String before;
        if ("PROPOSE".equals(command.getOperation())) {
            require(command.getCaseId() == null && Boolean.TRUE.equals(command.getUnreportedQuantityHandled())
                            && StrUtil.isNotBlank(command.getPhysicalEvidence()) && command.getPhysicalEvidence().length() <= 1000,
                    "须确认未记产量已核对并说明现场读数、换单/复位事实及未记产量处置（1000字以内）");
            require(cases.selectCount(caseScope(factory).eq(MesCounterBaselineCase::getRuleId, command.getRuleId()).eq(MesCounterBaselineCase::getStatus, "PENDING")) == 0, "已有待审基线，请先复核或撤回");
            var c = capture(command, factory, true);
            require(c.fingerprint().equals(command.getExpectedFingerprint()), "规则、事件或派工事实变化，请重新预览");
            row = new MesCounterBaselineCase();
            row.setTenantId(tenant());
            row.setFactoryCode(factory);
            row.setRuleId(c.rule().getId());
            row.setEventId(c.event().getId());
            row.setArrangeCode(c.arrange().getCode());
            row.setCounterValue(c.event().getRawValue());
            row.setEventTime(c.event().getSourceEventTime());
            row.setSourceEventId(c.event().getSourceEventId());
            row.setEvidenceJson(c.json());
            row.setEvidenceFingerprint(c.fingerprint());
            row.setPhysicalEvidence(command.getPhysicalEvidence().trim());
            row.setReason(command.getReason());
            row.setStatus("PENDING");
            row.setApplicantId(actor.getId());
            require(cases.insert(row) == 1, "基线申请保存失败");
            before = "NONE";
        } else {
            require(command.getCaseId() != null && command.getEventId() == null && command.getArrangeCode() == null
                    && command.getExpectedFingerprint() == null && command.getPhysicalEvidence() == null && command.getUnreportedQuantityHandled() == null, "复核或撤回只引用原申请，不得覆盖证据");
            row = cases.selectOne(caseScope(factory).eq(MesCounterBaselineCase::getId, command.getCaseId()).eq(MesCounterBaselineCase::getRuleId, command.getRuleId()).last("FOR UPDATE"));
            require(row != null && "PENDING".equals(row.getStatus()), "基线申请不存在或已终结");
            before = row.getStatus();
            boolean withdraw = "WITHDRAW".equals(command.getOperation());
            require(withdraw ? Objects.equals(actor.getId(), row.getApplicantId()) : !Objects.equals(actor.getId(), row.getApplicantId()),
                    withdraw ? "仅原申请人可撤回" : "基线须由非申请人独立复核");
            Captured c = null;
            if ("APPROVE".equals(command.getOperation())) {
                var frozen = new MesCounterBaselineCommand();
                frozen.setRuleId(row.getRuleId());
                frozen.setEventId(row.getEventId());
                frozen.setArrangeCode(row.getArrangeCode());
                c = capture(frozen, factory, true);
                require(row.getEvidenceFingerprint().equals(c.fingerprint()), "冻结事实变化或事件过期，请驳回/撤回后重新申请");
            }
            row.setStatus(withdraw ? "WITHDRAWN" : "APPROVE".equals(command.getOperation()) ? "APPROVED" : "REJECTED");
            row.setReviewerId(actor.getId());
            row.setReviewReason(command.getReason());
            row.setReviewTime(LocalDateTime.now());
            require(cases.updateById(row) == 1, "基线复核回执保存失败");
            if (c != null) {
                var rule = c.rule();
                rule.setLastCounterValue(row.getCounterValue());
                rule.setLastEventTime(row.getEventTime());
                rule.setLastSourceEventId(row.getSourceEventId());
                rule.setCounterArrangeCode(row.getArrangeCode());
                rule.setCounterBaselineCaseId(row.getId());
                rule.setLastCounterEventId(row.getEventId());
                require(rules.updateById(rule) == 1, "批准基线写入失败");
            }
        }
        var op = new MesCounterBaselineOperation();
        op.setTenantId(tenant());
        op.setFactoryCode(factory);
        op.setRuleId(row.getRuleId());
        op.setCaseId(row.getId());
        op.setRequestId(command.getRequestId());
        op.setFingerprint(hash);
        op.setOperationType(command.getOperation());
        op.setBeforeStatus(before);
        op.setAfterStatus(row.getStatus());
        op.setOperatorId(actor.getId());
        op.setOperatorCode(actor.getUsername());
        op.setReason(command.getReason());
        require(operations.insert(op) == 1, "基线永久操作账保存失败");
        return Map.of("case", row, "operation", op, "replayed", false);
    }

    private Captured capture(MesCounterBaselineCommand command, String factory, boolean lock) {
        require(command != null && command.getRuleId() != null && command.getEventId() != null && StrUtil.isNotBlank(command.getArrangeCode()), "须明确规则、原始事件及目标派工");
        sqlSession.clearCache();
        var rule = rule(command.getRuleId(), factory, lock);
        require(rule != null && "CUMULATIVE".equals(rule.getQuantityMode()) && !Boolean.TRUE.equals(rule.getEnabled()), "只有停用的累计计数规则可以校准，不能与自动产量并行交接");
        var event = events.selectOne(new LambdaQueryWrapper<MesAutoReportEventEntity>().eq(MesAutoReportEventEntity::getId, command.getEventId())
                .eq(MesAutoReportEventEntity::getTenantId, tenant()).eq(MesAutoReportEventEntity::getFactoryCode, factory));
        require(event != null && Objects.equals(rule.getDeviceCode(), event.getDeviceCode()) && Objects.equals(rule.getEventIdentifier(), event.getEventIdentifier()), "原始事件须精确对应同厂设备及计数标识");
        require(!"SUCCESS".equals(event.getStatus()) && event.getReportId() == null, "已生成报工的事件不能再用作未记产量校准锚点");
        require(event.getRawValue() != null && event.getRawValue().signum() >= 0 && event.getSourceEventTime() != null, "原始事件须有非负真实计数及时间");
        LocalDateTime now = LocalDateTime.now();
        require(!event.getSourceEventTime().isAfter(now)
                && !event.getSourceEventTime().isBefore(now.minusMinutes(Objects.requireNonNullElse(rule.getMaxEventDelayMinutes(), 1440))), "校准须使用允许延迟范围内且不在未来的真实事件");
        require(rule.getLastEventTime() == null || event.getSourceEventTime().isAfter(rule.getLastEventTime()), "新基线事件须晚于旧基线，禁止回拨已处理时间");
        var query = new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getTenantId, tenant()).eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, command.getArrangeCode().trim());
        if (lock) query.last("FOR SHARE");
        var arrange = arranges.selectOne(query);
        require(arrange != null && "PRODUCT".equals(arrange.getStatus()) && MesWorksiteTriggerProjection.forExecution(null, arrange) == com.kitex.mom.mes.enums.WorksiteTypeEnum.AUTO
                && Objects.equals(rule.getDeviceCode(), arrange.getPlannedDeviceCode()), "新基线须绑定同厂设备的生产中自动派工");
        require(arrange.getStartTime() != null && !event.getSourceEventTime().isBefore(arrange.getStartTime()), "基线事件不得早于目标派工实际开工；开工时间缺失须先治理，不能猜测计数归属");
        require(StrUtil.isBlank(event.getRequestedArrangeCode()) || arrange.getCode().equals(event.getRequestedArrangeCode()), "事件指定的派工与校准目标不一致");
        require(StrUtil.isBlank(rule.getRouteCode()) || Objects.equals(rule.getRouteCode(), arrange.getRouteCode()), "派工不在规则限定工序");
        require(StrUtil.isBlank(rule.getWorksiteCode()) || Arrays.asList(Objects.toString(arrange.getWorksiteCodes(), "").split(",")).stream().map(String::trim).anyMatch(rule.getWorksiteCode()::equals), "派工不在规则限定工位");
        // 不冻结会被异步处理改变的事件状态/重试次数；原始设备事实由数据库禁止覆盖。
        var fact = Arrays.asList(event.getId(), event.getSourceEventId(), event.getDeviceCode(), event.getEventIdentifier(), event.getSourceEventTime(), event.getRawValue(), event.getRequestedArrangeCode(), event.getFactoryCode(), event.getTenantId());
        String json = JSONUtil.toJsonStr(Arrays.asList("MES_COUNTER_BASELINE_V1", rule, fact, arrange));
        require(json.length() <= 100000, "校准证据超出容量，不能截断");
        return new Captured(rule, event, arrange, json, hash(json));
    }

    private MesAutoReportRuleEntity rule(Long id, String factory, boolean lock) {
        var q = new LambdaQueryWrapper<MesAutoReportRuleEntity>().eq(MesAutoReportRuleEntity::getId, id).eq(MesAutoReportRuleEntity::getTenantId, tenant()).eq(MesAutoReportRuleEntity::getFactoryCode, factory);
        if (lock) q.last("FOR UPDATE");
        return rules.selectOne(q);
    }

    private LambdaQueryWrapper<MesCounterBaselineCase> caseScope(String factory) {
        return new LambdaQueryWrapper<MesCounterBaselineCase>().eq(MesCounterBaselineCase::getTenantId, tenant()).eq(MesCounterBaselineCase::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<MesCounterBaselineOperation> opScope(String factory) {
        return new LambdaQueryWrapper<MesCounterBaselineOperation>().eq(MesCounterBaselineOperation::getTenantId, tenant()).eq(MesCounterBaselineOperation::getFactoryCode, factory);
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private record Captured(MesAutoReportRuleEntity rule, MesAutoReportEventEntity event, MesTaskArrangeEntity arrange,
                            String json, String fingerprint) {
    }
}
