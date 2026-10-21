package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskScanCandidate;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskAutomationOperationEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskCaseEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskNoticeEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskScanStateEntity;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskAutomationOperationMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskNoticeMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskScanStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单交付行自动扫描工作器；每行独立事务，失败不会阻塞同批其他订单。
 */
@Service
@RequiredArgsConstructor
public class OmsFulfillmentRiskAutomationWorker {
    private final OmsFulfillmentRiskService riskService;
    private final OmsFulfillmentRiskScanStateMapper states;
    private final OmsFulfillmentRiskNoticeMapper notices;
    private final OmsFulfillmentRiskAutomationOperationMapper operations;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean scanOne(OmsFulfillmentRiskScanCandidate candidate, int intervalMinutes, int maxNoticeAttempts) {
        validateCandidate(candidate);
        states.lockKey("OMS-RISK-SCAN:" + candidate.getTenantId() + ':' + candidate.getFactoryCode()
                + ':' + candidate.getOrderDetailId());
        OmsFulfillmentRiskScanStateEntity state = state(candidate.getOrderDetailId(), true);
        LocalDateTime now = LocalDateTime.now();
        if (state != null && state.getNextScanTime() != null && state.getNextScanTime().isAfter(now)) return false;
        long bucket = now.toEpochSecond(ZoneOffset.ofHours(8)) / Math.max(60L, intervalMinutes * 60L);
        String requestId = "AUTO-RISK-" + candidate.getOrderDetailId() + '-' + bucket;
        List<OmsFulfillmentRiskCaseEntity> cases = riskService.syncSystem(candidate.getOrderDetailId(), requestId);
        String before = state == null ? null : state.getStatus();
        if (state == null) {
            state = new OmsFulfillmentRiskScanStateEntity().setOrderDetailId(candidate.getOrderDetailId())
                    .setOrderCode(candidate.getOrderCode()).setProductCode(candidate.getProductCode())
                    .setStatus("ACTIVE").setRevision(1).setSuccessCount(1).setFailureCount(0)
                    .setLastScanTime(now).setLastSuccessTime(now).setNextScanTime(now.plusMinutes(intervalMinutes))
                    .setLastRequestId(requestId).setLastRiskCount(activeCount(cases)).setFactoryCode(candidate.getFactoryCode());
            state.setTenantId(candidate.getTenantId());
            require(states.insert(state) == 1, "履约风险扫描状态创建失败");
        } else {
            state.setStatus("ACTIVE").setRevision(state.getRevision() + 1)
                    .setSuccessCount(state.getSuccessCount() + 1).setFailureCount(0).setLastError(null)
                    .setLastScanTime(now).setLastSuccessTime(now).setNextScanTime(now.plusMinutes(intervalMinutes))
                    .setLastRequestId(requestId).setLastRiskCount(activeCount(cases));
            require(states.updateById(state) == 1, "履约风险扫描状态保存失败");
        }
        operation(state.getId(), null, requestId + "-STATE", "SCAN_SUCCESS", before, "ACTIVE",
                "自动读取当前跨域事实；活动风险=" + activeCount(cases), candidate.getFactoryCode());
        enqueue(cases, maxNoticeAttempts, now);
        return true;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OmsFulfillmentRiskScanCandidate candidate, Throwable failure, int maxFailures) {
        validateCandidate(candidate);
        states.lockKey("OMS-RISK-SCAN:" + candidate.getTenantId() + ':' + candidate.getFactoryCode()
                + ':' + candidate.getOrderDetailId());
        OmsFulfillmentRiskScanStateEntity state = state(candidate.getOrderDetailId(), true);
        LocalDateTime now = LocalDateTime.now();
        String error = error(failure);
        int failures = state == null ? 1 : state.getFailureCount() + 1;
        String after = failures >= maxFailures ? "ATTENTION" : "ACTIVE";
        int backoff = Math.min(360, 15 * (1 << Math.min(failures - 1, 4)));
        String requestId = "AUTO-RISK-FAIL-" + IdUtil.getSnowflakeNextIdStr();
        String before = state == null ? null : state.getStatus();
        if (state == null) {
            state = new OmsFulfillmentRiskScanStateEntity().setOrderDetailId(candidate.getOrderDetailId())
                    .setOrderCode(candidate.getOrderCode()).setProductCode(candidate.getProductCode())
                    .setStatus(after).setRevision(1).setSuccessCount(0).setFailureCount(failures)
                    .setLastScanTime(now).setNextScanTime(now.plusMinutes(backoff)).setLastRequestId(requestId)
                    .setLastRiskCount(0).setLastError(error).setFactoryCode(candidate.getFactoryCode());
            state.setTenantId(candidate.getTenantId());
            require(states.insert(state) == 1, "履约风险扫描失败状态创建失败");
        } else {
            state.setStatus(after).setRevision(state.getRevision() + 1).setFailureCount(failures)
                    .setLastScanTime(now).setNextScanTime(now.plusMinutes(backoff)).setLastRequestId(requestId)
                    .setLastError(error);
            require(states.updateById(state) == 1, "履约风险扫描失败状态保存失败");
        }
        operation(state.getId(), null, requestId + "-STATE", "SCAN_FAILURE", before, after,
                "连续失败=" + failures + "；下次重试=" + state.getNextScanTime() + "；" + error,
                candidate.getFactoryCode());
    }

