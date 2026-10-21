package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesAutoReportEventMapper;
import com.kitex.mom.mes.mapper.MesAutoReportRuleMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.service.MesAutoReportCaptureService;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.MesIdentifierBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;

/**
 * 首次接收时冻结可信采集依据。预览不是占码；最终共享报工事务仍重锁重验原实例。
 * 只接受实名、有权限的原生产者，不缓存令牌、不用SYSTEM伪造人员。
 */
@Service
@RequiredArgsConstructor
public class MesAutoReportCaptureServiceImpl extends ServiceImpl<MesAutoReportEventMapper, MesAutoReportEventEntity>
        implements MesAutoReportCaptureService {

    private final MesActualFactoryAccessService access;
    private final MesTaskArrangeMapper arranges;
    private final MesAutoReportRuleMapper rules;
    private final MesExecutionTemplateService templates;
    private final MesIdentifierBindingService bindings;
    private final MesIdentifierNodeReadiness nodeReadiness;

    @Override
    @PreAuthorize("@pms.hasPermission('mesAutoReport:event')")
    public void freeze(MesAutoReportEventEntity event, MesAutoReportEventDTO input) {
        String factory = access.require("write");
        require(MesAutoReportCaptureEvidence.VERSION.equals(input.getCaptureVersion())
                && input.getArrangeCode() != null && !input.getArrangeCode().isBlank(),
                "V2设备事件须明确原派工，不能处理时按设备猜当前工单");
        var matches = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, event.getRequestedArrangeCode()).last("LIMIT 2"));
        require(matches.size() == 1, "原采集派工未唯一归属当前实名租户/工厂");
        var arrange = matches.get(0);
        var contract = templates.contract(arrange);
        var policy = contract.getIdentifierNodePolicy();
        nodeReadiness.assertReady(nodeReadiness.execution(arrange, contract, com.kitex.mom.mes.enums.MesReportChannelEnum.DEVICE));
        require(!"NONE".equals(policy.getAction()) && "ONE_CODE".equals(contract.getReportMode())
                && contract.getAllowedTriggers().contains("AUTO_EVENT") && "AUTO".equals(arrange.getWorksiteTypes())
                && "PRODUCT".equals(arrange.getStatus()) && Objects.equals(event.getDeviceCode(), arrange.getPlannedDeviceCode()),
                "V2动态采集须为允许设备触发的生产中单件原节点及锁定设备");
        var preview = bindings.preview(arrange.getCode(), event.getTransferCode(), input.getExternalIdentifier());
        if ("BIND".equals(policy.getAction())) {
            require(input.getExpectedBindingId() == null && input.getExpectedBindingVersion() == null
                    && Objects.equals(event.getTransferCode(), preview.getCanonicalIdentifier()),
                    "设备绑定必须提供原内部规范SN及外码，不能在迟到接收时解析可变静态别名");
        } else {
            require(input.getExternalIdentifier() == null
                    && Objects.equals(preview.getExpectedBindingId(), input.getExpectedBindingId())
                    && Objects.equals(preview.getExpectedBindingVersion(), input.getExpectedBindingVersion()),
                    "设备原采集实例/版本与该节点不一致；禁止改取当前新占用");
            event.setExpectedBindingId(MesAutoReportCaptureEvidence.instanceId(input.getExpectedBindingId()));
        }
        var ruleMatches = rules.selectList(new LambdaQueryWrapper<MesAutoReportRuleEntity>()
                .eq(MesAutoReportRuleEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesAutoReportRuleEntity::getFactoryCode, factory)
                .eq(MesAutoReportRuleEntity::getDeviceCode, event.getDeviceCode())
                .eq(MesAutoReportRuleEntity::getEventIdentifier, event.getEventIdentifier())
                .eq(MesAutoReportRuleEntity::getEnabled, true).last("LIMIT 2"));
        require(ruleMatches.size() == 1, "动态原事件须唯一命中已启用单件规则，不冻结模糊规则");
        var rule = ruleMatches.get(0);
        require((rule.getRouteCode() == null || rule.getRouteCode().isBlank() || Objects.equals(rule.getRouteCode(), arrange.getRouteCode()))
                && (rule.getWorksiteCode() == null || rule.getWorksiteCode().isBlank()
                || (arrange.getWorksiteCodes() != null && Arrays.stream(arrange.getWorksiteCodes().split(","))
                .map(String::trim).anyMatch(rule.getWorksiteCode()::equals))), "原采集派工不在单件规则范围内");
        MesAutoReportCaptureEvidence.singleQuantity(input, rule);
        var actor = SecurityUtils.getUser();
        require(actor.getUsername() != null && !actor.getUsername().isBlank() && actor.getUsername().length() <= 100,
                "原采集账号编码缺失或超过审计容量");
        event.setTenantId(TenantContextHolder.getTenantId());
        event.setCaptureVersion(MesAutoReportCaptureEvidence.VERSION).setExternalIdentifier(input.getExternalIdentifier())
                .setExpectedBindingVersion(input.getExpectedBindingVersion()).setCaptureActorId(actor.getId())
                .setCaptureActorCode(actor.getUsername()).setCaptureRuleId(rule.getId())
                .setCaptureRuleSnapshot(MesAutoReportCaptureEvidence.ruleSnapshot(rule))
                .setCapturePolicySnapshot(JSONUtil.toJsonStr(policy)).setCaptureCanonicalIdentifier(preview.getCanonicalIdentifier());
        event.setCaptureSnapshot(MesAutoReportCaptureEvidence.captureSnapshot(event));
        event.setCaptureFingerprint(MesAutoReportCaptureEvidence.fingerprint(event.getCaptureSnapshot()));
        MesAutoReportCaptureEvidence.verifyStored(event);
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mesAutoReport:event')")
    public void authorizeDelivery(MesAutoReportEventEntity event) { verifyOwner(event); }

    @Override
    @PreAuthorize("@pms.hasPermission('mesAutoReport:event','mesAutoReport:retry')")
    public void authorizeProcessing(MesAutoReportEventEntity event) { verifyOwner(event); }

    private void verifyOwner(MesAutoReportEventEntity event) {
        String factory = access.require("write");
        MesAutoReportCaptureEvidence.verifyStored(event);
        var actor = SecurityUtils.getUser();
        require(Objects.equals(event.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(event.getFactoryCode(), factory) && Objects.equals(event.getCaptureActorId(), actor.getId())
                && Objects.equals(event.getCaptureActorCode(), actor.getUsername()),
                "动态事件须以原采集实名和工厂查证/续投；不能用SYSTEM或其他账号代报原事件");
    }

    /** 原事件只能由受控接收服务入账，领域服务不暴露通用写入口。 */
    @Override
    public MesAutoReportEventMapper getBaseMapper() { throw new CheckedException("可信采集禁止通用CRUD，请使用正式设备事件入口"); }

    private void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }
}
