package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskCommand;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskQuery;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskCaseEntity;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskOperationEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.vo.OmsFulfillmentRiskWorkbenchVO;
import com.kitex.mom.oms.vo.OmsFulfillmentSummaryVO;
import com.kitex.mom.oms.vo.OmsLifeVO;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskCaseMapper;
import com.kitex.mom.plan.mapper.OmsFulfillmentRiskOperationMapper;
import com.kitex.mom.plan.mapper.OmsOrderDetailsMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 将实时履约风险转为有责任人、期限、处置和异岗事实复核的持久案件。
 */
@Service
@RequiredArgsConstructor
public class OmsFulfillmentRiskService {
    private static final Set<String> ACTIVE = Set.of("OPEN", "ASSIGNED", "MITIGATING", "PENDING_CLOSE");

    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsMapper detailMapper;
    private final OmsFulfillmentRiskCaseMapper caseMapper;
    private final OmsFulfillmentRiskOperationMapper operationMapper;
    private final RemoteUserService userService;
    private final RemoteMdmFactoryAccessService factoryAccessService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OmsFulfillmentRiskWorkbenchVO workbench(Page<OmsFulfillmentRiskCaseEntity> page,
                                                   OmsFulfillmentRiskQuery query) {
        String factoryCode = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode), "请先选择当前工厂");
        OmsFulfillmentRiskQuery criteria = query == null ? new OmsFulfillmentRiskQuery() : query;
        validateWorkbenchQuery(criteria);
        LocalDateTime observedAt = LocalDateTime.now();
        LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity> detailQuery = workbenchQuery(factoryCode, criteria, observedAt);
        detailQuery.orderByAsc(OmsFulfillmentRiskCaseEntity::getSeverity)
                .orderByAsc(OmsFulfillmentRiskCaseEntity::getResponseDueTime)
                .orderByDesc(OmsFulfillmentRiskCaseEntity::getLastDetectedTime)
                .orderByDesc(OmsFulfillmentRiskCaseEntity::getId);
        Page<OmsFulfillmentRiskCaseEntity> result = caseMapper.selectPage(page, detailQuery);
        markOverdue(result.getRecords(), observedAt);

        return new OmsFulfillmentRiskWorkbenchVO()
                .setTotalCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)))
                .setActiveCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)
                        .in(OmsFulfillmentRiskCaseEntity::getStatus, ACTIVE)))
                .setCriticalCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)
                        .eq(OmsFulfillmentRiskCaseEntity::getSeverity, "CRITICAL")
                        .in(OmsFulfillmentRiskCaseEntity::getStatus, ACTIVE)))
                .setUnassignedCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)
                        .isNull(OmsFulfillmentRiskCaseEntity::getOwnerUserId)
                        .in(OmsFulfillmentRiskCaseEntity::getStatus, "OPEN", "ASSIGNED", "MITIGATING")))
                .setOverdueCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)
                        .in(OmsFulfillmentRiskCaseEntity::getStatus, "ASSIGNED", "MITIGATING")
                        .isNotNull(OmsFulfillmentRiskCaseEntity::getResponseDueTime)
                        .lt(OmsFulfillmentRiskCaseEntity::getResponseDueTime, observedAt)))
                .setPendingCloseCount(caseMapper.selectCount(workbenchQuery(factoryCode, criteria, observedAt)
                        .eq(OmsFulfillmentRiskCaseEntity::getStatus, "PENDING_CLOSE")))
                .setPage(result);
    }

    public List<OmsFulfillmentRiskCaseEntity> list(Long orderDetailId) {
        OmsOrderDetailsEntity detail = detail(orderDetailId, false);
        List<OmsFulfillmentRiskCaseEntity> rows = listInternal(detail);
        markOverdue(rows);
        return rows;
    }

    private LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity> workbenchQuery(String factoryCode,
                                                                            OmsFulfillmentRiskQuery query, LocalDateTime observedAt) {
        boolean overdueOnly = Boolean.TRUE.equals(query.getOverdueOnly());
        return new LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity>()
                .eq(OmsFulfillmentRiskCaseEntity::getFactoryCode, factoryCode)
                .like(StrUtil.isNotBlank(query.getOrderCode()), OmsFulfillmentRiskCaseEntity::getOrderCode,
                        StrUtil.trim(query.getOrderCode()))
                .like(StrUtil.isNotBlank(query.getProductCode()), OmsFulfillmentRiskCaseEntity::getProductCode,
                        StrUtil.trim(query.getProductCode()))
                .eq(StrUtil.isNotBlank(query.getRiskCode()), OmsFulfillmentRiskCaseEntity::getRiskCode,
                        StrUtil.trim(query.getRiskCode()))
                .eq(StrUtil.isNotBlank(query.getSeverity()), OmsFulfillmentRiskCaseEntity::getSeverity,
                        StrUtil.trim(query.getSeverity()))
                .eq(StrUtil.isNotBlank(query.getStatus()), OmsFulfillmentRiskCaseEntity::getStatus,
                        StrUtil.trim(query.getStatus()))
                .eq(StrUtil.isNotBlank(query.getOwnerCode()), OmsFulfillmentRiskCaseEntity::getOwnerCode,
                        StrUtil.trim(query.getOwnerCode()))
                .in(Boolean.TRUE.equals(query.getActiveOnly()), OmsFulfillmentRiskCaseEntity::getStatus, ACTIVE)
                .isNull(Boolean.TRUE.equals(query.getUnassignedOnly()), OmsFulfillmentRiskCaseEntity::getOwnerUserId)
                .ne(Boolean.TRUE.equals(query.getUnassignedOnly()), OmsFulfillmentRiskCaseEntity::getStatus,
                        "PENDING_CLOSE")
                .in(overdueOnly, OmsFulfillmentRiskCaseEntity::getStatus, "ASSIGNED", "MITIGATING")
                .isNotNull(overdueOnly, OmsFulfillmentRiskCaseEntity::getResponseDueTime)
                .lt(overdueOnly, OmsFulfillmentRiskCaseEntity::getResponseDueTime, observedAt);
    }

    private void markOverdue(Collection<OmsFulfillmentRiskCaseEntity> rows) {
        markOverdue(rows, LocalDateTime.now());
    }

    private void markOverdue(Collection<OmsFulfillmentRiskCaseEntity> rows, LocalDateTime observedAt) {
        rows.forEach(row -> row.setOverdue(isOverdue(row, observedAt)));
    }

    private boolean isOverdue(OmsFulfillmentRiskCaseEntity row, LocalDateTime now) {
        return Set.of("ASSIGNED", "MITIGATING").contains(row.getStatus())
                && row.getResponseDueTime() != null && row.getResponseDueTime().isBefore(now);
    }

    private void validateWorkbenchQuery(OmsFulfillmentRiskQuery query) {
        require(length(query.getOrderCode()) <= 100, "订单编号筛选长度不得超过100");
        require(length(query.getProductCode()) <= 100, "产品编码筛选长度不得超过100");
        require(length(query.getOwnerCode()) <= 100, "责任人工号筛选长度不得超过100");
        require(StrUtil.isBlank(query.getRiskCode()) || Set.of("PLAN_INCONSISTENT", "PLAN_UNAVAILABLE",
                        "MES_UNAVAILABLE", "PMS_UNAVAILABLE", "WMS_UNAVAILABLE", "DELIVERY_OVERDUE",
                        "DELIVERY_OVER", "PRODUCTION_OVER", "QUALITY_DELIVERABILITY_UNAVAILABLE",
                        "QUALITY_DELIVERABILITY_INCONSISTENT", "QUALITY_DELIVERABLE_SHORTAGE")
                .contains(query.getRiskCode()), "未知的履约风险编码");
        require(StrUtil.isBlank(query.getSeverity()) || Set.of("CRITICAL", "WARNING", "UNKNOWN")
                .contains(query.getSeverity()), "未知的履约风险等级");
        require(StrUtil.isBlank(query.getStatus()) || Set.of("OPEN", "ASSIGNED", "MITIGATING",
                "PENDING_CLOSE", "CLOSED").contains(query.getStatus()), "未知的履约风险状态");
    }

    private int length(String value) {
        return value == null ? 0 : value.trim().length();
    }

    public List<OmsFulfillmentRiskOperationEntity> operations(Long caseId) {
        OmsFulfillmentRiskCaseEntity entity = riskCase(caseId, false);
        return operationMapper.selectList(new LambdaQueryWrapper<OmsFulfillmentRiskOperationEntity>()
                .eq(OmsFulfillmentRiskOperationEntity::getFactoryCode, entity.getFactoryCode())
                .and(scope -> scope.eq(OmsFulfillmentRiskOperationEntity::getCaseId, entity.getId())
                        .or(batch -> batch.isNull(OmsFulfillmentRiskOperationEntity::getCaseId)
                                .eq(OmsFulfillmentRiskOperationEntity::getOrderDetailId, entity.getOrderDetailId())))
                .orderByAsc(OmsFulfillmentRiskOperationEntity::getOperationTime)
                .orderByAsc(OmsFulfillmentRiskOperationEntity::getId));
    }

    public List<OmsFulfillmentRiskCaseEntity> sync(Long orderDetailId, OmsFulfillmentRiskCommand.Sync command) {
        MomUser actor = currentUser();
        return syncInternal(orderDetailId, command, actor.getUsername(), userName(actor));
    }

    /**
     * 仅由恢复了明确租户、工厂上下文的独立调度器调用。
     */
    public List<OmsFulfillmentRiskCaseEntity> syncSystem(Long orderDetailId, String requestId) {
        OmsFulfillmentRiskCommand.Sync command = new OmsFulfillmentRiskCommand.Sync();
        command.setRequestId(requestId);
        return syncInternal(orderDetailId, command, "SYSTEM", "履约风险自动扫描");
    }

    private List<OmsFulfillmentRiskCaseEntity> syncInternal(Long orderDetailId,
                                                            OmsFulfillmentRiskCommand.Sync command, String actorCode, String actorName) {
        OmsOrderDetailsEntity detail = detail(orderDetailId, false);
        String requestId = required(command == null ? null : command.getRequestId(), 64, "风险同步请求号");
        String requestFingerprint = sha("SYNC|" + orderDetailId);
        OmsFulfillmentRiskOperationEntity replay = operationByRequest(requestId, detail.getFactoryCode());
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint)
                    && "SYNC_BATCH".equals(replay.getOperationType()), "风险同步请求号已被其他载荷使用");
            return list(orderDetailId);
        }
        OmsLifeVO life = trackService.lifePlan(orderDetailId);
        require(life != null && life.getFulfillmentSummary() != null, "履约事实未形成，不能同步风险");
        Map<String, Signal> signals = signalMap(life.getFulfillmentSummary());
        String currentEvidence = json(summaryFacts(life.getFulfillmentSummary()));
        return transactionTemplate.execute(status -> syncTx(detail, requestId, requestFingerprint, signals,
                currentEvidence, actorCode, actorName));
    }

    public OmsFulfillmentRiskCaseEntity assign(Long caseId, OmsFulfillmentRiskCommand.Assign command) {
        String requestId = required(command == null ? null : command.getRequestId(), 64, "风险分派请求号");
        String ownerCode = required(command.getOwnerCode(), 100, "责任人工号");
        LocalDateTime due = command.getResponseDueTime();
        require(due != null && due.isAfter(LocalDateTime.now()), "响应期限必须晚于当前时间");
        String plan = required(command.getActionPlan(), 2000, "处置计划");
        OmsFulfillmentRiskCaseEntity observed = riskCase(caseId, false);
        String fingerprint = sha("ASSIGN|" + caseId + '|' + ownerCode + '|' + due + '|' + plan);
        OmsFulfillmentRiskOperationEntity existing = operationByRequest(requestId, observed.getFactoryCode());
        if (existing != null) {
            require(Objects.equals(existing.getRequestFingerprint(), fingerprint)
                    && Objects.equals(existing.getCaseId(), caseId), "风险分派请求号已被其他载荷使用");
            return riskCase(caseId, false);
        }
        SysUserEntity owner = resolveOwner(ownerCode, observed.getFactoryCode());
        return transactionTemplate.execute(status -> {
            caseMapper.lockKey("OMS_FULFILLMENT_RISK_REQUEST:" + requestId);
            OmsFulfillmentRiskOperationEntity replay = operationByRequest(requestId, observed.getFactoryCode());
            if (replay != null) {
                require(Objects.equals(replay.getRequestFingerprint(), fingerprint)
                        && Objects.equals(replay.getCaseId(), caseId), "风险分派请求号已被其他载荷使用");
                return riskCase(caseId, false);
            }
            OmsFulfillmentRiskCaseEntity entity = riskCase(caseId, true);
            require(Set.of("OPEN", "ASSIGNED", "MITIGATING").contains(entity.getStatus()), "当前风险状态不可分派");
            String before = entity.getStatus();
            entity.setOwnerUserId(owner.getUserId()).setOwnerCode(owner.getUsername()).setOwnerName(owner.getName())
                    .setResponseDueTime(due).setActionPlan(plan).setAcknowledgedTime(LocalDateTime.now())
                    .setStatus("ASSIGNED").setLastOperationRequestId(requestId).setLastOperationType("ASSIGN");
            require(caseMapper.updateById(entity) == 1, "风险分派保存失败");
            audit(entity, requestId, fingerprint, "ASSIGN", before, entity.getStatus(),
                    "责任人=" + entity.getOwnerName() + "，响应期限=" + due + "，处置计划=" + plan);
            return entity;
        });
    }

    public OmsFulfillmentRiskCaseEntity progress(Long caseId, OmsFulfillmentRiskCommand.Progress command) {
        String requestId = required(command == null ? null : command.getRequestId(), 64, "风险处置请求号");
        String result = required(command.getActionResult(), 4000, "处置进展");
        OmsFulfillmentRiskCaseEntity observed = riskCase(caseId, false);
        String fingerprint = sha("PROGRESS|" + caseId + '|' + result);
        return transactionTemplate.execute(status -> {
            caseMapper.lockKey("OMS_FULFILLMENT_RISK_REQUEST:" + requestId);
            OmsFulfillmentRiskOperationEntity replay = operationByRequest(requestId, observed.getFactoryCode());
            if (replay != null) {
                require(Objects.equals(replay.getRequestFingerprint(), fingerprint)
                        && Objects.equals(replay.getCaseId(), caseId), "风险处置请求号已被其他载荷使用");
                return riskCase(caseId, false);
            }
            OmsFulfillmentRiskCaseEntity entity = riskCase(caseId, true);
            MomUser user = currentUser();
            require(Objects.equals(entity.getOwnerCode(), user.getUsername()), "仅当前责任人可以提交处置进展");
            require(Set.of("ASSIGNED", "MITIGATING").contains(entity.getStatus()), "当前风险状态不可提交处置进展");
            String before = entity.getStatus();
            entity.setActionResult(result).setStatus("MITIGATING")
                    .setLastOperationRequestId(requestId).setLastOperationType("PROGRESS");
            require(caseMapper.updateById(entity) == 1, "风险处置进展保存失败");
            audit(entity, requestId, fingerprint, "PROGRESS", before, entity.getStatus(), result);
            return entity;
        });
    }

    public OmsFulfillmentRiskCaseEntity close(Long caseId, OmsFulfillmentRiskCommand.Close command) {
        String requestId = required(command == null ? null : command.getRequestId(), 64, "风险关闭请求号");
        String remark = required(command.getCloseRemark(), 1000, "关闭复核意见");
        OmsFulfillmentRiskCaseEntity observed = riskCase(caseId, false);
        String requestFingerprint = sha("CLOSE|" + caseId + '|' + remark);
        OmsFulfillmentRiskOperationEntity replay = operationByRequest(requestId, observed.getFactoryCode());
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint)
                    && Objects.equals(replay.getCaseId(), caseId), "风险关闭请求号已被其他载荷使用");
            return riskCase(caseId, false);
        }
        require("PENDING_CLOSE".equals(observed.getStatus()), "风险须先同步确认消失，才能进入关闭复核");
        OmsLifeVO life = trackService.lifePlan(observed.getOrderDetailId());
        require(life != null && life.getFulfillmentSummary() != null, "关闭复核无法读取当前履约事实");
        require(!signalMap(life.getFulfillmentSummary()).containsKey(observed.getRiskCode()),
                "当前权威事实仍存在该风险，不能关闭");
        String closeEvidence = json(summaryFacts(life.getFulfillmentSummary()));
        String closeEvidenceFingerprint = sha(closeEvidence);
        return transactionTemplate.execute(status -> {
            caseMapper.lockKey("OMS_FULFILLMENT_RISK_REQUEST:" + requestId);
            OmsFulfillmentRiskOperationEntity concurrentReplay = operationByRequest(requestId, observed.getFactoryCode());
            if (concurrentReplay != null) {
                require(Objects.equals(concurrentReplay.getRequestFingerprint(), requestFingerprint)
                        && Objects.equals(concurrentReplay.getCaseId(), caseId), "风险关闭请求号已被其他载荷使用");
                return riskCase(caseId, false);
            }
            OmsFulfillmentRiskCaseEntity entity = riskCase(caseId, true);
            require("PENDING_CLOSE".equals(entity.getStatus()), "风险状态已变化，请重新同步后复核");
            MomUser reviewer = currentUser();
            require(StrUtil.isBlank(entity.getOwnerCode()) || !Objects.equals(entity.getOwnerCode(), reviewer.getUsername()),
                    "风险责任人与关闭复核人必须分离");
            require(entity.getOwnerUserId() == null || StrUtil.isNotBlank(entity.getActionResult()),
                    "已分派风险必须先由责任人提交处置结果，才能关闭");
            String before = entity.getStatus();
            entity.setStatus("CLOSED").setClearedEvidenceFingerprint(closeEvidenceFingerprint)
                    .setClearedEvidenceSnapshot(closeEvidence).setClosedBy(reviewer.getUsername())
                    .setClosedByName(userName(reviewer)).setClosedTime(LocalDateTime.now()).setCloseRemark(remark)
                    .setLastOperationRequestId(requestId).setLastOperationType("CLOSE");
            require(caseMapper.updateById(entity) == 1, "风险关闭保存失败");
            audit(entity, requestId, requestFingerprint, "CLOSE", before, entity.getStatus(),
                    remark + "；关闭事实指纹=" + closeEvidenceFingerprint);
            return entity;
        });
    }

    private List<OmsFulfillmentRiskCaseEntity> syncTx(OmsOrderDetailsEntity detail, String requestId,
                                                      String requestFingerprint, Map<String, Signal> signals, String currentEvidence,
                                                      String actorCode, String actorName) {
        caseMapper.lockKey("OMS_FULFILLMENT_RISK_REQUEST:" + requestId);
        OmsFulfillmentRiskOperationEntity replay = operationByRequest(requestId, detail.getFactoryCode());
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint)
                    && "SYNC_BATCH".equals(replay.getOperationType()), "风险同步请求号已被其他载荷使用");
            return listInternal(detail);
        }
        caseMapper.lockKey("OMS_FULFILLMENT_RISK_LINE:" + detail.getFactoryCode() + ':' + detail.getId());
        List<OmsFulfillmentRiskCaseEntity> active = caseMapper.selectList(
                new LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity>()
                        .eq(OmsFulfillmentRiskCaseEntity::getOrderDetailId, detail.getId())
                        .eq(OmsFulfillmentRiskCaseEntity::getFactoryCode, detail.getFactoryCode())
                        .in(OmsFulfillmentRiskCaseEntity::getStatus, ACTIVE).last("FOR UPDATE"));
        Map<String, OmsFulfillmentRiskCaseEntity> byCode = new LinkedHashMap<>();
        for (OmsFulfillmentRiskCaseEntity row : active) {
            require(byCode.put(row.getRiskCode(), row) == null, "同一订单行存在重复活动风险案件，请先治理");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> created = new ArrayList<>(), refreshed = new ArrayList<>(), pendingClose = new ArrayList<>();
        for (Signal signal : signals.values()) {
            OmsFulfillmentRiskCaseEntity entity = byCode.remove(signal.code());
            if (entity == null) {
                entity = new OmsFulfillmentRiskCaseEntity().setCaseNo("OFR-" + IdUtil.getSnowflakeNextIdStr())
                        .setOrderDetailId(detail.getId()).setOrderCode(detail.getOrderCode())
                        .setProductCode(detail.getProductCode()).setDemandNo(signal.demandNo())
                        .setRiskCode(signal.code()).setRiskName(signal.name()).setSeverity(signal.severity())
                        .setStatus("OPEN").setOccurrenceCount(1).setSourceFingerprint(signal.fingerprint())
                        .setEvidenceSnapshot(signal.evidence()).setFirstDetectedTime(now).setLastDetectedTime(now)
                        .setLastOperationRequestId(requestId).setLastOperationType("SYNC_BATCH")
                        .setFactoryCode(detail.getFactoryCode());
                require(caseMapper.insert(entity) == 1, "履约风险案件创建失败");
                created.add(entity.getId());
            } else {
                boolean recurrence = "PENDING_CLOSE".equals(entity.getStatus());
                boolean changed = !Objects.equals(entity.getSourceFingerprint(), signal.fingerprint());
                if (recurrence || changed) entity.setOccurrenceCount(entity.getOccurrenceCount() + 1);
                if (recurrence) entity.setStatus(entity.getOwnerUserId() == null ? "OPEN" : "MITIGATING")
                        .setLastClearedTime(null).setClearedEvidenceFingerprint(null).setClearedEvidenceSnapshot(null);
                entity.setRiskName(signal.name()).setSeverity(signal.severity()).setDemandNo(signal.demandNo())
                        .setSourceFingerprint(signal.fingerprint()).setEvidenceSnapshot(signal.evidence())
                        .setLastDetectedTime(now).setLastOperationRequestId(requestId).setLastOperationType("SYNC_BATCH");
                require(caseMapper.updateById(entity) == 1, "履约风险案件刷新失败");
                refreshed.add(entity.getId());
            }
        }
        String clearedFingerprint = sha(currentEvidence);
        for (OmsFulfillmentRiskCaseEntity entity : byCode.values()) {
            if (!"PENDING_CLOSE".equals(entity.getStatus())) {
                entity.setStatus("PENDING_CLOSE").setLastClearedTime(now)
                        .setClearedEvidenceFingerprint(clearedFingerprint).setClearedEvidenceSnapshot(currentEvidence)
                        .setLastOperationRequestId(requestId).setLastOperationType("SYNC_BATCH");
                require(caseMapper.updateById(entity) == 1, "履约风险恢复状态保存失败");
                pendingClose.add(entity.getId());
            }
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("activeRiskCodes", signals.keySet());
        content.put("createdCaseIds", created);
        content.put("refreshedCaseIds", refreshed);
        content.put("pendingCloseCaseIds", pendingClose);
        auditBatch(detail, requestId, requestFingerprint, json(content), actorCode, actorName);
        return listInternal(detail);
    }

    private Map<String, Signal> signalMap(OmsFulfillmentSummaryVO summary) {
        LinkedHashMap<String, Signal> result = new LinkedHashMap<>();
        if ("INCONSISTENT".equals(summary.getPlanDataStatus())) add(result, signal("PLAN_INCONSISTENT",
                "PLAN来源不一致", "CRITICAL", summary,
                Map.of("planDataStatus", "INCONSISTENT", "messages", sourceMessages(summary, "PLAN"))));
        addUnavailable(result, "PLAN", summary.getPlanDataStatus(), summary);
        addUnavailable(result, "MES", summary.getMesDataStatus(), summary);
        addUnavailable(result, "PMS", summary.getPmsDataStatus(), summary);
        addUnavailable(result, "WMS", summary.getWmsDataStatus(), summary);
        if ("AVAILABLE".equals(summary.getWmsDataStatus())
                && "UNAVAILABLE".equals(summary.getQualityDeliverabilityStatus())) add(result,
                signal("QUALITY_DELIVERABILITY_UNAVAILABLE", "质量净可交事实不可用", "UNKNOWN", summary,
                        Map.of("status", "UNAVAILABLE", "messages", sourceMessages(summary, "WMS质量净可交"))));
        if ("INCONSISTENT".equals(summary.getQualityDeliverabilityStatus())) add(result,
                signal("QUALITY_DELIVERABILITY_INCONSISTENT", "质量净可交事实不一致", "CRITICAL", summary,
                        qualityFacts(summary)));
        BigDecimal ordered = summary.getOrderedQuantity();
        BigDecimal delivered = summary.getDeliveredQuantity();
        BigDecimal produced = summary.getProducedQuantity();
        if (ordered != null && delivered != null && delivered.compareTo(ordered) > 0) add(result,
                signal("DELIVERY_OVER", "订单累计超交", "WARNING", summary,
                        quantities(ordered, delivered, summary.getRemainingQuantity())));
        if (ordered != null && produced != null && produced.compareTo(ordered) > 0) add(result,
                signal("PRODUCTION_OVER", "订单成品超产", "WARNING", summary, quantities(ordered, produced, null)));
        if (summary.getPromisedDate() != null && summary.getPromisedDate().isBefore(LocalDate.now())
                && summary.getRemainingQuantity() != null && summary.getRemainingQuantity().signum() > 0) add(result,
                signal("DELIVERY_OVERDUE", "订单逾期欠交", "CRITICAL", summary,
                        quantities(ordered, delivered, summary.getRemainingQuantity())));
        if ("AVAILABLE".equals(summary.getQualityDeliverabilityStatus())
                && summary.getDeliverableShortageQuantity() != null
                && summary.getDeliverableShortageQuantity().signum() > 0) add(result,
                signal("QUALITY_DELIVERABLE_SHORTAGE", "质量净可交量短缺",
                        summary.getPromisedDate() != null && !summary.getPromisedDate().isAfter(LocalDate.now().plusDays(7))
                                ? "CRITICAL" : "WARNING", summary, qualityFacts(summary)));
        return result;
    }

    private void addUnavailable(Map<String, Signal> target, String source, String status,
                                OmsFulfillmentSummaryVO summary) {
        if ("UNAVAILABLE".equals(status)) add(target, signal(source + "_UNAVAILABLE", source + "履约事实不可用",
                "UNKNOWN", summary, Map.of("source", source, "status", status,
                        "messages", sourceMessages(summary, source))));
    }

    private Signal signal(String code, String name, String severity, OmsFulfillmentSummaryVO summary,
                          Map<String, Object> facts) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>(summaryFacts(summary));
        evidence.put("riskCode", code);
        evidence.put("riskFacts", facts);
        String snapshot = json(evidence);
        LinkedHashMap<String, Object> fingerprintFacts = new LinkedHashMap<>();
        fingerprintFacts.put("orderDetailId", summary.getOrderDetailId());
        fingerprintFacts.put("factoryCode", summary.getFactoryCode());
        fingerprintFacts.put("riskCode", code);
        fingerprintFacts.put("riskFacts", facts);
        return new Signal(code, name, severity, summary.getDemandNo(), sha(json(fingerprintFacts)), snapshot);
    }

    private List<String> sourceMessages(OmsFulfillmentSummaryVO summary, String source) {
        return Optional.ofNullable(summary.getRiskMessages()).orElse(List.of()).stream()
                .filter(message -> StrUtil.startWithIgnoreCase(message, source)).sorted().toList();
    }

    private Map<String, Object> summaryFacts(OmsFulfillmentSummaryVO summary) {
        LinkedHashMap<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderDetailId", summary.getOrderDetailId());
        facts.put("orderCode", summary.getOrderCode());
        facts.put("factoryCode", summary.getFactoryCode());
        facts.put("productCode", summary.getProductCode());
        facts.put("demandNo", summary.getDemandNo());
        facts.put("promisedDate", summary.getPromisedDate());
        facts.put("orderedQuantity", summary.getOrderedQuantity());
        facts.put("producedQuantity", summary.getProducedQuantity());
        facts.put("stockedQuantity", summary.getStockedQuantity());
        facts.put("deliveredQuantity", summary.getDeliveredQuantity());
        facts.put("remainingQuantity", summary.getRemainingQuantity());
        facts.put("fulfillmentRate", summary.getFulfillmentRate());
        facts.put("overallStatus", summary.getOverallStatus());
        facts.put("riskLevel", summary.getRiskLevel());
        facts.put("planDataStatus", summary.getPlanDataStatus());
        facts.put("mesDataStatus", summary.getMesDataStatus());
        facts.put("pmsDataStatus", summary.getPmsDataStatus());
        facts.put("wmsDataStatus", summary.getWmsDataStatus());
        facts.put("qualityDeliverabilityStatus", summary.getQualityDeliverabilityStatus());
        facts.put("qualifiedStockQuantity", summary.getQualifiedStockQuantity());
        facts.put("qualityHeldStockQuantity", summary.getQualityHeldStockQuantity());
        facts.put("otherWarehouseStockQuantity", summary.getOtherWarehouseStockQuantity());
        facts.put("reservedStockQuantity", summary.getReservedStockQuantity());
        facts.put("priorCommittedQuantity", summary.getPriorCommittedQuantity());
        facts.put("netDeliverableQuantity", summary.getNetDeliverableQuantity());
        facts.put("deliverableShortageQuantity", summary.getDeliverableShortageQuantity());
        return facts;
    }

    private Map<String, Object> qualityFacts(OmsFulfillmentSummaryVO summary) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("status", summary.getQualityDeliverabilityStatus());
        result.put("remainingQuantity", summary.getRemainingQuantity());
        result.put("qualifiedStockQuantity", summary.getQualifiedStockQuantity());
        result.put("qualityHeldStockQuantity", summary.getQualityHeldStockQuantity());
        result.put("otherWarehouseStockQuantity", summary.getOtherWarehouseStockQuantity());
        result.put("lockedOrExpiredStockQuantity", summary.getLockedOrExpiredStockQuantity());
        result.put("reservedStockQuantity", summary.getReservedStockQuantity());
        result.put("priorCommittedQuantity", summary.getPriorCommittedQuantity());
        result.put("netDeliverableQuantity", summary.getNetDeliverableQuantity());
        result.put("deliverableShortageQuantity", summary.getDeliverableShortageQuantity());
        result.put("allocationPolicy", summary.getDeliverabilityPolicy());
        result.put("message", summary.getDeliverabilityMessage());
        return result;
    }

    private Map<String, Object> quantities(BigDecimal ordered, BigDecimal actual, BigDecimal remaining) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("orderedQuantity", ordered);
        result.put("actualQuantity", actual);
        result.put("remainingQuantity", remaining);
        return result;
    }

    private void add(Map<String, Signal> target, Signal signal) {
        require(target.put(signal.code(), signal) == null, "重复履约风险编码：" + signal.code());
    }

    private SysUserEntity resolveOwner(String ownerCode, String factoryCode) {
        R<List<SysUserEntity>> response = userService.getUserListByUserName(ownerCode);
        require(response != null && response.isOk() && response.getData() != null, "责任人主数据不可读取");
        SysUserEntity owner = response.getData().stream().filter(Objects::nonNull)
                .filter(row -> Objects.equals(row.getUsername(), ownerCode)).findFirst().orElse(null);
        require(owner != null && StrUtil.isNotBlank(owner.getUsername()) && StrUtil.isNotBlank(owner.getName()),
                "责任人不存在或实名信息不完整");
        R<Boolean> access = factoryAccessService.check(new MdmFactoryAccessCheck().setUserId(owner.getUserId())
                .setFactoryCode(factoryCode).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
                "责任人没有当前工厂访问权，不能分派");
        return owner;
    }

    private List<OmsFulfillmentRiskCaseEntity> listInternal(OmsOrderDetailsEntity detail) {
        return caseMapper.selectList(new LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity>()
                .eq(OmsFulfillmentRiskCaseEntity::getOrderDetailId, detail.getId())
                .eq(OmsFulfillmentRiskCaseEntity::getFactoryCode, detail.getFactoryCode())
                .orderByDesc(OmsFulfillmentRiskCaseEntity::getId));
    }

    private OmsOrderDetailsEntity detail(Long id, boolean lock) {
        require(id != null, "订单交付行不能为空");
        LambdaQueryWrapper<OmsOrderDetailsEntity> query = new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsOrderDetailsEntity detail = detailMapper.selectOne(query);
        require(detail != null, "订单交付行不存在");
        requireFactory(detail.getFactoryCode());
        return detail;
    }

    private OmsFulfillmentRiskCaseEntity riskCase(Long id, boolean lock) {
        require(id != null, "履约风险案件不能为空");
        LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity> query = new LambdaQueryWrapper<OmsFulfillmentRiskCaseEntity>()
                .eq(OmsFulfillmentRiskCaseEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        OmsFulfillmentRiskCaseEntity entity = caseMapper.selectOne(query);
        require(entity != null, "履约风险案件不存在");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private OmsFulfillmentRiskOperationEntity operationByRequest(String requestId, String factoryCode) {
        return operationMapper.selectOne(new LambdaQueryWrapper<OmsFulfillmentRiskOperationEntity>()
                .eq(OmsFulfillmentRiskOperationEntity::getRequestId, requestId)
                .eq(OmsFulfillmentRiskOperationEntity::getFactoryCode, factoryCode));
    }

    private void audit(OmsFulfillmentRiskCaseEntity entity, String requestId, String fingerprint,
                       String type, String before, String after, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new OmsFulfillmentRiskOperationEntity().setCaseId(entity.getId())
                        .setOrderDetailId(entity.getOrderDetailId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                        .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                        .setOperatorCode(user.getUsername()).setOperatorName(userName(user))
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1,
                "履约风险操作履历保存失败");
    }

    private void auditBatch(OmsOrderDetailsEntity detail, String requestId, String fingerprint, String content,
                            String actorCode, String actorName) {
        require(operationMapper.insert(new OmsFulfillmentRiskOperationEntity().setCaseId(null)
                        .setOrderDetailId(detail.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                        .setOperationType("SYNC_BATCH").setBeforeStatus(null).setAfterStatus("SYNCED").setContent(content)
                        .setOperatorCode(actorCode).setOperatorName(actorName)
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(detail.getFactoryCode())) == 1,
                "履约风险同步履历保存失败");
    }

    private MomUser currentUser() {
        MomUser user;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ex) {
            user = null;
        }
        require(user != null && user.getId() != null && StrUtil.isNotBlank(user.getUsername()),
                "履约风险责任闭环必须由已登录用户操作");
        return user;
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(current) && Objects.equals(current, factoryCode), "当前工厂无权操作该履约风险");
    }

    private String required(String value, int max, String label) {
        String text = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(text) && text.length() <= max, label + "不能为空且长度不得超过" + max);
        return text;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("履约风险证据无法序列化");
        }
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("履约风险指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Signal(String code, String name, String severity, String demandNo,
                          String fingerprint, String evidence) {
    }
}
