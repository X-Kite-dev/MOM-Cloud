package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.entity.PlnSchedulingEffectActionEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectObservationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectActionMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectObservationMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectReviewMapper;
import com.kitex.mom.plan.service.PlnSchedulingEffectActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 执行效果阈值行动账。
 *
 * <p>定时器只记录待确认事实，不借用人工身份建案；新观察替换旧待办时保留原行，
 * 同一MES事实指纹和容差重复采集不会制造第二个待办。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnSchedulingEffectActionServiceImpl
        extends ServiceImpl<PlnSchedulingEffectActionMapper, PlnSchedulingEffectActionEntity>
        implements PlnSchedulingEffectActionService {

    private static final Set<String> EVIDENCE_STATUSES = Set.of("IN_PROGRESS", "COMPLETED");

    private final PlnSchedulingEffectReviewMapper reviewMapper;
    private final PlnSchedulingEffectObservationMapper observationMapper;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnSchedulingEffectActionEntity reconcile(PlnSchedulingEffectReviewEntity review,
                                                     PlnSchedulingEffectObservationEntity observation) {
        require(review != null && observation != null && review.getId() != null
                        && Objects.equals(review.getId(), observation.getReviewId())
                        && Objects.equals(review.getTenantId(), observation.getTenantId())
                        && Objects.equals(review.getFactoryCode(), observation.getFactoryCode())
                        && Objects.equals(review.getCurrentObservationId(), observation.getId()),
                "效果行动只能依据当前工厂最新的不可变观察记录生成");
        // 历史回评允许没有租户标记；它可以保留观察记录，但不能进入租户隔离的行动账。
        if (review.getTenantId() == null || review.getTenantId() <= 0
                || StrUtil.isBlank(review.getFactoryCode())) return null;
        reviewMapper.lockKey("EFFECT_ACTION:" + review.getTenantId() + ':' + review.getId());
        List<PlnSchedulingEffectActionEntity> actions = baseMapper.selectList(query(review)
                .orderByDesc(PlnSchedulingEffectActionEntity::getId));
        boolean thresholdExceeded = exceeded(observation);
        String fingerprint = actionFingerprint(observation);
        int tolerance = Objects.requireNonNullElse(observation.getToleranceMinutes(), 30);
        PlnSchedulingEffectActionEntity current = actions.stream()
                .filter(item -> "PENDING".equals(item.getActionStatus()))
                .findFirst().orElse(null);
        if (thresholdExceeded && sameEvidence(current, fingerprint, tolerance)) return current;

        if (current != null) {
            current.setActionStatus(thresholdExceeded || !EVIDENCE_STATUSES.contains(observation.getObservationStatus())
                    ? "SUPERSEDED" : "RESOLVED");
            require(baseMapper.updateById(current) == 1, "旧效果偏差行动状态更新失败");
        }
        if (!thresholdExceeded) return null;

        PlnSchedulingEffectActionEntity confirmed = actions.stream()
                .filter(item -> "CONFIRMED".equals(item.getActionStatus()))
                .filter(item -> sameEvidence(item, fingerprint, tolerance))
                .findFirst().orElse(null);
        if (confirmed != null) return confirmed;

        PlnSchedulingEffectActionEntity created = new PlnSchedulingEffectActionEntity()
                .setReviewId(review.getId()).setObservationId(observation.getId())
                .setEffectFingerprint(fingerprint).setToleranceMinutes(tolerance)
                .setActionStatus("PENDING").setTriggerReason(reason(observation, tolerance))
                .setFactoryCode(review.getFactoryCode());
        created.setTenantId(review.getTenantId());
        created.setCreateBy("SYSTEM_EFFECT_REVIEW");
        require(baseMapper.insert(created) == 1, "效果偏差待确认行动保存失败");
        return created;
    }

    @Override
    public PlnSchedulingEffectActionEntity pending(PlnSchedulingEffectReviewEntity review) {
        if (review == null || review.getCurrentObservationId() == null) return null;
        PlnSchedulingEffectObservationEntity current = observationMapper.selectById(review.getCurrentObservationId());
        if (current == null || !Objects.equals(current.getReviewId(), review.getId())
                || !Objects.equals(current.getTenantId(), review.getTenantId())
                || !Objects.equals(current.getFactoryCode(), review.getFactoryCode())
                || !exceeded(current)) return null;
        return baseMapper.selectList(query(review)
                        .eq(PlnSchedulingEffectActionEntity::getActionStatus, "PENDING")
                        .eq(PlnSchedulingEffectActionEntity::getEffectFingerprint, actionFingerprint(current))
                        .eq(PlnSchedulingEffectActionEntity::getToleranceMinutes,
                                Objects.requireNonNullElse(current.getToleranceMinutes(), 30))
                        .orderByDesc(PlnSchedulingEffectActionEntity::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(PlnSchedulingEffectActionEntity action, Long caseId, String actor) {
        require(action != null && action.getId() != null && caseId != null && caseId > 0
                && StrUtil.isNotBlank(actor), "偏差行动确认必须包含行动、案件和真实操作人");
        PlnSchedulingEffectActionEntity locked = baseMapper.lockById(action.getId());
        require(locked != null && "PENDING".equals(locked.getActionStatus()), "偏差行动已变化，请刷新后重试");
        PlnSchedulingEffectReviewEntity review = reviewMapper.selectById(locked.getReviewId());
        PlnSchedulingEffectActionEntity current = pending(review);
        require(review != null && Objects.equals(review.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(review.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && current != null && Objects.equals(current.getId(), locked.getId()),
                "当前观察指纹或工厂已变化，不能确认旧偏差行动");
        locked.setActionStatus("CONFIRMED").setConfirmedCaseId(caseId)
                .setConfirmedBy(actor.trim()).setConfirmedTime(LocalDateTime.now());
        locked.setUpdateBy(actor.trim());
        require(baseMapper.updateById(locked) == 1, "偏差行动确认记录保存失败");
    }

    private LambdaQueryWrapper<PlnSchedulingEffectActionEntity> query(PlnSchedulingEffectReviewEntity review) {
        return new LambdaQueryWrapper<PlnSchedulingEffectActionEntity>()
                .eq(PlnSchedulingEffectActionEntity::getTenantId, review.getTenantId())
                .eq(PlnSchedulingEffectActionEntity::getFactoryCode, review.getFactoryCode())
                .eq(PlnSchedulingEffectActionEntity::getReviewId, review.getId());
    }

    private boolean sameEvidence(PlnSchedulingEffectActionEntity action, String fingerprint, int tolerance) {
        return action != null && Objects.equals(action.getEffectFingerprint(), fingerprint)
                && Objects.equals(action.getToleranceMinutes(), tolerance);
    }

    /**
     * 行动只随MES实际状态变化。独立分钟证据的接口可用性、采集时间属于审计快照，
     * 不应让同一执行偏差待办反复失效；完整原始快照和其指纹仍保留在观察账中。
     */
    private String actionFingerprint(PlnSchedulingEffectObservationEntity observation) {
        try {
            JsonNode parsed = objectMapper.readTree(observation.getActualSnapshot());
            require(parsed instanceof ObjectNode, "效果观察快照格式不正确，不能建立偏差行动");
            ObjectNode facts = ((ObjectNode) parsed).deepCopy();
            facts.remove(List.of("capturedTime", "durationEvidenceVersion"));
            JsonNode operations = facts.get("operations");
            if (operations != null && operations.isArray()) {
                operations.forEach(item -> {
                    if (item instanceof ObjectNode row) row.remove("durationEvidence");
                });
            }
            byte[] bytes = objectMapper.writeValueAsBytes(facts);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("MES效果观察证据无法形成稳定行动指纹：" + ex.getMessage());
        }
    }

    private boolean exceeded(PlnSchedulingEffectObservationEntity observation) {
        if (observation == null || !EVIDENCE_STATUSES.contains(observation.getObservationStatus())
                || StrUtil.isBlank(observation.getActualFingerprint())
                || Objects.requireNonNullElse(observation.getMatchedOperationCount(), 0) <= 0) return false;
        int tolerance = Objects.requireNonNullElse(observation.getToleranceMinutes(), 30);
        BigDecimal gap = observation.getRealizationGap();
        return Objects.requireNonNullElse(observation.getMaximumAbsoluteStartDeviationMinutes(), 0L) > tolerance
                || Objects.requireNonNullElse(observation.getMaximumAbsoluteFinishDeviationMinutes(), 0L) > tolerance
                || Objects.requireNonNullElse(observation.getLateIssueCount(), 0) > 0
                || gap != null && gap.signum() < 0;
    }

    private String reason(PlnSchedulingEffectObservationEntity observation, int tolerance) {
        return "MES执行偏差超过本次" + tolerance + "分钟观察容差：最大开工偏差="
                + Objects.requireNonNullElse(observation.getMaximumAbsoluteStartDeviationMinutes(), 0L)
                + "分钟，最大完工偏差="
                + Objects.requireNonNullElse(observation.getMaximumAbsoluteFinishDeviationMinutes(), 0L)
                + "分钟，延期计划=" + Objects.requireNonNullElse(observation.getLateIssueCount(), 0)
                + "；待授权计划员确认，尚未选案、发布或下达MES";
    }
}
