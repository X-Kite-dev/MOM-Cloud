package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskAutomationOperationEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskCaseEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskNoticeEntity;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskAutomationOperationMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskCaseMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskNoticeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OmsFulfillmentRiskNoticeWorker {
    private final OmsFulfillmentRiskNoticeMapper notices;
    private final OmsFulfillmentRiskAutomationOperationMapper operations;
    private final OmsFulfillmentRiskCaseMapper cases;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public OmsFulfillmentRiskNoticeEntity claim(Long id, Long tenantId, String factoryCode) {
        OmsFulfillmentRiskNoticeEntity notice = locked(id, tenantId, factoryCode);
        if (notice == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if ("SENDING".equals(notice.getStatus())) {
            if (notice.getClaimedTime() == null || notice.getClaimedTime().isAfter(now.minusMinutes(10))) return null;
            String before = notice.getStatus();
            String requestId = "AUTO-NOTICE-STALE-" + IdUtil.getSnowflakeNextIdStr();
            notice.setStatus("UNCERTAIN").setNextAttemptTime(null)
                    .setLastError("消息投递占用超时，可能已送达；禁止自动重发，须人工核对")
                    .setLastOperationRequestId(requestId).setLastOperationType("NOTICE_UNCERTAIN");
            require(notices.updateById(notice) == 1, "履约风险催办结果不明保存失败");
            operation(notice, requestId,
                    "NOTICE_UNCERTAIN", before, "UNCERTAIN", notice.getLastError(), "SYSTEM", "履约风险消息任务");
            return null;
        }
        if (!java.util.Set.of("PENDING", "RETRY").contains(notice.getStatus())
                || notice.getNextAttemptTime() == null || notice.getNextAttemptTime().isAfter(now)) return null;
        OmsFulfillmentRiskCaseEntity risk = cases.selectOne(new LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity>()
                .eq(OmsFulfillmentRiskCaseEntity::getId, notice.getCaseId())
                .eq(OmsFulfillmentRiskCaseEntity::getFactoryCode, factoryCode));
        if (!relevant(notice, risk, now)) {
            String before = notice.getStatus();
            String requestId = "AUTO-NOTICE-CANCEL-" + IdUtil.getSnowflakeNextIdStr();
            notice.setStatus("CANCELLED").setNextAttemptTime(null)
                    .setLastError("投递前重验发现案件状态、责任人或期限已变化，旧催办自动取消")
                    .setLastOperationRequestId(requestId).setLastOperationType("NOTICE_AUTO_CANCEL");
            require(notices.updateById(notice) == 1, "过时履约风险催办取消失败");
            operation(notice, requestId, "NOTICE_AUTO_CANCEL", before, "CANCELLED", notice.getLastError(),
                    "SYSTEM", "履约风险消息任务");
            return null;
        }
        String before = notice.getStatus();
        String requestId = "AUTO-NOTICE-CLAIM-" + IdUtil.getSnowflakeNextIdStr();
        notice.setStatus("SENDING").setAttemptCount(notice.getAttemptCount() + 1).setClaimedTime(now)
                .setNextAttemptTime(null).setLastError(null)
                .setLastOperationRequestId(requestId).setLastOperationType("NOTICE_CLAIM");
        require(notices.updateById(notice) == 1, "履约风险催办占用失败");
        operation(notice, requestId,
                "NOTICE_CLAIM", before, "SENDING", "占用第" + notice.getAttemptCount() + "次投递",
                "SYSTEM", "履约风险消息任务");
        return notice;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id, Long tenantId, String factoryCode, int attempt, boolean delivered,
                       boolean uncertain, String detail) {
        OmsFulfillmentRiskNoticeEntity notice = locked(id, tenantId, factoryCode);
        require(notice != null && "SENDING".equals(notice.getStatus())
                && Objects.equals(notice.getAttemptCount(), attempt), "履约风险催办状态或尝试次数已变化");
        String after;
        String type;
        if (delivered) {
            after = "DELIVERED";
            type = "NOTICE_DELIVERED";
            notice.setDeliveredTime(LocalDateTime.now()).setLastError(null);
        } else if (uncertain) {
            after = "UNCERTAIN";
            type = "NOTICE_UNCERTAIN";
            notice.setLastError(trim("投递结果不明：" + detail));
        } else if (attempt >= notice.getMaxAttempts()) {
            after = "ATTENTION";
            type = "NOTICE_FAILURE";
            notice.setLastError(trim("达到最大尝试次数：" + detail));
        } else {
            after = "RETRY";
            type = "NOTICE_FAILURE";
            notice.setNextAttemptTime(LocalDateTime.now().plusMinutes(Math.min(240, 5L << Math.min(attempt - 1, 5))))
                    .setLastError(trim(detail));
        }
        String requestId = "AUTO-NOTICE-RESULT-" + IdUtil.getSnowflakeNextIdStr();
        notice.setStatus(after).setLastOperationRequestId(requestId).setLastOperationType(type);
        require(notices.updateById(notice) == 1, "履约风险催办投递结果保存失败");
        operation(notice, requestId, type,
                "SENDING", after, StrUtil.blankToDefault(notice.getLastError(), "消息服务确认送达"),
                "SYSTEM", "履约风险消息任务");
    }

    public void operation(OmsFulfillmentRiskNoticeEntity notice, String requestId, String type,
                          String before, String after, String content, String actorCode, String actorName) {
        OmsFulfillmentRiskAutomationOperationEntity op = new OmsFulfillmentRiskAutomationOperationEntity()
                .setNoticeId(notice.getId()).setRequestId(requestId)
                .setRequestFingerprint(sha(type + '|' + notice.getId() + '|' + content + '|' + actorCode))
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(actorCode).setOperatorName(actorName).setOperationTime(LocalDateTime.now())
                .setFactoryCode(notice.getFactoryCode());
        op.setTenantId(notice.getTenantId());
        require(operations.insert(op) == 1, "履约风险催办操作履历保存失败");
    }

    private OmsFulfillmentRiskNoticeEntity locked(Long id, Long tenantId, String factoryCode) {
        return notices.selectOne(new LambdaQueryWrapper<OmsFulfillmentRiskNoticeEntity>()
                .eq(OmsFulfillmentRiskNoticeEntity::getId, id)
                .eq(OmsFulfillmentRiskNoticeEntity::getTenantId, tenantId)
                .eq(OmsFulfillmentRiskNoticeEntity::getFactoryCode, factoryCode).last("FOR UPDATE"));
    }

    private boolean relevant(OmsFulfillmentRiskNoticeEntity notice, OmsFulfillmentRiskCaseEntity risk,
                             LocalDateTime now) {
        if (risk == null || "CLOSED".equals(risk.getStatus())) return false;
        return switch (notice.getCategory()) {
            case "UNASSIGNED" -> "OPEN".equals(risk.getStatus()) && risk.getOwnerUserId() == null
                    && notice.getNoticeKey().equals(risk.getId() + ":UNASSIGNED:" + risk.getOccurrenceCount());
            case "PENDING_CLOSE" -> "PENDING_CLOSE".equals(risk.getStatus())
                    && notice.getNoticeKey().equals(risk.getId() + ":PENDING_CLOSE:" + epoch(risk.getLastClearedTime()));
            case "DUE_SOON" -> java.util.Set.of("ASSIGNED", "MITIGATING").contains(risk.getStatus())
                    && Objects.equals(notice.getRecipientUserId(), risk.getOwnerUserId())
                    && risk.getResponseDueTime() != null && risk.getResponseDueTime().isAfter(now)
                    && notice.getNoticeKey().equals(risk.getId() + ":DUE_SOON:" + epoch(risk.getResponseDueTime()));
            case "OVERDUE" -> java.util.Set.of("ASSIGNED", "MITIGATING").contains(risk.getStatus())
                    && Objects.equals(notice.getRecipientUserId(), risk.getOwnerUserId())
                    && risk.getResponseDueTime() != null && !risk.getResponseDueTime().isAfter(now)
                    && notice.getNoticeKey().startsWith(risk.getId() + ":OVERDUE:" + epoch(risk.getResponseDueTime()) + ':');
            default -> false;
        };
    }

    private long epoch(LocalDateTime value) {
        return value == null ? 0 : value.toEpochSecond(ZoneOffset.ofHours(8));
    }

    private String trim(String value) {
        String text = StrUtil.blankToDefault(value, "未知错误");
        return text.substring(0, Math.min(1000, text.length()));
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("履约风险消息指纹生成失败");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
