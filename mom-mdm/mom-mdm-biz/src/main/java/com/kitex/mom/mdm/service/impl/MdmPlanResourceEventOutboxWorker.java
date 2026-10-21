package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 单条MDM资源事实独立投递；PLAN以来源事件ID永久幂等，允许超时后的安全重放。
 */
@Service
@RequiredArgsConstructor
public class MdmPlanResourceEventOutboxWorker {
    private final MdmPlanResourceEventOutboxMapper outboxMapper;
    private final MdmPlanResourceEventOperationMapper operationMapper;
    private final MdmToolingUnavailableWindowMapper toolingWindowMapper;
    private final MdmToolingMapper toolingMapper;
    private final MdmEmployeeCalendarExceptionMapper employeeCalendarMapper;
    private final MdmEmployeeSkillMapper employeeSkillMapper;
    private final MdmDeviceRecoveryReceiptMapper recoveryReceiptMapper;
    private final MdmDeviceMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final RemotePlnAutomationEventService remoteEventService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new NonRetryableDeliveryException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverOne(Long outboxId) {
        MdmPlanResourceEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        if (!"ACTIVE".equals(outbox.getSourceStatus())
                || !MdmPlanResourceEventOutboxServiceImpl.DEVICE_RECOVERY.equals(outbox.getSourceType())
                && !Boolean.TRUE.equals(outbox.getOpenEnded())
                && (outbox.getExpectedRecoveryTime() == null
                || !outbox.getExpectedRecoveryTime().isAfter(LocalDateTime.now()))) {
            throw new SourceClosedException("MDM不可用事实已撤销或结束");
        }
        require(StrUtil.isNotBlank(outbox.getSourcePayloadJson())
                        && Objects.equals(outbox.getSourceFingerprint(), hash(outbox.getSourcePayloadJson())),
                "MDM资源来源快照缺失或指纹不一致");
        require(StrUtil.isNotBlank(outbox.getPayloadJson())
                        && Objects.equals(outbox.getPayloadFingerprint(), hash(outbox.getPayloadJson())),
                "MDM资源事件载荷缺失或指纹不一致");
        revalidateSource(outbox);

        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事件投递占用失败");
        operation(outbox, "DELIVER:" + outbox.getEventId() + ':' + attempt, "DELIVERY_START", before,
                "PROCESSING", attempt, "开始向PLAN制造事件中心投递MDM资源状态事实");

        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(outbox.getEventId());
        command.setSourceSystem("MDM");
        command.setEventType(outbox.getEventType());
        command.setEventVersion(outbox.getEventVersion());
        command.setAggregateType("MDM_RESOURCE");
        command.setAggregateId(outbox.getResourceType() + ':' + outbox.getResourceCode());
        command.setFactoryCode(outbox.getFactoryCode());
        command.setOccurredTime(outbox.getOccurredTime());
        command.setPriority(10);
        command.setPayloadJson(outbox.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException(response == null ? "PLAN制造事件中心无响应" : response.getMessage());
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), outbox.getEventId()), "PLAN收件回执事件ID不一致");
        outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                .setPlanInboxId(receipt.getInboxId()).setPlanEventNo(receipt.getEventNo())
                .setPlanEventStatus(receipt.getStatus()).setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事件成功状态保存失败");
        operation(outbox, "DELIVERED:" + outbox.getEventId() + ':' + attempt, "DELIVERY_SUCCESS", "PROCESSING",
                "DELIVERED", attempt, "PLAN收件号=" + receipt.getEventNo() + "，状态=" + receipt.getStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : ""));
        return true;
    }

    private void revalidateSource(MdmPlanResourceEventOutboxEntity outbox) {
        if (MdmPlanResourceEventOutboxServiceImpl.DEVICE_RECOVERY.equals(outbox.getSourceType())) {
            MdmDeviceRecoveryReceipt receipt = recoveryReceiptMapper.selectById(outbox.getSourceId());
            JsonNode snapshot = sourceSnapshot(outbox);
            if (receipt == null || !Objects.equals(receipt.getTenantId(), outbox.getTenantId())
                    || !Objects.equals(receipt.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(receipt.getDeviceCode(), outbox.getResourceCode())
                    || !Objects.equals(receipt.getRequestId(), outbox.getSourceNo())
                    || !Objects.equals(receipt.getAfterJson(), snapshot.path("afterJson").asText())
                    || !Objects.equals(receipt.getRequestFingerprint(), snapshot.path("requestFingerprint").asText())
                    || !Objects.equals(receipt.getSourceFingerprint(), snapshot.path("sourceFingerprint").asText())
                    || !Objects.equals(String.valueOf(receipt.getId()), snapshot.path("receiptId").asText()))
                throw new NonRetryableDeliveryException("MDM设备恢复回执或冻结来源已不一致");
            MdmDeviceEntity device = deviceMapper.selectById(receipt.getDeviceId());
            if (device == null || !Objects.equals(device.getTenantId(), outbox.getTenantId())
                    || !Objects.equals(device.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(device.getDeviceCode(), outbox.getResourceCode())
                    || !Boolean.TRUE.equals(device.getStatus()) || !"0".equals(device.getDeviceState())
                    || !"1".equals(device.getDeviceUsable()))
                throw new SourceClosedException("设备已再次不可用，旧恢复事件不能继续投递");
            var latest = recoveryReceiptMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MdmDeviceRecoveryReceipt>()
                    .eq(MdmDeviceRecoveryReceipt::getDeviceId, device.getId()).eq(MdmDeviceRecoveryReceipt::getTenantId, outbox.getTenantId())
                    .eq(MdmDeviceRecoveryReceipt::getFactoryCode, outbox.getFactoryCode()).orderByDesc(MdmDeviceRecoveryReceipt::getId).last("LIMIT 1"));
            if (latest == null || !Objects.equals(latest.getId(), receipt.getId()))
                throw new SourceClosedException("已有更新的设备资格恢复回执，旧恢复事件已过时");
            return;
        }
        if (MdmPlanResourceEventOutboxServiceImpl.TOOLING_WINDOW.equals(outbox.getSourceType())) {
            MdmToolingUnavailableWindowEntity source = toolingWindowMapper.selectById(outbox.getSourceId());
            String windowType = source == null ? "" : StrUtil.blankToDefault(source.getWindowType(), "")
                    .toUpperCase(Locale.ROOT);
            if (source == null || Boolean.FALSE.equals(source.getStatus())
                    || !Objects.equals(source.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(source.getToolingCode(), outbox.getResourceCode())
                    || !Objects.equals(source.getStartTime(), outbox.getEventStartTime())
                    || !Objects.equals(source.getEndTime(), outbox.getExpectedRecoveryTime())
                    || !Objects.equals(windowType + "："
                    + StrUtil.blankToDefault(source.getReason(), "工装计划不可用"), outbox.getReason())) {
                throw new SourceClosedException("工装不可用窗口不存在、已停用或时间已经变化");
            }
            return;
        }
        if (MdmPlanResourceEventOutboxServiceImpl.TOOLING_MASTER.equals(outbox.getSourceType())) {
            MdmToolingEntity source = toolingMapper.selectById(outbox.getSourceId());
            String state = source == null ? "" : StrUtil.blankToDefault(source.getToolingState(), "AVAILABLE")
                    .toUpperCase(Locale.ROOT);
            boolean unavailable = source != null && (!Boolean.TRUE.equals(source.getStatus())
                    || !List.of("AVAILABLE", "IN_USE").contains(state));
            JsonNode snapshot = sourceSnapshot(outbox);
            if (!unavailable || !Boolean.TRUE.equals(outbox.getOpenEnded())
                    || !Objects.equals(source.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(source.getToolingCode(), outbox.getResourceCode())
                    || !Objects.equals(source.getUnavailableSince(), outbox.getEventStartTime())
                    || !Objects.equals(state, snapshot.path("toolingState").asText())) {
                throw new SourceClosedException("工装主档已恢复可排产或主状态事实已经变化");
            }
            return;
        }
        if (MdmPlanResourceEventOutboxServiceImpl.EMPLOYEE_CALENDAR.equals(outbox.getSourceType())) {
            MdmEmployeeCalendarExceptionEntity source = employeeCalendarMapper.selectById(outbox.getSourceId());
            if (source == null || source.getCalendarDate() == null) {
                throw new SourceClosedException("人员日历例外不存在或日期缺失");
            }
            String exceptionType = StrUtil.blankToDefault(source.getExceptionType(), "").toUpperCase(Locale.ROOT);
            LocalDateTime actualStart = source.getStartTime() == null
                    ? source.getCalendarDate().atStartOfDay() : source.getCalendarDate().atTime(source.getStartTime());
            LocalDateTime actualEnd = source.getEndTime() == null
                    ? source.getCalendarDate().plusDays(1).atStartOfDay()
                    : source.getCalendarDate().atTime(source.getEndTime());
            if (actualStart != null && actualEnd != null && !actualEnd.isAfter(actualStart))
                actualEnd = actualEnd.plusDays(1);
            if (Boolean.FALSE.equals(source.getStatus())
                    || !Objects.equals(source.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(source.getEmployeeCode(), outbox.getResourceCode())
                    || "OVERTIME".equals(exceptionType)
                    || !Objects.equals(actualStart, outbox.getEventStartTime())
                    || !Objects.equals(actualEnd, outbox.getExpectedRecoveryTime())
                    || !Objects.equals(exceptionType + "："
                    + StrUtil.blankToDefault(source.getReason(), "人员计划不可用"), outbox.getReason())) {
                throw new SourceClosedException("人员日历例外不存在、已停用或不再构成不可用事实");
            }
            return;
        }
        if (MdmPlanResourceEventOutboxServiceImpl.EMPLOYEE_QUALIFICATION.equals(outbox.getSourceType())) {
            MdmEmployeeSkillEntity source = employeeSkillMapper.selectById(outbox.getSourceId());
            JsonNode snapshot = sourceSnapshot(outbox);
            String factType = snapshot.path("factType").asText();
            boolean disabled = "QUALIFICATION_DISABLED".equals(factType);
            LocalDateTime actualStart = source == null ? null : disabled ? source.getInvalidTime()
                    : source.getValidTo() == null ? null : source.getValidTo().plusDays(1).atStartOfDay();
            boolean stillInvalid = source != null && (disabled
                    ? Boolean.FALSE.equals(source.getStatus())
                    : Boolean.TRUE.equals(source.getStatus()) && source.getValidTo() != null);
            if (!stillInvalid || !Boolean.TRUE.equals(outbox.getOpenEnded())
                    || !Objects.equals(source.getFactoryCode(), outbox.getFactoryCode())
                    || !Objects.equals(source.getEmployeeCode(), outbox.getResourceCode())
                    || !Objects.equals(source.getSkillCode(), snapshot.path("skillCode").asText())
                    || !Objects.equals(actualStart, outbox.getEventStartTime())) {
                throw new SourceClosedException("人员资质已恢复有效或失效事实已经变化");
            }
            return;
        }
        throw new NonRetryableDeliveryException("MDM资源事件来源类型不受支持");
    }

    private JsonNode sourceSnapshot(MdmPlanResourceEventOutboxEntity outbox) {
        try {
            return objectMapper.readTree(outbox.getSourcePayloadJson());
        } catch (Exception exception) {
            throw new NonRetryableDeliveryException("MDM资源来源快照无法解析");
        }
    }

    private void operation(MdmPlanResourceEventOutboxEntity outbox, String requestId, String type, String before,
                           String after, int attempt, String content) {
        require(operationMapper.insert(new MdmPlanResourceEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM").setOperatorName("MDM资源事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "MDM资源事件投递履历保存失败");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    public static class NonRetryableDeliveryException extends RuntimeException {
        public NonRetryableDeliveryException(String message) {
            super(message);
        }
    }

    public static class SourceClosedException extends NonRetryableDeliveryException {
        public SourceClosedException(String message) {
            super(message);
        }
    }
}
