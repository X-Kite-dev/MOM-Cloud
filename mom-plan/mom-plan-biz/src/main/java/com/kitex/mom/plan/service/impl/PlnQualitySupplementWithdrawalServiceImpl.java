package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanWithdrawalDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.dto.PlanMrpSupplyWithdrawalDTO;
import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnQualitySupplementWithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PlnQualitySupplementWithdrawalServiceImpl implements PlnQualitySupplementWithdrawalService {
    private static final Set<String> LIVE = Set.of("SUBMITTED", "APPROVED", "EXECUTING", "PARTIAL", "FAILED");

    private final PlnQualitySupplementWithdrawalMapper withdrawalMapper;
    private final PlnQualitySupplementWithdrawalOperationMapper operationMapper;
    private final PlnQualitySupplementDemandMapper supplementMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingBatchMapper batchMapper;
    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;
    private final PlanMatMrpService mrpService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualitySupplementWithdrawalEntity submit(
            PlanQualityLocalReplanDTO.SupplementWithdrawalSubmitCommand command) {
        require(command != null && command.getSupplementId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "补产撤回申请必须提供补产需求、请求号和原因");
        String requestId = trimmed(command.getRequestId(), 64, "撤回申请请求号");
        String reason = trimmed(command.getReason(), 500, "撤回原因");
        withdrawalMapper.lockKey("SUBMIT:" + requestId);
        PlnQualitySupplementWithdrawalEntity replay = withdrawalMapper.selectOne(
                new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                        .eq(PlnQualitySupplementWithdrawalEntity::getSubmitRequestId, requestId));
        String requestFingerprint = hash(command.getSupplementId() + "|" + reason);
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint),
                    "补产撤回申请幂等号已被不同请求使用");
            return replay;
        }
        PlnQualitySupplementDemandEntity supplement = requireSupplement(command.getSupplementId(), true);
        requireFactory(supplement.getFactoryCode());
        require("MRP_GENERATED".equals(supplement.getStatus()), "仅已生成MRP且尚未完成/撤回的补产需求允许申请撤回");
        withdrawalMapper.lockKey("SUPPLEMENT:" + supplement.getId());
        List<PlnQualitySupplementWithdrawalEntity> active = withdrawalMapper.selectList(
                new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                        .eq(PlnQualitySupplementWithdrawalEntity::getSupplementId, supplement.getId())
                        .in(PlnQualitySupplementWithdrawalEntity::getStatus, LIVE).last("FOR UPDATE"));
        require(active.isEmpty(), "该补产需求已有待处理或可重试的撤回单，请勿重复申请");
        WithdrawalEvidence evidence = captureEvidence(supplement, true);
        MomUser user = currentUser();
        PlnQualitySupplementWithdrawalEntity entity = new PlnQualitySupplementWithdrawalEntity()
                .setWithdrawalNo("QSW-" + IdUtil.getSnowflakeNextIdStr()).setSubmitRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setSupplementId(supplement.getId())
                .setSupplementNo(supplement.getSupplementNo()).setProductDemandId(supplement.getProductDemandId())
                .setProductDemandNo(supplement.getProductDemandNo()).setSupplementIssueId(supplement.getSupplementIssueId())
                .setSupplementIssueNo(supplement.getSupplementIssueNo())
                .setEvidenceFingerprint(evidence.combinedFingerprint)
                .setLocalEvidenceFingerprint(evidence.localFingerprint)
                .setSupplyEvidenceFingerprint(evidence.supplyFingerprint)
                .setSupplyEvidenceSnapshot(json(evidence.supplyEvidence))
                .setMesEvidenceFingerprint(evidence.mesFingerprint).setEvidenceSnapshot(evidence.snapshot)
                .setReason(reason).setStatus("SUBMITTED").setApplicantCode(userCode(user))
                .setApplicantName(userName(user)).setSubmittedTime(LocalDateTime.now()).setRetryCount(0)
                .setSupplyPhaseStatus("PENDING").setMesPhaseStatus("PENDING").setWmsPhaseStatus("PENDING")
                .setApsPhaseStatus("PENDING").setPlanPhaseStatus("PENDING")
                .setFactoryCode(supplement.getFactoryCode());
        return persistSubmitted(entity, requestId, requestFingerprint, user);
    }

    private PlnQualitySupplementWithdrawalEntity persistSubmitted(
            PlnQualitySupplementWithdrawalEntity entity, String requestId, String fingerprint, MomUser user) {
        require(withdrawalMapper.insert(entity) == 1, "补产撤回申请保存失败");
        audit(entity, requestId, fingerprint, "SUBMIT", null, "SUBMITTED", entity.getReason(), user);
        return entity;
    }

    @Override
    public PlnQualitySupplementWithdrawalEntity review(
            PlanQualityLocalReplanDTO.SupplementWithdrawalReviewCommand command) {
        require(command != null && command.getWithdrawalId() != null && StrUtil.isNotBlank(command.getRequestId())
                && command.getApproved() != null && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
                && StrUtil.isNotBlank(command.getReason()), "补产撤回复核参数不完整");
        String requestId = trimmed(command.getRequestId(), 64, "撤回复核请求号");
        String reason = trimmed(command.getReason(), 500, "复核说明");
        String fingerprint = hash(command.getWithdrawalId() + "|" + command.getApproved() + '|'
                + command.getExpectedEvidenceFingerprint().trim() + '|' + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PlnQualitySupplementWithdrawalEntity decision = tx.execute(status -> {
            withdrawalMapper.lockKey("REVIEW:" + requestId);
            PlnQualitySupplementWithdrawalOperationEntity old = operationMapper.selectOne(
                    new LambdaQueryWrapper<PlnQualitySupplementWithdrawalOperationEntity>()
                            .eq(PlnQualitySupplementWithdrawalOperationEntity::getRequestId, requestId));
            if (old != null) {
                require(Objects.equals(old.getWithdrawalId(), command.getWithdrawalId())
                                && Objects.equals(old.getRequestFingerprint(), fingerprint),
                        "撤回复核幂等号已被不同请求使用");
                return requireWithdrawal(command.getWithdrawalId(), false);
            }
            PlnQualitySupplementWithdrawalEntity entity = requireWithdrawal(command.getWithdrawalId(), true);
            requireFactory(entity.getFactoryCode());
            require("SUBMITTED".equals(entity.getStatus()), "仅待复核撤回申请允许审批");
            require(Objects.equals(entity.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                    "撤回申请证据指纹不一致，请刷新后重试");
            MomUser user = currentUser();
            require(!Objects.equals(entity.getApplicantCode(), userCode(user)), "撤回申请人与复核人必须分离");
            String before = entity.getStatus();
            if (!Boolean.TRUE.equals(command.getApproved())) {
                entity.setStatus("REJECTED").setApproved(false);
            } else {
                PlnQualitySupplementDemandEntity supplement = requireSupplement(entity.getSupplementId(), true);
                WithdrawalEvidence current = captureEvidence(supplement, true);
                if (!Objects.equals(entity.getEvidenceFingerprint(), current.combinedFingerprint)) {
                    entity.setStatus("STALE").setApproved(false).setLastError("下游事实已变化，请重新申请撤回");
                } else {
                    entity.setStatus("APPROVED").setApproved(true)
                            .setExecutionRequestId("QSW-EXEC-" + entity.getId());
                }
            }
            entity.setReviewRequestId(requestId).setReviewReason(reason).setReviewerCode(userCode(user))
                    .setReviewerName(userName(user)).setReviewedTime(LocalDateTime.now());
            require(withdrawalMapper.updateById(entity) == 1, "补产撤回复核状态保存失败");
            audit(entity, requestId, fingerprint, Boolean.TRUE.equals(command.getApproved()) ? "REVIEW_APPROVE" : "REVIEW_REJECT",
                    before, entity.getStatus(), reason, user);
            return entity;
        });
        if (decision != null && "APPROVED".equals(decision.getStatus())) execute(decision.getId());
        return withdrawalMapper.selectById(command.getWithdrawalId());
    }

    @Override
    public PlnQualitySupplementWithdrawalEntity retry(
            PlanQualityLocalReplanDTO.SupplementWithdrawalRetryCommand command) {
        require(command != null && command.getWithdrawalId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "撤回重试必须提供撤回单、请求号和原因");
        String requestId = trimmed(command.getRequestId(), 64, "撤回重试请求号");
        String reason = trimmed(command.getReason(), 500, "重试原因");
        String fingerprint = hash(command.getWithdrawalId() + "|" + reason);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PlnQualitySupplementWithdrawalEntity entity = tx.execute(status -> {
            withdrawalMapper.lockKey("RETRY:" + requestId);
            PlnQualitySupplementWithdrawalOperationEntity replay = operationMapper.selectOne(
                    new LambdaQueryWrapper<PlnQualitySupplementWithdrawalOperationEntity>()
                            .eq(PlnQualitySupplementWithdrawalOperationEntity::getRequestId, requestId));
            if (replay != null) {
                require(Objects.equals(replay.getWithdrawalId(), command.getWithdrawalId())
                                && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                        "撤回重试幂等号已被不同请求使用");
                return requireWithdrawal(command.getWithdrawalId(), false);
            }
            PlnQualitySupplementWithdrawalEntity locked = requireWithdrawal(command.getWithdrawalId(), true);
            requireFactory(locked.getFactoryCode());
            require(Set.of("FAILED", "PARTIAL").contains(locked.getStatus()), "仅失败或部分完成的撤回单允许重试");
            String before = locked.getStatus();
            locked.setStatus("APPROVED").setLastError(null)
                    .setRetryCount(Optional.ofNullable(locked.getRetryCount()).orElse(0) + 1);
            require(withdrawalMapper.updateById(locked) == 1, "撤回重试状态保存失败");
            MomUser user = currentUser();
            audit(locked, requestId, fingerprint, "RETRY", before, "APPROVED", reason, user);
            return locked;
        });
        if (entity != null && "APPROVED".equals(entity.getStatus())) execute(entity.getId());
        return withdrawalMapper.selectById(command.getWithdrawalId());
    }

    private void execute(Long withdrawalId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> executeInTransaction(withdrawalId));
        } catch (RuntimeException ex) {
            TransactionTemplate failureTx = new TransactionTemplate(transactionManager);
            failureTx.executeWithoutResult(status -> {
                PlnQualitySupplementWithdrawalEntity entity = requireWithdrawal(withdrawalId, true);
                if ("SUCCEEDED".equals(entity.getStatus())) return;
                entity.setStatus(anySucceeded(entity) ? "PARTIAL" : "FAILED")
                        .setLastError(limit(Objects.toString(ex.getMessage(), "撤回执行失败"), 1000));
                require(withdrawalMapper.updateById(entity) == 1, "补产撤回失败状态保存失败");
            });
        }
    }

    private void executeInTransaction(Long withdrawalId) {
        PlnQualitySupplementWithdrawalEntity entity = requireWithdrawal(withdrawalId, true);
        if ("SUCCEEDED".equals(entity.getStatus())) return;
        require("APPROVED".equals(entity.getStatus()), "撤回单尚未审批或不允许执行");
        PlnQualitySupplementDemandEntity supplement = requireSupplement(entity.getSupplementId(), true);
        PlnIssueEntity issue = requireIssue(entity.getSupplementIssueId(), true);
        WithdrawalEvidence current = captureEvidence(supplement, false, entity.getSupplyEvidenceSnapshot());
        require(Objects.equals(entity.getLocalEvidenceFingerprint(), current.localFingerprint),
                "撤回执行前下游事实已变化，请重新申请撤回");
        require(Objects.equals(entity.getSupplyEvidenceFingerprint(), current.supplyFingerprint),
                "撤回执行前下层供应事实已变化，请重新申请撤回");
        entity.setStatus("EXECUTING").setLastError(null);
        require(withdrawalMapper.updateById(entity) == 1, "撤回执行状态保存失败");

        LocalDateTime now = LocalDateTime.now();
        int supplyCount = 0;
        for (PlanMrpSupplyWithdrawalDTO.Evidence evidence : current.supplyEvidence) {
            if (!"ELIGIBLE".equals(evidence.getStatus())) continue;
            PlanMrpSupplyWithdrawalDTO.Command command = new PlanMrpSupplyWithdrawalDTO.Command()
                    .setMrpId(evidence.getMrpId()).setRequestId(limit("QSW-" + entity.getId() + "-SUP-" + evidence.getMrpId(), 64))
                    .setExpectedEvidenceFingerprint(evidence.getEvidenceFingerprint())
                    .setExpectedDownstreamEvidenceFingerprint(evidence.getDownstreamEvidenceFingerprint())
                    .setReason(entity.getReason());
            PlanMrpSupplyWithdrawalDTO.Receipt receipt = mrpService.withdrawGeneratedSupply(command);
            require(Set.of("SUCCEEDED", "ALREADY_WITHDRAWN").contains(receipt.getStatus()),
                    "MRP[" + evidence.getMrpId() + "]下层供应撤回失败：" + receipt.getMessage());
            supplyCount++;
        }
        entity.setSupplyPhaseStatus(supplyCount == 0 ? "NOT_APPLICABLE" : "SUCCEEDED")
                .setSupplyPhaseMessage(supplyCount == 0 ? "MRP未生成下层净供应单据"
                        : "已关闭" + supplyCount + "条未进入执行的精确下层供应单据（含PMS未下单计划）")
                .setSupplyHandledTime(now);
        require(withdrawalMapper.updateById(entity) == 1, "补产撤回下层供应阶段回执保存失败");

        if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            MesPlanWithdrawalDTO.Command command = mesCommand(entity, supplement, false, entity.getMesEvidenceFingerprint());
            R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(command);
            require(response != null && response.isOk() && response.getData() != null
                            && "SUCCEEDED".equals(response.getData().getStatus()),
                    "MES开工前撤回失败：" + (response == null ? "无响应" : response.getMsg()));
            entity.setMesPhaseStatus("SUCCEEDED").setMesPhaseMessage(response.getData().getMessage())
                    .setMesHandledTime(now);
        } else {
            entity.setMesPhaseStatus("NOT_APPLICABLE").setMesPhaseMessage("补产计划尚未下达MES")
                    .setMesHandledTime(now);
        }
        require(withdrawalMapper.updateById(entity) == 1, "补产撤回MES阶段回执保存失败");

        List<PlanMatMrpEntity> mrpRows = mrpRows(supplement.getProductDemandNo(), true);
        for (PlanMatMrpEntity row : mrpRows) {
            String requestId = "QSW-" + entity.getId() + '-' + row.getId();
            require(Boolean.TRUE.equals(mrpService.releaseForWithdrawal(row.getId(), requestId, entity.getReason())),
                    "MRP[" + row.getId() + "]预留释放失败");
        }
        entity.setWmsPhaseStatus("SUCCEEDED").setWmsPhaseMessage("已逐MRP释放主料/替代料预留并同步Pegging")
                .setWmsHandledTime(LocalDateTime.now());
        require(withdrawalMapper.updateById(entity) == 1, "补产撤回WMS阶段回执保存失败");

        unlockAps(issue);
        entity.setApsPhaseStatus("SUCCEEDED").setApsPhaseMessage("已解锁该补产计划的工序、任务明细和独占执行批次")
                .setApsHandledTime(LocalDateTime.now());
        require(withdrawalMapper.updateById(entity) == 1, "补产撤回APS阶段回执保存失败");

        issue.setStatus(IssueStatusEnum.NOT_ISSUED.getCode());
        issue.setLockStatus(false);
        issue.setLockTaskId(null);
        issue.setLockTime(null);
        issue.setScheduledDate(null);
        issue.setScheduledEndDate(null);
        issue.setRemark(limit(StrUtil.blankToDefault(issue.getRemark(), "") + "；补产撤回单" + entity.getWithdrawalNo(), 500));
        require(issueMapper.updateById(issue) == 1, "补产生产计划关闭失败");
        PlnProductDemandEntity demand = demandMapper.selectOne(new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, supplement.getProductDemandId()).last("FOR UPDATE"));
        require(demand != null && Objects.equals(demand.getDemandNo(), supplement.getProductDemandNo()),
                "补产产品需求不存在或来源不一致");
        demand.setPlanState("3");
        require(demandMapper.updateById(demand) == 1, "补产产品需求关闭失败");
        supplement.setStatus("WITHDRAWN");
        require(supplementMapper.updateById(supplement) == 1, "补产需求撤回终态保存失败");
        entity.setPlanPhaseStatus("SUCCEEDED").setPlanPhaseMessage("产品需求、MRP、补产计划及补产申请已关闭")
                .setPlanHandledTime(LocalDateTime.now()).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now())
                .setResultSnapshot(json(Map.of("supplementStatus", "WITHDRAWN", "demandState", "3",
                        "issueStatus", IssueStatusEnum.NOT_ISSUED.getCode(), "mrpCount", mrpRows.size())));
        require(withdrawalMapper.updateById(entity) == 1, "补产撤回终态回执保存失败");
    }

    private WithdrawalEvidence captureEvidence(PlnQualitySupplementDemandEntity supplement, boolean previewMes) {
        return captureEvidence(supplement, previewMes, null);
    }

    private WithdrawalEvidence captureEvidence(PlnQualitySupplementDemandEntity supplement, boolean previewMes,
                                               String frozenSupplySnapshot) {
        require("MRP_GENERATED".equals(supplement.getStatus()), "补产需求状态已变化，不能撤回");
        PlnProductDemandEntity demand = demandMapper.selectById(supplement.getProductDemandId());
        require(demand != null && Objects.equals(demand.getDemandNo(), supplement.getProductDemandNo())
                        && "2".equals(demand.getPlanState()) && "QUALITY_SUPPLEMENT".equals(demand.getSourceType())
                        && Objects.equals(demand.getSourceId(), supplement.getId()),
                "补产产品需求/MRP来源关系不完整，禁止撤回");
        PlnIssueEntity issue = requireIssue(supplement.getSupplementIssueId(), false);
        require(Objects.equals(issue.getIssueNo(), supplement.getSupplementIssueNo())
                        && "QUALITY_SUPPLEMENT".equals(issue.getPlanType())
                        && Set.of(IssueStatusEnum.UNISSUED.getCode(), IssueStatusEnum.ISSUED.getCode()).contains(issue.getStatus())
                        && optional(issue.getFinishNum()).signum() == 0,
                "补产计划已生产、完成、关闭或来源不一致，不能直接撤回");
        List<PlanMatMrpEntity> mrpRows = mrpRows(supplement.getProductDemandNo(), false);
        require(!mrpRows.isEmpty(), "补产需求没有MRP明细，禁止伪撤回");
        Map<Long, PlanMrpSupplyWithdrawalDTO.Evidence> frozenSupply = new HashMap<>();
        if (frozenSupplySnapshot != null) {
            for (PlanMrpSupplyWithdrawalDTO.Evidence row : JSONUtil.parseArray(frozenSupplySnapshot)
                    .toList(PlanMrpSupplyWithdrawalDTO.Evidence.class)) {
                require(row.getMrpId() != null && frozenSupply.putIfAbsent(row.getMrpId(), row) == null,
                        "冻结的MRP下层供应清单包含重复或空来源");
            }
            require(frozenSupply.size() == mrpRows.size(), "冻结的MRP下层供应清单与当前MRP数量不一致");
        }
        List<PlanMrpSupplyWithdrawalDTO.Evidence> supplyEvidence = new ArrayList<>();
        for (PlanMatMrpEntity row : mrpRows) {
            require(row.getPurchaseOrderId() == null && StrUtil.isBlank(row.getPurchaseOrderNo()),
                    "MRP物料[" + row.getMatCode() + "]已进入采购审批/订单，须先完成PMS受控撤回");
            if (frozenSupplySnapshot == null) {
                supplyEvidence.add(mrpService.previewGeneratedSupplyWithdrawal(row.getId()));
            } else {
                PlanMrpSupplyWithdrawalDTO.Evidence frozen = frozenSupply.get(row.getId());
                require(frozen != null, "MRP[" + row.getId() + "]未在冻结供应清单中");
                PlanMrpSupplyWithdrawalDTO.Evidence current = mrpService.captureGeneratedSupplyWithdrawalForExecution(
                        row.getId(), frozen.getDownstreamEvidenceFingerprint());
                require(Objects.equals(current.getEvidenceFingerprint(), frozen.getEvidenceFingerprint()),
                        "MRP[" + row.getId() + "]下层供应本地事实已变化");
                supplyEvidence.add(current);
            }
        }
        List<PlnSchedulingOperationEntity> operations = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .eq(issue.getLockTaskId() != null, PlnSchedulingOperationEntity::getTaskId, issue.getLockTaskId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)
                        .orderByAsc(PlnSchedulingOperationEntity::getId));
        if (Boolean.TRUE.equals(issue.getLockStatus())) {
            require(issue.getLockTaskId() != null && !operations.isEmpty()
                            && operations.stream().allMatch(item -> Boolean.TRUE.equals(item.getLockStatus())),
                    "补产计划APS锁定事实不完整，禁止撤回");
        }
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getId));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            long count = batchAllocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                    .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId()));
            require(count == 1, "APS批次[" + allocation.getBatchNo() + "]包含其他计划，须先拆批/重排后撤回");
        }
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("supplement", List.of(supplement.getId(), supplement.getStatus(), supplement.getRequestQuantity(),
                supplement.getProductDemandId(), supplement.getSupplementIssueId()));
        local.put("demand", List.of(demand.getId(), demand.getDemandNo(), demand.getPlanState(), demand.getNeedNum()));
        local.put("issue", Arrays.asList(issue.getId(), issue.getIssueNo(), issue.getStatus(), issue.getLockStatus(),
                issue.getLockTaskId(), issue.getFinishNum()));
        local.put("mrp", mrpRows.stream().map(row -> Arrays.asList(row.getId(), row.getMatCode(), row.getNum(), row.getStatus(),
                row.getMainMaterialReservationNo(), row.getMainMaterialReservationStatus(), row.getMaterialReservationNo(),
                row.getMaterialReservationStatus(), row.getInventoryPeggingStatus(), row.getSupplySourceType(),
                row.getSupplySourceId(), row.getSupplySourceNo(), row.getSupplySourceQuantity(), row.getSupplySourceStatus())).toList());
        local.put("operations", operations.stream().map(row -> Arrays.asList(row.getId(), row.getTaskId(), row.getProcessCode(),
                row.getScheduledStartTime(), row.getScheduledEndTime(), row.getLockStatus(), row.getSchedulingBatchId())).toList());
        local.put("allocations", allocations.stream().map(row -> Arrays.asList(row.getId(), row.getBatchId(), row.getBatchNo(),
                row.getIssueId(), row.getAllocatedQuantity())).toList());
        String localSnapshot = json(local);
        String localFingerprint = hash(localSnapshot);
        String supplyFingerprint = hash(supplyEvidence.stream()
                .map(PlanMrpSupplyWithdrawalDTO.Evidence::getEvidenceFingerprint).collect(Collectors.joining("|")));
        String mesFingerprint = hash("MES_NOT_CREATED");
        Object mesSnapshot = Map.of("status", "NOT_APPLICABLE");
        if (previewMes && IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            MesPlanWithdrawalDTO.Command preview = mesCommand(null, supplement, true, null);
            R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(preview);
            require(response != null && response.isOk() && response.getData() != null
                            && "ELIGIBLE".equals(response.getData().getStatus())
                            && StrUtil.isNotBlank(response.getData().getEvidenceFingerprint()),
                    "MES撤回资格校验失败：" + (response == null ? "无响应" : response.getMsg()));
            mesFingerprint = response.getData().getEvidenceFingerprint();
            mesSnapshot = response.getData();
        } else if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            mesFingerprint = hash("MES_DEFERRED_TO_IDEMPOTENT_EXECUTION");
            mesSnapshot = Map.of("status", "DEFERRED");
        }
        String combined = hash(localFingerprint + '|' + supplyFingerprint + '|' + mesFingerprint);
        return new WithdrawalEvidence(localFingerprint, supplyFingerprint, mesFingerprint, combined, supplyEvidence,
                json(Map.of("local", local, "supply", supplyEvidence, "mes", mesSnapshot, "capturedTime", LocalDateTime.now())));
    }

    private MesPlanWithdrawalDTO.Command mesCommand(PlnQualitySupplementWithdrawalEntity entity,
                                                    PlnQualitySupplementDemandEntity supplement,
                                                    boolean preview, String expectedEvidence) {
        MesPlanWithdrawalDTO.Command command = new MesPlanWithdrawalDTO.Command();
        command.setRequestId(preview ? limit("QSW-PREVIEW-" + supplement.getId() + '-' + IdUtil.getSnowflakeNextIdStr(), 64)
                : entity.getExecutionRequestId());
        command.setIssueId(supplement.getSupplementIssueId());
        command.setIssueNo(supplement.getSupplementIssueNo());
        command.setExpectedQuantity(supplement.getRequestQuantity());
        command.setFactoryCode(supplement.getFactoryCode());
        command.setPreview(preview);
        command.setExpectedEvidenceFingerprint(expectedEvidence);
        command.setReason(preview ? "补产撤回申请/复核前资格校验" : entity.getReason());
        return command;
    }

    private void unlockAps(PlnIssueEntity issue) {
        schedulingOperationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                .set(PlnSchedulingOperationEntity::getLockStatus, false)
                .set(PlnSchedulingOperationEntity::getLockTime, null)
                .set(PlnSchedulingOperationEntity::getScheduleStatus, "WITHDRAWN"));
        taskDetailMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId())
                .set(PlnSchedulingTaskDetailEntity::getLockStatus, false)
                .set(PlnSchedulingTaskDetailEntity::getLockTaskId, null)
                .set(PlnSchedulingTaskDetailEntity::getLockTime, null)
                .set(PlnSchedulingTaskDetailEntity::getOperationScheduleStatus, "WITHDRAWN"));
        List<PlnSchedulingBatchAllocationEntity> allocations = batchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId()));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            long count = batchAllocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                    .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId()));
            require(count == 1, "APS批次来源在审批后发生变化，禁止撤回");
            batchMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingBatchEntity>()
                    .eq(PlnSchedulingBatchEntity::getId, allocation.getBatchId())
                    .set(PlnSchedulingBatchEntity::getLockStatus, false)
                    .set(PlnSchedulingBatchEntity::getLockTime, null)
                    .set(PlnSchedulingBatchEntity::getBatchStatus, "WITHDRAWN"));
        }
    }

    private List<PlanMatMrpEntity> mrpRows(String demandNo, boolean lock) {
        LambdaQueryWrapper<PlanMatMrpEntity> query = new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo).orderByAsc(PlanMatMrpEntity::getId);
        if (lock) query.last("FOR UPDATE");
        return mrpMapper.selectList(query);
    }

    private PlnQualitySupplementDemandEntity requireSupplement(Long id, boolean lock) {
        LambdaQueryWrapper<PlnQualitySupplementDemandEntity> query = new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                .eq(PlnQualitySupplementDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnQualitySupplementDemandEntity entity = supplementMapper.selectOne(query);
        require(entity != null, "质量补产需求不存在");
        return entity;
    }

    private PlnQualitySupplementWithdrawalEntity requireWithdrawal(Long id, boolean lock) {
        LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity> query = new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                .eq(PlnQualitySupplementWithdrawalEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnQualitySupplementWithdrawalEntity entity = withdrawalMapper.selectOne(query);
        require(entity != null, "质量补产撤回单不存在");
        return entity;
    }

    private PlnIssueEntity requireIssue(Long id, boolean lock) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnIssueEntity entity = issueMapper.selectOne(query);
        require(entity != null, "补产生产计划不存在");
        return entity;
    }

    private void audit(PlnQualitySupplementWithdrawalEntity entity, String requestId, String fingerprint,
                       String type, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PlnQualitySupplementWithdrawalOperationEntity()
                .setWithdrawalId(entity.getId()).setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(userCode(user)).setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(entity.getFactoryCode())) == 1, "补产撤回操作履历保存失败");
    }

    private boolean anySucceeded(PlnQualitySupplementWithdrawalEntity entity) {
        return Stream.of(entity.getSupplyPhaseStatus(), entity.getMesPhaseStatus(), entity.getWmsPhaseStatus(), entity.getApsPhaseStatus(),
                        entity.getPlanPhaseStatus()).filter(Objects::nonNull)
                .anyMatch(value -> Set.of("SUCCEEDED", "NOT_APPLICABLE").contains(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("补产撤回证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("补产撤回证据指纹生成失败");
        }
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trimmed(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(result) && result.length() <= max, label + "不能为空且不能超过" + max + "字");
        return result;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void requireFactory(String factory) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factory), "当前工厂无权操作该补产撤回单");
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : StrUtil.blankToDefault(user.getUsername(), "SYSTEM");
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), userCode(user));
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record WithdrawalEvidence(String localFingerprint, String supplyFingerprint, String mesFingerprint,
                                      String combinedFingerprint,
                                      List<PlanMrpSupplyWithdrawalDTO.Evidence> supplyEvidence,
                                      String snapshot) {
    }
}