    private void enqueue(List<OmsFulfillmentRiskCaseEntity> cases, int maxAttempts, LocalDateTime now) {
        for (OmsFulfillmentRiskCaseEntity risk : cases) {
            String category = null, key = null;
            Long recipient = null;
            if ("OPEN".equals(risk.getStatus())) {
                category = "UNASSIGNED";
                key = category + ':' + risk.getOccurrenceCount();
            } else if ("PENDING_CLOSE".equals(risk.getStatus())) {
                category = "PENDING_CLOSE";
                key = category + ':' + epoch(risk.getLastClearedTime());
            } else if (Set.of("ASSIGNED", "MITIGATING").contains(risk.getStatus())
                    && risk.getResponseDueTime() != null && !risk.getResponseDueTime().isAfter(now)) {
                category = "OVERDUE";
                key = category + ':' + epoch(risk.getResponseDueTime()) + ':' + LocalDate.now();
                recipient = risk.getOwnerUserId();
            } else if (Set.of("ASSIGNED", "MITIGATING").contains(risk.getStatus())
                    && risk.getResponseDueTime() != null && !risk.getResponseDueTime().isAfter(now.plusMinutes(30))) {
                category = "DUE_SOON";
                key = category + ':' + epoch(risk.getResponseDueTime());
                recipient = risk.getOwnerUserId();
            }
            if (category == null) continue;
            String noticeKey = risk.getId() + ":" + key;
            if (notices.selectCount(new LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity>()
                    .eq(OmsFulfillmentRiskNoticeEntity::getNoticeKey, noticeKey)) > 0) continue;
            String message = message(risk, category);
            OmsFulfillmentRiskNoticeEntity notice = new OmsFulfillmentRiskNoticeEntity()
                    .setCaseId(risk.getId()).setOrderDetailId(risk.getOrderDetailId()).setNoticeKey(noticeKey)
                    .setCaseNo(risk.getCaseNo()).setOrderCode(risk.getOrderCode()).setProductCode(risk.getProductCode())
                    .setRiskName(risk.getRiskName())
                    .setCategory(category).setRecipientUserId(recipient).setRecipientCode(risk.getOwnerCode())
                    .setRecipientName(risk.getOwnerName()).setBusinessCode("PLAN_OMS").setMessage(message)
                    .setStatus("PENDING").setAttemptCount(0).setMaxAttempts(maxAttempts)
                    .setNextAttemptTime(now).setFactoryCode(risk.getFactoryCode());
            notice.setTenantId(risk.getTenantId());
            String operationRequestId = "AUTO-NOTICE-" + risk.getId() + '-' + sha(noticeKey).substring(0, 16) + "-ENQ";
            notice.setLastOperationRequestId(operationRequestId).setLastOperationType("NOTICE_ENQUEUE");
            require(notices.insert(notice) == 1, "履约风险催办入箱失败");
            operation(null, notice.getId(), operationRequestId, "NOTICE_ENQUEUE",
                    null, "PENDING", category + "；案件=" + risk.getCaseNo(), risk.getFactoryCode());
        }
    }

    private String message(OmsFulfillmentRiskCaseEntity risk, String category) {
        String action = switch (category) {
            case "UNASSIGNED" -> "尚未分派责任人，请主管立即分派";
            case "DUE_SOON" -> "响应期限即将到达，请责任人提交处置进展";
            case "OVERDUE" -> "响应期限已逾期，请责任人立即处理并由主管升级协调";
            case "PENDING_CLOSE" -> "风险事实已恢复，请非责任人复核关闭";
            default -> "请核对处理";
        };
        return "订单履约风险通知\n案件：" + risk.getCaseNo() + "\n订单：" + risk.getOrderCode()
                + "\n产品：" + risk.getProductCode() + "\n风险：" + risk.getRiskName()
                + "\n等级：" + risk.getSeverity() + "\n要求：" + action;
    }

    private OmsFulfillmentRiskScanStateEntity state(Long orderDetailId, boolean lock) {
        LambdaQueryWrapper<OmsFulfillmentRiskScanStateEntity> query =
                new LambdaQueryWrapper<OmsFulfillmentRiskScanStateEntity>()
                        .eq(OmsFulfillmentRiskScanStateEntity::getOrderDetailId, orderDetailId);
        if (lock) query.last("FOR UPDATE");
        return states.selectOne(query);
    }

    private void operation(Long stateId, Long noticeId, String requestId, String type, String before,
                           String after, String content, String factoryCode) {
        OmsFulfillmentRiskAutomationOperationEntity op = new OmsFulfillmentRiskAutomationOperationEntity()
                .setScanStateId(stateId).setNoticeId(noticeId).setRequestId(requestId)
                .setRequestFingerprint(sha(type + '|' + stateId + '|' + noticeId + '|' + content))
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode("SYSTEM").setOperatorName("履约风险自动化")
                .setOperationTime(LocalDateTime.now()).setFactoryCode(factoryCode);
        op.setTenantId(TenantContextHolder.getTenantId());
        require(operations.insert(op) == 1, "履约风险自动化履历保存失败");
    }

    private int activeCount(List<OmsFulfillmentRiskCaseEntity> cases) {
        return (int) cases.stream().filter(row -> !"CLOSED".equals(row.getStatus())).count();
    }

    private long epoch(LocalDateTime value) {
        return value == null ? 0 : value.toEpochSecond(ZoneOffset.ofHours(8));
    }

    private void validateCandidate(OmsFulfillmentRiskScanCandidate c) {
        require(c != null && c.getOrderDetailId() != null && c.getTenantId() != null
                && StrUtil.isNotBlank(c.getFactoryCode()), "履约风险扫描候选缺少租户、工厂或订单行");
        require(Objects.equals(c.getTenantId(), TenantContextHolder.getTenantId()), "履约风险扫描租户上下文不一致");
    }

    private String error(Throwable value) {
        Throwable ex = value;
        while (ex.getCause() != null && ex.getCause() != ex) ex = ex.getCause();
        String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return message.substring(0, Math.min(message.length(), 1000));
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("履约风险自动化指纹生成失败");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
