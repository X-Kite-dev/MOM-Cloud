package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.dto.OmsFulfillmentSnapshotCommand;
import com.kitex.mom.oms.entity.OmsFulfillmentSnapshotEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.vo.OmsFulfillmentSummaryVO;
import com.kitex.mom.oms.vo.OmsLifeVO;
import com.kitex.mom.plan.mapper.OmsFulfillmentSnapshotMapper;
import com.kitex.mom.plan.mapper.OmsOrderDetailsMapper;
import com.kitex.mom.plan.service.OmsFulfillmentSnapshotService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 冻结可核验的跨域履约事实，并给出相对上一版本的确定差异。
 */
@Service
@RequiredArgsConstructor
public class OmsFulfillmentSnapshotServiceImpl implements OmsFulfillmentSnapshotService {
    private static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;

    private final OmsFulfillmentSnapshotMapper snapshotMapper;
    private final OmsOrderDetailsMapper orderDetailsMapper;
    private final OmsOrderBaseTrackService trackService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OmsFulfillmentSnapshotEntity capture(Long orderDetailId, OmsFulfillmentSnapshotCommand.Capture command) {
        require(orderDetailId != null, "订单交付行不能为空");
        require(command != null && text(command.getRequestId(), 64), "稳定请求号不能为空且不能超过64字");
        require(text(command.getReason(), 1000), "快照原因不能为空且不能超过1000字");
        MomUser actor = actor();
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        require(selectedFactory != null && !selectedFactory.isBlank(), "请先选择工厂再冻结履约承诺快照");
        String requestFingerprint = sha(orderDetailId + "|" + actor.getUsername() + "|" + command.getReason().trim());

        snapshotMapper.lockRequest(selectedFactory + ":" + command.getRequestId().trim());
        OmsFulfillmentSnapshotEntity replay = byRequest(command.getRequestId());
        if (replay != null) return replay(replay, orderDetailId, requestFingerprint);

        OmsOrderDetailsEntity detail = orderDetailsMapper.selectOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, orderDetailId).last("FOR UPDATE"));
        require(detail != null, "订单交付行不存在");
        require(Objects.equals(selectedFactory, detail.getFactoryCode()), "当前工厂无权冻结该订单交付履约");
        replay = byRequest(command.getRequestId());
        if (replay != null) return replay(replay, orderDetailId, requestFingerprint);

        OmsLifeVO life = trackService.lifePlan(orderDetailId);
        OmsFulfillmentSummaryVO summary = life == null ? null : life.getFulfillmentSummary();
        require(summary != null && Objects.equals(summary.getOrderDetailId(), orderDetailId), "当前履约摘要未形成");
        require(Objects.equals(summary.getFactoryCode(), detail.getFactoryCode())
                && Objects.equals(summary.getOrderCode(), detail.getOrderCode())
                && Objects.equals(summary.getProductCode(), detail.getProductCode()), "履约摘要与订单交付行身份不一致");
        require(summary.getPromisedDate() != null && summary.getOrderedQuantity() != null
                && summary.getOrderedQuantity().signum() >= 0, "订单承诺交期或数量不完整，禁止冻结历史承诺");
        require(oneOf(summary.getPlanDataStatus(), "AVAILABLE", "NOT_STARTED")
                        && oneOf(summary.getMesDataStatus(), "AVAILABLE", "NOT_APPLICABLE")
                        && oneOf(summary.getPmsDataStatus(), "AVAILABLE", "NOT_APPLICABLE")
                        && oneOf(summary.getWmsDataStatus(), "AVAILABLE")
                        && oneOf(summary.getQualityDeliverabilityStatus(), "AVAILABLE", "NOT_STARTED"),
                "跨域事实存在不可用或不一致，禁止冻结可能误导的历史承诺；请恢复数据源或治理来源后重试");

        String summaryJson = json(summary, "履约摘要");
        String milestoneJson = json(life.getFulfillmentMilestones(), "履约里程碑");
        String lifecycleJson = json(life, "履约生命周期");
        int totalBytes = summaryJson.getBytes(StandardCharsets.UTF_8).length
                + milestoneJson.getBytes(StandardCharsets.UTF_8).length
                + lifecycleJson.getBytes(StandardCharsets.UTF_8).length;
        require(totalBytes <= MAX_SNAPSHOT_BYTES, "履约快照超过2MiB，请先治理异常明细数量，禁止截断审计证据");

        OmsFulfillmentSnapshotEntity previous = snapshotMapper.selectOne(
                new LambdaQueryWrapper<OmsFulfillmentSnapshotEntity>()
                        .eq(OmsFulfillmentSnapshotEntity::getOrderDetailId, orderDetailId)
                        .orderByDesc(OmsFulfillmentSnapshotEntity::getSnapshotVersion).last("LIMIT 1"));
        int version = previous == null ? 1 : previous.getSnapshotVersion() + 1;
        OmsFulfillmentSnapshotEntity entity = new OmsFulfillmentSnapshotEntity()
                .setOrderDetailId(orderDetailId).setOrderCode(detail.getOrderCode())
                .setProductCode(detail.getProductCode()).setSnapshotVersion(version)
                .setPreviousSnapshotId(previous == null ? null : previous.getId())
                .setCaptureType("MANUAL_BASELINE").setCaptureReason(command.getReason().trim())
                .setRequestId(command.getRequestId().trim()).setRequestFingerprint(requestFingerprint)
                .setEvidenceFingerprint(sha(lifecycleJson)).setOverallStatus(summary.getOverallStatus())
                .setRiskLevel(summary.getRiskLevel()).setPromisedDate(summary.getPromisedDate())
                .setOrderedQuantity(summary.getOrderedQuantity()).setProducedQuantity(summary.getProducedQuantity())
                .setStockedQuantity(summary.getStockedQuantity()).setDeliveredQuantity(summary.getDeliveredQuantity())
                .setRemainingQuantity(summary.getRemainingQuantity())
                .setCurrentOrderReservedQuantity(summary.getCurrentOrderReservedStockQuantity())
                .setNetDeliverableQuantity(summary.getNetDeliverableQuantity())
                .setDeliverableShortageQuantity(summary.getDeliverableShortageQuantity())
                .setSummarySnapshot(summaryJson).setMilestoneSnapshot(milestoneJson)
                .setLifecycleSnapshot(lifecycleJson).setOperatorCode(actor.getUsername())
                .setOperatorName(userName(actor)).setCapturedTime(LocalDateTime.now())
                .setFactoryCode(detail.getFactoryCode());
        applyDelta(entity, previous);
        require(snapshotMapper.insert(entity) == 1, "履约承诺快照保存失败");
        return entity;
    }

    @Override
    public List<OmsFulfillmentSnapshotEntity> list(Long orderDetailId) {
        require(orderDetailId != null, "订单交付行不能为空");
        OmsOrderDetailsEntity detail = orderDetailsMapper.selectById(orderDetailId);
        require(detail != null, "订单交付行不存在");
        requireFactory(detail.getFactoryCode());
        return snapshotMapper.selectList(new LambdaQueryWrapper<OmsFulfillmentSnapshotEntity>()
                .select(OmsFulfillmentSnapshotEntity::getId,
                        OmsFulfillmentSnapshotEntity::getOrderDetailId,
                        OmsFulfillmentSnapshotEntity::getOrderCode,
                        OmsFulfillmentSnapshotEntity::getProductCode,
                        OmsFulfillmentSnapshotEntity::getSnapshotVersion,
                        OmsFulfillmentSnapshotEntity::getPreviousSnapshotId,
                        OmsFulfillmentSnapshotEntity::getCaptureType,
                        OmsFulfillmentSnapshotEntity::getCaptureReason,
                        OmsFulfillmentSnapshotEntity::getOverallStatus,
                        OmsFulfillmentSnapshotEntity::getRiskLevel,
                        OmsFulfillmentSnapshotEntity::getPromisedDate,
                        OmsFulfillmentSnapshotEntity::getOrderedQuantity,
                        OmsFulfillmentSnapshotEntity::getProducedQuantity,
                        OmsFulfillmentSnapshotEntity::getStockedQuantity,
                        OmsFulfillmentSnapshotEntity::getDeliveredQuantity,
                        OmsFulfillmentSnapshotEntity::getRemainingQuantity,
                        OmsFulfillmentSnapshotEntity::getCurrentOrderReservedQuantity,
                        OmsFulfillmentSnapshotEntity::getNetDeliverableQuantity,
                        OmsFulfillmentSnapshotEntity::getDeliverableShortageQuantity,
                        OmsFulfillmentSnapshotEntity::getPromisedDateShiftDays,
                        OmsFulfillmentSnapshotEntity::getOrderedQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getProducedQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getStockedQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getDeliveredQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getRemainingQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getCurrentOrderReservedQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getNetDeliverableQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getDeliverableShortageQuantityDelta,
                        OmsFulfillmentSnapshotEntity::getOperatorCode,
                        OmsFulfillmentSnapshotEntity::getOperatorName,
                        OmsFulfillmentSnapshotEntity::getCapturedTime,
                        OmsFulfillmentSnapshotEntity::getFactoryCode)
                .eq(OmsFulfillmentSnapshotEntity::getOrderDetailId, orderDetailId)
                .orderByDesc(OmsFulfillmentSnapshotEntity::getSnapshotVersion));
    }

    @Override
    public OmsFulfillmentSnapshotEntity detail(Long snapshotId) {
        require(snapshotId != null, "履约快照不能为空");
        OmsFulfillmentSnapshotEntity entity = snapshotMapper.selectById(snapshotId);
        require(entity != null, "履约快照不存在");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private void applyDelta(OmsFulfillmentSnapshotEntity current, OmsFulfillmentSnapshotEntity previous) {
        if (previous == null) return;
        if (previous.getPromisedDate() != null && current.getPromisedDate() != null) {
            current.setPromisedDateShiftDays(Math.toIntExact(ChronoUnit.DAYS.between(
                    previous.getPromisedDate(), current.getPromisedDate())));
        }
        current.setOrderedQuantityDelta(delta(current.getOrderedQuantity(), previous.getOrderedQuantity()));
        current.setProducedQuantityDelta(delta(current.getProducedQuantity(), previous.getProducedQuantity()));
        current.setStockedQuantityDelta(delta(current.getStockedQuantity(), previous.getStockedQuantity()));
        current.setDeliveredQuantityDelta(delta(current.getDeliveredQuantity(), previous.getDeliveredQuantity()));
        current.setRemainingQuantityDelta(delta(current.getRemainingQuantity(), previous.getRemainingQuantity()));
        current.setCurrentOrderReservedQuantityDelta(delta(current.getCurrentOrderReservedQuantity(), previous.getCurrentOrderReservedQuantity()));
        current.setNetDeliverableQuantityDelta(delta(current.getNetDeliverableQuantity(), previous.getNetDeliverableQuantity()));
        current.setDeliverableShortageQuantityDelta(delta(current.getDeliverableShortageQuantity(), previous.getDeliverableShortageQuantity()));
    }

    private BigDecimal delta(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null ? null : current.subtract(previous);
    }

    private boolean oneOf(String value, String... allowed) {
        return List.of(allowed).contains(value);
    }

    private OmsFulfillmentSnapshotEntity byRequest(String requestId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<OmsFulfillmentSnapshotEntity>()
                .eq(OmsFulfillmentSnapshotEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(OmsFulfillmentSnapshotEntity::getRequestId, requestId.trim()).last("LIMIT 1"));
    }

    private OmsFulfillmentSnapshotEntity replay(OmsFulfillmentSnapshotEntity entity, Long orderDetailId, String fingerprint) {
        require(Objects.equals(entity.getOrderDetailId(), orderDetailId)
                        && Objects.equals(entity.getRequestFingerprint(), fingerprint),
                "稳定请求号已被不同交付行、操作人或原因使用");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private String json(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException(label + "无法序列化");
        }
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("履约快照指纹生成失败");
        }
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && user.getUsername() != null && !user.getUsername().isBlank(),
                "履约承诺快照必须由已登录用户冻结");
        return user;
    }

    private String userName(MomUser user) {
        return user.getName() == null || user.getName().isBlank() ? user.getUsername() : user.getName();
    }

    private void requireFactory(String factoryCode) {
        require(Objects.equals(FactoryContextHolder.getFactoryCode(), factoryCode), "当前工厂无权查看该履约快照");
    }

    private boolean text(String value, int max) {
        return value != null && !value.trim().isEmpty() && value.trim().length() <= max;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
