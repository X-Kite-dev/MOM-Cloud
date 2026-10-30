package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanWithdrawalDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.PlanStatusEnum;
import com.kitex.mom.plan.dto.PlanMrpSupplyWithdrawalDTO;
import com.kitex.mom.plan.dto.PlnDemandReplanEvidenceDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 产品需求重新计划前的通用下游撤回协调器。
 *
 * <p>它不决定新的来源版本，也不直接运行 MRP；来源模块先冻结本服务返回的证据，异岗批准后再用
 * 同一快照执行。执行固定收口下层供应、MES、WMS、APS 和旧根生产计划，并将需求恢复为“已接收”，
 * 随后由来源模块发布新版本并调用 MRP。所有远端命令均由调用方业务主键派生，失败续跑不会换号。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnDemandReplanCoordinator {

    private final PlnProductDemandMapper demandMapper;
    private final PlnProductDemandChangeMapper demandChangeMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingOperationMapper operationMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingBatchAllocationMapper allocationMapper;
    private final PlnSchedulingBatchMapper batchMapper;
    private final PlanMatMrpService mrpService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final ObjectMapper objectMapper;

    /**
     * 冻结当前需求全部可撤回下游事实；没有下游时返回 DIRECT。
     */
    public PlnDemandReplanEvidenceDTO.Evidence preview(Long demandId, Long previousMpsLineId) {
        return capture(demandId, previousMpsLineId, null, true, null);
    }

    /**
     * 冻结跨期拆量需求的全部旧MPS桶，任何一个桶缺失都不得继续换版。
     */
    public PlnDemandReplanEvidenceDTO.Evidence preview(Long demandId, List<Long> previousMpsLineIds) {
        List<Long> ids = normalizeLineIds(previousMpsLineIds);
        return capture(demandId, ids.isEmpty() ? null : ids.get(0), ids, true, null);
    }

    /**
     * 重新采集并校验审批时证据，防止使用已经漂移的候选发布。
     */
    public PlnDemandReplanEvidenceDTO.Evidence verify(String frozenSnapshot) {
        PlnDemandReplanEvidenceDTO.Evidence frozen = parse(frozenSnapshot);
        PlnDemandReplanEvidenceDTO.Evidence current = capture(frozen.getDemandId(),
                frozen.getPreviousMpsLineId(), frozen.getPreviousMpsLineIds(), true, null);
        require(Objects.equals(frozen.getEvidenceFingerprint(), current.getEvidenceFingerprint()),
                "MPS候选下游事实已变化，请驳回或取消后重新编制：" + frozen.getDemandNo());
        return current;
    }

    /**
     * 使用冻结清单执行撤回。调用方必须位于事务内；远端服务成功而本地回滚后，可用相同 executionKey 重试。
     */
    public PlnDemandReplanEvidenceDTO.Receipt execute(String frozenSnapshot, String executionKey, String reason) {
        PlnDemandReplanEvidenceDTO.Evidence frozen = parse(frozenSnapshot);
        if ("DIRECT".equals(frozen.getMode())) {
            PlnDemandReplanEvidenceDTO.Evidence current = capture(frozen.getDemandId(),
                    frozen.getPreviousMpsLineId(), frozen.getPreviousMpsLineIds(), false, frozen);
            require(Objects.equals(frozen.getEvidenceFingerprint(), current.getEvidenceFingerprint()),
                    "MPS直接发布需求事实已变化：" + frozen.getDemandNo());
            return new PlnDemandReplanEvidenceDTO.Receipt().setDemandId(frozen.getDemandId())
                    .setWithdrawnSupplyCount(0).setClosedIssueCount(0).setMessage("需求尚无下游，直接发布新MPS行");
        }

        String key = required(executionKey, 56, "MPS重排执行键");
        String cause = required(reason, 500, "MPS修订原因");
        PlnDemandReplanEvidenceDTO.Evidence current = capture(frozen.getDemandId(),
                frozen.getPreviousMpsLineId(), frozen.getPreviousMpsLineIds(), false, frozen);
        require(Objects.equals(frozen.getEvidenceFingerprint(), current.getEvidenceFingerprint()),
                "MPS执行前PLAN/MRP/APS或供应事实已变化：" + frozen.getDemandNo());

        int withdrawnSupplyCount = 0;
        for (PlanMrpSupplyWithdrawalDTO.Evidence supply : current.getSupplyEvidence()) {
            if (!"ELIGIBLE".equals(supply.getStatus())) continue;
            PlanMrpSupplyWithdrawalDTO.Receipt receipt = mrpService.withdrawGeneratedSupply(
                    new PlanMrpSupplyWithdrawalDTO.Command().setMrpId(supply.getMrpId())
                            .setRequestId(limit(key + "-SUP-" + supply.getMrpId(), 64))
                            .setExpectedEvidenceFingerprint(supply.getEvidenceFingerprint())
                            .setExpectedDownstreamEvidenceFingerprint(supply.getDownstreamEvidenceFingerprint())
                            .setReason(cause));
            require(Set.of("SUCCEEDED", "ALREADY_WITHDRAWN", "NOT_APPLICABLE").contains(receipt.getStatus()),
                    "MRP[" + supply.getMrpId() + "]下层供应撤回失败：" + receipt.getMessage());
            if (!"NOT_APPLICABLE".equals(receipt.getStatus())) withdrawnSupplyCount++;
        }

        Map<Long, PlnDemandReplanEvidenceDTO.IssueEvidence> frozenIssues = current.getIssues().stream()
                .collect(Collectors.toMap(PlnDemandReplanEvidenceDTO.IssueEvidence::getIssueId, Function.identity()));
        for (PlnDemandReplanEvidenceDTO.IssueEvidence issueEvidence : current.getIssues()) {
            if (!IssueStatusEnum.ISSUED.getCode().equals(issueEvidence.getIssueStatus())) continue;
            PlnIssueEntity issue = requireIssue(issueEvidence.getIssueId(), true);
            R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(
                    mesCommand(issue, false, limit(key + "-MES-" + issue.getId(), 64),
                            issueEvidence.getMesEvidenceFingerprint(), cause));
            require(response != null && response.isOk() && response.getData() != null
                            && Set.of("SUCCEEDED", "ALREADY_WITHDRAWN").contains(response.getData().getStatus())
                            && Objects.equals(response.getData().getEvidenceFingerprint(),
                            issueEvidence.getMesEvidenceFingerprint()),
                    "MES计划撤回失败：" + (response == null ? "无响应" : response.getMsg()));
        }

        List<PlanMatMrpEntity> mrpRows = mrpRows(frozen.getDemandNo(), true);
        require(mrpRows.size() == frozen.getMrpIds().size()
                        && mrpRows.stream().map(PlanMatMrpEntity::getId).toList().equals(frozen.getMrpIds()),
                "MPS执行时MRP集合已变化：" + frozen.getDemandNo());
        for (PlanMatMrpEntity row : mrpRows) {
            require(Boolean.TRUE.equals(mrpService.releaseForWithdrawal(row.getId(),
                            limit(key + "-MRP-" + row.getId(), 64), cause)),
                    "MRP[" + row.getId() + "]库存预留释放失败");
            // 旧行即将在同一事务内被安全删除重算；先清除已核验的旧MPS来源，满足通用MRP重算门禁。
            require(mrpMapper.update(null, new LambdaUpdateWrapper<PlanMatMrpEntity>()
                            .eq(PlanMatMrpEntity::getId, row.getId())
                            .eq(row.getMpsVersionId() != null, PlanMatMrpEntity::getMpsVersionId,
                                    row.getMpsVersionId())
                            .isNull(row.getMpsVersionId() == null, PlanMatMrpEntity::getMpsVersionId)
                            .eq(row.getMpsLineId() != null, PlanMatMrpEntity::getMpsLineId, row.getMpsLineId())
                            .isNull(row.getMpsLineId() == null, PlanMatMrpEntity::getMpsLineId)
                            .set(PlanMatMrpEntity::getMpsVersionId, null)
                            .set(PlanMatMrpEntity::getMpsLineId, null)) == 1,
                    "MRP[" + row.getId() + "]旧MPS来源清理失败");
        }

        List<PlnIssueEntity> issues = activeRootIssues(frozen.getDemandNo(), null, true);
        require(issues.size() == frozenIssues.size()
                        && issues.stream().allMatch(item -> frozenIssues.containsKey(item.getId())),
                "MPS执行时根生产计划集合已变化：" + frozen.getDemandNo());
        for (PlnIssueEntity issue : issues) {
            unlockAps(issue);
            issue.setStatus(IssueStatusEnum.NOT_ISSUED.getCode());
            issue.setLockStatus(false);
            issue.setLockTaskId(null);
            issue.setLockTime(null);
            issue.setScheduledDate(null);
            issue.setScheduledEndDate(null);
            issue.setRemark(limit(StrUtil.blankToDefault(issue.getRemark(), "")
                    + "；MPS滚动修订关闭旧计划，等待新版本MRP重排", 500));
            require(issueMapper.updateById(issue) == 1, "MPS修订关闭原生产计划失败：" + issue.getIssueNo());
        }

        PlnProductDemandEntity demand = requireDemand(frozen.getDemandId(), true);
        require(Objects.equals(demand.getDemandNo(), frozen.getDemandNo())
                        && PlanStatusEnum.MRP.getCode().equals(demand.getPlanState()),
                "MPS修订来源需求编号或状态已变化");
        demand.setPlanState(PlanStatusEnum.RECEIVE.getCode());
        require(demandMapper.updateById(demand) == 1, "MPS修订恢复需求待MRP状态失败");
        return new PlnDemandReplanEvidenceDTO.Receipt().setDemandId(demand.getId())
                .setWithdrawnSupplyCount(withdrawnSupplyCount).setClosedIssueCount(issues.size())
                .setMessage("已撤回" + withdrawnSupplyCount + "条下层供应、关闭" + issues.size()
                        + "条旧根计划并释放WMS/APS/MES事实");
    }

    /**
     * 将冻结证据序列化保存到 MPS 候选行。
     */
    public String snapshot(PlnDemandReplanEvidenceDTO.Evidence evidence) {
        return json(evidence);
    }

    private PlnDemandReplanEvidenceDTO.Evidence capture(Long demandId, Long previousMpsLineId,
                                                        List<Long> previousMpsLineIds,
                                                        boolean previewRemote,
                                                        PlnDemandReplanEvidenceDTO.Evidence frozen) {
        PlnProductDemandEntity demand = requireDemand(demandId, true);
        requireFactory(demand.getFactoryCode());
        require(demand.getNeedNum() != null && demand.getNeedNum().signum() > 0
                        && zero(demand.getCompleteNum()),
                "MPS修订需求数量无效或已有完成事实");
        require(demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                        .eq(PlnProductDemandChangeEntity::getDemandId, demand.getId())
                        .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) == 0,
                "需求已有待复核变更，不能同时执行MPS修订");

        List<PlanMatMrpEntity> mrpRows = mrpRows(demand.getDemandNo(), false);
        String mode = mrpRows.isEmpty() ? "DIRECT" : "WITHDRAW_REPLAN";
        require(("DIRECT".equals(mode) && PlanStatusEnum.RECEIVE.getCode().equals(demand.getPlanState()))
                        || ("WITHDRAW_REPLAN".equals(mode) && PlanStatusEnum.MRP.getCode().equals(demand.getPlanState())),
                "MPS需求状态与下游MRP事实不一致");
        if ("DIRECT".equals(mode)) {
            require(issueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                            .and(q -> q.eq(PlnIssueEntity::getSourceDemandNo, demand.getDemandNo())
                                    .or(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                                            .eq(PlnIssueEntity::getIssueNo, demand.getDemandNo())))) == 0,
                    "需求没有MRP但已有生产计划，禁止直接发布MPS");
        }
        List<Long> expectedMpsLines = previousMpsLineIds == null
                ? (previousMpsLineId == null ? List.of() : List.of(previousMpsLineId))
                : normalizeLineIds(previousMpsLineIds);
        for (PlanMatMrpEntity row : mrpRows) {
            require(Objects.equals(row.getFactoryCode(), demand.getFactoryCode())
                            && (expectedMpsLines.isEmpty() ? row.getMpsLineId() == null
                            : expectedMpsLines.contains(row.getMpsLineId()))
                            && zero(row.getCompleteNum()) && row.getPurchaseOrderId() == null
                            && StrUtil.isBlank(row.getPurchaseOrderNo()),
                    "MRP来源不是当前活动MPS，或已形成完成量/采购订单：" + row.getId());
        }

        boolean executionCapture = frozen != null;
        List<PlnIssueEntity> issues = activeRootIssues(
                demand.getDemandNo(), demand.getProductCode(), executionCapture);
        Map<Long, List<PlnSchedulingOperationEntity>> operations = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingTaskDetailEntity>> details = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingBatchAllocationEntity>> allocations = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingBatchEntity>> batches = new LinkedHashMap<>();
        for (PlnIssueEntity issue : issues) {
            require("issue".equals(issue.getType()) && !"QUALITY_SUPPLEMENT".equals(issue.getPlanType())
                            && Set.of(IssueStatusEnum.UNISSUED.getCode(), IssueStatusEnum.ISSUED.getCode())
                            .contains(issue.getStatus())
                            && zero(issue.getFinishNum()) && zero(issue.getUnqualifiedNum())
                            && zero(issue.getQualityIsolatedNum()) && zero(issue.getQualityReleasedNum())
                            && zero(issue.getQualityRejectedNum()) && zero(issue.getScrapNum())
                            && zero(issue.getReworkNum()) && zero(issue.getReworkCompletedNum())
                            && zero(issue.getReworkQualifiedNum()) && zero(issue.getReworkRejectedNum()),
                    "生产计划已有执行/质量事实或不是可撤回根计划：" + issue.getIssueNo());
            LambdaQueryWrapper<PlnSchedulingOperationEntity> operationQuery =
                    new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                            .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                            .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)
                            .orderByAsc(PlnSchedulingOperationEntity::getId);
            LambdaQueryWrapper<PlnSchedulingTaskDetailEntity> detailQuery =
                    new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                            .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId())
                            .orderByAsc(PlnSchedulingTaskDetailEntity::getId);
            LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity> allocationQuery =
                    new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                            .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId())
                            .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                            .orderByAsc(PlnSchedulingBatchAllocationEntity::getId);
            if (executionCapture) {
                operationQuery.last("FOR UPDATE");
                detailQuery.last("FOR UPDATE");
                allocationQuery.last("FOR UPDATE");
            }
            List<PlnSchedulingOperationEntity> issueOperations = operationMapper.selectList(operationQuery);
            List<PlnSchedulingTaskDetailEntity> issueDetails = taskDetailMapper.selectList(detailQuery);
            List<PlnSchedulingBatchAllocationEntity> issueAllocations = allocationMapper.selectList(allocationQuery);
            List<PlnSchedulingBatchEntity> issueBatches = new ArrayList<>();
            for (PlnSchedulingBatchAllocationEntity allocation : issueAllocations) {
                require(allocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                                .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                        "APS批次[" + allocation.getBatchNo() + "]包含其他计划，须先拆批/重排");
                LambdaQueryWrapper<PlnSchedulingBatchEntity> batchQuery =
                        new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                                .eq(PlnSchedulingBatchEntity::getId, allocation.getBatchId());
                if (executionCapture) batchQuery.last("FOR UPDATE");
                PlnSchedulingBatchEntity batch = batchMapper.selectOne(batchQuery);
                require(batch != null && Objects.equals(batch.getTaskId(), allocation.getTaskId())
                                && Objects.equals(batch.getFactoryCode(), issue.getFactoryCode())
                                && Objects.equals(allocation.getFactoryCode(), issue.getFactoryCode())
                                && batch.getPlannedQuantity() != null
                                && allocation.getAllocatedQuantity() != null
                                && batch.getPlannedQuantity().compareTo(allocation.getAllocatedQuantity()) == 0,
                        "APS批次头与计划分配来源不一致：" + allocation.getBatchNo());
                issueBatches.add(batch);
            }
            if (Boolean.TRUE.equals(issue.getLockStatus())) {
                require(issue.getLockTaskId() != null && !issueOperations.isEmpty()
                                && !issueDetails.isEmpty() && !issueAllocations.isEmpty()
                                && issueOperations.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus()))
                                && issueDetails.stream().allMatch(row -> Boolean.TRUE.equals(row.getLockStatus())),
                        "生产计划已锁定但APS工序、任务或批次来源不完整：" + issue.getIssueNo());
            }
            operations.put(issue.getId(), issueOperations);
            details.put(issue.getId(), issueDetails);
            allocations.put(issue.getId(), issueAllocations);
            batches.put(issue.getId(), issueBatches);
        }

        Map<Long, PlanMrpSupplyWithdrawalDTO.Evidence> frozenSupply = frozen == null ? Map.of()
                : frozen.getSupplyEvidence().stream().collect(Collectors.toMap(
                PlanMrpSupplyWithdrawalDTO.Evidence::getMrpId, Function.identity()));
        List<PlanMrpSupplyWithdrawalDTO.Evidence> supplies = new ArrayList<>();
        for (PlanMatMrpEntity row : mrpRows) {
            if (frozen == null) {
                supplies.add(mrpService.previewGeneratedSupplyWithdrawal(row.getId()));
            } else {
                PlanMrpSupplyWithdrawalDTO.Evidence expected = frozenSupply.get(row.getId());
                require(expected != null, "冻结供应证据缺少MRP：" + row.getId());
                PlanMrpSupplyWithdrawalDTO.Evidence current = mrpService.captureGeneratedSupplyWithdrawalForExecution(
                        row.getId(), expected.getDownstreamEvidenceFingerprint());
                require(Objects.equals(current.getEvidenceFingerprint(), expected.getEvidenceFingerprint()),
                        "MRP下层供应事实已变化：" + row.getId());
                supplies.add(current);
            }
        }

        Map<Long, PlnDemandReplanEvidenceDTO.IssueEvidence> frozenIssues = frozen == null ? Map.of()
                : frozen.getIssues().stream().collect(Collectors.toMap(
                PlnDemandReplanEvidenceDTO.IssueEvidence::getIssueId, Function.identity()));
        List<PlnDemandReplanEvidenceDTO.IssueEvidence> issueEvidence = new ArrayList<>();
        for (PlnIssueEntity issue : issues) {
            String mesFingerprint = hash("MES_NOT_ISSUED");
            if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
                if (previewRemote) {
                    R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(
                            mesCommand(issue, true, limit("MPSR-PREVIEW-" + issue.getId() + "-"
                                    + IdUtil.getSnowflakeNextIdStr(), 64), null, "MPS滚动修订资格校验"));
                    require(response != null && response.isOk() && response.getData() != null
                                    && "ELIGIBLE".equals(response.getData().getStatus())
                                    && StrUtil.isNotBlank(response.getData().getEvidenceFingerprint()),
                            "MES计划撤回资格校验失败：" + (response == null ? "无响应" : response.getMsg()));
                    mesFingerprint = response.getData().getEvidenceFingerprint();
                } else {
                    PlnDemandReplanEvidenceDTO.IssueEvidence expected = frozenIssues.get(issue.getId());
                    require(expected != null && Objects.equals(expected.getIssueStatus(), issue.getStatus())
                                    && expected.getQuantity() != null && issue.getNum() != null
                                    && expected.getQuantity().compareTo(issue.getNum()) == 0,
                            "冻结MES计划证据与当前生产计划不一致：" + issue.getIssueNo());
                    mesFingerprint = expected.getMesEvidenceFingerprint();
                }
            }
            issueEvidence.add(new PlnDemandReplanEvidenceDTO.IssueEvidence().setIssueId(issue.getId())
                    .setIssueNo(issue.getIssueNo()).setIssueStatus(issue.getStatus())
                    .setQuantity(issue.getNum()).setMesEvidenceFingerprint(mesFingerprint));
        }

        Map<String, Object> local = new LinkedHashMap<>();
        List<Object> demandEvidence = new ArrayList<>(Arrays.asList(demand.getId(), demand.getDemandNo(), demand.getFactoryCode(),
                demand.getProductCode(), demand.getNeedNum(), demand.getPlanEndDate(), demand.getPlanState(),
                demand.getDemandWarehouseCode(), demand.getCompleteNum(), previousMpsLineId));
        // 历史单桶证据没有列表字段，保持原指纹；新候选显式冻结全部桶行。
        if (previousMpsLineIds != null) demandEvidence.add(expectedMpsLines);
        local.put("demand", demandEvidence);
        boolean bucketAware = previousMpsLineIds != null;
        local.put("mrp", mrpRows.stream().map(row -> mrpEvidence(row, bucketAware)).toList());
        local.put("issues", issues.stream().map(row -> Arrays.asList(row.getId(), row.getIssueNo(), row.getProductCode(),
                row.getNum(), row.getDemandTime(), row.getStatus(), row.getLockStatus(), row.getLockTaskId(),
                row.getScheduledDate(), row.getScheduledEndDate())).toList());
        local.put("operations", flattenOperations(operations));
        local.put("details", flattenDetails(details));
        local.put("allocations", flattenAllocations(allocations));
        local.put("batches", flattenBatches(batches));
        String localFingerprint = hash(json(local));
        String supplyFingerprint = hash(supplies.stream()
                .map(PlanMrpSupplyWithdrawalDTO.Evidence::getEvidenceFingerprint).collect(Collectors.joining("|")));
        String mesFingerprint = hash(issueEvidence.stream()
                .map(PlnDemandReplanEvidenceDTO.IssueEvidence::getMesEvidenceFingerprint)
                .collect(Collectors.joining("|")));
        String combined = hash(mode + "|" + localFingerprint + "|" + supplyFingerprint + "|" + mesFingerprint);
        return new PlnDemandReplanEvidenceDTO.Evidence().setMode(mode).setDemandId(demand.getId())
                .setDemandNo(demand.getDemandNo()).setFactoryCode(demand.getFactoryCode())
                .setPreviousMpsLineId(previousMpsLineId).setPreviousMpsLineIds(
                        previousMpsLineIds == null ? null : expectedMpsLines)
                .setLocalFingerprint(localFingerprint)
                .setSupplyFingerprint(supplyFingerprint).setEvidenceFingerprint(combined)
                .setMrpIds(mrpRows.stream().map(PlanMatMrpEntity::getId).toList())
                .setSupplyEvidence(supplies).setIssues(issueEvidence);
    }

    private List<Long> normalizeLineIds(List<Long> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private List<Object> mrpEvidence(PlanMatMrpEntity row, boolean bucketAware) {
        List<Object> result = new ArrayList<>(Arrays.asList(row.getId(), row.getMatCode(), row.getNum(),
                row.getStatus(), row.getMpsVersionId(), row.getMpsLineId(), row.getSupplySourceType(),
                row.getSupplySourceId(), row.getSupplySourceNo(), row.getSupplySourceStatus(),
                row.getMainMaterialReservationStatus(), row.getMaterialReservationStatus(),
                row.getInventoryPeggingStatus()));
        if (bucketAware) {
            result.add(row.getPlanningSourceKey());
            result.add(row.getMpsBucketNo());
        }
        return result;
    }

    private List<List<Object>> flattenOperations(Map<Long, List<PlnSchedulingOperationEntity>> source) {
        List<List<Object>> result = new ArrayList<>();
        source.forEach((issueId, rows) -> rows.forEach(row -> result.add(Arrays.asList(issueId, row.getId(),
                row.getTaskId(), row.getProcessCode(), row.getScheduledStartTime(), row.getScheduledEndTime(),
                row.getLockStatus(), row.getSchedulingBatchId()))));
        return result;
    }

    private List<List<Object>> flattenDetails(Map<Long, List<PlnSchedulingTaskDetailEntity>> source) {
        List<List<Object>> result = new ArrayList<>();
        source.forEach((issueId, rows) -> rows.forEach(row -> result.add(Arrays.asList(issueId, row.getId(),
                row.getTaskId(), row.getNum(), row.getLockStatus(), row.getOperationScheduleStatus()))));
        return result;
    }

    private List<List<Object>> flattenAllocations(Map<Long, List<PlnSchedulingBatchAllocationEntity>> source) {
        List<List<Object>> result = new ArrayList<>();
        source.forEach((issueId, rows) -> rows.forEach(row -> result.add(Arrays.asList(issueId, row.getId(),
                row.getBatchId(), row.getBatchNo(), row.getAllocatedQuantity()))));
        return result;
    }

    private List<List<Object>> flattenBatches(Map<Long, List<PlnSchedulingBatchEntity>> source) {
        List<List<Object>> result = new ArrayList<>();
        source.forEach((issueId, rows) -> rows.forEach(row -> result.add(Arrays.asList(issueId, row.getId(),
                row.getTaskId(), row.getBatchNo(), row.getBatchStatus(), row.getLockStatus(),
                row.getPlannedQuantity(), row.getFactoryCode()))));
        return result;
    }

    private List<PlnIssueEntity> activeRootIssues(String demandNo, String productCode, boolean lock) {
        LambdaQueryWrapper<PlnIssueEntity> allQuery = new LambdaQueryWrapper<PlnIssueEntity>()
                .and(q -> q.eq(PlnIssueEntity::getSourceDemandNo, demandNo)
                        .or(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                                .eq(PlnIssueEntity::getIssueNo, demandNo)))
                .orderByAsc(PlnIssueEntity::getId);
        if (lock) allQuery.last("FOR UPDATE");
        List<PlnIssueEntity> all = issueMapper.selectList(allQuery);
        require(all.stream().noneMatch(row -> Set.of(IssueStatusEnum.PRODUCT.getCode(),
                        IssueStatusEnum.COMPLETE.getCode()).contains(row.getStatus())),
                "MPS需求已有开工或完工生产计划，必须走现场变更：" + demandNo);
        List<PlnIssueEntity> active = all.stream().filter(row -> Set.of(IssueStatusEnum.UNISSUED.getCode(),
                        IssueStatusEnum.ISSUED.getCode()).contains(row.getStatus()))
                .sorted(Comparator.comparing(PlnIssueEntity::getId)).toList();
        require(active.stream().allMatch(row -> "issue".equals(row.getType())
                        && (StrUtil.isBlank(productCode) || Objects.equals(row.getProductCode(), productCode))),
                "MPS需求存在非正式根计划或产品不一致的活动计划，禁止遗漏撤回：" + demandNo);
        return active;
    }

    private void unlockAps(PlnIssueEntity issue) {
        operationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
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
        List<PlnSchedulingBatchAllocationEntity> allocations = allocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issue.getId()));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            require(allocationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                            .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                    "APS批次来源在复核后变化，禁止MPS撤回");
            require(batchMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingBatchEntity>()
                            .eq(PlnSchedulingBatchEntity::getId, allocation.getBatchId())
                            .set(PlnSchedulingBatchEntity::getLockStatus, false)
                            .set(PlnSchedulingBatchEntity::getLockTime, null)
                            .set(PlnSchedulingBatchEntity::getBatchStatus, "WITHDRAWN")) == 1,
                    "APS批次撤回失败：" + allocation.getBatchNo());
        }
    }

    private MesPlanWithdrawalDTO.Command mesCommand(PlnIssueEntity issue, boolean preview, String requestId,
                                                    String expectedEvidence, String reason) {
        MesPlanWithdrawalDTO.Command command = new MesPlanWithdrawalDTO.Command();
        command.setRequestId(requestId);
        command.setIssueId(issue.getId());
        command.setIssueNo(issue.getIssueNo());
        command.setExpectedQuantity(issue.getNum());
        command.setFactoryCode(issue.getFactoryCode());
        command.setPreview(preview);
        command.setExpectedEvidenceFingerprint(expectedEvidence);
        command.setReason(reason);
        return command;
    }

    private List<PlanMatMrpEntity> mrpRows(String demandNo, boolean lock) {
        LambdaQueryWrapper<PlanMatMrpEntity> query = new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, demandNo).orderByAsc(PlanMatMrpEntity::getId);
        if (lock) query.last("FOR UPDATE");
        return mrpMapper.selectList(query);
    }

    private PlnProductDemandEntity requireDemand(Long id, boolean lock) {
        LambdaQueryWrapper<PlnProductDemandEntity> query = new LambdaQueryWrapper<PlnProductDemandEntity>()
                .eq(PlnProductDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnProductDemandEntity demand = demandMapper.selectOne(query);
        require(demand != null, "MPS产品需求不存在：" + id);
        return demand;
    }

    private PlnIssueEntity requireIssue(Long id, boolean lock) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnIssueEntity issue = issueMapper.selectOne(query);
        require(issue != null, "MPS来源生产计划不存在：" + id);
        return issue;
    }

    private PlnDemandReplanEvidenceDTO.Evidence parse(String snapshot) {
        require(StrUtil.isNotBlank(snapshot), "MPS候选缺少下游冻结证据");
        try {
            PlnDemandReplanEvidenceDTO.Evidence evidence = objectMapper.readValue(snapshot,
                    PlnDemandReplanEvidenceDTO.Evidence.class);
            require(evidence.getDemandId() != null && StrUtil.isNotBlank(evidence.getDemandNo())
                            && Set.of("DIRECT", "WITHDRAW_REPLAN").contains(evidence.getMode())
                            && evidence.getMrpIds() != null && evidence.getSupplyEvidence() != null
                            && evidence.getIssues() != null && evidence.getEvidenceFingerprint() != null
                            && evidence.getEvidenceFingerprint().length() == 64,
                    "MPS候选下游冻结证据不完整");
            return evidence;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("MPS候选下游冻结证据无法解析");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("MPS下游证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MPS下游证据指纹生成失败");
        }
    }

    private boolean zero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    private String required(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(result) && result.length() <= max,
                label + "不能为空且不能超过" + max + "字");
        return result;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode),
                "当前工厂无权操作该MPS需求");
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
