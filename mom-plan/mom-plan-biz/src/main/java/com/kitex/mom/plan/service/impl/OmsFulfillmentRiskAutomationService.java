package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskAutomationCommand;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskScanCandidate;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskAutomationOperationEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskNoticeEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskScanStateEntity;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskAutomationOperationMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskNoticeMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskScanStateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * 自动扫描、持久催办、故障工作台及人工结果核对。默认关闭，须显式配置并注册XXL-JOB。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OmsFulfillmentRiskAutomationService {
    private final OmsFulfillmentRiskScanStateMapper states;
    private final OmsFulfillmentRiskNoticeMapper notices;
    private final OmsFulfillmentRiskAutomationOperationMapper operations;
    private final OmsFulfillmentRiskAutomationWorker scanWorker;
    private final OmsFulfillmentRiskNoticeWorker noticeWorker;
    private final RemoteUserService users;
    private final RemoteMessagePushService messages;

    @Value("${mom.oms-fulfillment-risk-automation.enabled:${MOM_OMS_RISK_AUTOMATION_ENABLED:false}}")
    private boolean enabled;
    @Value("${mom.oms-fulfillment-risk-automation.scan-interval-minutes:15}")
    private int scanIntervalMinutes;
    @Value("${mom.oms-fulfillment-risk-automation.scan-batch-size:50}")
    private int scanBatchSize;
    @Value("${mom.oms-fulfillment-risk-automation.max-scan-failures:5}")
    private int maxScanFailures;
    @Value("${mom.oms-fulfillment-risk-automation.max-notice-attempts:5}")
    private int maxNoticeAttempts;

    public Map<String, Integer> scanDue() {
        schedulerOnly();
        if (!enabled) return Map.of("disabled", 1, "completed", 0, "succeeded", 0, "failed", 0);
        int completed = states.completeIneligible();
        int succeeded = 0, failed = 0;
        List<OmsFulfillmentRiskScanCandidate> candidates = states.dueCandidates(bound(scanBatchSize, 1, 200));
        for (OmsFulfillmentRiskScanCandidate candidate : candidates) {
            try {
                boolean done = inScope(candidate.getTenantId(), candidate.getFactoryCode(),
                        () -> scanWorker.scanOne(candidate, bound(scanIntervalMinutes, 5, 1440),
                                bound(maxNoticeAttempts, 1, 20)));
                if (done) succeeded++;
            } catch (RuntimeException ex) {
                failed++;
                try {
                    inScope(candidate.getTenantId(), candidate.getFactoryCode(), () -> {
                        scanWorker.recordFailure(candidate, ex, bound(maxScanFailures, 1, 20));
                        return false;
                    });
                } catch (RuntimeException recordFailure) {
                    log.error("履约风险扫描失败状态未保存，orderDetailId={}，tenantId={}",
                            candidate.getOrderDetailId(), candidate.getTenantId(), recordFailure);
                }
            }
        }
        return Map.of("disabled", 0, "completed", completed, "succeeded", succeeded, "failed", failed);
    }

    public Map<String, Integer> dispatchDue() {
        schedulerOnly();
        if (!enabled) return Map.of("disabled", 1, "delivered", 0, "failed", 0);
        int delivered = 0, failed = 0;
        for (OmsFulfillmentRiskNoticeEntity candidate : notices.dueAcrossTenants(100)) {
            try {
                boolean result = inScope(candidate.getTenantId(), candidate.getFactoryCode(),
                        () -> dispatchOne(candidate));
                if (result) delivered++;
                else failed++;
            } catch (RuntimeException ex) {
                failed++;
                log.error("履约风险催办调度失败，noticeId={}，tenantId={}",
                        candidate.getId(), candidate.getTenantId(), ex);
            }
        }
        return Map.of("disabled", 0, "delivered", delivered, "failed", failed);
    }

    private boolean dispatchOne(OmsFulfillmentRiskNoticeEntity candidate) {
        OmsFulfillmentRiskNoticeEntity claimed = noticeWorker.claim(candidate.getId(), candidate.getTenantId(),
                candidate.getFactoryCode());
        if (claimed == null) return false;
        DeliveryResult result = deliver(claimed);
        noticeWorker.finish(claimed.getId(), claimed.getTenantId(), claimed.getFactoryCode(),
                claimed.getAttemptCount(), result.delivered(), result.uncertain(), result.detail());
        return result.delivered();
    }

    private DeliveryResult deliver(OmsFulfillmentRiskNoticeEntity notice) {
        if (notice.getRecipientUserId() == null) {
            try {
                MessageBusinessDTO dto = new MessageBusinessDTO();
                dto.setCode(notice.getBusinessCode());
                dto.setMessage(notice.getMessage());
                R<Boolean> response = messages.businessPush(dto);
                boolean ok = response != null && response.isOk() && Boolean.TRUE.equals(response.getData());
                return new DeliveryResult(ok, false, ok ? "消息服务确认送达" : responseMessage(response));
            } catch (Exception ex) {
                return new DeliveryResult(false, true, error(ex));
            }
        }
        R<List<SysUserEntity>> userResponse;
        try {
            userResponse = users.getUserByIdInner(List.of(notice.getRecipientUserId()));
        } catch (Exception ex) {
            return new DeliveryResult(false, false, "收件人主数据读取失败：" + error(ex));
        }
        List<SysUserEntity> found = userResponse == null ? null : userResponse.getData();
        if (CollUtil.isEmpty(found) || found.stream().noneMatch(u -> Objects.equals(u.getUserId(), notice.getRecipientUserId())))
            return new DeliveryResult(false, false, "责任人系统账号不存在");
        SysUserEntity recipient = found.stream().filter(u -> Objects.equals(u.getUserId(), notice.getRecipientUserId()))
                .findFirst().orElseThrow();
        MessageDTO dto = new MessageDTO();
        dto.setCode("PLAN_OMS");
        dto.setMessageType(MessageTypeEnum.TEXT.getType());
        dto.setUserList(List.of(recipient.getUserId()));
        dto.setUserIds(StrUtil.nullToEmpty(recipient.getWxDingUserid()));
        dto.setMessage(notice.getMessage());
        dto.setRequestedChannels(List.of("SYSTEM"));
        try {
            R<Boolean> response = messages.pushPersonMsg(dto);
            boolean ok = response != null && response.isOk() && Boolean.TRUE.equals(response.getData());
            return new DeliveryResult(ok, false, ok ? "消息服务确认送达" : responseMessage(response));
        } catch (Exception ex) {
            return new DeliveryResult(false, true, error(ex));
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        String factory = access();
        Long tenant = TenantContextHolder.getTenantId();
        LambdaQueryWrapper<OmsFulfillmentRiskScanStateEntity> stateScope = stateScope(tenant, factory);
        LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity> noticeScope = noticeScope(tenant, factory);
        List<OmsFulfillmentRiskScanStateEntity> recentStates = states.selectList(stateScope.orderByDesc(
                OmsFulfillmentRiskScanStateEntity::getLastScanTime).last("LIMIT 20"));
        List<OmsFulfillmentRiskNoticeEntity> issueNotices = notices.selectList(noticeScope
                .in(OmsFulfillmentRiskNoticeEntity::getStatus, "UNCERTAIN", "ATTENTION")
                .orderByDesc(OmsFulfillmentRiskNoticeEntity::getId).last("LIMIT 20"));
        return Map.of("enabled", enabled, "scanIntervalMinutes", bound(scanIntervalMinutes, 5, 1440),
                "scanAttentionCount", states.selectCount(stateScope(tenant, factory)
                        .eq(OmsFulfillmentRiskScanStateEntity::getStatus, "ATTENTION")),
                "noticePendingCount", notices.selectCount(noticeScope(tenant, factory)
                        .in(OmsFulfillmentRiskNoticeEntity::getStatus, "PENDING", "RETRY", "SENDING")),
                "noticeAttentionCount", notices.selectCount(noticeScope(tenant, factory)
                        .in(OmsFulfillmentRiskNoticeEntity::getStatus, "UNCERTAIN", "ATTENTION")),
                "recentStates", recentStates, "issueNotices", issueNotices,
                "notice", "自动化默认关闭；必须同时启用配置并注册oms_fulfillment_risk_scan与oms_fulfillment_risk_notice_dispatch调度。结果不明消息不会自动重发。");
    }

    @Transactional(readOnly = true)
    public Page<OmsFulfillmentRiskNoticeEntity> noticePage(SearchPage page, String status, String orderCode) {
        String factory = access();
        validateNoticeStatus(status);
        LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity> query = noticeScope(TenantContextHolder.getTenantId(), factory)
                .eq(StrUtil.isNotBlank(status), OmsFulfillmentRiskNoticeEntity::getStatus, status)
                .like(StrUtil.isNotBlank(orderCode), OmsFulfillmentRiskNoticeEntity::getOrderCode,
                        validateOrderCode(orderCode))
                .orderByDesc(OmsFulfillmentRiskNoticeEntity::getId);
        return notices.selectPage(page.toMpPage(), query);
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsFulfillmentRiskNoticeEntity resolve(OmsFulfillmentRiskAutomationCommand.NoticeResolve command) {
        String factory = access();
        var actor = SecurityUtils.getUser();
        require(command != null && command.getNoticeId() != null && command.getRequestId() != null
                && command.getRequestId().matches("[A-Za-z0-9_-]{16,64}")
                && Set.of("RETRY", "ACK_DELIVERED", "CANCEL").contains(command.getAction())
                && StrUtil.isNotBlank(command.getExpectedStatus()) && StrUtil.isNotBlank(command.getReason())
                && command.getReason().trim().length() <= 1000, "催办核对须填写动作、原状态、稳定请求号和1000字内依据");
        String content = command.getAction() + "；" + command.getReason().trim();
        String type = "NOTICE_MANUAL_" + command.getAction();
        String fingerprint = sha(type + '|' + command.getNoticeId() + '|' + content + '|' + actor.getUsername());
        OmsFulfillmentRiskAutomationOperationEntity replay = operations.selectOne(
                new LambdaQueryWrapper<OmsFulfillmentRiskAutomationOperationEntity>()
                        .eq(OmsFulfillmentRiskAutomationOperationEntity::getRequestId, command.getRequestId())
                        .eq(OmsFulfillmentRiskAutomationOperationEntity::getFactoryCode, factory));
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), command.getNoticeId())
                    && Objects.equals(replay.getRequestFingerprint(), fingerprint), "催办核对请求号已用于其他内容");
            OmsFulfillmentRiskNoticeEntity replayNotice = notices.selectOne(
                    noticeScope(TenantContextHolder.getTenantId(), factory)
                            .eq(OmsFulfillmentRiskNoticeEntity::getId, command.getNoticeId()));
            require(replayNotice != null, "原催办核对结果不存在");
            return replayNotice;
        }
        OmsFulfillmentRiskNoticeEntity notice = notices.selectOne(noticeScope(TenantContextHolder.getTenantId(), factory)
                .eq(OmsFulfillmentRiskNoticeEntity::getId, command.getNoticeId()).last("FOR UPDATE"));
        require(notice != null && Objects.equals(notice.getStatus(), command.getExpectedStatus()), "催办状态已变化，请刷新后核对");
        String before = notice.getStatus();
        if ("RETRY".equals(command.getAction())) {
            require(Set.of("UNCERTAIN", "ATTENTION").contains(before), "仅结果不明或人工关注催办可重新排队");
            notice.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                    .setLastError("人工核对后重新排队：" + command.getReason().trim());
        } else if ("ACK_DELIVERED".equals(command.getAction())) {
            require("UNCERTAIN".equals(before), "仅结果不明催办可确认已送达");
            notice.setStatus("DELIVERED").setDeliveredTime(LocalDateTime.now()).setNextAttemptTime(null)
                    .setLastError("人工核实已送达：" + command.getReason().trim());
        } else {
            require(Set.of("PENDING", "RETRY", "UNCERTAIN", "ATTENTION").contains(before), "当前催办不可取消");
            notice.setStatus("CANCELLED").setNextAttemptTime(null)
                    .setLastError("人工取消：" + command.getReason().trim());
        }
        notice.setLastOperationRequestId(command.getRequestId()).setLastOperationType(type);
        require(notices.updateById(notice) == 1, "履约风险催办人工核对保存失败");
        noticeWorker.operation(notice, command.getRequestId(), type, before, notice.getStatus(), content,
                actor.getUsername(), StrUtil.blankToDefault(actor.getName(), actor.getUsername()));
        return notice;
    }

    private <T> T inScope(Long tenantId, String factoryCode, Supplier<T> work) {
        require(tenantId != null && StrUtil.isNotBlank(factoryCode), "自动化待办缺少租户或工厂");
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean tenantSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean factorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenantId);
            FactoryContextHolder.setFactoryCode(factoryCode);
            return work.get();
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(oldTenant);
            if (tenantSkip) TenantContextHolder.setTenantSkip();
            FactoryContextHolder.setFactoryCode(oldFactory);
            if (factorySkip) FactoryContextHolder.setFactorySkip();
        }
    }

    private void schedulerOnly() {
        require(RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null,
                "跨租户履约风险自动化仅允许独立调度器执行");
    }

    private String access() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                        && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "请实名登录并选择实际工厂");
        return factory;
    }

    private LambdaQueryWrapper<OmsFulfillmentRiskScanStateEntity> stateScope(Long tenant, String factory) {
        return new LambdaQueryWrapper<OmsFulfillmentRiskScanStateEntity>()
                .eq(OmsFulfillmentRiskScanStateEntity::getTenantId, tenant)
                .eq(OmsFulfillmentRiskScanStateEntity::getFactoryCode, factory);
    }

    private LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity> noticeScope(Long tenant, String factory) {
        return new LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity>()
                .eq(OmsFulfillmentRiskNoticeEntity::getTenantId, tenant)
                .eq(OmsFulfillmentRiskNoticeEntity::getFactoryCode, factory);
    }

    private void validateNoticeStatus(String status) {
        require(StrUtil.isBlank(status) || Set.of("PENDING", "SENDING", "RETRY", "DELIVERED", "UNCERTAIN",
                "ATTENTION", "CANCELLED").contains(status), "未知的履约风险催办状态");
    }

    private String validateOrderCode(String value) {
        String text = StrUtil.trim(value);
        require(text == null || text.length() <= 100, "订单编号筛选长度不得超过100");
        return text;
    }

    private int bound(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String responseMessage(R<?> response) {
        return response == null ? "消息服务无回执" : StrUtil.blankToDefault(response.getMessage(), "消息服务未确认送达");
    }

    private String error(Throwable value) {
        Throwable ex = value;
        while (ex.getCause() != null && ex.getCause() != ex) ex = ex.getCause();
        String m = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return m.substring(0, Math.min(1000, m.length()));
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("履约风险自动化指纹生成失败");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    private record DeliveryResult(boolean delivered, boolean uncertain, String detail) {
    }
}
