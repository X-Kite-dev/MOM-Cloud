package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesApsSourceQuantityEventEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;
import com.kitex.mom.mes.mapper.MesApsBatchAllocationMapper;
import com.kitex.mom.mes.mapper.MesApsSourceQuantityEventMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesApsSourceQuantityService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MesApsSourceQuantityServiceImpl implements MesApsSourceQuantityService {

    private final MesTaskMapper taskMapper;
    private final MesApsBatchAllocationMapper allocationMapper;
    private final MesApsSourceQuantityEventMapper eventMapper;
    private final RemotePlnIssueService remotePlnIssueService;
    private final MesSourceQuantityPlanOutbox planOutbox;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(MesTaskEntity sourceTask, String requestId, MesSourceQuantityKind kind,
                       BigDecimal quantityDelta, String sourceReference, String content) {
        recordQuantities(sourceTask, requestId, kind, quantityDelta, sourceReference, content, false);
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordDeferred(MesTaskEntity sourceTask, String requestId, MesSourceQuantityKind kind,
                               BigDecimal quantityDelta, String sourceReference, String content) {
        if (kind != MesSourceQuantityKind.QUALITY_REJECTED)
            throw new CheckedException("正式检验来源通知仅处理质量判退事实");
        recordQuantities(sourceTask, requestId, kind, quantityDelta, sourceReference, content, true);
    }

    private void recordQuantities(MesTaskEntity sourceTask, String requestId, MesSourceQuantityKind kind,
                                  BigDecimal quantityDelta, String sourceReference, String content, boolean deferred) {
        if (sourceTask == null || sourceTask.getId() == null || StrUtil.isBlank(sourceTask.getCode())) {
            throw new CheckedException("执行偏差归集缺少原生产工单");
        }
        String request = StrUtil.trim(requestId);
        if (StrUtil.isBlank(request) || request.length() > 120) {
            throw new CheckedException("执行偏差归集请求号不能为空且不能超过120位");
        }
        if (kind == null || quantityDelta == null || quantityDelta.signum() == 0) {
            throw new CheckedException("执行偏差归集类型和非零数量不能为空");
        }
        String fingerprint = fingerprint(sourceTask, kind, quantityDelta, sourceReference);
        eventMapper.lockRequest(request);
        List<MesApsSourceQuantityEventEntity> existing = eventMapper.selectList(
                new LambdaQueryWrapper<MesApsSourceQuantityEventEntity>()
                        .eq(MesApsSourceQuantityEventEntity::getRequestId, request));
        if (!existing.isEmpty()) {
            if (existing.stream().anyMatch(item -> !Objects.equals(item.getRequestFingerprint(), fingerprint))) {
                throw new CheckedException("执行偏差归集请求号已使用且载荷不一致：" + request);
            }
            if (deferred) planOutbox.requireExisting(sourceTask, request, fingerprint);
            return;
        }

        taskMapper.acquireApsBatchTransactionLock("SOURCE_QUANTITY:"
                + StrUtil.blankToDefault(sourceTask.getApsBatchNo(), sourceTask.getCode()));
        List<MesApsBatchAllocationEntity> allocations = StrUtil.isBlank(sourceTask.getApsBatchNo())
                ? List.of() : allocationMapper.selectList(new LambdaQueryWrapper<MesApsBatchAllocationEntity>()
                .eq(MesApsBatchAllocationEntity::getMesTaskId, sourceTask.getId())
                .orderByAsc(MesApsBatchAllocationEntity::getAllocationSeq));
        List<OmsOrderLifeCycleDTO> callbacks = new ArrayList<>();
        if (allocations.isEmpty()) {
            if (StrUtil.isBlank(sourceTask.getPlanNo())) {
                throw new CheckedException("执行偏差归集工单未关联来源计划：" + sourceTask.getCode());
            }
            BigDecimal currentTotal = legacyTotal(sourceTask.getId(), kind);
            BigDecimal afterTotal = currentTotal.add(quantityDelta);
            validateLegacyTotals(sourceTask, kind, afterTotal);
            persist(event(sourceTask, request, fingerprint, kind, null, sourceTask.getPlanNo(),
                    quantityDelta, afterTotal, sourceReference, content), deferred);
            callbacks.add(callback(sourceTask, request, kind, null, sourceTask.getPlanNo(), quantityDelta, content));
        } else {
            BigDecimal allocatedTotal = allocations.stream().map(MesApsBatchAllocationEntity::getAllocatedQuantity)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sourceTask.getNoticeNum() == null || allocatedTotal.compareTo(sourceTask.getNoticeNum()) != 0) {
                throw new CheckedException("APS来源账与工单数量不守恒：" + sourceTask.getApsBatchNo());
            }
            BigDecimal currentTotal = allocations.stream().map(item -> current(item, kind))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal afterTotal = currentTotal.add(quantityDelta);
            validateTotals(allocations, kind, afterTotal, allocatedTotal);
            BigDecimal assigned = BigDecimal.ZERO;
            for (int index = 0; index < allocations.size(); index++) {
                MesApsBatchAllocationEntity allocation = allocations.get(index);
                BigDecimal target = index == allocations.size() - 1 ? afterTotal.subtract(assigned)
                        : afterTotal.multiply(allocation.getAllocatedQuantity())
                        .divide(allocatedTotal, 8, RoundingMode.HALF_UP);
                BigDecimal before = current(allocation, kind);
                BigDecimal delta = target.subtract(before);
                if (target.signum() < 0) {
                    throw new CheckedException("来源计划执行偏差累计量不能为负：" + allocation.getIssueNo());
                }
                assign(allocation, kind, target);
                if (allocationMapper.updateById(allocation) != 1) {
                    throw new CheckedException("APS来源数量账已被并发更新，请重试");
                }
                assigned = assigned.add(target);
                if (delta.signum() != 0) {
                    persist(event(sourceTask, request, fingerprint, kind, allocation.getIssueId(),
                            allocation.getIssueNo(), delta, target, sourceReference, content), deferred);
                    callbacks.add(callback(sourceTask, request, kind, allocation.getIssueId(),
                            allocation.getIssueNo(), delta, content));
                }
            }
        }
        if (deferred) {
            planOutbox.enqueue(sourceTask, request, fingerprint, callbacks);
        } else if (!callbacks.isEmpty()) {
            R<Boolean> result = remotePlnIssueService.edit(callbacks);
            if (result == null || !Boolean.TRUE.equals(result.getData())) {
                throw new CheckedException("执行偏差归集回写PLAN失败：" + sourceTask.getCode());
            }
        }
    }

    private void persist(MesApsSourceQuantityEventEntity event, boolean deferred) {
        if (eventMapper.insert(event) != 1 && deferred)
            throw new CheckedException("来源数量事实未保存，已整体回滚");
    }

    private void validateTotals(List<MesApsBatchAllocationEntity> allocations, MesSourceQuantityKind kind,
                                BigDecimal afterTotal, BigDecimal allocatedTotal) {
        if (afterTotal.signum() < 0) throw new CheckedException("执行偏差累计量不能为负");
        if (kind == MesSourceQuantityKind.UNQUALIFIED_REPORT || kind == MesSourceQuantityKind.QUALITY_ISOLATED
                || kind == MesSourceQuantityKind.QUALITY_RELEASED
                || kind == MesSourceQuantityKind.QUALITY_REJECTED || kind == MesSourceQuantityKind.SCRAP) {
            if (afterTotal.compareTo(allocatedTotal) > 0) {
                throw new CheckedException("执行偏差累计量超过APS批次分配量：" + kind);
            }
        }
        BigDecimal unqualified = kind == MesSourceQuantityKind.UNQUALIFIED_REPORT ? afterTotal
                : sum(allocations, MesSourceQuantityKind.UNQUALIFIED_REPORT);
        BigDecimal isolated = kind == MesSourceQuantityKind.QUALITY_ISOLATED ? afterTotal
                : sum(allocations, MesSourceQuantityKind.QUALITY_ISOLATED);
        BigDecimal released = kind == MesSourceQuantityKind.QUALITY_RELEASED ? afterTotal
                : sum(allocations, MesSourceQuantityKind.QUALITY_RELEASED);
        if (released.compareTo(unqualified.max(isolated)) > 0) {
            throw new CheckedException("QMS放行累计量超过已上报不合格或已隔离数量");
        }
        BigDecimal rework = sum(allocations, MesSourceQuantityKind.REWORK_CREATED);
        BigDecimal completed = kind == MesSourceQuantityKind.REWORK_COMPLETED ? afterTotal
                : sum(allocations, MesSourceQuantityKind.REWORK_COMPLETED);
        BigDecimal qualified = kind == MesSourceQuantityKind.REWORK_QUALIFIED ? afterTotal
                : sum(allocations, MesSourceQuantityKind.REWORK_QUALIFIED);
        BigDecimal rejected = kind == MesSourceQuantityKind.REWORK_REJECTED ? afterTotal
                : sum(allocations, MesSourceQuantityKind.REWORK_REJECTED);
        if (kind == MesSourceQuantityKind.REWORK_CREATED) rework = afterTotal;
        if (completed.compareTo(rework) > 0 || qualified.add(rejected).compareTo(completed) > 0) {
            throw new CheckedException("返工创建、完工与复检处置数量不守恒");
        }
    }

    private void validateLegacyTotals(MesTaskEntity task, MesSourceQuantityKind kind, BigDecimal afterTotal) {
        if (afterTotal.signum() < 0) throw new CheckedException("执行偏差累计量不能为负");
        if ((kind == MesSourceQuantityKind.UNQUALIFIED_REPORT || kind == MesSourceQuantityKind.QUALITY_ISOLATED
                || kind == MesSourceQuantityKind.QUALITY_RELEASED
                || kind == MesSourceQuantityKind.QUALITY_REJECTED || kind == MesSourceQuantityKind.SCRAP)
                && (task.getNoticeNum() == null || afterTotal.compareTo(task.getNoticeNum()) > 0)) {
            throw new CheckedException("执行偏差累计量超过工单数量：" + kind);
        }
        BigDecimal unqualified = kind == MesSourceQuantityKind.UNQUALIFIED_REPORT ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.UNQUALIFIED_REPORT);
        BigDecimal isolated = kind == MesSourceQuantityKind.QUALITY_ISOLATED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.QUALITY_ISOLATED);
        BigDecimal released = kind == MesSourceQuantityKind.QUALITY_RELEASED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.QUALITY_RELEASED);
        if (released.compareTo(unqualified.max(isolated)) > 0) {
            throw new CheckedException("QMS放行累计量超过已上报不合格或已隔离数量");
        }
        BigDecimal created = kind == MesSourceQuantityKind.REWORK_CREATED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.REWORK_CREATED);
        BigDecimal completed = kind == MesSourceQuantityKind.REWORK_COMPLETED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.REWORK_COMPLETED);
        BigDecimal qualified = kind == MesSourceQuantityKind.REWORK_QUALIFIED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.REWORK_QUALIFIED);
        BigDecimal rejected = kind == MesSourceQuantityKind.REWORK_REJECTED ? afterTotal
                : legacyTotal(task.getId(), MesSourceQuantityKind.REWORK_REJECTED);
        if (completed.compareTo(created) > 0 || qualified.add(rejected).compareTo(completed) > 0) {
            throw new CheckedException("返工创建、完工与复检处置数量不守恒");
        }
    }

    private BigDecimal legacyTotal(Long taskId, MesSourceQuantityKind kind) {
        return eventMapper.selectList(new LambdaQueryWrapper<MesApsSourceQuantityEventEntity>()
                        .eq(MesApsSourceQuantityEventEntity::getMesTaskId, taskId)
                        .eq(MesApsSourceQuantityEventEntity::getEventKind, kind.name()))
                .stream().map(MesApsSourceQuantityEventEntity::getQuantityDelta)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sum(List<MesApsBatchAllocationEntity> allocations, MesSourceQuantityKind kind) {
        return allocations.stream().map(item -> current(item, kind)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal current(MesApsBatchAllocationEntity item, MesSourceQuantityKind kind) {
        BigDecimal value = switch (kind) {
            case UNQUALIFIED_REPORT -> item.getUnqualifiedQuantity();
            case QUALITY_ISOLATED -> item.getQualityIsolatedQuantity();
            case QUALITY_RELEASED -> item.getQualityReleasedQuantity();
            case QUALITY_REJECTED -> item.getQualityRejectedQuantity();
            case SCRAP -> item.getScrapQuantity();
            case REWORK_CREATED -> item.getReworkQuantity();
            case REWORK_COMPLETED -> item.getReworkCompletedQuantity();
            case REWORK_QUALIFIED -> item.getReworkQualifiedQuantity();
            case REWORK_REJECTED -> item.getReworkRejectedQuantity();
        };
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    private void assign(MesApsBatchAllocationEntity item, MesSourceQuantityKind kind, BigDecimal value) {
        switch (kind) {
            case UNQUALIFIED_REPORT -> item.setUnqualifiedQuantity(value);
            case QUALITY_ISOLATED -> item.setQualityIsolatedQuantity(value);
            case QUALITY_RELEASED -> item.setQualityReleasedQuantity(value);
            case QUALITY_REJECTED -> item.setQualityRejectedQuantity(value);
            case SCRAP -> item.setScrapQuantity(value);
            case REWORK_CREATED -> item.setReworkQuantity(value);
            case REWORK_COMPLETED -> item.setReworkCompletedQuantity(value);
            case REWORK_QUALIFIED -> item.setReworkQualifiedQuantity(value);
            case REWORK_REJECTED -> item.setReworkRejectedQuantity(value);
        }
    }

    private MesApsSourceQuantityEventEntity event(MesTaskEntity task, String requestId, String fingerprint,
                                                  MesSourceQuantityKind kind, Long issueId, String issueNo,
                                                  BigDecimal delta, BigDecimal after, String reference,
                                                  String content) {
        return new MesApsSourceQuantityEventEntity().setRequestId(requestId)
                .setRequestFingerprint(fingerprint).setEventKind(kind.name())
                .setMesTaskId(task.getId()).setMesTaskCode(task.getCode()).setApsBatchNo(task.getApsBatchNo())
                .setSourceIssueId(issueId).setSourceIssueNo(issueNo).setQuantityDelta(delta)
                .setQuantityAfter(after).setSourceReference(StrUtil.trim(reference))
                .setEventContent(StrUtil.maxLength(StrUtil.trim(content), 1000))
                .setOccurredTime(LocalDateTime.now()).setFactoryCode(task.getFactoryCode());
    }

    private OmsOrderLifeCycleDTO callback(MesTaskEntity task, String requestId, MesSourceQuantityKind kind,
                                          Long issueId, String issueNo, BigDecimal delta, String content) {
        OmsOrderLifeCycleDTO dto = new OmsOrderLifeCycleDTO();
        dto.setRequestId(callbackRequestId(requestId, Objects.requireNonNullElse(issueId, issueNo)));
        dto.setNo(issueNo);
        dto.setProductCode(task.getMatCode());
        dto.setType("mes");
        dto.setContent(content);
        switch (kind) {
            case UNQUALIFIED_REPORT -> dto.setUnqualifiedNum(delta);
            case QUALITY_ISOLATED -> dto.setQualityIsolatedNum(delta);
            case QUALITY_RELEASED -> dto.setQualityReleasedNum(delta);
            case QUALITY_REJECTED -> dto.setQualityRejectedNum(delta);
            case SCRAP -> dto.setScrapNum(delta);
            case REWORK_CREATED -> dto.setReworkNum(delta);
            case REWORK_COMPLETED -> dto.setReworkCompletedNum(delta);
            case REWORK_QUALIFIED -> dto.setReworkQualifiedNum(delta);
            case REWORK_REJECTED -> dto.setReworkRejectedNum(delta);
        }
        return dto;
    }

    private String callbackRequestId(String requestId, Object sourceIssue) {
        String raw = "MES-SOURCE-QTY:" + requestId + ":" + sourceIssue;
        return raw.length() <= 200 ? raw : "MES-SOURCE-QTY:SHA256:" + sha256(raw);
    }

    private String fingerprint(MesTaskEntity task, MesSourceQuantityKind kind, BigDecimal delta, String reference) {
        try {
            String raw = String.join("|", String.valueOf(task.getId()), task.getCode(), kind.name(),
                    delta.stripTrailingZeros().toPlainString(), StrUtil.nullToEmpty(reference).trim());
            return sha256(raw);
        } catch (Exception exception) {
            throw new CheckedException("执行偏差归集指纹生成失败");
        }
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("执行偏差归集摘要生成失败");
        }
    }
}
