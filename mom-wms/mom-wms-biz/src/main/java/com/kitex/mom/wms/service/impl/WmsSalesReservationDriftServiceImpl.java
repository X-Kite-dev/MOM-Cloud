package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsSalesReservationDriftDTO;
import com.kitex.mom.wms.dto.WmsSalesStockReservationCommand;
import com.kitex.mom.wms.dto.WmsSalesStockReservationSnapshot;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftCaseEntity;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftOperationEntity;
import com.kitex.mom.wms.entity.WmsSalesStockReservationEntity;
import com.kitex.mom.wms.mapper.WmsSalesReservationDriftCaseMapper;
import com.kitex.mom.wms.mapper.WmsSalesReservationDriftOperationMapper;
import com.kitex.mom.wms.mapper.WmsSalesStockReservationMapper;
import com.kitex.mom.wms.service.WmsSalesReservationDriftService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 销售预留漂移发现、工作台、异岗审批和原子换批闭环。
 */
@Service
@RequiredArgsConstructor
public class WmsSalesReservationDriftServiceImpl implements WmsSalesReservationDriftService {
    private static final Set<String> ACTIVE_CASE_STATUSES = Set.of(
            "OPEN", "PENDING_APPROVAL", "REJECTED", "PENDING_CLOSE");
    private static final String POLICY = "FULL_DRIFT; PRODUCT_WAREHOUSE; QUALITY_USABLE; FEFO_THEN_FIFO";

