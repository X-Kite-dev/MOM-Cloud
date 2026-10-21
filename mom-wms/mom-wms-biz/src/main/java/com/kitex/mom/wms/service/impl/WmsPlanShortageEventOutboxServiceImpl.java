package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsPlanShortageEventDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOperationEntity;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import com.kitex.mom.wms.entity.WmsTransferVarianceCaseEntity;
import com.kitex.mom.wms.mapper.WmsPlanShortageEventOperationMapper;
import com.kitex.mom.wms.mapper.WmsPlanShortageEventOutboxMapper;
import com.kitex.mom.wms.service.WmsPlanShortageEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WmsPlanShortageEventOutboxServiceImpl implements WmsPlanShortageEventOutboxService {
    public static final String SOURCE_CANCELLED = "WMS_AGENT_TRANSFER_CANCELLED";
    public static final String SOURCE_SHORT_RECEIPT = "WMS_AGENT_TRANSFER_SHORT_RECEIPT";

    private final WmsPlanShortageEventOutboxMapper outboxMapper;
    private final WmsPlanShortageEventOperationMapper operationMapper;
    private final WmsPlanShortageEventOutboxWorker worker;
    private final WmsPlanShortageEventOutboxFailureService failureService;
    private final ObjectMapper objectMapper;

    private static String compact(String value, int max) {
        String text = StrUtil.blankToDefault(value, "-").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsPlanShortageEventOutboxEntity enqueueTransferCancelled(WmsAllocationBillEntity bill,
                                                                     BigDecimal shortageQuantity) {
        requireSourceBill(bill, shortageQuantity);
        require(bill.getCancelTime() != null, "Agent调拨取消缺少权威取消时刻");
        return enqueue(SOURCE_CANCELLED, bill.getId(), bill.getBillNo(), bill, null, shortageQuantity,
                bill.getCancelTime(), "已冻结齐套供应的跨仓调拨被取消；" + bill.getCancelReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsPlanShortageEventOutboxEntity enqueueTransferShortReceived(WmsAllocationBillEntity bill,
                                                                         WmsTransferVarianceCaseEntity variance,
                                                                         BigDecimal shortageQuantity) {
        requireSourceBill(bill, shortageQuantity);
        require(variance != null && variance.getId() != null && Objects.equals(variance.getAllocationBillId(), bill.getId())
                && variance.getReportedTime() != null, "Agent调拨短收差异事实不完整");
        return enqueue(SOURCE_SHORT_RECEIPT, variance.getId(), variance.getCaseNo(), bill, variance,
                shortageQuantity, variance.getReportedTime(), "已冻结齐套供应的跨仓调拨最终短收；"
                        + variance.getReasonCode() + "：" + variance.getReasonDescription());
    }

    private WmsPlanShortageEventOutboxEntity enqueue(String sourceType, Long sourceId, String sourceNo,
                                                     WmsAllocationBillEntity bill,
                                                     WmsTransferVarianceCaseEntity variance,
                                                     BigDecimal shortageQuantity, LocalDateTime occurredTime,
                                                     String reason) {
        String eventId = SOURCE_CANCELLED.equals(sourceType) ? "WMS-TRANSFER:" + bill.getId() + ":CANCELLED"
                : "WMS-VARIANCE:" + sourceId + ":SHORT";
        outboxMapper.lockKey(eventId);
        String sourceJson = sourceSnapshot(sourceType, sourceId, sourceNo, bill, variance, shortageQuantity,
                occurredTime, reason);
        String sourceFingerprint = hash(sourceJson);
        WmsPlanShortageEventOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<WmsPlanShortageEventOutboxEntity>()
                        .eq(WmsPlanShortageEventOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), sourceFingerprint),
                    "同一WMS供应失效事件ID的权威事实已变化，禁止覆盖");
            return existing;
        }
        WmsPlanShortageEventOutboxEntity outbox = new WmsPlanShortageEventOutboxEntity()
                .setEventId(eventId).setEventType("MATERIAL_SHORTAGE_CHANGED").setEventVersion(1)
                .setSourceType(sourceType).setSourceId(sourceId).setSourceNo(sourceNo)
                .setAllocationBillId(bill.getId()).setAllocationBillNo(bill.getBillNo())
                .setVarianceCaseId(variance == null ? null : variance.getId())
                .setVarianceCaseNo(variance == null ? null : variance.getCaseNo())
                .setMrpId(bill.getSourceMrpId()).setIssueNo(bill.getSourceBusinessNo())
                .setMaterialCode(bill.getSourceMaterialCode()).setShortageQuantity(shortageQuantity)
                .setOccurredTime(occurredTime.truncatedTo(ChronoUnit.MILLIS)).setReason(compact(reason, 1000))
                .setSourcePayloadJson(sourceJson).setSourceFingerprint(sourceFingerprint)
                .setStatus("WAITING_DATA").setAttemptCount(0).setMaxAttempts(8).setFactoryCode(bill.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "WMS供应失效事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, "WAITING_DATA", 0,
                "供应失效事实已与WMS业务事务同箱保存，等待补充新的预计齐料时刻");
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void supplySupplementForecast(Long varianceCaseId, WmsAllocationBillEntity supplementBill) {
        if (varianceCaseId == null || supplementBill == null || supplementBill.getId() == null) return;
        WmsPlanShortageEventOutboxEntity found = outboxMapper.selectOne(
                new LambdaQueryWrapper<WmsPlanShortageEventOutboxEntity>()
                        .eq(WmsPlanShortageEventOutboxEntity::getVarianceCaseId, varianceCaseId));
        if (found == null) return;
        WmsPlanShortageEventOutboxEntity outbox = requireOutbox(found.getId(), true);
        if (List.of("PROCESSING", "RETRY_WAIT", "DELIVERED", "DEAD_LETTER").contains(outbox.getStatus())) return;
        require(Objects.equals(supplementBill.getSourceMrpId(), outbox.getMrpId())
                        && Objects.equals(supplementBill.getFactoryCode(), outbox.getFactoryCode())
                        && supplementBill.getEstimatedArrivalTime() != null
                        && supplementBill.getEstimatedArrivalTime().isAfter(LocalDateTime.now()),
                "补调单的MRP来源、工厂或预计到达时刻不完整");
        String requestId = "AUTO:SUPPLEMENT:" + supplementBill.getId();
        String basis = "短收差异批准补调，按调拨单" + supplementBill.getBillNo() + "冻结路线策略预计到达";
        applyForecast(outbox, supplementBill.getEstimatedArrivalTime(), requestId, basis, true);
    }

    @Override
    public Page<WmsPlanShortageEventOutboxEntity> page(SearchPage page, WmsPlanShortageEventDTO.Query query) {
        WmsPlanShortageEventDTO.Query q = query == null ? new WmsPlanShortageEventDTO.Query() : query;
        return outboxMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<WmsPlanShortageEventOutboxEntity>()
                .eq(StrUtil.isNotBlank(q.getSourceType()), WmsPlanShortageEventOutboxEntity::getSourceType, q.getSourceType())
                .like(StrUtil.isNotBlank(q.getSourceNo()), WmsPlanShortageEventOutboxEntity::getSourceNo, q.getSourceNo())
                .like(StrUtil.isNotBlank(q.getAllocationBillNo()), WmsPlanShortageEventOutboxEntity::getAllocationBillNo,
                        q.getAllocationBillNo())
                .like(StrUtil.isNotBlank(q.getIssueNo()), WmsPlanShortageEventOutboxEntity::getIssueNo, q.getIssueNo())
                .like(StrUtil.isNotBlank(q.getMaterialCode()), WmsPlanShortageEventOutboxEntity::getMaterialCode,
                        q.getMaterialCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), WmsPlanShortageEventOutboxEntity::getStatus, q.getStatus())
                .orderByAsc(WmsPlanShortageEventOutboxEntity::getStatus)
                .orderByDesc(WmsPlanShortageEventOutboxEntity::getOccurredTime));
    }

    @Override
    public WmsPlanShortageEventDTO.Detail detail(Long outboxId) {
        WmsPlanShortageEventOutboxEntity outbox = requireOutbox(outboxId, false);
        WmsPlanShortageEventDTO.Detail detail = new WmsPlanShortageEventDTO.Detail();
        BeanUtils.copyProperties(outbox, detail);
        detail.setOperations(operationMapper.selectList(
                new LambdaQueryWrapper<WmsPlanShortageEventOperationEntity>()
                        .eq(WmsPlanShortageEventOperationEntity::getOutboxId, outboxId)
                        .orderByAsc(WmsPlanShortageEventOperationEntity::getOperationTime)
                        .orderByAsc(WmsPlanShortageEventOperationEntity::getId)));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsPlanShortageEventOutboxEntity supplyForecast(WmsPlanShortageEventDTO.ForecastCommand command) {
        require(command != null && command.getOutboxId() != null && command.getExpectedSupplyTime() != null
                        && StrUtil.isNotBlank(command.getRequestId()) && StrUtil.isNotBlank(command.getReason()),
                "预计供应补充参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128 && reason.length() <= 500,
                "人工预测请求号最多128位、预测依据最多500位");
        WmsPlanShortageEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, requestId, "FORECAST_SUPPLIED", reason)) return outbox;
        applyForecast(outbox, command.getExpectedSupplyTime(), requestId, reason, false);
        return outbox;
    }

    private void applyForecast(WmsPlanShortageEventOutboxEntity outbox, LocalDateTime expectedSupplyTime,
                               String requestId, String basis, boolean automatic) {
        require("WAITING_DATA".equals(outbox.getStatus()) || ("PENDING".equals(outbox.getStatus())
                        && Objects.requireNonNullElse(outbox.getAttemptCount(), 0) == 0),
                "事件已开始投递，禁止覆盖同一事件ID的供应预测证据");
        require(expectedSupplyTime.isAfter(LocalDateTime.now()), "预计供应时刻必须晚于当前时刻");
        String before = outbox.getStatus();
        String payload = eventPayload(outbox, expectedSupplyTime, basis);
        outbox.setExpectedSupplyTime(expectedSupplyTime).setForecastBasis(compact(basis, 500))
                .setPayloadJson(payload).setPayloadFingerprint(hash(payload)).setStatus("PENDING")
                .setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null);
        if (!automatic) {
            MomUser user = currentUser();
            outbox.setManualRequestId(requestId).setManualReason(basis)
                    .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                    .setManualOperationTime(LocalDateTime.now());
        }
        require(outboxMapper.updateById(outbox) == 1, "预计供应证据与待投递状态保存失败");
        operation(outbox, requestId, "FORECAST_SUPPLIED", before, "PENDING", 0,
                compact(basis, 500) + "；预计供应=" + expectedSupplyTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(WmsPlanShortageEventDTO.ManualActionCommand command) {
        require(command != null && command.getOutboxId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "人工重试参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128 && reason.length() <= 500, "人工重试请求号最多128位、原因最多500位");
        WmsPlanShortageEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, requestId, "MANUAL_RETRY", reason)) return true;
        require(List.of("RETRY_WAIT", "DEAD_LETTER").contains(outbox.getStatus()), "仅退避或死信事件允许人工重试");
        require(outbox.getExpectedSupplyTime() != null && outbox.getExpectedSupplyTime().isAfter(LocalDateTime.now()),
                "预计供应时刻缺失或已过期，请先补充新预测");
        String before = outbox.getStatus();
        MomUser user = currentUser();
        outbox.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null).setManualRequestId(requestId).setManualReason(reason)
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "WMS缺料事件人工重试状态保存失败");
        operation(outbox, requestId, "MANUAL_RETRY", before, "PENDING", 0, reason);
        return true;
    }

    @Override
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<WmsPlanShortageEventOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<WmsPlanShortageEventOutboxEntity>()
                        .in(WmsPlanShortageEventOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .le(WmsPlanShortageEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(WmsPlanShortageEventOutboxEntity::getNextAttemptTime)
                        .orderByAsc(WmsPlanShortageEventOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (WmsPlanShortageEventOutboxEntity candidate : due) {
            try {
                boolean done = candidate.getTenantId() == null ? dispatchOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> dispatchOne(candidate.getId()));
                if (done) delivered++;
            } catch (RuntimeException exception) {
                log.error("WMS缺料事件调度异常，eventId={}，tenantId={}", candidate.getEventId(),
                        candidate.getTenantId(), exception);
            }
        }
        return delivered;
    }

    private boolean dispatchOne(Long outboxId) {
        try {
            return Boolean.TRUE.equals(worker.deliverOne(outboxId));
        } catch (RuntimeException exception) {
            failureService.markFailure(outboxId, exception,
                    !(exception instanceof WmsPlanShortageEventOutboxWorker.NonRetryableDeliveryException));
            return false;
        }
    }

    private String sourceSnapshot(String sourceType, Long sourceId, String sourceNo,
                                  WmsAllocationBillEntity bill, WmsTransferVarianceCaseEntity variance,
                                  BigDecimal shortageQuantity, LocalDateTime occurredTime, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceType", sourceType);
        value.put("sourceId", sourceId);
        value.put("sourceNo", sourceNo);
        value.put("allocationBillId", bill.getId());
        value.put("allocationBillNo", bill.getBillNo());
        value.put("allocationState", SOURCE_SHORT_RECEIPT.equals(sourceType)
                ? "DISCREPANCY_PENDING" : bill.getAllocationState());
        value.put("varianceCaseId", variance == null ? null : variance.getId());
        value.put("varianceCaseNo", variance == null ? null : variance.getCaseNo());
        value.put("mrpId", bill.getSourceMrpId());
        value.put("issueNo", bill.getSourceBusinessNo());
        value.put("materialCode", bill.getSourceMaterialCode());
        value.put("shortageQuantity", shortageQuantity);
        value.put("occurredTime", occurredTime.truncatedTo(ChronoUnit.MILLIS));
        value.put("reason", compact(reason, 1000));
        value.put("factoryCode", bill.getFactoryCode());
        return json(value);
    }

    private String eventPayload(WmsPlanShortageEventOutboxEntity outbox, LocalDateTime expectedSupplyTime,
                                String forecastBasis) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mrpId", outbox.getMrpId());
        value.put("issueNo", outbox.getIssueNo());
        value.put("materialCode", outbox.getMaterialCode());
        value.put("shortageQuantity", outbox.getShortageQuantity());
        value.put("expectedSupplyTime", expectedSupplyTime);
        value.put("affectedProcessCodes", List.of());
        value.put("freezeMinutes", 120);
        value.put("maxDelayMinutes", 10080);
        value.put("reason", compact(outbox.getReason() + "；供应预测依据：" + forecastBasis, 1000));
        value.put("sourceType", outbox.getSourceType());
        value.put("sourceNo", outbox.getSourceNo());
        value.put("sourceFingerprint", outbox.getSourceFingerprint());
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("WMS缺料事件事实序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("WMS缺料事件摘要生成失败");
        }
    }

    private WmsPlanShortageEventOutboxEntity requireOutbox(Long id, boolean lock) {
        require(id != null, "Outbox ID不能为空");
        WmsPlanShortageEventOutboxEntity outbox = lock ? outboxMapper.lockById(id) : outboxMapper.selectById(id);
        require(outbox != null, "WMS缺料事件不存在");
        String currentFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(currentFactory) || Objects.equals(currentFactory, outbox.getFactoryCode()),
                "请求工厂超出当前数据范围");
        return outbox;
    }

    private boolean manualReplay(WmsPlanShortageEventOutboxEntity outbox, String requestId,
                                 String operationType, String content) {
        WmsPlanShortageEventOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsPlanShortageEventOperationEntity>()
                        .eq(WmsPlanShortageEventOperationEntity::getRequestId, requestId));
        if (duplicate == null) return false;
        require(Objects.equals(duplicate.getOutboxId(), outbox.getId())
                        && Objects.equals(duplicate.getOperationType(), operationType)
                        && duplicate.getContent() != null && duplicate.getContent().startsWith(content),
                "人工操作幂等号已被不同事件、动作或内容使用");
        return true;
    }

    private void operation(WmsPlanShortageEventOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new WmsPlanShortageEventOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(compact(content, 1000)).setOperatorCode(userCode(user))
                .setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "WMS缺料事件操作履历保存失败");
    }

    private void requireSourceBill(WmsAllocationBillEntity bill, BigDecimal shortageQuantity) {
        require(bill != null && bill.getId() != null && "PLAN_MRP".equals(bill.getSourceBusinessType())
                        && bill.getSourceMrpId() != null && StrUtil.isNotBlank(bill.getSourceBusinessNo())
                        && StrUtil.isNotBlank(bill.getSourceMaterialCode()) && StrUtil.isNotBlank(bill.getFactoryCode())
                        && shortageQuantity != null && shortageQuantity.signum() > 0,
                "WMS供应失效事件缺少结构化MRP来源、计划号、物料、工厂或正数缺口");
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }
}
