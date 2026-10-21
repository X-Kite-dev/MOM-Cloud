package com.kitex.mom.pms.service.impl;

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
import com.kitex.mom.pms.dto.PmsPlanCommitmentEventDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.PmsPlanCommitmentEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PmsPlanCommitmentEventOutboxServiceImpl implements PmsPlanCommitmentEventOutboxService {
    public static final String ACTIVATED = "PMS_DELIVERY_COMMITMENT_ACTIVATED";
    public static final String CANCELLED = "PMS_DELIVERY_COMMITMENT_CANCELLED";
    public static final String ORDER_REMAINING_CANCELLED = "PMS_ORDER_REMAINING_CANCELLED";
    public static final String ORDER_AMENDED = "PMS_ORDER_AMENDED";
    private static final Set<String> OPEN_ORDER_STATUSES = Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
            PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode());

    private final PmsPlanCommitmentEventOutboxMapper outboxMapper;
    private final PmsPlanCommitmentEventOperationMapper operationMapper;
    private final PmsPlanMapper planMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper detailMapper;
    private final PmsDeliveryCommitmentMapper commitmentMapper;
    private final PmsDeliveryCommitmentItemMapper itemMapper;
    private final PmsPlanCommitmentEventOutboxWorker worker;
    private final PmsPlanCommitmentEventOutboxFailureService failureService;
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
    public void enqueueActivated(PmsDeliveryCommitmentEntity commitment) {
        enqueue(commitment, ACTIVATED, commitment.getConfirmedTime(), commitment.getChangeReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueCancelled(PmsDeliveryCommitmentEntity commitment) {
        enqueue(commitment, CANCELLED, commitment.getCancelledTime(), commitment.getCancelReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueOrderRemainingCancelled(PmsOrderChangeRequestEntity request, PmsOrderChangeLineEntity line) {
        require(request != null && request.getId() != null && line != null && line.getOrderDetailId() != null
                        && StrUtil.isNotBlank(line.getPmsPlanNo()) && StrUtil.isNotBlank(line.getMaterialCode())
                        && line.getCancelledQuantity() != null && line.getCancelledQuantity().signum() > 0
                        && StrUtil.isNotBlank(request.getFactoryCode()),
                "采购订单取消事件缺少变更单、订单行、采购计划、物料、取消数量或工厂");
        List<PmsPlanEntity> plans = planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, request.getFactoryCode())
                .eq(PmsPlanEntity::getPmsPlanNo, line.getPmsPlanNo())
                .eq(PmsPlanEntity::getMatCode, line.getMaterialCode()));
        require(plans.size() == 1 && StrUtil.isNotBlank(plans.get(0).getOrderNo()),
                "取消订单行无法唯一映射PLAN需求号，禁止丢失供应撤回事件");
        PmsPlanEntity plan = plans.get(0);
        String eventId = "PMS-ORDER-CHANGE:" + request.getId() + ':' + line.getOrderDetailId();
        outboxMapper.lockKey(eventId);
        AggregateEvidence evidence = aggregateEvidence(plan);
        BigDecimal planGap = decimal(plan.getPmsNum()).subtract(decimal(plan.getPlanOrderNum())).max(BigDecimal.ZERO);
        LocalDateTime occurred = Objects.requireNonNullElse(request.getReviewedTime(), LocalDateTime.now())
                .truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceType", ORDER_REMAINING_CANCELLED);
        source.put("changeRequestId", request.getId());
        source.put("requestNo", request.getRequestNo());
        source.put("pmsOrderId", request.getPmsOrderId());
        source.put("pmsOrderNo", request.getPmsOrderNo());
        source.put("orderDetailId", line.getOrderDetailId());
        source.put("pmsPlanNo", plan.getPmsPlanNo());
        source.put("planDemandNo", plan.getOrderNo());
        source.put("materialCode", plan.getMatCode());
        source.put("cancelledQuantity", line.getCancelledQuantity());
        source.put("remainingProcurementGap", planGap);
        source.put("openOrderedQuantity", evidence.openOrdered());
        source.put("confirmedOpenQuantity", evidence.confirmedOpen());
        source.put("occurredTime", occurred);
        source.put("reason", compact(request.getReason(), 1000));
        source.put("factoryCode", request.getFactoryCode());
        String sourceJson = json(source);
        String sourceFingerprint = hash(sourceJson);
        PmsPlanCommitmentEventOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                        .eq(PmsPlanCommitmentEventOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), sourceFingerprint),
                    "同一采购订单取消事件的来源事实已变化，禁止覆盖");
            return;
        }
        String status = planGap.signum() > 0 ? "WAITING_DATA" : "SOURCE_CLOSED";
        PmsPlanCommitmentEventOutboxEntity outbox = new PmsPlanCommitmentEventOutboxEntity()
                .setEventId(eventId).setEventType("MATERIAL_SHORTAGE_CHANGED").setEventVersion(1)
                .setSourceType(ORDER_REMAINING_CANCELLED).setOrderChangeRequestId(request.getId())
                .setCommitmentNo(request.getRequestNo()).setCommitmentVersion(1)
                .setPmsOrderId(request.getPmsOrderId()).setOrderDetailId(line.getOrderDetailId())
                .setPmsOrderNo(request.getPmsOrderNo()).setPmsPlanNo(plan.getPmsPlanNo())
                .setPlanDemandNo(plan.getOrderNo()).setMaterialCode(plan.getMatCode())
                .setOpenOrderedQuantity(evidence.openOrdered()).setConfirmedOpenQuantity(evidence.confirmedOpen())
                .setAffectedQuantity(line.getCancelledQuantity()).setCoverageStatus(planGap.signum() > 0 ? "GAP" : "COVERED")
                .setOccurredTime(occurred).setReason(compact(request.getReason(), 1000))
                .setSourcePayloadJson(sourceJson).setSourceFingerprint(sourceFingerprint).setStatus(status)
                .setAttemptCount(0).setMaxAttempts(8).setFactoryCode(request.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "采购订单取消供应事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, status, 0,
                planGap.signum() > 0
                        ? "订单剩余供给已取消，采购缺口=" + planGap + "；待责任人补充可信替代供应时刻后投递PLAN"
                        : "订单剩余供给已取消，但采购计划当前无未覆盖缺口，事件留痕后关闭");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueOrderAmended(PmsOrderChangeRequestEntity request, PmsOrderChangeLineEntity line) {
        BigDecimal targetOutstanding = line == null || line.getTargetOrderQuantity() == null
                || line.getArrivalQuantitySnapshot() == null ? BigDecimal.ZERO
                : line.getTargetOrderQuantity().subtract(line.getArrivalQuantitySnapshot()).max(BigDecimal.ZERO);
        require(request != null && request.getId() != null && "AMEND_ORDER".equals(request.getChangeType())
                        && line != null && line.getOrderDetailId() != null && Boolean.TRUE.equals(line.getSupplyAffected())
                        && StrUtil.isNotBlank(line.getPmsPlanNo()) && StrUtil.isNotBlank(line.getMaterialCode())
                        && targetOutstanding.signum() > 0 && StrUtil.isNotBlank(request.getFactoryCode()),
                "采购订单变更事件缺少成功变更单、受影响订单行、采购计划、物料、未到货量或工厂");
        List<PmsPlanEntity> plans = planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, request.getFactoryCode())
                .eq(PmsPlanEntity::getPmsPlanNo, line.getPmsPlanNo())
                .eq(PmsPlanEntity::getMatCode, line.getMaterialCode()));
        require(plans.size() == 1 && StrUtil.isNotBlank(plans.get(0).getOrderNo()),
                "变更订单行无法唯一映射PLAN需求号，禁止丢失供应变化事件");
        PmsPlanEntity plan = plans.get(0);
        String eventId = "PMS-ORDER-AMEND:" + request.getId() + ':' + line.getOrderDetailId();
        outboxMapper.lockKey(eventId);
        AggregateEvidence evidence = aggregateEvidence(plan);
        BigDecimal commitmentGap = evidence.openOrdered().subtract(evidence.confirmedOpen()).max(BigDecimal.ZERO);
        BigDecimal affected = line.getQuantityDelta() != null && line.getQuantityDelta().signum() != 0
                ? line.getQuantityDelta().abs() : targetOutstanding;
        LocalDateTime occurred = Objects.requireNonNullElse(request.getReviewedTime(), LocalDateTime.now())
                .truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("sourceType", ORDER_AMENDED);
        source.put("changeRequestId", request.getId());
        source.put("requestNo", request.getRequestNo());
        source.put("changeVersion", request.getChangeVersion());
        source.put("pmsOrderId", request.getPmsOrderId());
        source.put("pmsOrderNo", request.getPmsOrderNo());
        source.put("orderDetailId", line.getOrderDetailId());
        source.put("pmsPlanNo", plan.getPmsPlanNo());
        source.put("planDemandNo", plan.getOrderNo());
        source.put("materialCode", plan.getMatCode());
        source.put("originalSupplierCode", request.getOriginalSupplierCode());
        source.put("targetSupplierCode", request.getTargetSupplierCode());
        source.put("originalQuantity", line.getOrderQuantitySnapshot());
        source.put("targetQuantity", line.getTargetOrderQuantity());
        source.put("originalDeliveryDate", line.getDeliveryDate());
        source.put("targetDeliveryDate", line.getTargetDeliveryDate());
        source.put("targetOutstandingQuantity", targetOutstanding);
        source.put("commitmentGap", commitmentGap);
        source.put("openOrderedQuantity", evidence.openOrdered());
        source.put("confirmedOpenQuantity", evidence.confirmedOpen());
        source.put("occurredTime", occurred);
        source.put("reason", compact(request.getReason(), 1000));
        source.put("factoryCode", request.getFactoryCode());
        String sourceJson = json(source);
        String sourceFingerprint = hash(sourceJson);
        PmsPlanCommitmentEventOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                        .eq(PmsPlanCommitmentEventOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), sourceFingerprint),
                    "同一采购订单变更事件的来源事实已变化，禁止覆盖");
            return;
        }
        String status = commitmentGap.signum() > 0 ? "WAITING_DATA" : "SOURCE_CLOSED";
        PmsPlanCommitmentEventOutboxEntity outbox = new PmsPlanCommitmentEventOutboxEntity()
                .setEventId(eventId).setEventType("MATERIAL_SHORTAGE_CHANGED").setEventVersion(1)
                .setSourceType(ORDER_AMENDED).setOrderChangeRequestId(request.getId())
                .setCommitmentNo(request.getRequestNo()).setCommitmentVersion(request.getChangeVersion())
                .setPmsOrderId(request.getPmsOrderId()).setOrderDetailId(line.getOrderDetailId())
                .setPmsOrderNo(request.getPmsOrderNo()).setPmsPlanNo(plan.getPmsPlanNo())
                .setPlanDemandNo(plan.getOrderNo()).setMaterialCode(plan.getMatCode())
                .setOpenOrderedQuantity(evidence.openOrdered()).setConfirmedOpenQuantity(evidence.confirmedOpen())
                .setAffectedQuantity(affected).setCoverageStatus(commitmentGap.signum() > 0 ? "GAP" : "COVERED")
                .setOccurredTime(occurred).setReason(compact(request.getReason(), 1000))
                .setSourcePayloadJson(sourceJson).setSourceFingerprint(sourceFingerprint).setStatus(status)
                .setAttemptCount(0).setMaxAttempts(8).setFactoryCode(request.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "采购订单变更供应事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, status, 0,
                commitmentGap.signum() > 0
                        ? "订单供应事实已变更，正式承诺缺口=" + commitmentGap + "；待补充可信供应时刻后投递PLAN"
                        : "订单供应事实已变更，但当前正式承诺仍覆盖未到货量，事件留痕后关闭");
    }

    private void enqueue(PmsDeliveryCommitmentEntity commitment, String sourceType,
                         LocalDateTime occurredTime, String reason) {
        require(commitment != null && commitment.getId() != null && StrUtil.isNotBlank(commitment.getPmsPlanNo())
                && StrUtil.isNotBlank(commitment.getMatCode()) && StrUtil.isNotBlank(commitment.getFactoryCode())
                && occurredTime != null, "供应商承诺事件缺少承诺、采购计划、物料、工厂或发生时刻");
        PmsPlanEntity plan = resolvePlan(commitment);
        String eventId = "PMS-COMMITMENT:" + commitment.getId() + ':'
                + (ACTIVATED.equals(sourceType) ? "ACTIVATED" : "CANCELLED");
        outboxMapper.lockKey(eventId);
        AggregateEvidence evidence = aggregateEvidence(plan);
        String sourceJson = sourceSnapshot(commitment, plan, sourceType, occurredTime, reason, evidence);
        String sourceFingerprint = hash(sourceJson);
        PmsPlanCommitmentEventOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                        .eq(PmsPlanCommitmentEventOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), sourceFingerprint),
                    "同一PMS承诺事件ID的权威事实已变化，禁止覆盖");
            return;
        }
        if (ACTIVATED.equals(sourceType)) closeSupersededPending(commitment);
        LocalDateTime expected = evidence.fullCoverageTime();
        boolean actionable = expected != null && expected.isAfter(LocalDateTime.now());
        String status = "RECEIVED".equals(evidence.coverageStatus()) ? "SOURCE_CLOSED"
                : actionable ? "PENDING" : "WAITING_DATA";
        PmsPlanCommitmentEventOutboxEntity outbox = new PmsPlanCommitmentEventOutboxEntity()
                .setEventId(eventId).setEventType("MATERIAL_SHORTAGE_CHANGED").setEventVersion(1)
                .setSourceType(sourceType).setCommitmentId(commitment.getId())
                .setCommitmentNo(commitment.getCommitmentNo()).setCommitmentVersion(commitment.getVersionNo())
                .setPmsOrderId(commitment.getPmsOrderId()).setOrderDetailId(commitment.getOrderDetailId())
                .setPmsOrderNo(commitment.getPmsOrderNo()).setPmsPlanNo(plan.getPmsPlanNo())
                .setPlanDemandNo(plan.getOrderNo()).setMaterialCode(plan.getMatCode())
                .setOpenOrderedQuantity(evidence.openOrdered()).setConfirmedOpenQuantity(evidence.confirmedOpen())
                .setCoverageStatus(evidence.coverageStatus()).setOccurredTime(occurredTime.truncatedTo(ChronoUnit.MILLIS))
                .setExpectedSupplyTime(actionable ? expected : null)
                .setForecastBasis(actionable ? "当前采购计划全部未到订单量已由正式承诺覆盖" : null)
                .setReason(compact(reason, 1000)).setSourcePayloadJson(sourceJson)
                .setSourceFingerprint(sourceFingerprint).setStatus(status).setAttemptCount(0).setMaxAttempts(8)
                .setNextAttemptTime(actionable ? LocalDateTime.now() : null).setFactoryCode(plan.getFactoryCode());
        if (actionable) applyPayload(outbox, expected, outbox.getForecastBasis());
        require(outboxMapper.insert(outbox) == 1, "PMS承诺事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, status, 0,
                "承诺变化已与PMS业务事务同箱保存；覆盖=" + evidence.coverageStatus()
                        + "，未到=" + evidence.openOrdered() + "，已确认=" + evidence.confirmedOpen()
                        + (actionable ? "，预计齐料=" + expected : "，等待可信齐料时刻"));
    }

    private void closeSupersededPending(PmsDeliveryCommitmentEntity current) {
        List<PmsPlanCommitmentEventOutboxEntity> old = outboxMapper.selectList(
                new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                        .eq(PmsPlanCommitmentEventOutboxEntity::getOrderDetailId, current.getOrderDetailId())
                        .in(PmsPlanCommitmentEventOutboxEntity::getStatus, "WAITING_DATA", "PENDING", "RETRY_WAIT"));
        for (PmsPlanCommitmentEventOutboxEntity item : old) {
            String before = item.getStatus();
            item.setStatus("SOURCE_CLOSED").setNextAttemptTime(null)
                    .setLastErrorCode("COMMITMENT_SUPERSEDED")
                    .setLastErrorMessage("原承诺已由版本v" + current.getVersionNo() + "替代");
            require(outboxMapper.updateById(item) == 1, "旧PMS承诺事件关闭失败");
            operation(item, "SOURCE-CLOSE:" + current.getId() + ':' + item.getId(), "SOURCE_CLOSED", before, "SOURCE_CLOSED",
                    Objects.requireNonNullElse(item.getAttemptCount(), 0), item.getLastErrorMessage());
        }
    }

    private PmsPlanEntity resolvePlan(PmsDeliveryCommitmentEntity commitment) {
        List<PmsPlanEntity> plans = planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, commitment.getFactoryCode())
                .eq(PmsPlanEntity::getPmsPlanNo, commitment.getPmsPlanNo())
                .eq(PmsPlanEntity::getMatCode, commitment.getMatCode()));
        require(plans.size() == 1 && StrUtil.isNotBlank(plans.get(0).getOrderNo()),
                "采购计划无法唯一映射PLAN需求号，禁止向智能排产发布");
        return plans.get(0);
    }

    private AggregateEvidence aggregateEvidence(PmsPlanEntity plan) {
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getFactoryCode, plan.getFactoryCode())
                .eq(PmsOrderDetailEntity::getPmsPlanNo, plan.getPmsPlanNo())
                .eq(PmsOrderDetailEntity::getMatCode, plan.getMatCode()));
        Map<Long, PmsOrderEntity> orders = details.isEmpty() ? Map.of()
                : orderMapper.selectBatchIds(details.stream().map(PmsOrderDetailEntity::getPmsOrderId).distinct().toList())
                .stream().collect(Collectors.toMap(PmsOrderEntity::getId, value -> value));
        List<PmsOrderDetailEntity> openDetails = details.stream()
                .filter(row -> orders.containsKey(row.getPmsOrderId())
                        && OPEN_ORDER_STATUSES.contains(orders.get(row.getPmsOrderId()).getStatus())).toList();
        BigDecimal openOrdered = openDetails.stream().map(row -> decimal(row.getOrderNum())
                        .subtract(decimal(row.getArrivalNum())).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, PmsOrderDetailEntity> byId = openDetails.stream()
                .collect(Collectors.toMap(PmsOrderDetailEntity::getId, value -> value));
        List<PmsDeliveryCommitmentEntity> commitments = openDetails.isEmpty() ? List.of()
                : commitmentMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                .in(PmsDeliveryCommitmentEntity::getOrderDetailId, byId.keySet())
                .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE"));
        Map<Long, List<PmsDeliveryCommitmentItemEntity>> items = commitments.isEmpty() ? Map.of()
                : itemMapper.selectList(new LambdaQueryWrapper<PmsDeliveryCommitmentItemEntity>()
                        .in(PmsDeliveryCommitmentItemEntity::getCommitmentId,
                                commitments.stream().map(PmsDeliveryCommitmentEntity::getId).toList())
                        .orderByAsc(PmsDeliveryCommitmentItemEntity::getCommittedDeliveryTime)
                        .orderByAsc(PmsDeliveryCommitmentItemEntity::getSequenceNo)).stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentItemEntity::getCommitmentId));
        List<TimedQuantity> openCommitments = new ArrayList<>();
        for (PmsDeliveryCommitmentEntity commitment : commitments) {
            PmsOrderDetailEntity detail = byId.get(commitment.getOrderDetailId());
            BigDecimal consumed = decimal(detail == null ? null : detail.getArrivalNum())
                    .subtract(value(commitment.getArrivalNumBaseline())).max(BigDecimal.ZERO);
            for (PmsDeliveryCommitmentItemEntity item : items.getOrDefault(commitment.getId(), List.of())) {
                BigDecimal quantity = value(item.getCommittedNum());
                BigDecimal used = consumed.min(quantity);
                consumed = consumed.subtract(used);
                BigDecimal open = quantity.subtract(used);
                if (open.signum() > 0) openCommitments.add(new TimedQuantity(deliveryTime(item), open));
            }
        }
        openCommitments.sort(Comparator.comparing(TimedQuantity::time));
        BigDecimal confirmed = openCommitments.stream().map(TimedQuantity::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String coverage = openOrdered.signum() == 0 ? "RECEIVED" : confirmed.signum() == 0 ? "NONE"
                : confirmed.compareTo(openOrdered) >= 0 ? "FULL" : "PARTIAL";
        LocalDateTime fullTime = null;
        if ("FULL".equals(coverage)) {
            BigDecimal cumulative = BigDecimal.ZERO;
            for (TimedQuantity item : openCommitments) {
                cumulative = cumulative.add(item.quantity());
                if (cumulative.compareTo(openOrdered) >= 0) {
                    fullTime = item.time();
                    break;
                }
            }
        }
        return new AggregateEvidence(openOrdered, confirmed, coverage, fullTime);
    }

    @Override
    public Page<PmsPlanCommitmentEventOutboxEntity> page(SearchPage page, PmsPlanCommitmentEventDTO.Query query) {
        PmsPlanCommitmentEventDTO.Query q = query == null ? new PmsPlanCommitmentEventDTO.Query() : query;
        return outboxMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                .eq(q.getPmsOrderId() != null, PmsPlanCommitmentEventOutboxEntity::getPmsOrderId, q.getPmsOrderId())
                .like(StrUtil.isNotBlank(q.getPmsPlanNo()), PmsPlanCommitmentEventOutboxEntity::getPmsPlanNo, q.getPmsPlanNo())
                .like(StrUtil.isNotBlank(q.getPlanDemandNo()), PmsPlanCommitmentEventOutboxEntity::getPlanDemandNo, q.getPlanDemandNo())
                .like(StrUtil.isNotBlank(q.getMaterialCode()), PmsPlanCommitmentEventOutboxEntity::getMaterialCode, q.getMaterialCode())
                .eq(StrUtil.isNotBlank(q.getSourceType()), PmsPlanCommitmentEventOutboxEntity::getSourceType, q.getSourceType())
                .eq(StrUtil.isNotBlank(q.getStatus()), PmsPlanCommitmentEventOutboxEntity::getStatus, q.getStatus())
                .orderByDesc(PmsPlanCommitmentEventOutboxEntity::getOccurredTime)
                .orderByDesc(PmsPlanCommitmentEventOutboxEntity::getId));
    }

    @Override
    public PmsPlanCommitmentEventDTO.Detail detail(Long outboxId) {
        PmsPlanCommitmentEventOutboxEntity outbox = requireOutbox(outboxId, false);
        PmsPlanCommitmentEventDTO.Detail detail = new PmsPlanCommitmentEventDTO.Detail();
        BeanUtils.copyProperties(outbox, detail);
        detail.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PmsPlanCommitmentEventOperationEntity>()
                .eq(PmsPlanCommitmentEventOperationEntity::getOutboxId, outboxId)
                .orderByAsc(PmsPlanCommitmentEventOperationEntity::getOperationTime)
                .orderByAsc(PmsPlanCommitmentEventOperationEntity::getId)));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsPlanCommitmentEventOutboxEntity supplyForecast(PmsPlanCommitmentEventDTO.ForecastCommand command) {
        require(command != null && command.getOutboxId() != null && command.getExpectedSupplyTime() != null
                        && StrUtil.isNotBlank(command.getRequestId()) && StrUtil.isNotBlank(command.getReason()),
                "承诺变化预测补充参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128 && reason.length() <= 500, "请求号最多128位、预测依据最多500位");
        PmsPlanCommitmentEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, requestId, "FORECAST_SUPPLIED", reason)) return outbox;
        require("WAITING_DATA".equals(outbox.getStatus()), "只有待补充事件允许录入新预计齐料时刻");
        require(command.getExpectedSupplyTime().isAfter(LocalDateTime.now()), "预计齐料时刻必须晚于当前时刻");
        String before = outbox.getStatus();
        applyPayload(outbox, command.getExpectedSupplyTime(), reason);
        MomUser user = currentUser();
        outbox.setExpectedSupplyTime(command.getExpectedSupplyTime()).setForecastBasis(reason)
                .setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null).setManualRequestId(requestId).setManualReason(reason)
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "PMS承诺事件预测保存失败");
        operation(outbox, requestId, "FORECAST_SUPPLIED", before, "PENDING", 0,
                reason + "；预计齐料=" + command.getExpectedSupplyTime());
        return outbox;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean retry(PmsPlanCommitmentEventDTO.ManualActionCommand command) {
        require(command != null && command.getOutboxId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "人工重试参数不完整");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        require(requestId.length() <= 128, "人工重试幂等号不能超过128字符");
        require(reason.length() <= 500, "人工重试原因不能超过500字符");
        PmsPlanCommitmentEventOutboxEntity outbox = requireOutbox(command.getOutboxId(), true);
        if (manualReplay(outbox, requestId, "MANUAL_RETRY", reason)) return true;
        require(List.of("RETRY_WAIT", "DEAD_LETTER").contains(outbox.getStatus()), "仅退避或死信事件允许人工重试");
        require(outbox.getExpectedSupplyTime() != null && outbox.getExpectedSupplyTime().isAfter(LocalDateTime.now()),
                "预计齐料时刻缺失或已过期，请先补充新预测");
        String before = outbox.getStatus();
        MomUser user = currentUser();
        outbox.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null).setManualRequestId(requestId).setManualReason(reason)
                .setManualOperatorCode(userCode(user)).setManualOperatorName(userName(user))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "PMS承诺事件人工重试失败");
        operation(outbox, requestId, "MANUAL_RETRY", before, "PENDING", 0, reason);
        return true;
    }

    @Override
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<PmsPlanCommitmentEventOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<PmsPlanCommitmentEventOutboxEntity>()
                        .in(PmsPlanCommitmentEventOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .le(PmsPlanCommitmentEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(PmsPlanCommitmentEventOutboxEntity::getNextAttemptTime)
                        .orderByAsc(PmsPlanCommitmentEventOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (PmsPlanCommitmentEventOutboxEntity candidate : due) {
            try {
                boolean done = candidate.getTenantId() == null ? dispatchOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> dispatchOne(candidate.getId()));
                if (done) delivered++;
            } catch (RuntimeException exception) {
                log.error("PMS承诺事件调度异常，eventId={}，tenantId={}", candidate.getEventId(), candidate.getTenantId(), exception);
            }
        }
        return delivered;
    }

    private boolean dispatchOne(Long id) {
        try {
            return Boolean.TRUE.equals(worker.deliverOne(id));
        } catch (RuntimeException exception) {
            boolean sourceClosed = exception instanceof PmsPlanCommitmentEventOutboxWorker.SourceClosedException;
            failureService.markFailure(id, exception,
                    !(exception instanceof PmsPlanCommitmentEventOutboxWorker.NonRetryableDeliveryException), sourceClosed);
            return false;
        }
    }

    private void applyPayload(PmsPlanCommitmentEventOutboxEntity outbox, LocalDateTime expected, String basis) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pmsPlanNo", outbox.getPmsPlanNo());
        value.put("planDemandNo", outbox.getPlanDemandNo());
        value.put("materialCode", outbox.getMaterialCode());
        value.put("expectedSupplyTime", expected);
        value.put("affectedProcessCodes", List.of());
        value.put("freezeMinutes", 120);
        value.put("maxDelayMinutes", 10080);
        String changeLabel = ORDER_REMAINING_CANCELLED.equals(outbox.getSourceType())
                ? "采购订单剩余供给受控撤回" : ORDER_AMENDED.equals(outbox.getSourceType())
                ? "采购订单供应事实受控变更" : "供应商正式交期承诺变化";
        value.put("reason", compact(changeLabel + "：" + outbox.getReason() + "；预测依据：" + basis, 1000));
        value.put("sourceType", outbox.getSourceType());
        value.put("commitmentId", outbox.getCommitmentId());
        value.put("commitmentNo", outbox.getCommitmentNo());
        value.put("commitmentVersion", outbox.getCommitmentVersion());
        value.put("coverageStatus", outbox.getCoverageStatus());
        value.put("openOrderedQuantity", outbox.getOpenOrderedQuantity());
        value.put("confirmedOpenQuantity", outbox.getConfirmedOpenQuantity());
        value.put("affectedQuantity", outbox.getAffectedQuantity());
        value.put("sourceFingerprint", outbox.getSourceFingerprint());
        String payload = json(value);
        outbox.setPayloadJson(payload).setPayloadFingerprint(hash(payload));
    }

    private String sourceSnapshot(PmsDeliveryCommitmentEntity commitment, PmsPlanEntity plan, String sourceType,
                                  LocalDateTime occurred, String reason, AggregateEvidence evidence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceType", sourceType);
        value.put("commitmentId", commitment.getId());
        value.put("commitmentNo", commitment.getCommitmentNo());
        value.put("commitmentVersion", commitment.getVersionNo());
        value.put("commitmentStatus", commitment.getStatus());
        value.put("pmsOrderId", commitment.getPmsOrderId());
        value.put("orderDetailId", commitment.getOrderDetailId());
        value.put("pmsPlanNo", plan.getPmsPlanNo());
        value.put("planDemandNo", plan.getOrderNo());
        value.put("materialCode", plan.getMatCode());
        value.put("openOrderedQuantity", evidence.openOrdered());
        value.put("confirmedOpenQuantity", evidence.confirmedOpen());
        value.put("coverageStatus", evidence.coverageStatus());
        value.put("fullCoverageTime", evidence.fullCoverageTime());
        value.put("occurredTime", occurred.truncatedTo(ChronoUnit.MILLIS));
        value.put("reason", compact(reason, 1000));
        value.put("factoryCode", plan.getFactoryCode());
        return json(value);
    }

    private PmsPlanCommitmentEventOutboxEntity requireOutbox(Long id, boolean lock) {
        require(id != null, "Outbox ID不能为空");
        PmsPlanCommitmentEventOutboxEntity outbox = lock ? outboxMapper.lockById(id) : outboxMapper.selectById(id);
        require(outbox != null, "PMS承诺事件不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, outbox.getFactoryCode()), "请求工厂超出当前数据范围");
        return outbox;
    }

    private boolean manualReplay(PmsPlanCommitmentEventOutboxEntity outbox, String requestId, String type, String content) {
        PmsPlanCommitmentEventOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsPlanCommitmentEventOperationEntity>()
                        .eq(PmsPlanCommitmentEventOperationEntity::getRequestId, requestId));
        if (duplicate == null) return false;
        require(Objects.equals(duplicate.getOutboxId(), outbox.getId())
                        && Objects.equals(duplicate.getOperationType(), type)
                        && duplicate.getContent() != null && duplicate.getContent().startsWith(content),
                "人工操作幂等号已被不同事件、动作或内容使用");
        return true;
    }

    private void operation(PmsPlanCommitmentEventOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new PmsPlanCommitmentEventOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(compact(content, 1000)).setOperatorCode(userCode(user))
                .setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "PMS承诺事件操作履历保存失败");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("PMS承诺事件事实序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("PMS承诺事件摘要生成失败");
        }
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDateTime deliveryTime(PmsDeliveryCommitmentItemEntity item) {
        if (item.getCommittedDeliveryTime() != null) return item.getCommittedDeliveryTime();
        return Objects.requireNonNull(item.getCommittedDeliveryDate(), "供应承诺日期不能为空")
                .atTime(LocalTime.of(23, 59));
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

    private record TimedQuantity(LocalDateTime time, BigDecimal quantity) {
    }

    private record AggregateEvidence(BigDecimal openOrdered, BigDecimal confirmedOpen, String coverageStatus,
                                     LocalDateTime fullCoverageTime) {
    }
}