    private final WmsSalesReservationDriftCaseMapper caseMapper;
    private final WmsSalesReservationDriftOperationMapper operationMapper;
    private final WmsSalesStockReservationMapper reservationMapper;
    private final WmsSalesStockReservationService reservationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> scan(WmsSalesReservationDriftDTO.Scan command) {
        MomUser actor = actor();
        String factory = factory();
        require(command != null && requestId(command.getRequestId()), "漂移扫描须提供16至64位稳定请求号");
        String fingerprint = sha("SCAN|" + factory + '|' + actor.getUsername());
        caseMapper.lockKey("SCAN:" + factory);
        WmsSalesReservationDriftOperationEntity replay = operationByRequest(command.getRequestId(), factory);
        if (replay != null) {
            require("SCAN_BATCH".equals(replay.getOperationType())
                    && Objects.equals(fingerprint, replay.getRequestFingerprint()), "扫描请求号已用于其他操作或载荷");
            return scanResult(replay.getContent(), true);
        }

        List<WmsSalesStockReservationEntity> headers = reservationMapper.selectList(
                        new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                                .eq(WmsSalesStockReservationEntity::getFactoryCode, factory)
                                .eq(WmsSalesStockReservationEntity::getStatus, "ACTIVE")
                                .orderByAsc(WmsSalesStockReservationEntity::getId)).stream()
                .filter(row -> active(row).signum() > 0).toList();
        require(headers.size() <= 1000, "当前工厂活动销售预留超过1000单，拒绝截断漂移扫描");
        Map<Long, WmsSalesStockReservationEntity> headerByReservation = headers.stream()
                .collect(Collectors.toMap(WmsSalesStockReservationEntity::getId, row -> row));
        Map<Long, WmsSalesStockReservationSnapshot> observed = reservationService.snapshotsByPlanIds(
                headers.stream().map(WmsSalesStockReservationEntity::getDeliverPlanId).collect(Collectors.toSet()));
        Map<Long, WmsSalesReservationDriftCaseEntity> openCases = caseMapper.selectList(
                        new LambdaQueryWrapper<WmsSalesReservationDriftCaseEntity>()
                                .eq(WmsSalesReservationDriftCaseEntity::getFactoryCode, factory)
                                .in(WmsSalesReservationDriftCaseEntity::getStatus, ACTIVE_CASE_STATUSES)
                                .orderByAsc(WmsSalesReservationDriftCaseEntity::getId)).stream()
                .collect(Collectors.toMap(WmsSalesReservationDriftCaseEntity::getReservationId,
                        row -> row, (left, right) -> {
                            throw new CheckedException("同一销售预留存在多个活动漂移待办");
                        }));

        int created = 0, refreshed = 0, recovered = 0, unchanged = 0;
        Set<Long> seenReservations = new HashSet<>();
        for (WmsSalesStockReservationSnapshot snapshot : observed.values()) {
            if (snapshot.getReservationId() == null) continue;
            seenReservations.add(snapshot.getReservationId());
            WmsSalesStockReservationEntity header = headerByReservation.get(snapshot.getReservationId());
            require(header != null, "漂移扫描快照缺少销售预留主账");
            String evidence = evidence(snapshot);
            String evidenceFingerprint = sha(evidence);
            WmsSalesReservationDriftCaseEntity current = openCases.get(snapshot.getReservationId());
            String childRequest = childRequest(command.getRequestId(), snapshot.getReservationId());
            if (quantity(snapshot.getDriftQuantity()).signum() > 0) {
                if (current == null) {
                    createCase(header, snapshot, evidence, evidenceFingerprint, childRequest, actor);
                    created++;
                } else if (!Objects.equals(current.getEvidenceFingerprint(), evidenceFingerprint)
                        || "PENDING_CLOSE".equals(current.getStatus())) {
                    refreshDrift(current, snapshot, evidence, evidenceFingerprint, childRequest, actor);
                    refreshed++;
                } else unchanged++;
            } else if (current != null) {
                if (!"PENDING_CLOSE".equals(current.getStatus())
                        || !Objects.equals(current.getEvidenceFingerprint(), evidenceFingerprint)) {
                    recover(current, snapshot, evidence, evidenceFingerprint, childRequest, actor,
                            "当前健康量已覆盖全部活动预留");
                    recovered++;
                } else unchanged++;
            }
        }
        List<WmsSalesReservationDriftCaseEntity> inactiveCases = openCases.values().stream()
                .filter(row -> !seenReservations.contains(row.getReservationId()))
                .filter(row -> !"PENDING_CLOSE".equals(row.getStatus())).toList();
        Map<Long, WmsSalesStockReservationSnapshot> inactiveObserved = reservationService.snapshotsByPlanIds(
                inactiveCases.stream().map(WmsSalesReservationDriftCaseEntity::getDeliverPlanId)
                        .collect(Collectors.toSet()));
        for (WmsSalesReservationDriftCaseEntity current : inactiveCases) {
            WmsSalesStockReservationSnapshot snapshot = inactiveObserved.get(current.getDeliverPlanId());
            require(snapshot != null && Objects.equals(snapshot.getReservationId(), current.getReservationId()),
                    "非活动销售预留待办无法取得完整关闭证据");
            String evidence = evidence(snapshot);
            recover(current, snapshot, evidence, sha(evidence),
                    childRequest(command.getRequestId(), current.getReservationId()), actor,
                    "销售预留已无活动余额或主账不再活动");
            recovered++;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("refreshed", refreshed);
        result.put("recovered", recovered);
        result.put("unchanged", unchanged);
        result.put("observed", observed.size());
        result.put("replayed", 0);
        audit(null, null, command.getRequestId(), "SCAN_BATCH", null, "SCANNED", fingerprint,
                sha(JSONUtil.toJsonStr(result)), JSONUtil.toJsonStr(result), actor, factory);
        return result;
    }

    @Override
    public Page<WmsSalesReservationDriftCaseEntity> page(SearchPage page, WmsSalesReservationDriftDTO.Query query) {
        String factory = factory();
        String status = query == null ? null : trim(query.getStatus(), 30, "状态");
        require(StrUtil.isBlank(status) || ACTIVE_CASE_STATUSES.contains(status) || "RESOLVED".equals(status), "未知漂移待办状态");
        LambdaQueryWrapper<WmsSalesReservationDriftCaseEntity> wrapper = new LambdaQueryWrapper<WmsSalesReservationDriftCaseEntity>()
                .select(WmsSalesReservationDriftCaseEntity.class, field -> !Set.of(
                        "evidence_snapshot", "resolution_evidence_snapshot").contains(field.getColumn()))
                .eq(WmsSalesReservationDriftCaseEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(status), WmsSalesReservationDriftCaseEntity::getStatus, status)
                .like(query != null && StrUtil.isNotBlank(query.getOrderCode()), WmsSalesReservationDriftCaseEntity::getOrderCode,
                        query == null ? null : trim(query.getOrderCode(), 100, "订单号"))
                .like(query != null && StrUtil.isNotBlank(query.getProductCode()), WmsSalesReservationDriftCaseEntity::getProductCode,
                        query == null ? null : trim(query.getProductCode(), 100, "产品编码"))
                .like(query != null && StrUtil.isNotBlank(query.getCaseNo()), WmsSalesReservationDriftCaseEntity::getCaseNo,
                        query == null ? null : trim(query.getCaseNo(), 100, "待办号"))
                .orderByAsc(WmsSalesReservationDriftCaseEntity::getStatus)
                .orderByDesc(WmsSalesReservationDriftCaseEntity::getLastDetectedTime)
                .orderByDesc(WmsSalesReservationDriftCaseEntity::getId);
        return caseMapper.selectPage(page.toMpPage(), wrapper);
    }

    @Override
    public WmsSalesReservationDriftCaseEntity detail(Long caseId) {
        require(caseId != null, "漂移待办不能为空");
        WmsSalesReservationDriftCaseEntity row = caseMapper.selectById(caseId);
        require(row != null && Objects.equals(factory(), row.getFactoryCode()), "漂移待办不存在或超出当前工厂");
        return row;
    }

    @Override
    public Map<String, Long> overview() {
        String factory = factory();
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of("OPEN", "PENDING_APPROVAL", "REJECTED", "PENDING_CLOSE", "RESOLVED")) {
            result.put(status, caseMapper.selectCount(new LambdaQueryWrapper<WmsSalesReservationDriftCaseEntity>()
                    .eq(WmsSalesReservationDriftCaseEntity::getFactoryCode, factory)
                    .eq(WmsSalesReservationDriftCaseEntity::getStatus, status)));
        }
        result.put("ACTIVE", result.get("OPEN") + result.get("PENDING_APPROVAL")
                + result.get("REJECTED") + result.get("PENDING_CLOSE"));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesReservationDriftCaseEntity submit(Long caseId, WmsSalesReservationDriftDTO.Submit command) {
        MomUser actor = actor();
        String factory = factory();
        require(caseId != null && command != null && requestId(command.getRequestId())
                        && hash(command.getExpectedEvidenceFingerprint()) && text(command.getReason(), 1000),
                "换批提审须提供待办、当前证据指纹、16至64位稳定请求号和1000字内原因");
        String reason = command.getReason().trim();
        String fingerprint = sha("SUBMIT|" + caseId + '|' + command.getExpectedEvidenceFingerprint()
                + '|' + reason + '|' + actor.getUsername());
        caseMapper.lockKey("REQUEST:" + factory + ':' + command.getRequestId());
        WmsSalesReservationDriftOperationEntity replay = operationByRequest(command.getRequestId(), factory);
        if (replay != null) return replayCase(replay, caseId, "SUBMIT", fingerprint);
        WmsSalesReservationDriftCaseEntity row = lockCase(caseId, factory);
        require(Set.of("OPEN", "REJECTED").contains(row.getStatus()), "仅待处理或已驳回待办可重新提审");
        Observation observation = observe(row.getDeliverPlanId());
        require(observation.snapshot().getDriftQuantity().signum() > 0, "当前预留漂移已消失，请先刷新待办");
        require(Objects.equals(command.getExpectedEvidenceFingerprint(), row.getEvidenceFingerprint())
                        && Objects.equals(command.getExpectedEvidenceFingerprint(), observation.fingerprint()),
                "漂移证据已变化，请刷新待办后重新提审");
        String before = row.getStatus();
        row.setStatus("PENDING_APPROVAL").setProposalPolicy(POLICY).setProposalReason(reason)
                .setProposedEvidenceFingerprint(observation.fingerprint()).setSubmittedBy(actor.getUsername())
                .setSubmittedByName(userName(actor)).setSubmittedTime(LocalDateTime.now())
                .setReviewDecision(null).setReviewComment(null).setReviewerCode(null).setReviewerName(null)
                .setReviewTime(null).setLastRequestId(command.getRequestId()).setLastOperationType("SUBMIT");
        require(caseMapper.updateById(row) == 1, "漂移换批提审保存失败");
        audit(row.getId(), row.getReservationId(), command.getRequestId(), "SUBMIT", before, row.getStatus(),
                fingerprint, observation.fingerprint(), reason + "；策略=" + POLICY, actor, factory);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesReservationDriftCaseEntity review(Long caseId, WmsSalesReservationDriftDTO.Review command) {
        MomUser actor = actor();
        String factory = factory();
        require(caseId != null && command != null && requestId(command.getRequestId())
                        && Set.of("APPROVE", "REJECT", "CLOSE").contains(command.getDecision())
                        && hash(command.getExpectedEvidenceFingerprint()) && text(command.getComment(), 1000),
                "漂移复核须提供决定、当前证据指纹、16至64位稳定请求号和1000字内意见");
        String comment = command.getComment().trim();
        String type = "REVIEW_" + command.getDecision();
        String fingerprint = sha(type + '|' + caseId + '|' + command.getExpectedEvidenceFingerprint()
                + '|' + comment + '|' + actor.getUsername());
        caseMapper.lockKey("REQUEST:" + factory + ':' + command.getRequestId());
        WmsSalesReservationDriftOperationEntity replay = operationByRequest(command.getRequestId(), factory);
        if (replay != null) return replayCase(replay, caseId, type, fingerprint);
        WmsSalesReservationDriftCaseEntity row = lockCase(caseId, factory);
        require(!Objects.equals(row.getSubmittedBy(), actor.getUsername()), "换批提审人与审批人必须分离");
        String before = row.getStatus();
        Observation observation = observe(row.getDeliverPlanId());
        require(Objects.equals(command.getExpectedEvidenceFingerprint(), row.getEvidenceFingerprint())
                        && Objects.equals(command.getExpectedEvidenceFingerprint(), observation.fingerprint()),
                "漂移证据已变化，请先刷新待办，禁止审批旧证据");
        if ("CLOSE".equals(command.getDecision())) {
            require("PENDING_CLOSE".equals(before), "仅漂移已恢复待关闭的待办可以复核关闭");
            require(observation.snapshot().getDriftQuantity().signum() == 0, "漂移已经复发，不能关闭待办");
            resolve(row, "CLOSE", comment, actor, observation, null);
        } else {
            require("PENDING_APPROVAL".equals(before), "仅待审批换批方案可审批或驳回");
            require(Objects.equals(row.getProposedEvidenceFingerprint(), observation.fingerprint()),
                    "提审证据与当前证据不一致");
            if ("REJECT".equals(command.getDecision())) {
                row.setStatus("REJECTED").setReviewDecision("REJECT").setReviewComment(comment)
                        .setReviewerCode(actor.getUsername()).setReviewerName(userName(actor))
                        .setReviewTime(LocalDateTime.now()).setLastRequestId(command.getRequestId())
                        .setLastOperationType(type);
            } else {
                require(observation.snapshot().getDriftQuantity().signum() > 0, "当前预留漂移已消失，不能批准换批");
                WmsSalesStockReservationCommand reallocate = new WmsSalesStockReservationCommand();
                reallocate.setDeliverPlanId(row.getDeliverPlanId());
                reallocate.setRequestId(command.getRequestId());
                reallocate.setReason(StrUtil.sub("漂移待办" + row.getCaseNo() + "审批通过：" + row.getProposalReason()
                        + "；复核意见：" + comment, 0, 500));
                reservationService.reallocateDrifted(reallocate);
                Observation resolved = observe(row.getDeliverPlanId());
                require(resolved.snapshot().getDriftQuantity().signum() == 0,
                        "换批完成后仍存在漂移，整笔审批回滚");
                resolve(row, "APPROVE", comment, actor, resolved, command.getRequestId());
            }
        }
        row.setLastRequestId(command.getRequestId()).setLastOperationType(type);
        require(caseMapper.updateById(row) == 1, "漂移待办复核结果保存失败");
        audit(row.getId(), row.getReservationId(), command.getRequestId(), type, before, row.getStatus(),
                fingerprint, observation.fingerprint(), comment, actor, factory);
        return row;
    }

    @Override
    public List<WmsSalesReservationDriftOperationEntity> operations(Long caseId) {
        require(caseId != null, "漂移待办不能为空");
        String factory = factory();
        WmsSalesReservationDriftCaseEntity row = caseMapper.selectById(caseId);
        require(row != null && Objects.equals(factory, row.getFactoryCode()), "漂移待办不存在或超出当前工厂");
        return operationMapper.selectList(new LambdaQueryWrapper<WmsSalesReservationDriftOperationEntity>()
                .eq(WmsSalesReservationDriftOperationEntity::getCaseId, caseId)
                .orderByAsc(WmsSalesReservationDriftOperationEntity::getOperationTime)
                .orderByAsc(WmsSalesReservationDriftOperationEntity::getId));
    }

    private void createCase(WmsSalesStockReservationEntity header, WmsSalesStockReservationSnapshot snapshot,
                            String evidence, String evidenceFingerprint, String requestId, MomUser actor) {
        LocalDateTime now = LocalDateTime.now();
        WmsSalesReservationDriftCaseEntity row = new WmsSalesReservationDriftCaseEntity()
                .setCaseNo("SRD-" + header.getId() + '-' + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")))
                .setReservationId(header.getId()).setReservationNo(header.getReservationNo())
                .setDeliverPlanId(header.getDeliverPlanId()).setDeliverPlanCode(header.getDeliverPlanCode())
                .setOrderCode(header.getOrderCode()).setOrderDetailId(header.getOrderDetailId())
                .setCustomerCode(header.getCustomerCode()).setProductCode(header.getProductCode())
                .setUnitCode(header.getUnitCode()).setActiveQuantity(snapshot.getActiveQuantity())
                .setHealthyQuantity(snapshot.getHealthyActiveQuantity()).setDriftQuantity(snapshot.getDriftQuantity())
                .setEvidenceFingerprint(evidenceFingerprint).setEvidenceSnapshot(evidence)
                .setDetectionCount(1).setFirstDetectedTime(now).setLastDetectedTime(now).setStatus("OPEN")
                .setLastRequestId(requestId).setLastOperationType("DETECT").setFactoryCode(header.getFactoryCode());
        require(caseMapper.insert(row) == 1, "销售预留漂移待办创建失败");
        audit(row.getId(), row.getReservationId(), requestId, "DETECT", null, "OPEN",
                sha("DETECT|" + row.getId() + '|' + evidenceFingerprint), evidenceFingerprint,
                "发现销售预留漂移" + snapshot.getDriftQuantity(), actor, header.getFactoryCode());
    }

    private void refreshDrift(WmsSalesReservationDriftCaseEntity row, WmsSalesStockReservationSnapshot snapshot,
                              String evidence, String evidenceFingerprint, String requestId, MomUser actor) {
        String before = row.getStatus();
        String type = "PENDING_CLOSE".equals(before) ? "REOPEN" :
                "PENDING_APPROVAL".equals(before) ? "INVALIDATE" : "REFRESH";
        row.setActiveQuantity(snapshot.getActiveQuantity()).setHealthyQuantity(snapshot.getHealthyActiveQuantity())
                .setDriftQuantity(snapshot.getDriftQuantity()).setEvidenceFingerprint(evidenceFingerprint)
                .setEvidenceSnapshot(evidence).setDetectionCount(row.getDetectionCount() + 1)
                .setLastDetectedTime(LocalDateTime.now()).setStatus("OPEN")
                .setProposalPolicy(null).setProposalReason(null).setProposedEvidenceFingerprint(null)
                .setSubmittedBy(null).setSubmittedByName(null).setSubmittedTime(null)
                .setReviewDecision(null).setReviewComment(null).setReviewerCode(null).setReviewerName(null)
                .setReviewTime(null).setLastRequestId(requestId).setLastOperationType(type);
        require(caseMapper.updateById(row) == 1, "销售预留漂移待办刷新失败");
        audit(row.getId(), row.getReservationId(), requestId, type, before, "OPEN",
                sha(type + '|' + row.getId() + '|' + evidenceFingerprint), evidenceFingerprint,
                "漂移证据变化，原提审自动失效", actor, row.getFactoryCode());
    }

    private void recover(WmsSalesReservationDriftCaseEntity row, WmsSalesStockReservationSnapshot snapshot,
                         String evidence, String evidenceFingerprint, String requestId, MomUser actor, String reason) {
        String before = row.getStatus();
        row.setActiveQuantity(snapshot == null ? BigDecimal.ZERO : snapshot.getActiveQuantity())
                .setHealthyQuantity(snapshot == null ? BigDecimal.ZERO : snapshot.getHealthyActiveQuantity())
                .setDriftQuantity(BigDecimal.ZERO).setEvidenceFingerprint(evidenceFingerprint)
                .setEvidenceSnapshot(evidence).setStatus("PENDING_CLOSE")
                .setProposalPolicy(null).setProposalReason(null).setProposedEvidenceFingerprint(null)
                .setSubmittedBy(null).setSubmittedByName(null).setSubmittedTime(null)
                .setReviewDecision(null).setReviewComment(null).setReviewerCode(null).setReviewerName(null)
                .setReviewTime(null)
                .setLastRequestId(requestId).setLastOperationType("RECOVER");
        require(caseMapper.updateById(row) == 1, "销售预留漂移恢复待关闭状态保存失败");
        audit(row.getId(), row.getReservationId(), requestId, "RECOVER", before, "PENDING_CLOSE",
                sha("RECOVER|" + row.getId() + '|' + evidenceFingerprint), evidenceFingerprint,
                reason, actor, row.getFactoryCode());
    }

    private void resolve(WmsSalesReservationDriftCaseEntity row, String decision, String comment, MomUser actor,
                         Observation resolved, String reallocationRequestId) {
        row.setStatus("RESOLVED").setActiveQuantity(resolved.snapshot().getActiveQuantity())
                .setHealthyQuantity(resolved.snapshot().getHealthyActiveQuantity()).setDriftQuantity(BigDecimal.ZERO)
                .setReviewDecision(decision).setReviewComment(comment).setReviewerCode(actor.getUsername())
                .setReviewerName(userName(actor)).setReviewTime(LocalDateTime.now())
                .setReallocationRequestId(reallocationRequestId)
                .setResolutionEvidenceFingerprint(resolved.fingerprint()).setResolutionEvidenceSnapshot(resolved.evidence())
                .setResolvedTime(LocalDateTime.now()).setLastRequestId(reallocationRequestId == null
                        ? row.getLastRequestId() : reallocationRequestId)
                .setLastOperationType("REVIEW_" + decision);
    }

    private Observation observe(Long planId) {
        Map<Long, WmsSalesStockReservationSnapshot> snapshots = reservationService.snapshotsByPlanIds(Set.of(planId));
        WmsSalesStockReservationSnapshot snapshot = snapshots.get(planId);
        require(snapshot != null && snapshot.getReservationId() != null, "销售预留主账或健康快照不存在");
        String evidence = evidence(snapshot);
        return new Observation(snapshot, evidence, sha(evidence));
    }

    private String evidence(WmsSalesStockReservationSnapshot snapshot) {
        String value = JSONUtil.toJsonStr(snapshot);
        require(value.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024,
                "销售预留漂移证据超过1MiB，拒绝截断审计事实");
        return value;
    }

    private WmsSalesReservationDriftCaseEntity lockCase(Long caseId, String factory) {
        WmsSalesReservationDriftCaseEntity row = caseMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesReservationDriftCaseEntity>()
                        .eq(WmsSalesReservationDriftCaseEntity::getId, caseId)
                        .eq(WmsSalesReservationDriftCaseEntity::getFactoryCode, factory).last("FOR UPDATE"));
        require(row != null, "销售预留漂移待办不存在或超出当前工厂");
        return row;
    }

    private WmsSalesReservationDriftOperationEntity operationByRequest(String requestId, String factory) {
        return operationMapper.selectOne(new LambdaQueryWrapper<WmsSalesReservationDriftOperationEntity>()
                .eq(WmsSalesReservationDriftOperationEntity::getFactoryCode, factory)
                .eq(WmsSalesReservationDriftOperationEntity::getRequestId, requestId).last("LIMIT 1"));
    }

    private WmsSalesReservationDriftCaseEntity replayCase(WmsSalesReservationDriftOperationEntity operation,
                                                          Long caseId, String type, String fingerprint) {
        require(Objects.equals(operation.getCaseId(), caseId) && Objects.equals(operation.getOperationType(), type)
                && Objects.equals(operation.getRequestFingerprint(), fingerprint), "稳定请求号已用于其他漂移待办或载荷");
        WmsSalesReservationDriftCaseEntity row = caseMapper.selectById(caseId);
        require(row != null && Objects.equals(row.getFactoryCode(), factory()), "漂移待办重放结果不存在");
        return row;
    }

    private void audit(Long caseId, Long reservationId, String requestId, String type, String before, String after,
                       String requestFingerprint, String evidenceFingerprint, String content,
                       MomUser actor, String factory) {
        WmsSalesReservationDriftOperationEntity op = new WmsSalesReservationDriftOperationEntity()
                .setCaseId(caseId).setReservationId(reservationId).setRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setOperationType(type).setBeforeStatus(before)
                .setAfterStatus(after).setEvidenceFingerprint(evidenceFingerprint).setContent(content)
                .setOperatorCode(actor.getUsername()).setOperatorName(userName(actor))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(factory);
        require(operationMapper.insert(op) == 1, "销售预留漂移不可变操作履历保存失败");
    }

    private Map<String, Integer> scanResult(String content, boolean replayed) {
        try {
            var json = JSONUtil.parseObj(content);
            Map<String, Integer> result = new LinkedHashMap<>();
            for (String key : List.of("created", "refreshed", "recovered", "unchanged", "observed"))
                result.put(key, json.getInt(key, 0));
            result.put("replayed", replayed ? 1 : 0);
            return result;
        } catch (Exception ex) {
            throw new CheckedException("原漂移扫描回执无法解析");
        }
    }

    private String childRequest(String scanRequest, Long reservationId) {
        return "SDC-" + sha(scanRequest + '|' + reservationId).substring(0, 56);
    }

    private BigDecimal active(WmsSalesStockReservationEntity row) {
        return quantity(row.getReservedNum()).subtract(quantity(row.getConsumedNum()))
                .subtract(quantity(row.getReleasedNum())).max(BigDecimal.ZERO);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String factory() {
        String value = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(value), "请先选择工厂");
        return value;
    }

    private MomUser actor() {
        MomUser value = SecurityUtils.getUser();
        require(value != null && value.getId() != null && StrUtil.isNotBlank(value.getUsername()), "漂移待办必须由实名登录用户操作");
        return value;
    }

    private String userName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private boolean requestId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{16,64}");
    }

    private boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private boolean text(String value, int max) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= max;
    }

    private String trim(String value, int max, String label) {
        if (StrUtil.isBlank(value)) return null;
        String result = value.trim();
        require(result.length() <= max, label + "不能超过" + max + "字");
        return result;
    }

    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("漂移待办指纹生成失败");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    private record Observation(WmsSalesStockReservationSnapshot snapshot, String evidence, String fingerprint) {
    }
}
