package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmRouteMaterialAssignmentQueryDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanWithdrawalDTO;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.MrpReadinessStatusEnum;
import com.kitex.mom.plan.constant.MrpSubstituteStatusEnum;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlanMrpInventoryPeggingService;
import com.kitex.mom.pms.api.feign.RemotePmsAgentPurchaseService;
import com.kitex.mom.pms.api.feign.RemotePmsPlanService;
import com.kitex.mom.pms.dto.*;
import com.kitex.mom.wms.api.feign.RemoteAllocationBillService;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.api.feign.RemoteMaterialReservationService;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MRP运算记录表
 *
 * @author kitex
 * @date 2025-04-15 17:37:58
 */
@Service
@AllArgsConstructor
public class PlanMatMrpServiceImpl extends ServiceImpl<PlanMatMrpMapper, PlanMatMrpEntity> implements PlanMatMrpService {

    private final PlnIssueMapper plnIssueMapper;

    private final PlnIssueMonthMapper plnIssueMonthMapper;

    private final PlnProductDemandMapper plnProductDemandMapper;

    private final PlnProductDemandChangeMapper demandChangeMapper;

    private final RemoteLiveStockService remoteLiveStockService;

    private final RemoteAllocationBillService remoteAllocationBillService;

    private final RemoteMaterialService remoteMaterialService;

    private final RemoteMaterialReservationService remoteMaterialReservationService;

    private final RemotePmsPlanService remotePmsPlanService;

    private final RemotePmsAgentPurchaseService remotePmsAgentPurchaseService;

    private final RemoteMesTaskService remoteMesTaskService;

    private final RemoteProcessRouteService remoteProcessRouteService;

    private final PlnSchedulingOperationMapper schedulingOperationMapper;

    private final PlnSchedulingTaskDetailMapper schedulingTaskDetailMapper;

    private final PlnSchedulingBatchMapper schedulingBatchMapper;

    private final PlnSchedulingBatchAllocationMapper schedulingBatchAllocationMapper;

    private final PlnSchedulingTaskMapper schedulingTaskMapper;

    private final PlanMrpInventoryPeggingService inventoryPeggingService;

    private final PlanMrpPurchaseStatusReceiptMapper purchaseStatusReceiptMapper;

    @Override
    public PlanMrpSupplyWithdrawalDTO.Evidence previewGeneratedSupplyWithdrawal(Long mrpId) {
        if (mrpId == null) throw new CheckedException("下层供应撤回资格校验必须提供MRP记录");
        PlanMatMrpEntity mrp = getById(mrpId);
        if (mrp == null) throw new CheckedException("待撤回MRP不存在：" + mrpId);
        return captureGeneratedSupplyEvidence(mrp, false, true, null);
    }

    @Override
    public PlanMrpSupplyWithdrawalDTO.Evidence captureGeneratedSupplyWithdrawalForExecution(
            Long mrpId, String expectedDownstreamEvidenceFingerprint) {
        if (mrpId == null || StrUtil.isBlank(expectedDownstreamEvidenceFingerprint)
                || expectedDownstreamEvidenceFingerprint.trim().length() != 64) {
            throw new CheckedException("下层供应执行冻结必须提供MRP和64位下游证据指纹");
        }
        PlanMatMrpEntity mrp = getById(mrpId);
        if (mrp == null) throw new CheckedException("待撤回MRP不存在：" + mrpId);
        return captureGeneratedSupplyEvidence(mrp, false, false,
                expectedDownstreamEvidenceFingerprint.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanMrpSupplyWithdrawalDTO.Receipt withdrawGeneratedSupply(PlanMrpSupplyWithdrawalDTO.Command command) {
        if (command == null || command.getMrpId() == null || StrUtil.isBlank(command.getRequestId())
                || StrUtil.isBlank(command.getExpectedEvidenceFingerprint())
                || StrUtil.isBlank(command.getExpectedDownstreamEvidenceFingerprint())
                || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("下层供应撤回必须提供MRP、请求号、冻结证据指纹和原因");
        }
        String requestId = requireText(command.getRequestId(), 64, "下层供应撤回请求号");
        String reason = requireText(command.getReason(), 500, "下层供应撤回原因");
        String expectedDownstream = requireText(command.getExpectedDownstreamEvidenceFingerprint(), 64,
                "下层供应远端证据指纹");
        requireSupply(expectedDownstream.length() == 64, "下层供应远端证据指纹必须为64位");
        String requestFingerprint = sha256(command.getMrpId() + "|" + command.getExpectedEvidenceFingerprint().trim()
                + "|" + expectedDownstream + "|" + reason);
        PlanMatMrpEntity requestOwner = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getSupplyWithdrawalRequestId, requestId), false);
        if (requestOwner != null && !Objects.equals(requestOwner.getId(), command.getMrpId())) {
            throw new CheckedException("下层供应撤回请求号已被其他MRP使用");
        }
        PlanMatMrpEntity mrp = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, command.getMrpId()).last("FOR UPDATE"), false);
        if (mrp == null) throw new CheckedException("待撤回MRP不存在：" + command.getMrpId());
        if (Objects.equals(mrp.getSupplyWithdrawalRequestId(), requestId)) {
            if (!Objects.equals(mrp.getSupplyWithdrawalFingerprint(), requestFingerprint)) {
                throw new CheckedException("下层供应撤回请求号已被不同命令使用");
            }
            if (!"WITHDRAWN".equals(mrp.getSupplySourceStatus())) {
                throw new CheckedException("下层供应撤回幂等账状态不完整，请人工核查");
            }
            return receipt(captureGeneratedSupplyEvidence(mrp, true, false, expectedDownstream),
                    "ALREADY_WITHDRAWN", "下层供应已由同一请求撤回");
        }
        if (StrUtil.isNotBlank(mrp.getSupplyWithdrawalRequestId())) {
            throw new CheckedException("该MRP下层供应已由其他请求撤回：" + mrp.getSupplyWithdrawalRequestId());
        }
        PlanMrpSupplyWithdrawalDTO.Evidence evidence = captureGeneratedSupplyEvidence(mrp, true, false, expectedDownstream);
        if ("NOT_APPLICABLE".equals(evidence.getStatus()))
            return receipt(evidence, "NOT_APPLICABLE", evidence.getMessage());
        if (!Objects.equals(evidence.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim())) {
            throw new CheckedException("下层供应事实已变化，请重新申请撤回");
        }
        if ("SELF_ISSUE".equals(mrp.getSupplySourceType())) {
            PlnIssueEntity source = issueBySource(mrp, true);
            if (IssueStatusEnum.ISSUED.getCode().equals(source.getStatus())) {
                MesPlanWithdrawalDTO.Command mesCommand = new MesPlanWithdrawalDTO.Command();
                mesCommand.setRequestId("MRPS-MES-" + sha256(requestId).substring(0, 48));
                mesCommand.setIssueId(source.getId());
                mesCommand.setIssueNo(source.getIssueNo());
                mesCommand.setExpectedQuantity(source.getNum());
                mesCommand.setFactoryCode(source.getFactoryCode());
                mesCommand.setPreview(false);
                mesCommand.setExpectedEvidenceFingerprint(expectedDownstream);
                mesCommand.setReason(reason);
                R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(mesCommand);
                requireSupply(response != null && response.isOk() && response.getData() != null
                                && "SUCCEEDED".equals(response.getData().getStatus())
                                && Objects.equals(response.getData().getEvidenceFingerprint(), expectedDownstream),
                        "下层自制MES计划撤回失败：" + (response == null ? "无响应" : response.getMsg()));
            }
            unlockSelfSupplyAps(source);
            source.setStatus("4");
            source.setLockStatus(false);
            source.setLockTaskId(null);
            source.setLockTime(null);
            source.setScheduledDate(null);
            source.setScheduledEndDate(null);
            source.setRemark(limitText(StrUtil.blankToDefault(source.getRemark(), "") + "；MRP下层供应撤回：" + reason, 500));
            if (plnIssueMapper.updateById(source) != 1) throw new CheckedException("下层自制计划撤回失败");
        } else {
            PlnIssueMonthEntity source = issueMonthBySource(mrp, true);
            if ("1".equals(source.getStatus())) {
                PmsPlanSupplyWithdrawalDTO.Command pmsCommand = pmsWithdrawalCommand(mrp, source,
                        limitText(requestId + "-PMS", 64), false, evidence.getDownstreamEvidenceFingerprint(), reason);
                R<PmsPlanSupplyWithdrawalDTO.Receipt> response = remotePmsPlanService.withdrawSupplyPlan(pmsCommand);
                if (response == null || !response.isOk() || response.getData() == null
                        || !Set.of("SUCCEEDED", "ALREADY_WITHDRAWN").contains(response.getData().getStatus())
                        || !Objects.equals(response.getData().getEvidenceFingerprint(), expectedDownstream)) {
                    throw new CheckedException("PMS采购/外协供应计划撤回失败："
                            + (response == null ? "无响应" : response.getMsg()));
                }
            }
            source.setStatus("4");
            source.setRemark(limitText(StrUtil.blankToDefault(source.getRemark(), "") + "；MRP下层供应撤回：" + reason, 500));
            if (plnIssueMonthMapper.updateById(source) != 1) throw new CheckedException("采购/外协月计划撤回失败");
        }
        mrp.setSupplySourceStatus("WITHDRAWN");
        mrp.setSupplyWithdrawalRequestId(requestId);
        mrp.setSupplyWithdrawalFingerprint(requestFingerprint);
        mrp.setSupplyWithdrawalReason(reason);
        mrp.setSupplyWithdrawalTime(LocalDateTime.now());
        if (!updateById(mrp)) throw new CheckedException("MRP下层供应撤回回执保存失败");
        return receipt(evidence, "SUCCEEDED", "已关闭未进入现场执行的精确供应单据（含PMS未下单计划及MES未接收自制计划）");
    }

    private PlanMrpSupplyWithdrawalDTO.Receipt receipt(PlanMrpSupplyWithdrawalDTO.Evidence evidence,
                                                       String status, String message) {
        PlanMrpSupplyWithdrawalDTO.Receipt result = new PlanMrpSupplyWithdrawalDTO.Receipt();
        BeanUtils.copyProperties(evidence, result);
        result.setStatus(status);
        result.setMessage(message);
        result.setHandledTime(LocalDateTime.now());
        return result;
    }

    private PlanMrpSupplyWithdrawalDTO.Evidence captureGeneratedSupplyEvidence(
            PlanMatMrpEntity mrp, boolean lock, boolean previewDownstream, String frozenDownstreamFingerprint) {
        BigDecimal quantity = Optional.ofNullable(mrp.getNum()).orElse(BigDecimal.ZERO);
        if (quantity.signum() <= 0 || !Boolean.TRUE.equals(mrp.getStatus())) {
            String snapshot = JSONUtil.toJsonStr(Arrays.asList(mrp.getId(), mrp.getMatCode(), quantity, mrp.getStatus(), "NO_SUPPLY"));
            return evidence(mrp, "NOT_APPLICABLE", sha256("NO_DOWNSTREAM"), snapshot, "MRP未生成净供应单据");
        }
        if (mrp.getSupplySourceId() == null || StrUtil.isBlank(mrp.getSupplySourceType())
                || StrUtil.isBlank(mrp.getSupplySourceNo()) || mrp.getSupplySourceQuantity() == null) {
            throw new CheckedException("MRP物料[" + mrp.getMatCode() + "]为历史下达数据，缺少精确供应来源Pegging，禁止猜测撤回");
        }
        if (mrp.getSupplySourceQuantity().compareTo(quantity) != 0) {
            throw new CheckedException("MRP物料[" + mrp.getMatCode() + "]供应来源数量与净供应量不守恒");
        }
        List<?> facts;
        String downstreamEvidenceFingerprint = sha256("LOCAL_SOURCE_ONLY");
        if ("SELF_ISSUE".equals(mrp.getSupplySourceType())) {
            PlnIssueEntity source = issueBySource(mrp, lock);
            requireSupply(Objects.equals(source.getIssueNo(), mrp.getSupplySourceNo())
                            && Objects.equals(source.getProductCode(), mrp.getMatCode())
                            && Objects.equals(source.getFactoryCode(), mrp.getFactoryCode())
                            && source.getNum() != null && source.getNum().compareTo(quantity) == 0,
                    "下层自制计划来源或数量不一致");
            requireSupply(Optional.ofNullable(source.getFinishNum()).orElse(BigDecimal.ZERO).signum() == 0,
                    "下层自制计划已有完工事实，必须进入现场停止/冲销流程");
            requireSupply(Set.of("0", "1", "4").contains(source.getStatus()),
                    "下层自制计划已接收、生产或完成，须走现场停止/退料/冲销流程");
            requireSupply(!"4".equals(source.getStatus()) || "WITHDRAWN".equals(mrp.getSupplySourceStatus()),
                    "下层自制来源已关闭但MRP缺少撤回回执，须先完成账实核查");
            List<PlnSchedulingBatchAllocationEntity> allocations = schedulingBatchAllocationMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                            .eq(PlnSchedulingBatchAllocationEntity::getIssueId, source.getId())
                            .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchId)
                            .orderByAsc(PlnSchedulingBatchAllocationEntity::getId));
            List<PlnSchedulingBatchEntity> batches = new ArrayList<>();
            for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
                requireSupply(Objects.equals(allocation.getIssueNo(), source.getIssueNo())
                                && Objects.equals(allocation.getFactoryCode(), source.getFactoryCode())
                                && schedulingBatchAllocationMapper.selectCount(
                                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                                        .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                        "下层自制APS批次来源不是本计划独占，须先拆批或重排");
                PlnSchedulingBatchEntity batch = schedulingBatchMapper.selectById(allocation.getBatchId());
                requireSupply(batch != null && Objects.equals(batch.getTaskId(), allocation.getTaskId())
                                && Objects.equals(batch.getFactoryCode(), source.getFactoryCode())
                                && batch.getPlannedQuantity() != null && allocation.getAllocatedQuantity() != null
                                && batch.getPlannedQuantity().compareTo(allocation.getAllocatedQuantity()) == 0,
                        "下层自制APS批次头与来源分配数量账不一致");
                batches.add(batch);
            }
            if (Boolean.TRUE.equals(source.getLockStatus())) {
                requireSupply(source.getLockTaskId() != null && !allocations.isEmpty()
                                && allocations.stream().allMatch(row -> Objects.equals(row.getTaskId(), source.getLockTaskId()))
                                && batches.stream().allMatch(batch -> Boolean.TRUE.equals(batch.getLockStatus()))
                                && allocations.stream().map(PlnSchedulingBatchAllocationEntity::getAllocatedQuantity)
                                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                                .compareTo(quantity) == 0,
                        "下层自制APS锁定批次数量账缺失或不守恒");
            }
            List<PlnSchedulingOperationEntity> operations = schedulingOperationMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                            .eq(PlnSchedulingOperationEntity::getIssueId, source.getId())
                            .orderByAsc(PlnSchedulingOperationEntity::getId));
            if (Boolean.TRUE.equals(source.getLockStatus())) {
                requireSupply(!operations.isEmpty() && operations.stream().allMatch(
                                operation -> Boolean.TRUE.equals(operation.getLockStatus())
                                        && Objects.equals(operation.getTaskId(), source.getLockTaskId())),
                        "下层自制APS工序锁定来源不完整");
            } else {
                requireSupply(operations.stream().noneMatch(operation -> Boolean.TRUE.equals(operation.getLockStatus()))
                                && batches.stream().noneMatch(batch -> Boolean.TRUE.equals(batch.getLockStatus())),
                        "下层自制计划未锁定但APS工序或批次仍占用资源，须先完成账实核查");
            }
            if ("1".equals(source.getStatus())) {
                requireSupply(Boolean.TRUE.equals(source.getLockStatus()), "下层自制计划已下达但缺少APS正式锁定");
                if (previewDownstream) {
                    MesPlanWithdrawalDTO.Command preview = new MesPlanWithdrawalDTO.Command();
                    preview.setRequestId("MRPS-PRE-" + mrp.getId() + '-' + IdUtil.getSnowflakeNextIdStr());
                    preview.setIssueId(source.getId());
                    preview.setIssueNo(source.getIssueNo());
                    preview.setExpectedQuantity(source.getNum());
                    preview.setFactoryCode(source.getFactoryCode());
                    preview.setPreview(true);
                    preview.setReason("MRP下层自制撤回资格校验");
                    R<MesPlanWithdrawalDTO.Receipt> response = remoteMesTaskService.withdrawPlan(preview);
                    requireSupply(response != null && response.isOk() && response.getData() != null
                                    && "ELIGIBLE".equals(response.getData().getStatus())
                                    && StrUtil.isNotBlank(response.getData().getEvidenceFingerprint()),
                            "下层自制MES撤回资格校验失败：" + (response == null ? "无响应" : response.getMsg()));
                    downstreamEvidenceFingerprint = response.getData().getEvidenceFingerprint();
                } else {
                    downstreamEvidenceFingerprint = requireFrozenDownstream(frozenDownstreamFingerprint);
                }
            }
            facts = Arrays.asList(source.getId(), source.getIssueNo(), source.getProductCode(), source.getNum(),
                    source.getStatus(), source.getFinishNum(), source.getLockStatus(), source.getLockTaskId(),
                    source.getFactoryCode(), allocations.stream().map(row -> Arrays.asList(row.getId(), row.getBatchId(),
                            row.getIssueNo(), row.getAllocatedQuantity())).toList(),
                    batches.stream().map(batch -> Arrays.asList(batch.getId(), batch.getTaskId(), batch.getBatchNo(),
                            batch.getBatchStatus(), batch.getLockStatus(), batch.getPlannedQuantity())).toList(),
                    operations.stream().map(row -> Arrays.asList(row.getId(), row.getTaskId(), row.getProcessCode(),
                            row.getLockStatus(), row.getSchedulingBatchId())).toList(), downstreamEvidenceFingerprint);
        } else if (Set.of("PURCHASE_ISSUE_MONTH", "OUTSOURCE_ISSUE_MONTH").contains(mrp.getSupplySourceType())) {
            PlnIssueMonthEntity source = issueMonthBySource(mrp, lock);
            requireSupply(Objects.equals(source.getIssueMonthNo(), mrp.getSupplySourceNo())
                            && Objects.equals(source.getProductCode(), mrp.getMatCode())
                            && Objects.equals(source.getFactoryCode(), mrp.getFactoryCode())
                            && source.getNum() != null && source.getNum().compareTo(quantity) == 0,
                    "采购/外协月计划来源或数量不一致");
            requireSupply(Optional.ofNullable(source.getFinishNum()).orElse(BigDecimal.ZERO).signum() == 0,
                    "采购/外协供应已有到货或完成事实，必须进入PMS受控撤回");
            requireSupply(Set.of("0", "1", "4").contains(source.getStatus()),
                    "采购/外协供应已形成订单、到货或完成事实，须先完成PMS订单/合同受控撤回");
            requireSupply(!"4".equals(source.getStatus()) || "WITHDRAWN".equals(mrp.getSupplySourceStatus()),
                    "采购/外协来源已关闭但MRP缺少撤回回执，须先完成账实核查");
            if ("1".equals(source.getStatus()) && previewDownstream) {
                requireSupply(source.getDownstreamPlanId() != null && StrUtil.isNotBlank(source.getDownstreamPlanNo())
                                && StrUtil.isNotBlank(source.getDownstreamPlanFingerprint()),
                        "采购/外协月计划已推送PMS但缺少精确计划关联，禁止按单号猜测撤回");
                PmsPlanSupplyWithdrawalDTO.Command preview = pmsWithdrawalCommand(mrp, source,
                        limitText("MRP-SUP-PRE-" + mrp.getId() + '-' + IdUtil.getSnowflakeNextIdStr(), 64), true, null,
                        "PLAN分层撤回资格校验");
                R<PmsPlanSupplyWithdrawalDTO.Receipt> response = remotePmsPlanService.withdrawSupplyPlan(preview);
                requireSupply(response != null && response.isOk() && response.getData() != null
                                && Set.of("ELIGIBLE", "ALREADY_WITHDRAWN").contains(response.getData().getStatus())
                                && StrUtil.isNotBlank(response.getData().getEvidenceFingerprint()),
                        "PMS采购/外协计划撤回资格校验失败：" + (response == null ? "无响应" : response.getMsg()));
                downstreamEvidenceFingerprint = response.getData().getEvidenceFingerprint();
            } else if ("1".equals(source.getStatus())) {
                downstreamEvidenceFingerprint = requireFrozenDownstream(frozenDownstreamFingerprint);
            }
            facts = Arrays.asList(source.getId(), source.getIssueMonthNo(), source.getProductCode(), source.getNum(),
                    source.getMaterialAttribute(), source.getStatus(), source.getFinishNum(), source.getFactoryCode(),
                    source.getDownstreamPlanId(), source.getDownstreamPlanNo(), source.getDownstreamPlanFingerprint(),
                    downstreamEvidenceFingerprint);
        } else {
            throw new CheckedException("MRP物料[" + mrp.getMatCode() + "]供应来源类型不受支持：" + mrp.getSupplySourceType());
        }
        Map<String, Object> snapshotFacts = new LinkedHashMap<>();
        snapshotFacts.put("mrp", Arrays.asList(mrp.getId(), mrp.getPlanDemandNo(), mrp.getMatCode(), quantity,
                mrp.getStatus(), mrp.getSupplySourceType(), mrp.getSupplySourceId(), mrp.getSupplySourceNo(),
                mrp.getSupplySourceQuantity(), mrp.getSupplySourceStatus()));
        snapshotFacts.put("source", facts);
        String snapshot = JSONUtil.toJsonStr(snapshotFacts);
        String status = "WITHDRAWN".equals(mrp.getSupplySourceStatus()) ? "ALREADY_WITHDRAWN" : "ELIGIBLE";
        return evidence(mrp, status, downstreamEvidenceFingerprint, snapshot, status.equals("ELIGIBLE")
                ? "下层供应未进入执行且无完成事实，可受控撤回" : "下层供应已受控撤回");
    }

    private PlanMrpSupplyWithdrawalDTO.Evidence evidence(PlanMatMrpEntity mrp, String status, String downstreamFingerprint,
                                                         String snapshot, String message) {
        return new PlanMrpSupplyWithdrawalDTO.Evidence().setMrpId(mrp.getId()).setMaterialCode(mrp.getMatCode())
                .setSupplyType(mrp.getSupplySourceType()).setSourceId(mrp.getSupplySourceId())
                .setSourceNo(mrp.getSupplySourceNo()).setSourceQuantity(mrp.getSupplySourceQuantity())
                .setSourceStatus(mrp.getSupplySourceStatus()).setStatus(status)
                .setEvidenceFingerprint(sha256(snapshot)).setDownstreamEvidenceFingerprint(downstreamFingerprint)
                .setEvidenceSnapshot(snapshot).setMessage(message);
    }

    private String requireFrozenDownstream(String fingerprint) {
        requireSupply(StrUtil.isNotBlank(fingerprint) && fingerprint.length() == 64,
                "下层供应执行缺少申请时的64位下游证据指纹");
        return fingerprint;
    }

    private void unlockSelfSupplyAps(PlnIssueEntity source) {
        schedulingOperationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getIssueId, source.getId())
                .set(PlnSchedulingOperationEntity::getLockStatus, false)
                .set(PlnSchedulingOperationEntity::getLockTime, null)
                .set(PlnSchedulingOperationEntity::getScheduleStatus, "WITHDRAWN"));
        schedulingTaskDetailMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getIssueId, source.getId())
                .set(PlnSchedulingTaskDetailEntity::getLockStatus, false)
                .set(PlnSchedulingTaskDetailEntity::getLockTaskId, null)
                .set(PlnSchedulingTaskDetailEntity::getLockTime, null)
                .set(PlnSchedulingTaskDetailEntity::getOperationScheduleStatus, "WITHDRAWN"));
        List<PlnSchedulingBatchAllocationEntity> allocations = schedulingBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, source.getId()));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            requireSupply(schedulingBatchAllocationMapper.selectCount(
                            new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                                    .eq(PlnSchedulingBatchAllocationEntity::getBatchId, allocation.getBatchId())) == 1,
                    "下层自制APS批次撤回时不再独占，禁止释放其他计划资源");
            schedulingBatchMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingBatchEntity>()
                    .eq(PlnSchedulingBatchEntity::getId, allocation.getBatchId())
                    .set(PlnSchedulingBatchEntity::getLockStatus, false)
                    .set(PlnSchedulingBatchEntity::getLockTime, null)
                    .set(PlnSchedulingBatchEntity::getBatchStatus, "WITHDRAWN"));
        }
    }

    private PmsPlanSupplyWithdrawalDTO.Command pmsWithdrawalCommand(PlanMatMrpEntity mrp,
                                                                    PlnIssueMonthEntity source,
                                                                    String requestId, boolean preview,
                                                                    String expectedEvidence, String reason) {
        return new PmsPlanSupplyWithdrawalDTO.Command().setRequestId(requestId)
                .setSourceIssueMonthId(source.getId()).setPmsPlanId(source.getDownstreamPlanId())
                .setPmsPlanNo(source.getDownstreamPlanNo()).setPlanDemandNo(source.getIssueMonthNo())
                .setMaterialCode(source.getProductCode()).setExpectedQuantity(source.getNum())
                .setSupplyType(source.getMaterialAttribute()).setFactoryCode(mrp.getFactoryCode())
                .setPreview(preview).setExpectedEvidenceFingerprint(expectedEvidence).setReason(reason);
    }

    private PlnIssueEntity issueBySource(PlanMatMrpEntity mrp, boolean lock) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, mrp.getSupplySourceId());
        if (lock) query.last("FOR UPDATE");
        PlnIssueEntity source = plnIssueMapper.selectOne(query);
        requireSupply(source != null, "下层自制供应来源不存在");
        return source;
    }

    private PlnIssueMonthEntity issueMonthBySource(PlanMatMrpEntity mrp, boolean lock) {
        LambdaQueryWrapper<PlnIssueMonthEntity> query = new LambdaQueryWrapper<PlnIssueMonthEntity>()
                .eq(PlnIssueMonthEntity::getId, mrp.getSupplySourceId());
        if (lock) query.last("FOR UPDATE");
        PlnIssueMonthEntity source = plnIssueMonthMapper.selectOne(query);
        requireSupply(source != null, "采购/外协供应来源不存在");
        return source;
    }

    private String requireText(String value, int max, String label) {
        String result = value == null ? null : value.trim();
        if (StrUtil.isBlank(result) || result.length() > max)
            throw new CheckedException(label + "不能为空且不能超过" + max + "字");
        return result;
    }

    private String limitText(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private void requireSupply(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("下层供应撤回证据指纹生成失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean releaseForWithdrawal(Long mrpId, String requestId, String reason) {
        if (mrpId == null || StrUtil.isBlank(requestId) || StrUtil.isBlank(reason)) {
            throw new CheckedException("MRP撤回释放必须提供MRP、请求号和原因");
        }
        PlanMatMrpEntity mrp = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, mrpId).last("FOR UPDATE"), false);
        if (mrp == null) throw new CheckedException("待撤回MRP不存在：" + mrpId);
        if ("RELEASED".equals(mrp.getMainMaterialReservationStatus())
                && (StrUtil.isBlank(mrp.getMaterialReservationNo())
                || "RELEASED".equals(mrp.getMaterialReservationStatus()))
                && Boolean.FALSE.equals(mrp.getStatus())) {
            return Boolean.TRUE;
        }
        releaseWithdrawalReservation(mrp, "PLAN_MRP_MAIN", requestId + "-M", reason);
        releaseWithdrawalReservation(mrp, "PLAN_MRP_SUBSTITUTE", requestId + "-S", reason);
        mrp.setStatus(Boolean.FALSE);
        if (StrUtil.isNotBlank(mrp.getMainMaterialReservationNo())) mrp.setMainMaterialReservationStatus("RELEASED");
        if (StrUtil.isNotBlank(mrp.getMaterialReservationNo())) mrp.setMaterialReservationStatus("RELEASED");
        if (StrUtil.isNotBlank(mrp.getMrpRunNo())) mrp.setInventoryPeggingStatus("RELEASED");
        if (!updateById(mrp)) throw new CheckedException("MRP撤回释放状态写回失败：" + mrpId);
        inventoryPeggingService.synchronizeStatus(mrpId, "RELEASED");
        return Boolean.TRUE;
    }

    private void releaseWithdrawalReservation(PlanMatMrpEntity mrp, String sourceType,
                                              String requestId, String reason) {
        boolean exists = "PLAN_MRP_MAIN".equals(sourceType)
                ? StrUtil.isNotBlank(mrp.getMainMaterialReservationNo())
                : StrUtil.isNotBlank(mrp.getMaterialReservationNo());
        if (!exists) return;
        WmsMaterialReservationReleaseDTO command = new WmsMaterialReservationReleaseDTO();
        command.setRequestId(requestId.length() > 64 ? requestId.substring(0, 64) : requestId);
        command.setSourceType(sourceType);
        command.setSourceId(mrp.getId());
        command.setReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        R<Boolean> response = remoteMaterialReservationService.release(command);
        if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
            throw new CheckedException("WMS撤回释放" + sourceType + "预留失败："
                    + (response == null ? "无响应" : response.getMsg()));
        }
    }

    @Override
    public PlanMrpShortageAnalysisDTO shortageAnalysis(PlanMrpShortageQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) {
            throw new CheckedException("Agent缺料分析必须指定工厂");
        }
        String actualFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isBlank(actualFactory) || !Objects.equals(actualFactory, query.getFactoryCode())) {
            throw new CheckedException("Agent缺料分析工厂与当前实际工厂不一致");
        }
        if (StrUtil.isAllBlank(query.getPlanDemandNo(), query.getSaleOrderCode())) {
            throw new CheckedException("Agent缺料分析必须指定计划需求号或销售订单号");
        }
        LambdaQueryWrapper<PlanMatMrpEntity> wrapper = new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getFactoryCode, query.getFactoryCode())
                .eq(StrUtil.isNotBlank(query.getPlanDemandNo()), PlanMatMrpEntity::getPlanDemandNo, query.getPlanDemandNo())
                .eq(StrUtil.isNotBlank(query.getSaleOrderCode()), PlanMatMrpEntity::getSaleOrderCode, query.getSaleOrderCode())
                .orderByAsc(PlanMatMrpEntity::getDemandDate)
                .orderByAsc(PlanMatMrpEntity::getMatCode);
        List<PlanMatMrpEntity> records = list(wrapper);
        enrichReservationStatuses(records);

        Set<String> demandNos = records.stream().map(PlanMatMrpEntity::getPlanDemandNo)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> materialCodes = records.stream().map(PlanMatMrpEntity::getMatCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        Map<String, List<PlnIssueEntity>> productionSupply = demandNos.isEmpty() ? Map.of()
                : plnIssueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                        .eq(PlnIssueEntity::getFactoryCode, query.getFactoryCode())
                        .in(PlnIssueEntity::getIssueNo, demandNos)
                        .in(!materialCodes.isEmpty(), PlnIssueEntity::getProductCode, materialCodes)).stream()
                .collect(Collectors.groupingBy(row -> supplyKey(row.getIssueNo(), row.getProductCode())));
        Map<Long, LocalDateTime> productionCompletionTimes = loadProductionCompletionTimes(productionSupply);
        Map<String, List<PmsAgentSupplyEvidenceDTO>> externalSupply = Map.of();
        boolean pmsEvidenceAvailable = true;
        boolean needsPmsEvidence = records.stream().anyMatch(row -> Set.of(MatAttributeEnum.PMS.getCode(), MatAttributeEnum.OUT.getCode()).contains(row.getType()));
        if (needsPmsEvidence && !demandNos.isEmpty()) {
            try {
                PmsAgentSupplyEvidenceQueryDTO evidenceQuery = new PmsAgentSupplyEvidenceQueryDTO();
                evidenceQuery.setFactoryCode(query.getFactoryCode());
                evidenceQuery.setOrderNos(new ArrayList<>(demandNos));
                R<List<PmsAgentSupplyEvidenceDTO>> response = remotePmsPlanService.agentSupplyEvidence(evidenceQuery);
                if (response == null || !response.isOk() || response.getData() == null) {
                    pmsEvidenceAvailable = false;
                } else {
                    externalSupply = response.getData().stream()
                            .filter(row -> Objects.equals(query.getFactoryCode(), row.getFactoryCode()))
                            .collect(Collectors.groupingBy(row -> supplyKey(row.getOrderNo(), row.getMaterialCode())));
                }
            } catch (Exception ignored) {
                pmsEvidenceAvailable = false;
            }
        }
        final Map<String, List<PmsAgentSupplyEvidenceDTO>> pmsSupply = externalSupply;
        final boolean pmsAvailable = pmsEvidenceAvailable;
        boolean operationImpactRequired = query.getSupplyEvidenceVersion() == null || query.getSupplyEvidenceVersion() >= 4;
        OperationImpactEvidence impactEvidence = operationImpactRequired
                ? loadOperationImpactEvidence(query.getFactoryCode(), demandNos, materialCodes)
                : new OperationImpactEvidence(Map.of(), Set.of(), Set.of(), true);

        List<PlanMrpShortageLineDTO> lines = records.stream().map(row -> {
            PlanMrpShortageLineDTO line = new PlanMrpShortageLineDTO();
            line.setMrpId(row.getId());
            line.setPlanDemandNo(row.getPlanDemandNo());
            line.setSaleOrderCode(row.getSaleOrderCode());
            line.setMaterialCode(row.getMatCode());
            line.setMaterialName(row.getMatName());
            line.setMaterialUnit(row.getMatUnit());
            line.setMaterialSpec(row.getMatSpec());
            line.setDemandWarehouseCode(row.getDemandWarehouseCode());
            line.setDemandWarehouseName(row.getDemandWarehouseName());
            line.setGrossDemandNum(row.getGrossDemandNum());
            line.setAllocatedNum(row.getStockAllocatedNum());
            line.setMrpRunNo(row.getMrpRunNo());
            line.setInventoryAllocationPriority(row.getInventoryAllocationPriority());
            line.setInventoryAllocationSequence(row.getInventoryAllocationSequence());
            line.setInventoryPeggedNum(row.getInventoryPeggedNum());
            line.setInventoryPeggingStatus(row.getInventoryPeggingStatus());
            line.setInventoryPeggingFingerprint(row.getInventoryPeggingFingerprint());
            line.setShortageNum(Optional.ofNullable(row.getRemainingDemandNum()).orElse(BigDecimal.ZERO));
            line.setReadinessStatus(row.getReadinessStatus());
            line.setSubstituteStatus(row.getSubstituteStatus());
            line.setSubstituteSuggestion(row.getSubstituteSuggestion());
            line.setReservationNo(row.getMaterialReservationNo());
            line.setReservationStatus(row.getMaterialReservationStatus());
            line.setReservationHealthy(row.getMaterialReservationHealthy());
            line.setReservationRisk(row.getMaterialReservationRisk());
            line.setMainReservationNo(row.getMainMaterialReservationNo());
            line.setMainReservationStatus(row.getMainMaterialReservationStatus());
            line.setMainReservationHealthy(row.getMainMaterialReservationHealthy());
            line.setMainReservationRisk(row.getMainMaterialReservationRisk());
            line.setDemandDate(row.getDemandDate());
            line.setPlannedReleaseDate(row.getOrderDate());
            line.setSupplyType(row.getType());
            enrichSupplyAdvice(row, line, productionSupply.getOrDefault(supplyKey(row.getPlanDemandNo(), row.getMatCode()), List.of()),
                    productionCompletionTimes,
                    pmsSupply.getOrDefault(supplyKey(row.getPlanDemandNo(), row.getMatCode()), List.of()), pmsAvailable,
                    query.getSupplyEvidenceVersion() == null || query.getSupplyEvidenceVersion() >= 3);
            if (operationImpactRequired) enrichOperationImpact(row, line, impactEvidence);
            return line;
        }).toList();
        boolean transferEvidenceRequired = query.getSupplyEvidenceVersion() != null
                && query.getSupplyEvidenceVersion() >= 6;
        if (transferEvidenceRequired) {
            enrichCrossWarehouseTransfers(query.getFactoryCode(), lines, query.getSupplyEvidenceVersion() >= 7);
        }
        boolean purchaseEvidenceRequired = query.getSupplyEvidenceVersion() != null
                && query.getSupplyEvidenceVersion() >= 8;
        if (purchaseEvidenceRequired) {
            Map<Long, PlanMatMrpEntity> recordMap = records.stream()
                    .collect(Collectors.toMap(PlanMatMrpEntity::getId, Function.identity(), (left, right) -> left));
            enrichPurchaseOptions(query.getFactoryCode(), lines, recordMap);
        }

        PlanMrpShortageAnalysisDTO result = new PlanMrpShortageAnalysisDTO();
        result.setDataAsOf(LocalDateTime.now());
        result.setScopeDescription("工厂=" + query.getFactoryCode()
                + (StrUtil.isBlank(query.getPlanDemandNo()) ? "" : "，计划=" + query.getPlanDemandNo())
                + (StrUtil.isBlank(query.getSaleOrderCode()) ? "" : "，订单=" + query.getSaleOrderCode()));
        result.setMaterialCount(lines.size());
        result.setShortageCount((int) lines.stream().filter(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) > 0).count());
        result.setSubstitutePendingCount((int) lines.stream().filter(line -> Set.of("PENDING", "POLICY_CONFLICT",
                        MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode())
                .contains(StrUtil.blankToDefault(line.getSubstituteStatus(), "NONE"))).count());
        result.setReservationRiskCount((int) lines.stream().filter(line -> Boolean.FALSE.equals(line.getReservationHealthy())
                || StrUtil.isNotBlank(line.getReservationRisk())
                || Boolean.FALSE.equals(line.getMainReservationHealthy())
                || StrUtil.isNotBlank(line.getMainReservationRisk())).count());
        result.setActionRequiredCount((int) lines.stream().filter(line -> !"NONE".equals(line.getRecommendedActionCode())).count());
        result.setSupplyEvidenceGapCount((int) lines.stream().filter(line -> Boolean.FALSE.equals(line.getSupplyEvidenceComplete())).count());
        result.setOperationImpactGapCount((int) lines.stream().filter(line -> Boolean.FALSE.equals(line.getOperationImpactEvidenceComplete())).count());
        result.setTransferEvidenceGapCount(transferEvidenceRequired
                ? (int) lines.stream().filter(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) > 0)
                .filter(line -> Boolean.FALSE.equals(line.getCrossWarehouseTransferEvidenceComplete())).count() : 0);
        result.setTransferSuggestedCount(transferEvidenceRequired
                ? (int) lines.stream().filter(line -> Optional.ofNullable(line.getSuggestedTransferNum())
                .orElse(BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0).count() : 0);
        result.setPurchaseReadyCount(purchaseEvidenceRequired
                ? (int) lines.stream().filter(line -> Optional.ofNullable(line.getRecommendedPurchaseNum())
                        .orElse(BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0)
                .filter(line -> Boolean.TRUE.equals(line.getPurchaseEvidenceComplete())).count() : 0);
        result.setPurchaseEvidenceGapCount(purchaseEvidenceRequired
                ? (int) lines.stream().filter(line -> Optional.ofNullable(line.getPurchaseUncoveredNum())
                        .orElse(BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0)
                .filter(line -> Boolean.FALSE.equals(line.getPurchaseEvidenceComplete())).count() : 0);
        boolean shortageExists = lines.stream().anyMatch(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) > 0);
        boolean unavailable = lines.stream().filter(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) > 0)
                .anyMatch(line -> line.getExpectedSupplyTime() == null);
        result.setMaterialAvailabilityStatus(!shortageExists ? "READY" : unavailable ? "BLOCKED" : "PLANNED");
        if (shortageExists && !unavailable) {
            result.setMaterialAvailableDate(lines.stream().map(PlanMrpShortageLineDTO::getExpectedSupplyDate)
                    .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
            result.setMaterialAvailableTime(lines.stream().map(PlanMrpShortageLineDTO::getExpectedSupplyTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        }
        result.setLines(lines);
        return result;
    }

    private void enrichCrossWarehouseTransfers(String factoryCode, List<PlanMrpShortageLineDTO> lines,
                                               boolean routePolicyRequired) {
        lines.stream().filter(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) <= 0).forEach(line -> {
            line.setCrossWarehouseTransferStatus("NOT_REQUIRED");
            line.setCrossWarehouseTransferEvidenceComplete(Boolean.TRUE);
            line.setTargetInTransitTransferNum(BigDecimal.ZERO);
            line.setAppliedInTransitTransferNum(BigDecimal.ZERO);
            line.setSuggestedTransferNum(BigDecimal.ZERO);
            line.setTransferUncoveredNum(BigDecimal.ZERO);
            line.setTransferSuggestions(List.of());
        });
        List<PlanMrpShortageLineDTO> shortages = lines.stream()
                .filter(line -> line.getShortageNum().compareTo(BigDecimal.ZERO) > 0).toList();
        if (shortages.isEmpty()) return;
        List<PlanMrpShortageLineDTO> scoped = shortages.stream()
                .filter(line -> StrUtil.isNotBlank(line.getDemandWarehouseCode())).toList();
        shortages.stream().filter(line -> StrUtil.isBlank(line.getDemandWarehouseCode())).forEach(line -> {
            line.setCrossWarehouseTransferStatus("DEMAND_WAREHOUSE_MISSING");
            line.setCrossWarehouseTransferEvidenceComplete(Boolean.FALSE);
            line.setTransferSuggestions(List.of());
        });
        if (scoped.isEmpty()) return;
        WmsCrossWarehouseTransferDTO.Query transferQuery = new WmsCrossWarehouseTransferDTO.Query();
        transferQuery.setFactoryCode(factoryCode);
        transferQuery.setRoutePolicyRequired(routePolicyRequired);
        transferQuery.setRequirements(scoped.stream().map(line -> {
            WmsCrossWarehouseTransferDTO.Requirement requirement = new WmsCrossWarehouseTransferDTO.Requirement();
            requirement.setReferenceId(String.valueOf(line.getMrpId()));
            requirement.setTargetWarehouseCode(line.getDemandWarehouseCode());
            requirement.setMaterialCode(line.getMaterialCode());
            requirement.setRequiredNum(line.getShortageNum());
            requirement.setDemandDate(line.getDemandDate());
            return requirement;
        }).toList());
        R<List<WmsCrossWarehouseTransferDTO.Recommendation>> response;
        try {
            response = remoteAllocationBillService.transferRecommendations(transferQuery);
        } catch (Exception ex) {
            response = null;
        }
        if (response == null || !response.isOk() || response.getData() == null) {
            scoped.forEach(line -> {
                line.setCrossWarehouseTransferStatus("WMS_EVIDENCE_UNAVAILABLE");
                line.setCrossWarehouseTransferEvidenceComplete(Boolean.FALSE);
                line.setTransferSuggestions(List.of());
            });
            return;
        }
        Map<String, WmsCrossWarehouseTransferDTO.Recommendation> byReference = response.getData().stream()
                .filter(item -> StrUtil.isNotBlank(item.getReferenceId()))
                .collect(Collectors.toMap(WmsCrossWarehouseTransferDTO.Recommendation::getReferenceId,
                        Function.identity(), (left, right) -> left));
        for (PlanMrpShortageLineDTO line : scoped) {
            WmsCrossWarehouseTransferDTO.Recommendation recommendation = byReference.get(String.valueOf(line.getMrpId()));
            if (recommendation == null || Boolean.FALSE.equals(recommendation.getEvidenceComplete())) {
                line.setCrossWarehouseTransferStatus("WMS_EVIDENCE_INCOMPLETE");
                line.setCrossWarehouseTransferEvidenceComplete(Boolean.FALSE);
                line.setTransferSuggestions(List.of());
                continue;
            }
            line.setCrossWarehouseTransferStatus(recommendation.getStatus());
            line.setCrossWarehouseTransferEvidenceComplete(Boolean.TRUE);
            line.setTargetInTransitTransferNum(recommendation.getTargetInTransitNum());
            line.setAppliedInTransitTransferNum(recommendation.getAppliedInTransitNum());
            line.setSuggestedTransferNum(recommendation.getSuggestedTransferNum());
            line.setTransferUncoveredNum(recommendation.getUncoveredNum());
            line.setTransferEvidenceFingerprint(recommendation.getEvidenceFingerprint());
            line.setTransferSuggestions(Optional.ofNullable(recommendation.getSourceCandidates()).orElse(List.of())
                    .stream().map(this::toPlanTransferSuggestion).toList());
        }
    }

    private PlanMrpTransferSuggestionDTO toPlanTransferSuggestion(
            WmsCrossWarehouseTransferDTO.SourceCandidate source) {
        PlanMrpTransferSuggestionDTO result = new PlanMrpTransferSuggestionDTO();
        result.setSourceWarehouseCode(source.getSourceWarehouseCode());
        result.setSourceWarehouseName(source.getSourceWarehouseName());
        result.setUsablePhysicalNum(source.getUsablePhysicalNum());
        result.setActiveReservedNum(source.getActiveReservedNum());
        result.setSafetyStockNum(source.getSafetyStockNum());
        result.setTransferableNum(source.getTransferableNum());
        result.setSuggestedNum(source.getSuggestedNum());
        result.setRoutePolicyId(source.getRoutePolicyId());
        result.setRoutePolicyCode(source.getRoutePolicyCode());
        result.setRoutePolicyName(source.getRoutePolicyName());
        result.setRoutePolicyFingerprint(source.getRoutePolicyFingerprint());
        result.setTransportMode(source.getTransportMode());
        result.setUrgencyLevel(source.getUrgencyLevel());
        result.setRoutePolicyUrgencyLevel(source.getRoutePolicyUrgencyLevel());
        result.setCalendarMode(source.getCalendarMode());
        result.setRoutePolicyMatched(source.getRoutePolicyMatched());
        result.setRoutePolicyGap(source.getRoutePolicyGap());
        result.setTransferLeadHours(source.getTransferLeadHours());
        result.setTransferLeadMinutes(source.getTransferLeadMinutes());
        result.setEstimatedArrivalTime(source.getEstimatedArrivalTime());
        result.setAllocations(Optional.ofNullable(source.getAllocations()).orElse(List.of()).stream().map(item -> {
            PlanMrpTransferSuggestionDTO.StockAllocation allocation = new PlanMrpTransferSuggestionDTO.StockAllocation();
            allocation.setStockId(item.getStockId());
            allocation.setSiteCode(item.getSiteCode());
            allocation.setSiteName(item.getSiteName());
            allocation.setContainerCode(item.getContainerCode());
            allocation.setBatchNo(item.getBatchNo());
            allocation.setSupplierCode(item.getSupplierCode());
            allocation.setValidEndDate(item.getValidEndDate());
            allocation.setStockInTime(item.getStockInTime());
            allocation.setAvailableNum(item.getAvailableNum());
            allocation.setSuggestedNum(item.getSuggestedNum());
            return allocation;
        }).toList());
        return result;
    }

    private void enrichPurchaseOptions(String factoryCode, List<PlanMrpShortageLineDTO> lines,
                                       Map<Long, PlanMatMrpEntity> records) {
        for (PlanMrpShortageLineDTO line : lines) {
            line.setMinOrderQuantity(0).setLotSize(1).setPurchaseUncoveredNum(BigDecimal.ZERO)
                    .setRecommendedPurchaseNum(BigDecimal.ZERO).setPurchaseEvidenceComplete(Boolean.TRUE)
                    .setPurchaseEvidenceStatus("NOT_REQUIRED").setPurchaseCandidates(List.of());
            PlanMatMrpEntity mrp = records.get(line.getMrpId());
            if (mrp == null || line.getShortageNum() == null
                    || line.getShortageNum().compareTo(BigDecimal.ZERO) <= 0) continue;
            if (!MatAttributeEnum.PMS.getCode().equals(mrp.getType())) {
                line.setPurchaseEvidenceStatus("NOT_PURCHASE_MATERIAL");
                continue;
            }
            line.setMinOrderQuantity(Math.max(0, Objects.requireNonNullElse(mrp.getMinOrderQuantity(), 0)));
            line.setLotSize(Math.max(1, Objects.requireNonNullElse(mrp.getLotSize(), 1)));
            BigDecimal uncovered = optional(line.getShortageNum())
                    .subtract(optional(line.getOpenOrderedSupplyNum()))
                    .subtract(optional(line.getPendingApprovalSupplyNum()))
                    .subtract(optional(line.getAppliedInTransitTransferNum())).max(BigDecimal.ZERO);
            line.setPurchaseUncoveredNum(uncovered);
            if (uncovered.compareTo(BigDecimal.ZERO) <= 0) {
                line.setPurchaseEvidenceStatus(optional(line.getPendingApprovalSupplyNum()).compareTo(BigDecimal.ZERO) > 0
                        ? "PURCHASE_APPROVAL_PENDING" : "PURCHASE_COVERED");
                if (optional(line.getPendingApprovalSupplyNum()).compareTo(BigDecimal.ZERO) > 0
                        && "SUPPLEMENT_PURCHASE".equals(line.getRecommendedActionCode())) {
                    line.setRecommendedActionCode("MONITOR_PURCHASE_APPROVAL")
                            .setRecommendedAction("已有采购订单草稿或正在审批，等待PMS正式审批结果后重新采集齐套事实");
                }
                continue;
            }
            BigDecimal recommended = adjustLotSize(mrp, uncovered);
            line.setRecommendedPurchaseNum(recommended);
            PmsAgentPurchaseCandidateQueryDTO query = new PmsAgentPurchaseCandidateQueryDTO();
            query.setFactoryCode(factoryCode);
            query.setPlanDemandNo(mrp.getPlanDemandNo());
            query.setMaterialCode(mrp.getMatCode());
            query.setDemandDate(mrp.getDemandDate());
            query.setSuggestedPurchaseNum(recommended);
            query.setPmsPlanNos(splitCodes(line.getSupplyPlanNos()));
            R<PmsAgentPurchaseOptionsDTO> response;
            try {
                response = remotePmsAgentPurchaseService.options(query);
            } catch (Exception ex) {
                response = null;
            }
            if (response == null || !response.isOk() || response.getData() == null) {
                line.setPurchaseEvidenceComplete(Boolean.FALSE).setPurchaseEvidenceStatus("PMS_PURCHASE_OPTIONS_UNAVAILABLE")
                        .setPurchaseEvidenceGap("PMS采购候选服务不可用，禁止猜测供应商、价格或交期");
                continue;
            }
            PmsAgentPurchaseOptionsDTO options = response.getData();
            line.setPurchaseEvidenceComplete(Boolean.TRUE.equals(options.getEvidenceComplete()))
                    .setPurchaseEvidenceStatus(options.getStatus()).setPurchaseEvidenceGap(options.getGapMessage())
                    .setPurchaseCandidates(Optional.ofNullable(options.getCandidates()).orElse(List.of())
                            .stream().map(this::toPlanPurchaseCandidate).toList());
        }
    }

    private PlanMrpPurchaseCandidateDTO toPlanPurchaseCandidate(PmsAgentPurchaseCandidateDTO source) {
        PlanMrpPurchaseCandidateDTO target = new PlanMrpPurchaseCandidateDTO();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private OperationImpactEvidence loadOperationImpactEvidence(String factoryCode, Set<String> demandNos,
                                                                Set<String> materialCodes) {
        if (demandNos.isEmpty() || materialCodes.isEmpty()) {
            return new OperationImpactEvidence(Map.of(), Set.of(), Set.of(), true);
        }
        List<PlnSchedulingOperationEntity> all = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, factoryCode)
                        .in(PlnSchedulingOperationEntity::getIssueNo, demandNos)
                        .orderByDesc(PlnSchedulingOperationEntity::getTaskId)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        if (all.isEmpty()) {
            return new OperationImpactEvidence(Map.of(), Set.of(), Set.of(), true);
        }
        Set<Long> candidateTaskIds = all.stream().map(PlnSchedulingOperationEntity::getTaskId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> officialTaskIds = schedulingTaskMapper.selectList(
                        new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                                .eq(PlnSchedulingTaskEntity::getFactoryCode, factoryCode)
                                .in(PlnSchedulingTaskEntity::getId, candidateTaskIds)
                                .eq(PlnSchedulingTaskEntity::getMode, "official")
                                .in(PlnSchedulingTaskEntity::getTaskStatus, List.of("SUCCESS", "PARTIAL")))
                .stream().map(PlnSchedulingTaskEntity::getId).collect(Collectors.toSet());
        Map<String, List<PlnSchedulingOperationEntity>> selected = new LinkedHashMap<>();
        Map<String, List<PlnSchedulingOperationEntity>> byIssue = all.stream()
                .filter(item -> StrUtil.isNotBlank(item.getIssueNo()))
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getIssueNo, LinkedHashMap::new, Collectors.toList()));
        byIssue.forEach((issueNo, rows) -> {
            Long lockedTaskId = rows.stream().filter(item -> Boolean.TRUE.equals(item.getLockStatus()))
                    .map(PlnSchedulingOperationEntity::getTaskId).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
            Long taskId = lockedTaskId != null ? lockedTaskId : rows.stream()
                    .filter(item -> officialTaskIds.contains(item.getTaskId()))
                    .map(PlnSchedulingOperationEntity::getTaskId).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
            if (taskId != null) {
                selected.put(issueNo, rows.stream().filter(item -> Objects.equals(taskId, item.getTaskId()))
                        .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq,
                                Comparator.nullsLast(Integer::compareTo))).toList());
            }
        });
        Set<String> routes = selected.values().stream().flatMap(List::stream)
                .map(PlnSchedulingOperationEntity::getRouteCode).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        if (routes.isEmpty()) {
            return new OperationImpactEvidence(selected, all.stream().map(PlnSchedulingOperationEntity::getIssueNo)
                    .filter(StrUtil::isNotBlank).collect(Collectors.toSet()), Set.of(), true);
        }
        try {
            MdmRouteMaterialAssignmentQueryDTO assignmentQuery = new MdmRouteMaterialAssignmentQueryDTO();
            assignmentQuery.setFactoryCode(factoryCode);
            assignmentQuery.setRouteCodes(routes);
            assignmentQuery.setMaterialCodes(materialCodes);
            R<List<MdmProcessProcedureMatEntity>> response = remoteProcessRouteService.getMaterialAssignments(assignmentQuery);
            if (response == null || !response.isOk() || response.getData() == null) {
                return new OperationImpactEvidence(selected, byIssue.keySet(), Set.of(), false);
            }
            Set<String> assignments = response.getData().stream()
                    .map(item -> operationMaterialKey(item.getRouteCode(), item.getProcessCode(), item.getMaterialCode()))
                    .collect(Collectors.toSet());
            return new OperationImpactEvidence(selected, byIssue.keySet(), assignments, true);
        } catch (Exception ignored) {
            return new OperationImpactEvidence(selected, byIssue.keySet(), Set.of(), false);
        }
    }

    private void enrichOperationImpact(PlanMatMrpEntity row, PlanMrpShortageLineDTO line,
                                       OperationImpactEvidence evidence) {
        line.setImpactedOperations(List.of()).setImpactedOperationCount(0).setOperationImpactEvidenceComplete(true);
        if (optional(row.getRemainingDemandNum()).compareTo(BigDecimal.ZERO) <= 0) {
            line.setOperationImpactStatus("NOT_SHORTAGE");
            return;
        }
        List<PlnSchedulingOperationEntity> operations = evidence.operationsByIssue().getOrDefault(row.getPlanDemandNo(), List.of());
        if (operations.isEmpty()) {
            line.setOperationImpactEvidenceComplete(false)
                    .setOperationImpactStatus(evidence.issuesWithAnySchedule().contains(row.getPlanDemandNo())
                            ? "OFFICIAL_SCHEDULE_MISSING" : "APS_NOT_SCHEDULED");
            return;
        }
        if (!evidence.materialAssignmentsVerified()) {
            line.setOperationImpactEvidenceComplete(false).setOperationImpactStatus("MDM_MAPPING_UNAVAILABLE");
            return;
        }
        Set<String> directlyAffected = operations.stream()
                .filter(operation -> evidence.assignmentKeys().contains(operationMaterialKey(
                        operation.getRouteCode(), operation.getProcessCode(), row.getMatCode())))
                .map(PlnSchedulingOperationEntity::getProcessCode).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (directlyAffected.isEmpty()) {
            line.setOperationImpactEvidenceComplete(false).setOperationImpactStatus("MATERIAL_OPERATION_MAPPING_MISSING");
            return;
        }
        Set<String> impacted = new LinkedHashSet<>(directlyAffected);
        boolean changed;
        do {
            changed = false;
            for (PlnSchedulingOperationEntity operation : operations) {
                if (!impacted.contains(operation.getProcessCode()) && splitCodes(operation.getPredecessorCodes()).stream().anyMatch(impacted::contains)) {
                    changed |= impacted.add(operation.getProcessCode());
                }
            }
        } while (changed);
        List<PlanMrpImpactedOperationDTO> result = operations.stream().filter(item -> impacted.contains(item.getProcessCode()))
                .map(this::toImpactedOperation).toList();
        PlanMrpImpactedOperationDTO first = result.get(0);
        line.setOperationImpactStatus("MAPPED")
                .setImpactedOperations(result).setImpactedOperationCount(result.size())
                .setFirstImpactedProcessCode(first.getProcessCode())
                .setFirstImpactedProcessName(first.getProcessName())
                .setFirstImpactedStartTime(first.getScheduledStartTime());
    }

    private PlanMrpImpactedOperationDTO toImpactedOperation(PlnSchedulingOperationEntity item) {
        return new PlanMrpImpactedOperationDTO().setSchedulingTaskId(item.getTaskId()).setIssueId(item.getIssueId())
                .setIssueNo(item.getIssueNo()).setRouteCode(item.getRouteCode()).setOperationSeq(item.getOperationSeq())
                .setProcessCode(item.getProcessCode()).setProcessName(item.getProcessName())
                .setResourceType(item.getResourceType()).setResourceCode(item.getResourceCode()).setResourceName(item.getResourceName())
                .setScheduledStartTime(item.getScheduledStartTime()).setScheduledEndTime(item.getScheduledEndTime())
                .setLockStatus(item.getLockStatus()).setScheduleStatus(item.getScheduleStatus());
    }

    private List<String> splitCodes(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(","))
                .map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private String operationMaterialKey(String routeCode, String processCode, String materialCode) {
        return StrUtil.nullToEmpty(routeCode) + "\u0000" + StrUtil.nullToEmpty(processCode)
                + "\u0000" + StrUtil.nullToEmpty(materialCode);
    }

    /**
     * 只采当前生产计划正式锁定任务的末工序时刻，候选试算不得冒充自制供应。
     */
    private Map<Long, LocalDateTime> loadProductionCompletionTimes(
            Map<String, List<PlnIssueEntity>> productionSupply) {
        List<PlnIssueEntity> issues = productionSupply.values().stream().flatMap(Collection::stream)
                .filter(item -> item.getId() != null && item.getLockTaskId() != null)
                .distinct().toList();
        if (issues.isEmpty()) return Map.of();
        Map<Long, Long> lockTaskByIssue = issues.stream().collect(Collectors.toMap(
                PlnIssueEntity::getId, PlnIssueEntity::getLockTaskId, (left, right) -> left));
        return schedulingOperationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .in(PlnSchedulingOperationEntity::getIssueId, lockTaskByIssue.keySet())
                        .in(PlnSchedulingOperationEntity::getTaskId,
                                new LinkedHashSet<>(lockTaskByIssue.values()))
                        .eq(PlnSchedulingOperationEntity::getLockStatus, Boolean.TRUE)
                        .isNotNull(PlnSchedulingOperationEntity::getScheduledEndTime))
                .stream().filter(operation -> Objects.equals(lockTaskByIssue.get(operation.getIssueId()),
                        operation.getTaskId()))
                .collect(Collectors.toMap(PlnSchedulingOperationEntity::getIssueId,
                        PlnSchedulingOperationEntity::getScheduledEndTime,
                        (left, right) -> left.isAfter(right) ? left : right));
    }

    private void enrichSupplyAdvice(PlanMatMrpEntity row, PlanMrpShortageLineDTO line,
                                    List<PlnIssueEntity> production,
                                    Map<Long, LocalDateTime> productionCompletionTimes,
                                    List<PmsAgentSupplyEvidenceDTO> external,
                                    boolean pmsEvidenceAvailable, boolean formalCommitmentRequired) {
        BigDecimal shortage = optional(row.getRemainingDemandNum());
        line.setPlannedSupplyNum(BigDecimal.ZERO).setOrderedSupplyNum(BigDecimal.ZERO)
                .setArrivedSupplyNum(BigDecimal.ZERO).setOpenOrderedSupplyNum(BigDecimal.ZERO)
                .setPendingApprovalSupplyNum(BigDecimal.ZERO).setConfirmedSupplyNum(BigDecimal.ZERO)
                .setOverdueCommittedSupplyNum(BigDecimal.ZERO)
                .setSupplyEvidenceComplete(true).setRecommendedActionCode("NONE").setRecommendedAction("无需处置");
        if (shortage.compareTo(BigDecimal.ZERO) <= 0) {
            line.setSupplyPlanStatus("READY");
            return;
        }
        String substituteStatus = StrUtil.blankToDefault(row.getSubstituteStatus(), "NONE");
        if ("POLICY_CONFLICT".equals(substituteStatus)) {
            line.setRecommendedActionCode("FIX_SUBSTITUTE_POLICY").setRecommendedAction("修复BOM多路径替代策略冲突后重新执行MRP");
        } else if (MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode().equals(substituteStatus)) {
            line.setRecommendedActionCode("CONTROLLED_MRP_RECALC")
                    .setRecommendedAction("自制中间件替代需先撤回下层供应，再按批准后的BOM/工艺受控重算MRP");
        } else if ("PENDING".equals(substituteStatus)) {
            line.setRecommendedActionCode("REVIEW_SUBSTITUTE").setRecommendedAction("复核并审批本次MRP冻结的替代建议");
        } else if (Boolean.FALSE.equals(row.getMaterialReservationHealthy()) || StrUtil.isNotBlank(row.getMaterialReservationRisk())
                || Boolean.FALSE.equals(row.getMainMaterialReservationHealthy())
                || StrUtil.isNotBlank(row.getMainMaterialReservationRisk())) {
            line.setRecommendedActionCode("RECONCILE_RESERVATION").setRecommendedAction("处理WMS主料/替代料预留失效、仓库或合规差异后重新采集事实");
        }
        if (MatAttributeEnum.MES.getCode().equals(row.getType())) {
            enrichProductionSupply(row, line, production, productionCompletionTimes, shortage);
        } else if (Set.of(MatAttributeEnum.PMS.getCode(), MatAttributeEnum.OUT.getCode()).contains(row.getType())) {
            enrichExternalSupply(row, line, external, shortage, pmsEvidenceAvailable, formalCommitmentRequired);
        } else {
            line.setSupplyPlanStatus("SUPPLY_TYPE_UNKNOWN").setSupplyEvidenceComplete(false);
            if ("NONE".equals(line.getRecommendedActionCode())) {
                line.setRecommendedActionCode("FIX_MATERIAL_MASTER").setRecommendedAction("维护物料采购/自制/外协属性后重新执行MRP");
            }
        }
    }

    private void enrichProductionSupply(PlanMatMrpEntity row, PlanMrpShortageLineDTO line,
                                        List<PlnIssueEntity> production,
                                        Map<Long, LocalDateTime> productionCompletionTimes,
                                        BigDecimal shortage) {
        BigDecimal planned = production.stream().map(PlnIssueEntity::getNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = production.stream().map(PlnIssueEntity::getFinishNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal releasedNum = production.stream().filter(issue -> Set.of("1", "2", "3").contains(issue.getStatus()))
                .map(PlnIssueEntity::getNum).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal open = releasedNum.subtract(completed).max(BigDecimal.ZERO);
        line.setPlannedSupplyNum(planned).setOrderedSupplyNum(releasedNum).setArrivedSupplyNum(completed)
                .setOpenOrderedSupplyNum(open).setSupplyPlanNos(production.stream().map(PlnIssueEntity::getIssueNo).filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(",")));
        boolean released = production.stream().anyMatch(issue -> !"0".equals(issue.getStatus()) && !"4".equals(issue.getStatus()));
        SupplyMoment completion = productionCoverageTime(production, productionCompletionTimes, shortage);
        boolean completedAfterMrp = completed.compareTo(BigDecimal.ZERO) > 0 && production.stream()
                .filter(issue -> optional(issue.getFinishNum()).compareTo(BigDecimal.ZERO) > 0)
                .anyMatch(issue -> factAfterMrp(row.getCreateTime(), issue.getUpdateTime()));
        if (completedAfterMrp) {
            line.setSupplyPlanStatus("PRODUCTION_COMPLETED_RECONCILE")
                    .setRecommendedActionCode("REFRESH_MRP_RECONCILE")
                    .setRecommendedAction("已有自制完工量但MRP仍显示缺口，先完成入库/库存对账并重算MRP，禁止直接补工单");
        } else if (production.isEmpty()) line.setSupplyPlanStatus("NO_PRODUCTION_ORDER");
        else if (!released) line.setSupplyPlanStatus("PRODUCTION_NOT_RELEASED");
        else if (open.compareTo(shortage) < 0) line.setSupplyPlanStatus("PRODUCTION_PARTIAL_COVERAGE");
        else if (completion == null) line.setSupplyPlanStatus("PRODUCTION_TIME_MISSING");
        else {
            line.setSupplyPlanStatus("PRODUCTION_COVERED");
            line.setExpectedSupplyDate(completion.time().toLocalDate()).setExpectedSupplyTime(completion.time())
                    .setSupplyTimePrecision(completion.precision())
                    .setSupplyDateBasis("MINUTE".equals(completion.precision())
                            ? "APS_SCHEDULED_END_TIME" : "APS_SCHEDULED_END_DATE_END");
        }
        if ("NONE".equals(line.getRecommendedActionCode())) {
            if (production.isEmpty() || !released)
                line.setRecommendedActionCode("RELEASE_PRODUCTION_ORDER").setRecommendedAction("下达自制供应工单并完成有限产能排程");
            else if (open.compareTo(shortage) < 0)
                line.setRecommendedActionCode("SUPPLEMENT_PRODUCTION_ORDER").setRecommendedAction("补充自制供应数量并评估受影响工序");
            else if (completion == null)
                line.setRecommendedActionCode("SCHEDULE_PRODUCTION_ORDER").setRecommendedAction("为自制供应工单补齐有限产能排程完工时刻");
            else if (row.getDemandDate() != null && completion.time().toLocalDate().isAfter(row.getDemandDate()))
                line.setRecommendedActionCode("EXPEDITE_PRODUCTION").setRecommendedAction("自制预计完工晚于需求日，评估加急或局部重排");
            else
                line.setRecommendedActionCode("MONITOR_PRODUCTION").setRecommendedAction("跟踪自制供应工单按期完工并入库");
        }
    }

    private void enrichExternalSupply(PlanMatMrpEntity row, PlanMrpShortageLineDTO line,
                                      List<PmsAgentSupplyEvidenceDTO> external, BigDecimal shortage,
                                      boolean evidenceAvailable, boolean formalCommitmentRequired) {
        if (!evidenceAvailable) {
            line.setSupplyPlanStatus("PMS_EVIDENCE_UNAVAILABLE").setSupplyEvidenceComplete(false);
            if ("NONE".equals(line.getRecommendedActionCode())) {
                line.setRecommendedActionCode("RESTORE_PMS_EVIDENCE").setRecommendedAction("恢复PMS供应证据服务后重新采集，禁止猜测齐料日期");
            }
            return;
        }
        BigDecimal planned = external.stream().map(PmsAgentSupplyEvidenceDTO::getPlannedNum).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ordered = external.stream().map(PmsAgentSupplyEvidenceDTO::getOrderedNum).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal arrived = external.stream().map(PmsAgentSupplyEvidenceDTO::getArrivedNum).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal open = external.stream().map(PmsAgentSupplyEvidenceDTO::getOpenApprovedOrderedNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingApproval = external.stream().map(PmsAgentSupplyEvidenceDTO::getPendingApprovalNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal confirmed = external.stream().map(PmsAgentSupplyEvidenceDTO::getConfirmedCommittedNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overdueCommitted = external.stream()
                .flatMap(item -> Optional.ofNullable(item.getCommitments()).orElse(List.of()).stream())
                .filter(item -> commitmentTime(item) != null && commitmentTime(item).isBefore(LocalDateTime.now()))
                .map(PmsSupplyCommitmentEvidenceDTO::getCommittedNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        line.setPlannedSupplyNum(planned).setOrderedSupplyNum(ordered).setArrivedSupplyNum(arrived).setOpenOrderedSupplyNum(open)
                .setPendingApprovalSupplyNum(pendingApproval)
                .setConfirmedSupplyNum(confirmed).setOverdueCommittedSupplyNum(overdueCommitted)
                .setSupplyPlanNos(external.stream().map(PmsAgentSupplyEvidenceDTO::getPmsPlanNo).filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(",")))
                .setSupplierCommitmentStatus(commitmentStatus(external))
                .setSupplierCommitmentNos(external.stream().flatMap(item -> Optional.ofNullable(item.getCommitments()).orElse(List.of()).stream())
                        .map(PmsSupplyCommitmentEvidenceDTO::getCommitmentNo).filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(",")));
        SupplyMoment coverage = formalCommitmentRequired
                ? confirmedCoverageTime(external, shortage) : plannedCoverageTime(external, shortage);
        boolean released = ordered.compareTo(BigDecimal.ZERO) > 0;
        boolean arrivedAfterMrp = arrived.compareTo(BigDecimal.ZERO) > 0 && external.stream()
                .filter(item -> optional(item.getArrivedNum()).compareTo(BigDecimal.ZERO) > 0)
                .anyMatch(item -> factAfterMrp(row.getCreateTime(), item.getLastArrivalTime()));
        if ((formalCommitmentRequired && arrivedAfterMrp) || (!formalCommitmentRequired && arrived.compareTo(BigDecimal.ZERO) > 0)) {
            line.setSupplyPlanStatus("SUPPLY_ARRIVED_RECONCILE")
                    .setRecommendedActionCode("REFRESH_MRP_RECONCILE")
                    .setRecommendedAction("已有采购/外协到货量但MRP仍显示缺口，先完成质检、入库与库存对账并重算MRP，禁止直接补单");
        } else if (external.isEmpty()) line.setSupplyPlanStatus("NO_SUPPLY_PLAN");
        else if (pendingApproval.compareTo(BigDecimal.ZERO) > 0 && open.compareTo(shortage) < 0)
            line.setSupplyPlanStatus("PURCHASE_APPROVAL_PENDING");
        else if (!released) line.setSupplyPlanStatus("SUPPLY_NOT_RELEASED");
        else if (open.compareTo(shortage) < 0) line.setSupplyPlanStatus("ORDERED_PARTIAL_COVERAGE");
        else if (formalCommitmentRequired && confirmed.compareTo(BigDecimal.ZERO) == 0)
            line.setSupplyPlanStatus("SUPPLIER_COMMITMENT_MISSING");
        else if (formalCommitmentRequired && confirmed.compareTo(shortage) < 0)
            line.setSupplyPlanStatus("SUPPLIER_COMMITMENT_PARTIAL");
        else if (coverage == null)
            line.setSupplyPlanStatus(formalCommitmentRequired ? "SUPPLIER_COMMITMENT_TIME_MISSING" : "SUPPLY_TIME_MISSING");
        else {
            line.setSupplyPlanStatus(formalCommitmentRequired ? "SUPPLIER_COMMITTED_COVERED" : "ORDERED_COVERED");
            line.setExpectedSupplyDate(coverage.time().toLocalDate()).setExpectedSupplyTime(coverage.time())
                    .setSupplyTimePrecision(coverage.precision())
                    .setSupplyDateBasis(formalCommitmentRequired ? "SUPPLIER_CONFIRMED_TIME" : "PMS_PLAN_REQUIRED_DATE_END");
        }
        if ("NONE".equals(line.getRecommendedActionCode())) {
            if (open.add(pendingApproval).compareTo(shortage) >= 0)
                line.setRecommendedActionCode("MONITOR_PURCHASE_APPROVAL")
                        .setRecommendedAction("采购订单草稿或审批中，等待PMS正式审批结果后重新采集齐套事实");
            else if (external.isEmpty() || !released)
                line.setRecommendedActionCode("RELEASE_SUPPLY_PLAN").setRecommendedAction("下达采购/外协计划并形成经审批订单");
            else if (open.compareTo(shortage) < 0)
                line.setRecommendedActionCode("SUPPLEMENT_PURCHASE").setRecommendedAction("补充未覆盖缺口的采购/外协数量");
            else if (formalCommitmentRequired && overdueCommitted.compareTo(BigDecimal.ZERO) > 0)
                line.setRecommendedActionCode("ESCALATE_SUPPLIER_DELAY").setRecommendedAction("供应商承诺批次已逾期未到，立即催交、记录回复并评估替代/调拨或计划调整");
            else if ((formalCommitmentRequired && confirmed.compareTo(shortage) < 0) || coverage == null)
                line.setRecommendedActionCode("CONFIRM_SUPPLY_TIME")
                        .setRecommendedAction(formalCommitmentRequired ? "取得供应商对未覆盖数量的正式分批到货时刻和证据后再评估齐料" : "补录供应计划日期后再评估齐料");
            else if (row.getDemandDate() != null && coverage.time().toLocalDate().isAfter(row.getDemandDate()))
                line.setRecommendedActionCode("EXPEDITE_SUPPLY").setRecommendedAction("供应计划晚于需求日，催交并评估改期开工");
            else
                line.setRecommendedActionCode("MONITOR_ARRIVAL").setRecommendedAction("跟踪采购/外协按期到货、质检与入库");
        }
    }

    private SupplyMoment confirmedCoverageTime(List<PmsAgentSupplyEvidenceDTO> evidence, BigDecimal shortage) {
        BigDecimal cumulative = BigDecimal.ZERO;
        List<PmsSupplyCommitmentEvidenceDTO> ordered = evidence.stream()
                .flatMap(item -> Optional.ofNullable(item.getCommitments()).orElse(List.of()).stream())
                .filter(item -> commitmentTime(item) != null)
                .sorted(Comparator.comparing(this::commitmentTime)).toList();
        for (PmsSupplyCommitmentEvidenceDTO item : ordered) {
            cumulative = cumulative.add(optional(item.getCommittedNum()));
            if (cumulative.compareTo(shortage) >= 0) return new SupplyMoment(commitmentTime(item),
                    StrUtil.blankToDefault(item.getDeliveryTimePrecision(), "DATE_END"));
        }
        return null;
    }

    private SupplyMoment plannedCoverageTime(List<PmsAgentSupplyEvidenceDTO> evidence, BigDecimal shortage) {
        BigDecimal cumulative = BigDecimal.ZERO;
        List<PmsAgentSupplyEvidenceDTO> ordered = evidence.stream()
                .filter(item -> Set.of("2", "3").contains(item.getPlanStatus()))
                .filter(item -> plannedDeliveryTime(item) != null)
                .sorted(Comparator.comparing(this::plannedDeliveryTime)).toList();
        for (PmsAgentSupplyEvidenceDTO item : ordered) {
            cumulative = cumulative.add(optional(item.getOrderedNum()).subtract(optional(item.getArrivedNum())).max(BigDecimal.ZERO));
            if (cumulative.compareTo(shortage) >= 0) return new SupplyMoment(plannedDeliveryTime(item),
                    StrUtil.blankToDefault(item.getPlannedDeliveryTimePrecision(), "DATE_END"));
        }
        return null;
    }

    private LocalDateTime commitmentTime(PmsSupplyCommitmentEvidenceDTO item) {
        if (item.getConfirmedDeliveryTime() != null) return item.getConfirmedDeliveryTime();
        return item.getConfirmedDeliveryDate() == null ? null
                : item.getConfirmedDeliveryDate().atTime(LocalTime.of(23, 59));
    }

    private LocalDateTime plannedDeliveryTime(PmsAgentSupplyEvidenceDTO item) {
        if (item.getPlannedDeliveryTime() != null) return item.getPlannedDeliveryTime();
        return item.getPlannedDeliveryDate() == null ? null
                : item.getPlannedDeliveryDate().atTime(LocalTime.of(23, 59));
    }

    /**
     * 按各正式自制工单未完数量累计，取第一次覆盖当前缺口的完工时刻。
     */
    private SupplyMoment productionCoverageTime(List<PlnIssueEntity> production,
                                                Map<Long, LocalDateTime> completionTimes,
                                                BigDecimal shortage) {
        List<ProductionLot> lots = production.stream()
                .filter(issue -> Set.of("1", "2", "3").contains(issue.getStatus()))
                .map(issue -> {
                    BigDecimal open = optional(issue.getNum()).subtract(optional(issue.getFinishNum()))
                            .max(BigDecimal.ZERO);
                    LocalDateTime exact = completionTimes.get(issue.getId());
                    SupplyMoment moment = exact != null ? new SupplyMoment(exact, "MINUTE")
                            : issue.getScheduledEndDate() == null ? null
                            : new SupplyMoment(issue.getScheduledEndDate().atTime(LocalTime.of(23, 59)), "DATE_END");
                    return new ProductionLot(open, moment);
                }).filter(lot -> lot.openQuantity().signum() > 0 && lot.moment() != null)
                .sorted(Comparator.comparing(lot -> lot.moment().time())).toList();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (ProductionLot lot : lots) {
            cumulative = cumulative.add(lot.openQuantity());
            if (cumulative.compareTo(shortage) >= 0) return lot.moment();
        }
        return null;
    }

    private String commitmentStatus(List<PmsAgentSupplyEvidenceDTO> evidence) {
        if (evidence.stream().anyMatch(item -> "PARTIAL".equals(item.getCommitmentCoverageStatus()))) return "PARTIAL";
        if (evidence.stream().anyMatch(item -> "FULL".equals(item.getCommitmentCoverageStatus()))) return "FULL";
        if (evidence.stream().allMatch(item -> "RECEIVED".equals(item.getCommitmentCoverageStatus())))
            return "RECEIVED";
        return "NONE";
    }

    private boolean factAfterMrp(LocalDateTime mrpCreatedTime, LocalDateTime factUpdatedTime) {
        return mrpCreatedTime == null || factUpdatedTime == null || factUpdatedTime.isAfter(mrpCreatedTime);
    }

    private String supplyKey(String demandNo, String materialCode) {
        return StrUtil.blankToDefault(demandNo, "") + "\u0000" + StrUtil.blankToDefault(materialCode, "");
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean agentSubstituteDecision(PlanAgentSubstituteDecisionDTO command) {
        if (command == null || command.getDecision() == null || command.getDecision().getMrpId() == null
                || StrUtil.isBlank(command.getFactoryCode()) || StrUtil.isBlank(command.getBusinessType())
                || StrUtil.isBlank(command.getBusinessNo()) || StrUtil.isBlank(command.getExpectedSubstituteStatus())
                || command.getExpectedShortageNum() == null) {
            throw new CheckedException("Agent替代决策参数不完整");
        }
        PlanMatMrpEntity mrp = getById(command.getDecision().getMrpId());
        if (mrp == null || !Objects.equals(mrp.getFactoryCode(), command.getFactoryCode())) {
            throw new CheckedException("MRP不存在或不属于审批冻结的工厂范围");
        }
        boolean inBusinessScope = switch (command.getBusinessType()) {
            case "PLAN_DEMAND" -> Objects.equals(mrp.getPlanDemandNo(), command.getBusinessNo());
            case "SALE_ORDER" -> Objects.equals(mrp.getSaleOrderCode(), command.getBusinessNo());
            default -> false;
        };
        if (!inBusinessScope) {
            throw new CheckedException("MRP不属于本次Agent运行冻结的计划或订单范围");
        }
        BigDecimal currentShortage = Optional.ofNullable(mrp.getRemainingDemandNum()).orElse(BigDecimal.ZERO);
        if (!Objects.equals(mrp.getSubstituteStatus(), command.getExpectedSubstituteStatus())
                || currentShortage.compareTo(Optional.ofNullable(command.getExpectedShortageNum()).orElse(BigDecimal.ZERO)) != 0
                || !Objects.equals(Objects.requireNonNullElse(mrp.getSubstituteSuggestion(), ""),
                Objects.requireNonNullElse(command.getExpectedSubstituteSuggestion(), ""))) {
            throw new CheckedException("MRP替代状态、缺口或候选建议已变化，旧审批快照失效，请重新运行Agent并申请");
        }
        return decideSubstitute(command.getDecision());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAgentTransferReceiptDTO agentTransferDecision(PlanAgentTransferDecisionDTO command) {
        if (command == null || command.getMrpId() == null || StrUtil.isBlank(command.getRequestId())
                || StrUtil.isBlank(command.getFactoryCode()) || StrUtil.isBlank(command.getBusinessType())
                || StrUtil.isBlank(command.getBusinessNo()) || StrUtil.isBlank(command.getTargetWarehouseCode())
                || StrUtil.isBlank(command.getSourceWarehouseCode()) || StrUtil.isBlank(command.getMaterialCode())
                || command.getExpectedShortageNum() == null || command.getExpectedTargetInTransitNum() == null
                || StrUtil.isBlank(command.getExpectedEvidenceFingerprint()) || command.getTransferNum() == null
                || CollUtil.isEmpty(command.getAllocations()) || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("Agent跨仓调拨命令参数不完整");
        }
        if (command.getRequestId().length() > 64 || command.getReason().length() > 500) {
            throw new CheckedException("Agent跨仓调拨请求号最多64位、原因最多500位");
        }
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, command.getFactoryCode())) {
            throw new CheckedException("Agent跨仓调拨工厂超出当前数据范围");
        }
        PlanMatMrpEntity mrp = Optional.ofNullable(getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .eq(PlanMatMrpEntity::getId, command.getMrpId()).last("FOR UPDATE"), false))
                .orElseThrow(() -> new CheckedException("MRP记录不存在"));
        if (!Objects.equals(mrp.getFactoryCode(), command.getFactoryCode())) {
            throw new CheckedException("MRP不属于审批冻结的工厂范围");
        }
        boolean inBusinessScope = switch (command.getBusinessType()) {
            case "PLAN_DEMAND" -> Objects.equals(mrp.getPlanDemandNo(), command.getBusinessNo());
            case "SALE_ORDER" -> Objects.equals(mrp.getSaleOrderCode(), command.getBusinessNo());
            default -> false;
        };
        if (!inBusinessScope) {
            throw new CheckedException("MRP不属于本次Agent运行冻结的计划或订单范围");
        }
        if (!Objects.equals(mrp.getDemandWarehouseCode(), command.getTargetWarehouseCode())
                || !Objects.equals(mrp.getMatCode(), command.getMaterialCode())) {
            throw new CheckedException("Agent调拨目标仓或物料与MRP需求不一致");
        }
        BigDecimal currentShortage = Optional.ofNullable(mrp.getRemainingDemandNum()).orElse(BigDecimal.ZERO);
        if (currentShortage.compareTo(command.getExpectedShortageNum()) != 0) {
            throw new CheckedException("MRP缺口已变化，旧调拨审批失效，请重新运行Agent");
        }
        PlanMrpShortageQueryDTO query = new PlanMrpShortageQueryDTO();
        query.setFactoryCode(command.getFactoryCode());
        boolean routePolicyRequired = Boolean.TRUE.equals(command.getRoutePolicyRequired());
        query.setSupplyEvidenceVersion(routePolicyRequired ? 7 : 6);
        if ("PLAN_DEMAND".equals(command.getBusinessType())) query.setPlanDemandNo(command.getBusinessNo());
        else query.setSaleOrderCode(command.getBusinessNo());
        PlanMrpShortageLineDTO current = shortageAnalysis(query).getLines().stream()
                .filter(line -> Objects.equals(line.getMrpId(), command.getMrpId())).findFirst()
                .orElseThrow(() -> new CheckedException("当前Agent分析范围已不包含目标MRP，旧审批失效"));
        if (Boolean.FALSE.equals(current.getCrossWarehouseTransferEvidenceComplete())
                || !Objects.equals(current.getTransferEvidenceFingerprint(), command.getExpectedEvidenceFingerprint())
                || Optional.ofNullable(current.getTargetInTransitTransferNum()).orElse(BigDecimal.ZERO)
                .compareTo(command.getExpectedTargetInTransitNum()) != 0) {
            throw new CheckedException("跨仓库存、预留、安全库存或在途证据已变化，旧调拨审批失效");
        }
        PlanMrpTransferSuggestionDTO selected = Optional.ofNullable(current.getTransferSuggestions()).orElse(List.of())
                .stream().filter(item -> Objects.equals(item.getSourceWarehouseCode(), command.getSourceWarehouseCode()))
                .findFirst().orElseThrow(() -> new CheckedException("冻结源仓已不在当前可调建议中"));
        if (selected.getSuggestedNum() == null || selected.getSuggestedNum().compareTo(command.getTransferNum()) != 0
                || !sameTransferAllocations(selected.getAllocations(), command.getAllocations())) {
            throw new CheckedException("跨仓建议数量或批次分配已变化，旧调拨审批失效");
        }
        if (routePolicyRequired && (!Boolean.TRUE.equals(selected.getRoutePolicyMatched())
                || !Objects.equals(selected.getRoutePolicyId(), command.getRoutePolicyId())
                || !Objects.equals(selected.getRoutePolicyCode(), command.getRoutePolicyCode())
                || !Objects.equals(selected.getRoutePolicyFingerprint(), command.getRoutePolicyFingerprint())
                || !Objects.equals(selected.getTransportMode(), command.getTransportMode())
                || !Objects.equals(selected.getUrgencyLevel(), command.getUrgencyLevel()))) {
            throw new CheckedException("跨仓路线、运输方式、日历或紧急等级时效已变化，旧调拨审批失效");
        }
        WmsCrossWarehouseTransferDTO.AgentCommand wmsCommand = new WmsCrossWarehouseTransferDTO.AgentCommand();
        wmsCommand.setRequestId(command.getRequestId());
        wmsCommand.setFactoryCode(command.getFactoryCode());
        wmsCommand.setBusinessType(command.getBusinessType());
        wmsCommand.setBusinessNo(command.getBusinessNo());
        wmsCommand.setIssueNo(mrp.getPlanDemandNo());
        wmsCommand.setMrpId(command.getMrpId());
        wmsCommand.setTargetWarehouseCode(command.getTargetWarehouseCode());
        wmsCommand.setMaterialCode(command.getMaterialCode());
        wmsCommand.setExpectedShortageNum(command.getExpectedShortageNum());
        wmsCommand.setExpectedTargetInTransitNum(command.getExpectedTargetInTransitNum());
        wmsCommand.setExpectedEvidenceFingerprint(command.getExpectedEvidenceFingerprint());
        wmsCommand.setSourceWarehouseCode(command.getSourceWarehouseCode());
        wmsCommand.setRoutePolicyId(command.getRoutePolicyId());
        wmsCommand.setRoutePolicyCode(command.getRoutePolicyCode());
        wmsCommand.setRoutePolicyFingerprint(command.getRoutePolicyFingerprint());
        wmsCommand.setTransportMode(command.getTransportMode());
        wmsCommand.setUrgencyLevel(command.getUrgencyLevel());
        wmsCommand.setRoutePolicyRequired(routePolicyRequired);
        wmsCommand.setTransferNum(command.getTransferNum());
        wmsCommand.setReason(command.getReason());
        wmsCommand.setAllocations(command.getAllocations().stream().map(item ->
                new WmsCrossWarehouseTransferDTO.StockAllocation().setStockId(item.getStockId())
                        .setSuggestedNum(item.getSuggestedNum())).toList());
        R<WmsCrossWarehouseTransferDTO.Receipt> response = remoteAllocationBillService.executeTransfer(wmsCommand);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("WMS未确认正式调拨单创建成功");
        }
        WmsCrossWarehouseTransferDTO.Receipt receipt = response.getData();
        return new PlanAgentTransferReceiptDTO().setMrpId(mrp.getId())
                .setAllocationBillId(receipt.getAllocationBillId()).setBillNo(receipt.getBillNo())
                .setAllocationState(receipt.getAllocationState()).setRequestId(receipt.getRequestId())
                .setSourceWarehouseCode(receipt.getSourceWarehouseCode())
                .setTargetWarehouseCode(receipt.getTargetWarehouseCode())
                .setMaterialCode(receipt.getMaterialCode()).setTransferNum(receipt.getTransferNum());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAgentPurchaseReceiptDTO agentPurchaseOrder(PlanAgentPurchaseOrderDTO command) {
        validatePurchaseCommand(command);
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, command.getFactoryCode())) {
            throw new CheckedException("Agent采购工厂超出当前数据范围");
        }
        PlanMatMrpEntity mrp = Optional.ofNullable(getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .eq(PlanMatMrpEntity::getId, command.getMrpId()).last("FOR UPDATE"), false))
                .orElseThrow(() -> new CheckedException("MRP记录不存在"));
        validatePurchaseBusinessScope(mrp, command);

        if (Objects.equals(command.getRequestId(), mrp.getPurchaseRequestId())
                && StrUtil.isNotBlank(mrp.getPurchaseOrderReceipt())) {
            return JSONUtil.toBean(mrp.getPurchaseOrderReceipt(), PlanAgentPurchaseReceiptDTO.class);
        }
        PlanMatMrpEntity duplicated = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPurchaseRequestId, command.getRequestId())
                .ne(PlanMatMrpEntity::getId, mrp.getId()), false);
        if (duplicated != null) throw new CheckedException("Agent采购请求号已被其他MRP记录使用");

        PlanAgentPurchaseReceiptDTO recovered = recoverPurchaseReceipt(command);
        if (recovered != null) {
            return persistPurchaseReceipt(mrp, recovered);
        }

        PlanMrpShortageQueryDTO query = new PlanMrpShortageQueryDTO();
        query.setFactoryCode(command.getFactoryCode());
        query.setSupplyEvidenceVersion(8);
        if ("PLAN_DEMAND".equals(command.getBusinessType())) query.setPlanDemandNo(command.getBusinessNo());
        else query.setSaleOrderCode(command.getBusinessNo());
        PlanMrpShortageLineDTO current = shortageAnalysis(query).getLines().stream()
                .filter(line -> Objects.equals(line.getMrpId(), command.getMrpId())).findFirst()
                .orElseThrow(() -> new CheckedException("当前Agent分析范围已不包含目标MRP，旧采购审批失效"));
        if (!MatAttributeEnum.PMS.getCode().equals(mrp.getType())
                || Boolean.FALSE.equals(current.getPurchaseEvidenceComplete())
                || current.getRecommendedPurchaseNum() == null
                || current.getRecommendedPurchaseNum().compareTo(command.getPurchaseNum()) != 0
                || optional(current.getShortageNum()).compareTo(command.getExpectedShortageNum()) != 0
                || optional(current.getOpenOrderedSupplyNum()).compareTo(command.getExpectedOpenApprovedNum()) != 0
                || optional(current.getPendingApprovalSupplyNum()).compareTo(command.getExpectedPendingApprovalNum()) != 0
                || optional(current.getAppliedInTransitTransferNum()).compareTo(command.getExpectedInTransitNum()) != 0
                || optional(current.getPurchaseUncoveredNum()).compareTo(command.getExpectedPurchaseUncoveredNum()) != 0
                || !Objects.equals(current.getMinOrderQuantity(), Math.max(0, Objects.requireNonNullElse(command.getMinOrderQuantity(), 0)))
                || !Objects.equals(current.getLotSize(), Math.max(1, Objects.requireNonNullElse(command.getLotSize(), 1)))) {
            throw new CheckedException("MRP缺口、已批采购、待审采购、调拨在途或批量参数已变化，旧采购审批失效");
        }
        PlanMrpPurchaseCandidateDTO selected = Optional.ofNullable(current.getPurchaseCandidates()).orElse(List.of())
                .stream().filter(candidate -> Objects.equals(candidate.getCandidateFingerprint(), command.getCandidateFingerprint())
                        && Objects.equals(candidate.getSupplierCode(), command.getSupplierCode())
                        && Objects.equals(candidate.getPriceId(), command.getPriceId())
                        && Objects.equals(candidate.getAuthorizationId(), command.getAuthorizationId()))
                .findFirst().orElseThrow(() -> new CheckedException("冻结采购候选已不在当前认证、价格和交期候选中"));
        if (!Objects.equals(selected.getPurchaseMode(), command.getPurchaseMode())
                || !Objects.equals(selected.getTargetPmsPlanNo(), command.getTargetPmsPlanNo())
                || !Objects.equals(selected.getSupplierId(), command.getSupplierId())
                || !Objects.equals(selected.getEarliestDeliveryDate(), command.getEarliestDeliveryDate())) {
            throw new CheckedException("采购计划、供应商或最早可达日期已变化，旧采购审批失效");
        }

        PmsAgentPurchaseCommandDTO pmsCommand = new PmsAgentPurchaseCommandDTO();
        BeanUtils.copyProperties(command, pmsCommand);
        R<PmsAgentPurchaseReceiptDTO> response = remotePmsAgentPurchaseService.execute(pmsCommand);
        if (response == null || !response.isOk() || response.getData() == null
                || !Boolean.TRUE.equals(response.getData().getWorkflowSubmitted())) {
            throw new CheckedException("PMS未确认采购订单已创建并提交正式审批");
        }
        return persistPurchaseReceipt(mrp, toPlanReceipt(response.getData()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanAgentPurchaseRejectionDTO.Receipt rejectAgentPurchaseOrder(
            PlanAgentPurchaseRejectionDTO.Command command) {
        if (command == null || StrUtil.isBlank(command.getEventId()) || command.getEventId().length() > 128
                || StrUtil.isBlank(command.getSourceFingerprint()) || command.getSourceFingerprint().length() != 64
                || StrUtil.isBlank(command.getAgentRequestId()) || command.getMrpId() == null
                || command.getPmsOrderId() == null || StrUtil.isBlank(command.getPmsOrderNo())
                || command.getPmsOrderDetailId() == null || StrUtil.isBlank(command.getPmsPlanNo())
                || !Set.of("6", "7", "8").contains(command.getOrderStatus()) || StrUtil.isBlank(command.getRejectReason())
                || command.getRejectedTime() == null || StrUtil.isBlank(command.getFactoryCode())) {
            throw new CheckedException("PMS采购供给终止事件缺少事件号、源指纹、Agent请求、MRP、订单行、终止状态、原因或工厂");
        }
        if (command.getRejectReason().trim().length() > 1000) {
            throw new CheckedException("PMS采购供给终止原因不能超过1000字符");
        }
        String currentFactory = FactoryContextHolder.getFactoryCode();
        if (StrUtil.isNotBlank(currentFactory) && !Objects.equals(currentFactory, command.getFactoryCode())) {
            throw new CheckedException("PMS采购供给终止事件超出当前工厂数据范围");
        }
        String fingerprint = hash(String.join("|", command.getEventId().trim(),
                command.getSourceFingerprint().trim(), command.getAgentRequestId().trim(),
                String.valueOf(command.getMrpId()), String.valueOf(command.getPmsOrderId()),
                command.getPmsOrderNo().trim(), String.valueOf(command.getPmsOrderDetailId()),
                command.getPmsPlanNo().trim(), command.getOrderStatus(), command.getRejectReason().trim(),
                command.getRejectedTime().toString(), command.getFactoryCode().trim()));
        purchaseStatusReceiptMapper.lockEvent(command.getEventId().trim());
        PlanMrpPurchaseStatusReceiptEntity duplicate = purchaseStatusReceiptMapper.selectOne(
                new LambdaQueryWrapper<PlanMrpPurchaseStatusReceiptEntity>()
                        .eq(PlanMrpPurchaseStatusReceiptEntity::getEventId, command.getEventId().trim()));
        if (duplicate != null) {
            if (!Objects.equals(duplicate.getRequestFingerprint(), fingerprint)) {
                throw new CheckedException("同一PMS采购状态事件号载荷不一致，禁止覆盖原回执");
            }
            return purchaseRejectionReceipt(duplicate, true);
        }

        PlanMatMrpEntity mrp = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, command.getMrpId()).last("FOR UPDATE"), false);
        if (mrp == null || !Objects.equals(mrp.getFactoryCode(), command.getFactoryCode())) {
            throw new CheckedException("PMS采购供给终止事件对应MRP不存在或跨工厂");
        }
        String priorStatus = mrp.getPurchaseOrderStatus();
        boolean sameCurrentOrder = Objects.equals(mrp.getPurchaseRequestId(), command.getAgentRequestId())
                && Objects.equals(mrp.getPurchaseOrderId(), command.getPmsOrderId())
                && Objects.equals(mrp.getPurchaseOrderNo(), command.getPmsOrderNo())
                && Objects.equals(mrp.getPurchaseOrderDetailId(), command.getPmsOrderDetailId());
        boolean creationReceiptMissing = StrUtil.isBlank(mrp.getPurchaseRequestId())
                && mrp.getPurchaseOrderId() == null && StrUtil.isBlank(mrp.getPurchaseOrderNo())
                && mrp.getPurchaseOrderDetailId() == null;
        String resultStatus;
        String message;
        if (sameCurrentOrder || creationReceiptMissing) {
            if (creationReceiptMissing) {
                mrp.setPurchaseRequestId(command.getAgentRequestId().trim());
                mrp.setPurchaseOrderId(command.getPmsOrderId());
                mrp.setPurchaseOrderNo(command.getPmsOrderNo().trim());
                mrp.setPurchaseOrderDetailId(command.getPmsOrderDetailId());
            }
            mrp.setPurchaseOrderStatus(command.getOrderStatus());
            mrp.setPurchaseOrderNum(BigDecimal.ZERO);
            mrp.setPurchaseStatusEventId(command.getEventId().trim());
            mrp.setPurchaseRejectReason(command.getRejectReason().trim());
            mrp.setPurchaseRejectedTime(command.getRejectedTime());
            if (!updateById(mrp)) throw new CheckedException("PMS采购驳回事实写回MRP失败");
            resultStatus = "APPLIED";
            message = creationReceiptMissing
                    ? "已修复缺失的采购创建回执并登记供给终止，MRP未保留有效供应占用"
                    : ("6".equals(command.getOrderStatus()) ? "采购订单驳回" : "采购订单剩余量取消")
                    + "已释放MRP有效供应占用，可重新分析缺口或进入计划撤回";
        } else {
            boolean newerPurchaseExists = StrUtil.isNotBlank(mrp.getPurchaseRequestId())
                    && !Objects.equals(mrp.getPurchaseRequestId(), command.getAgentRequestId());
            if (!newerPurchaseExists) {
                throw new CheckedException("MRP当前采购来源与PMS终止订单不一致，且无法证明已有后继采购");
            }
            resultStatus = "SUPERSEDED";
            message = "该供给终止事件对应旧采购订单，MRP已有后继采购，已保留事件但未覆盖当前供应事实";
        }
        PlanMrpPurchaseStatusReceiptEntity saved = new PlanMrpPurchaseStatusReceiptEntity()
                .setEventId(command.getEventId().trim()).setRequestFingerprint(fingerprint)
                .setSourceFingerprint(command.getSourceFingerprint().trim())
                .setAgentRequestId(command.getAgentRequestId().trim()).setMrpId(command.getMrpId())
                .setPmsOrderId(command.getPmsOrderId()).setPmsOrderNo(command.getPmsOrderNo().trim())
                .setPmsOrderDetailId(command.getPmsOrderDetailId()).setPmsPlanNo(command.getPmsPlanNo().trim())
                .setPriorOrderStatus(priorStatus).setResultStatus(resultStatus)
                .setRejectReason(command.getRejectReason().trim()).setRejectedTime(command.getRejectedTime())
                .setHandledTime(LocalDateTime.now()).setMessage(message).setFactoryCode(command.getFactoryCode());
        if (purchaseStatusReceiptMapper.insert(saved) != 1) {
            throw new CheckedException("PMS采购供给终止永久回执保存失败");
        }
        return purchaseRejectionReceipt(saved, false);
    }

    private PlanAgentPurchaseRejectionDTO.Receipt purchaseRejectionReceipt(
            PlanMrpPurchaseStatusReceiptEntity entity, boolean duplicate) {
        return new PlanAgentPurchaseRejectionDTO.Receipt().setEventId(entity.getEventId())
                .setMrpId(entity.getMrpId()).setAgentRequestId(entity.getAgentRequestId())
                .setPriorOrderStatus(entity.getPriorOrderStatus()).setResultStatus(entity.getResultStatus())
                .setHandledTime(entity.getHandledTime()).setDuplicate(duplicate).setMessage(entity.getMessage());
    }

    private PlanAgentPurchaseReceiptDTO recoverPurchaseReceipt(PlanAgentPurchaseOrderDTO command) {
        try {
            R<PmsAgentPurchaseReceiptDTO> existing = remotePmsAgentPurchaseService.receipt(
                    command.getRequestId(), command.getMrpId());
            if (existing == null || !existing.isOk() || existing.getData() == null) return null;
            PmsAgentPurchaseCommandDTO retry = new PmsAgentPurchaseCommandDTO();
            BeanUtils.copyProperties(command, retry);
            R<PmsAgentPurchaseReceiptDTO> resumed = remotePmsAgentPurchaseService.execute(retry);
            if (resumed == null || !resumed.isOk() || resumed.getData() == null
                    || !Boolean.TRUE.equals(resumed.getData().getWorkflowSubmitted())) {
                throw new CheckedException("已创建采购订单，但PMS正式审批流程尚未成功提交");
            }
            return toPlanReceipt(resumed.getData());
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ignored) {
            return null;
        }
    }

    private PlanAgentPurchaseReceiptDTO persistPurchaseReceipt(PlanMatMrpEntity mrp,
                                                               PlanAgentPurchaseReceiptDTO receipt) {
        mrp.setPurchaseRequestId(receipt.getRequestId());
        mrp.setPurchaseOrderId(receipt.getPmsOrderId());
        mrp.setPurchaseOrderNo(receipt.getPmsOrderNo());
        mrp.setPurchaseOrderDetailId(receipt.getPmsOrderDetailId());
        mrp.setPurchaseOrderNum(receipt.getPurchaseNum());
        mrp.setPurchaseOrderStatus(receipt.getOrderStatus());
        mrp.setPurchaseOrderReceipt(JSONUtil.toJsonStr(receipt));
        mrp.setPurchaseOrderTime(LocalDateTime.now());
        mrp.setPurchaseStatusEventId(null);
        mrp.setPurchaseRejectReason(null);
        mrp.setPurchaseRejectedTime(null);
        if (!updateById(mrp)) throw new CheckedException("PMS采购成功但PLAN回执写入失败，请按原幂等号重试");
        return receipt;
    }

    private PlanAgentPurchaseReceiptDTO toPlanReceipt(PmsAgentPurchaseReceiptDTO source) {
        PlanAgentPurchaseReceiptDTO target = new PlanAgentPurchaseReceiptDTO();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private void validatePurchaseBusinessScope(PlanMatMrpEntity mrp, PlanAgentPurchaseOrderDTO command) {
        if (!Objects.equals(mrp.getFactoryCode(), command.getFactoryCode())
                || !Objects.equals(mrp.getPlanDemandNo(), command.getPlanDemandNo())
                || !Objects.equals(mrp.getMatCode(), command.getMaterialCode())) {
            throw new CheckedException("MRP与采购命令的工厂、计划需求或物料范围不一致");
        }
        boolean inScope = switch (command.getBusinessType()) {
            case "PLAN_DEMAND" -> Objects.equals(mrp.getPlanDemandNo(), command.getBusinessNo());
            case "SALE_ORDER" -> Objects.equals(mrp.getSaleOrderCode(), command.getBusinessNo());
            default -> false;
        };
        if (!inScope) throw new CheckedException("MRP不属于本次Agent运行冻结业务范围");
    }

    private void validatePurchaseCommand(PlanAgentPurchaseOrderDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || command.getRequestId().length() > 64
                || StrUtil.isBlank(command.getFactoryCode()) || StrUtil.isBlank(command.getBusinessType())
                || StrUtil.isBlank(command.getBusinessNo()) || StrUtil.isBlank(command.getPlanDemandNo())
                || command.getMrpId() == null || StrUtil.isBlank(command.getMaterialCode())
                || command.getPurchaseNum() == null || command.getPurchaseNum().compareTo(BigDecimal.ZERO) <= 0
                || command.getDemandDate() == null || command.getRequestedDeliveryDate() == null
                || command.getExpectedShortageNum() == null || command.getExpectedOpenApprovedNum() == null
                || command.getExpectedPendingApprovalNum() == null || command.getExpectedInTransitNum() == null
                || command.getExpectedPurchaseUncoveredNum() == null || StrUtil.isBlank(command.getPurchaseMode())
                || command.getSupplierId() == null || StrUtil.isBlank(command.getSupplierCode())
                || command.getAuthorizationId() == null || command.getPriceId() == null
                || StrUtil.isBlank(command.getCandidateFingerprint()) || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("Agent采购命令缺少业务范围、冻结数量、供应商、认证、价格、交期或原因");
        }
        if (!Set.of("RELEASE_PLAN", "SUPPLEMENT_PLAN").contains(command.getPurchaseMode())
                || ("RELEASE_PLAN".equals(command.getPurchaseMode()) && StrUtil.isBlank(command.getTargetPmsPlanNo()))
                || command.getReason().length() > 500) {
            throw new CheckedException("Agent采购模式、采购计划或原因无效");
        }
    }

    private boolean sameTransferAllocations(List<PlanMrpTransferSuggestionDTO.StockAllocation> current,
                                            List<PlanMrpTransferSuggestionDTO.StockAllocation> frozen) {
        if (current == null || frozen == null || current.size() != frozen.size()) return false;
        Map<Long, BigDecimal> currentMap = current.stream().collect(Collectors.toMap(
                PlanMrpTransferSuggestionDTO.StockAllocation::getStockId,
                PlanMrpTransferSuggestionDTO.StockAllocation::getSuggestedNum, BigDecimal::add));
        Map<Long, BigDecimal> frozenMap = frozen.stream().collect(Collectors.toMap(
                PlanMrpTransferSuggestionDTO.StockAllocation::getStockId,
                PlanMrpTransferSuggestionDTO.StockAllocation::getSuggestedNum, BigDecimal::add));
        return currentMap.size() == frozenMap.size() && currentMap.entrySet().stream()
                .allMatch(entry -> frozenMap.containsKey(entry.getKey())
                        && entry.getValue().compareTo(frozenMap.get(entry.getKey())) == 0);
    }

    @Override
    public void enrichReservationStatuses(List<PlanMatMrpEntity> records) {
        if (CollUtil.isEmpty(records)) {
            return;
        }
        enrichReservationType(records, false);
        enrichReservationType(records, true);
    }

    @Override
    public List<PlanMrpInventoryPeggingEntity> inventoryPegging(Long mrpId) {
        PlanMatMrpEntity mrp = Optional.ofNullable(getById(mrpId))
                .orElseThrow(() -> new CheckedException("MRP记录不存在"));
        List<PlanMrpInventoryPeggingEntity> lines = inventoryPeggingService.listByMrpId(mrpId);
        BigDecimal total = lines.stream().map(PlanMrpInventoryPeggingEntity::getPeggedNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(Optional.ofNullable(mrp.getInventoryPeggedNum()).orElse(BigDecimal.ZERO)) != 0) {
            throw new CheckedException("MRP库存Pegging台账与汇总数量不一致，请先处理数据完整性异常");
        }
        if (!lines.isEmpty()) validateInventoryPeggingEvidence(mrp, lines);
        return lines;
    }

    private void validateInventoryPeggingEvidence(PlanMatMrpEntity mrp,
                                                  List<PlanMrpInventoryPeggingEntity> lines) {
        for (PlanMrpInventoryPeggingEntity line : lines) {
            if (!Objects.equals(line.getEvidenceFingerprint(), inventoryPeggingLineFingerprint(
                    line, mrp.getInventoryAllocationSequence()))) {
                throw new CheckedException("MRP记录[" + mrp.getId() + "]的库存Pegging明细指纹不一致");
            }
        }
        List<Map<String, Object>> evidence = lines.stream().sorted(Comparator
                        .comparing(PlanMrpInventoryPeggingEntity::getAllocationSequence)
                        .thenComparing(PlanMrpInventoryPeggingEntity::getSourceStockId))
                .map(line -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("allocationSequence", line.getAllocationSequence());
                    item.put("sourceStockId", line.getSourceStockId());
                    item.put("peggedNum", line.getPeggedNum());
                    item.put("evidenceFingerprint", line.getEvidenceFingerprint());
                    return item;
                }).toList();
        if (!Objects.equals(mrp.getInventoryPeggingFingerprint(), hash(JSONUtil.toJsonStr(evidence)))) {
            throw new CheckedException("MRP记录[" + mrp.getId() + "]的库存Pegging汇总指纹不一致");
        }
    }

    private String inventoryPeggingLineFingerprint(PlanMrpInventoryPeggingEntity line,
                                                   Integer demandAllocationSequence) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mrpRunNo", line.getMrpRunNo());
        evidence.put("planDemandNo", line.getPlanDemandNo());
        evidence.put("warehouseCode", line.getWarehouseCode());
        evidence.put("materialCode", line.getMaterialCode());
        evidence.put("bomPath", line.getBomPath());
        evidence.put("bomLevel", line.getBomLevel());
        evidence.put("demandDate", line.getDemandDate());
        evidence.put("timeBucket", line.getTimeBucket());
        evidence.put("demandPriority", line.getDemandPriority());
        evidence.put("demandAllocationSequence", demandAllocationSequence);
        evidence.put("allocationSequence", line.getAllocationSequence());
        evidence.put("sourceType", line.getSourceType());
        evidence.put("sourceStockId", line.getSourceStockId());
        evidence.put("sourceBatchNo", line.getSourceBatchNo());
        evidence.put("sourceSiteCode", line.getSourceSiteCode());
        evidence.put("sourceContainerCode", line.getSourceContainerCode());
        evidence.put("sourceSupplierCode", line.getSourceSupplierCode());
        evidence.put("sourceQualityResult", line.getSourceQualityResult());
        evidence.put("sourceValidEndDate", line.getSourceValidEndDate());
        evidence.put("sourceInTime", line.getSourceInTime());
        evidence.put("peggedNum", line.getPeggedNum());
        return hash(JSONUtil.toJsonStr(evidence));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("MRP库存Pegging证据指纹生成失败", ex);
        }
    }

    @Override
    public void lockInventoryAllocationPools(Set<String> lockKeys) {
        if (CollUtil.isEmpty(lockKeys)) {
            throw new CheckedException("MRP库存分配缺少工厂和需求仓锁范围");
        }
        if (lockKeys.size() > 50) {
            throw new CheckedException("单次MRP最多处理50个工厂仓库库存池");
        }
        lockKeys.stream().filter(StrUtil::isNotBlank).sorted()
                .forEach(key -> baseMapper.lockInventoryAllocationPool("MOM:MRP:PEGGING:" + key));
    }

    private void enrichReservationType(List<PlanMatMrpEntity> records, boolean mainMaterial) {
        Set<Long> sourceIds = records.stream()
                .filter(item -> StrUtil.isNotBlank(mainMaterial
                        ? item.getMainMaterialReservationNo() : item.getMaterialReservationNo()))
                .map(PlanMatMrpEntity::getId).collect(Collectors.toSet());
        if (sourceIds.isEmpty()) {
            return;
        }
        WmsMaterialReservationStatusQueryDTO query = new WmsMaterialReservationStatusQueryDTO();
        query.setSourceType(mainMaterial ? "PLAN_MRP_MAIN" : "PLAN_MRP_SUBSTITUTE");
        query.setSourceIds(sourceIds);
        R<Map<Long, String>> response;
        try {
            response = remoteMaterialReservationService.statuses(query);
        } catch (Exception ignored) {
            // WMS短时不可用不应阻断MRP列表查看，退回展示最近一次落库状态。
            return;
        }
        if (response == null || !response.isOk() || response.getData() == null) {
            return;
        }
        records.forEach(item -> {
            String actual = response.getData().get(item.getId());
            if (actual != null) {
                if (mainMaterial) {
                    item.setMainMaterialReservationStatus(actual);
                    item.setInventoryPeggingStatus(switch (actual) {
                        case "ACTIVE" -> "RESERVED";
                        case "CONSUMED" -> "CONSUMED";
                        case "RELEASED" -> "RELEASED";
                        default -> item.getInventoryPeggingStatus();
                    });
                    inventoryPeggingService.synchronizeStatus(item.getId(), actual);
                } else {
                    item.setMaterialReservationStatus(actual);
                }
            }
        });
        Set<String> reservationNos = records.stream().map(item -> mainMaterial
                        ? item.getMainMaterialReservationNo() : item.getMaterialReservationNo())
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        Map<String, WmsMaterialReservationLedgerDTO> ledgers = fetchReservationLedgers(reservationNos, false);
        if (ledgers == null) {
            return;
        }
        records.stream().filter(item -> StrUtil.isNotBlank(mainMaterial
                ? item.getMainMaterialReservationNo() : item.getMaterialReservationNo())).forEach(item -> {
            WmsMaterialReservationLedgerDTO ledger = ledgers.get(mainMaterial
                    ? item.getMainMaterialReservationNo() : item.getMaterialReservationNo());
            if (ledger == null) {
                if (mainMaterial) {
                    item.setMainMaterialReservationHealthy(Boolean.FALSE);
                    item.setMainMaterialReservationRisk("WMS主料预留账本不存在");
                } else {
                    item.setMaterialReservationHealthy(Boolean.FALSE);
                    item.setMaterialReservationRisk("WMS替代料预留账本不存在");
                }
                return;
            }
            List<String> issues = Optional.ofNullable(ledger.getIntegrityIssues()).orElse(List.of());
            if (!Objects.equals(item.getDemandWarehouseCode(), ledger.getWarehouseCode())) {
                issues = new ArrayList<>(issues);
                issues.add("WAREHOUSE_SCOPE_BROKEN");
            }
            String risk = issues.stream().map(this::reservationRiskLabel)
                    .distinct().collect(Collectors.joining("；"));
            if (mainMaterial) {
                item.setMainMaterialReservationHealthy(issues.isEmpty());
                item.setMainMaterialReservationRisk(risk);
            } else {
                item.setMaterialReservationHealthy(issues.isEmpty());
                item.setMaterialReservationRisk(risk);
            }
        });
    }

    @Override
    public List<PlanMatMrpReconcileEvidenceDTO> reconciliationEvidence(Set<Long> sourceIds) {
        if (CollUtil.isEmpty(sourceIds)) {
            return List.of();
        }
        if (sourceIds.size() > 200) {
            throw new CheckedException("单次最多查询200条MRP对账证据");
        }
        return listByIds(sourceIds).stream().map(item -> new PlanMatMrpReconcileEvidenceDTO()
                .setSourceId(item.getId())
                .setPlanDemandNo(item.getPlanDemandNo())
                .setRequirementMatCode(item.getMatCode())
                .setDemandWarehouseCode(item.getDemandWarehouseCode())
                .setSubstituteStatus(item.getSubstituteStatus())
                .setReservationNo(item.getMaterialReservationNo())
                .setReservationStatus(item.getMaterialReservationStatus())
                .setMainReservationNo(item.getMainMaterialReservationNo())
                .setMainReservationStatus(item.getMainMaterialReservationStatus())
                .setIssued(item.getStatus())
                .setFactoryCode(item.getFactoryCode())).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean decideSubstitute(PlanMatSubstituteDecisionDTO command) {
        if (command == null || command.getMrpId() == null || StrUtil.isBlank(command.getRequestId())) {
            throw new CheckedException("替代决策缺少MRP记录或请求唯一号");
        }
        if (command.getRequestId().length() > 64) {
            throw new CheckedException("替代决策请求唯一号不能超过64位");
        }
        PlanMatMrpEntity mrp = Optional.ofNullable(getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .eq(PlanMatMrpEntity::getId, command.getMrpId()).last("FOR UPDATE")))
                .orElseThrow(() -> new CheckedException("MRP记录不存在"));
        if (Objects.equals(command.getRequestId(), mrp.getSubstituteDecisionRequestId())
                || Objects.equals(command.getRequestId(), mrp.getSubstituteCancelRequestId())) {
            return Boolean.TRUE;
        }
        PlanMatMrpEntity duplicatedRequest = getOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .and(wrapper -> wrapper
                        .eq(PlanMatMrpEntity::getSubstituteDecisionRequestId, command.getRequestId())
                        .or()
                        .eq(PlanMatMrpEntity::getSubstituteCancelRequestId, command.getRequestId())), false);
        if (duplicatedRequest != null) {
            throw new CheckedException("替代决策请求号已被其他MRP记录使用");
        }
        requireNoPendingDemandChange(mrp);
        if (Boolean.TRUE.equals(mrp.getStatus())) {
            throw new CheckedException("MRP记录已下发，不能再变更替代方案");
        }
        if (StrUtil.isBlank(mrp.getDemandWarehouseCode())) {
            throw new CheckedException("MRP记录缺少需求仓库，不能安全处理替代；请按需求仓库重新运算MRP");
        }
        if (StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("替代决策原因不能为空");
        }
        if (command.getReason().length() > 500) {
            throw new CheckedException("替代决策原因不能超过500位");
        }
        MomUser user = SecurityUtils.getUser();
        LocalDateTime decisionTime = LocalDateTime.now();
        if ("CANCEL".equalsIgnoreCase(command.getAction())) {
            return cancelDecision(mrp, command, user, decisionTime);
        }
        if (!Set.of(MrpSubstituteStatusEnum.PENDING.getCode(),
                MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode()).contains(mrp.getSubstituteStatus())) {
            throw new CheckedException("当前MRP记录没有待确认的替代建议");
        }
        if ("REJECT".equalsIgnoreCase(command.getAction())) {
            return updateDecision(mrp, command, user, decisionTime,
                    MrpSubstituteStatusEnum.REJECTED.getCode(),
                    MrpReadinessStatusEnum.SHORTAGE.getCode(),
                    mrp.getNetDemandNum(), adjustLotSize(mrp, mrp.getNetDemandNum()), null);
        }
        if (!"APPROVE".equalsIgnoreCase(command.getAction())) {
            throw new CheckedException("替代决策动作仅支持APPROVE、REJECT或CANCEL");
        }
        validateMultilevelSubstituteBoundary(mrp);
        validateSupplyEvidenceBeforeSubstitute(mrp);
        validateMainMaterialShortageStillCurrent(mrp);
        List<PlanMatSubstituteCandidateDTO> candidates = StrUtil.isBlank(mrp.getSubstituteSuggestion())
                ? List.of() : JSONUtil.toList(mrp.getSubstituteSuggestion(), PlanMatSubstituteCandidateDTO.class);
        Map<String, PlanMatSubstituteCandidateDTO> candidateMap = candidates.stream()
                .collect(Collectors.toMap(PlanMatSubstituteCandidateDTO::getSubstituteCode,
                        Function.identity(), (left, right) -> left));
        if (CollUtil.isEmpty(command.getAllocations())) {
            throw new CheckedException("批准替代方案时至少选择一种替代料");
        }
        Map<String, BigDecimal> requested = new LinkedHashMap<>();
        for (PlanMatSubstituteAllocationDTO allocation : command.getAllocations()) {
            if (allocation == null || StrUtil.isBlank(allocation.getSubstituteCode())
                    || allocation.getRequirementNum() == null
                    || allocation.getRequirementNum().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("替代分配的物料和需求当量必须有效");
            }
            requested.merge(allocation.getSubstituteCode(), allocation.getRequirementNum(), BigDecimal::add);
        }
        if (!Boolean.TRUE.equals(mrp.getSubstituteMixAllowed()) && requested.size() > 1) {
            throw new CheckedException("该BOM需求禁止混用，只能批准一种替代料");
        }
        BigDecimal approvedRequirement = BigDecimal.ZERO;
        List<PlanMatSubstituteAllocationDTO> normalized = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
            PlanMatSubstituteCandidateDTO candidate = candidateMap.get(entry.getKey());
            if (candidate == null || candidate.getSubstituteRatio() == null
                    || candidate.getSubstituteRatio().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("替代料[" + entry.getKey() + "]不在本次MRP冻结建议中");
            }
            if (Boolean.FALSE.equals(candidate.getComplianceEligible())) {
                throw new CheckedException("替代料[" + entry.getKey() + "]合规校验未通过："
                        + StrUtil.blankToDefault(candidate.getComplianceMessage(), "不满足冻结授权条件"));
            }
            BigDecimal maxRequirement = candidate.getAvailableActualNum()
                    .divide(candidate.getSubstituteRatio(), 8, RoundingMode.DOWN);
            if (entry.getValue().compareTo(maxRequirement) > 0) {
                throw new CheckedException("替代料[" + entry.getKey() + "]批准数量超过运算时可用库存");
            }
            BigDecimal actualNum = entry.getValue().multiply(candidate.getSubstituteRatio());
            normalized.add(new PlanMatSubstituteAllocationDTO()
                    .setSubstituteCode(candidate.getSubstituteCode())
                    .setSubstituteName(candidate.getSubstituteName())
                    .setSubstituteUnit(candidate.getSubstituteUnit())
                    .setSubstituteRatio(candidate.getSubstituteRatio())
                    .setRequirementNum(entry.getValue())
                    .setActualNum(actualNum)
                    .setApplicableCustomerCodes(candidate.getApplicableCustomerCodes())
                    .setCertifiedSupplierCodes(candidate.getCertifiedSupplierCodes())
                    .setSupplierCertificateRef(candidate.getSupplierCertificateRef())
                    .setSupplierCertificateValidEnd(candidate.getSupplierCertificateValidEnd())
                    .setInspectionLevel(candidate.getInspectionLevel())
                    .setReferenceUnitPrice(candidate.getReferenceUnitPrice())
                    .setCurrency(candidate.getCurrency())
                    .setEstimatedAmount(candidate.getReferenceUnitPrice() == null ? null
                            : actualNum.multiply(candidate.getReferenceUnitPrice()))
                    .setCostBenchmarkUnitPrice(candidate.getCostBenchmarkUnitPrice())
                    .setUnitCostVariance(candidate.getUnitCostVariance())
                    .setEstimatedCostVariance(candidate.getUnitCostVariance() == null ? null
                            : actualNum.multiply(candidate.getUnitCostVariance()))
                    .setAllowedQualityResults(candidate.getAllowedQualityResults())
                    .setComplianceMode(candidate.getComplianceMode())
                    .setDeviationPermitNo(candidate.getDeviationPermitNo())
                    .setDeviationReason(candidate.getDeviationReason())
                    .setDeviationValidEnd(candidate.getDeviationValidEnd()));
            approvedRequirement = approvedRequirement.add(entry.getValue());
        }
        validateCurrentAvailable(mrp, normalized);
        if (approvedRequirement.compareTo(mrp.getNetDemandNum()) > 0) {
            throw new CheckedException("批准替代需求当量不能超过净缺料数量");
        }
        if (!Boolean.TRUE.equals(mrp.getSubstituteMixAllowed())
                && approvedRequirement.compareTo(mrp.getNetDemandNum()) != 0) {
            throw new CheckedException("禁止混用时，单一替代料必须完整覆盖净缺料数量");
        }
        BigDecimal remaining = mrp.getNetDemandNum().subtract(approvedRequirement);
        String substituteStatus = remaining.compareTo(BigDecimal.ZERO) == 0
                ? MrpSubstituteStatusEnum.APPROVED.getCode()
                : MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode();
        String readinessStatus = remaining.compareTo(BigDecimal.ZERO) == 0
                ? MrpReadinessStatusEnum.SUBSTITUTE_APPROVED.getCode()
                : MrpReadinessStatusEnum.PARTIAL_SHORTAGE.getCode();
        WmsMaterialReservationResultDTO reservation = createReservation(mrp, command, normalized);
        try {
            return updateDecision(mrp, command, user, decisionTime, substituteStatus, readinessStatus,
                    remaining, adjustLotSize(mrp, remaining), JSONUtil.toJsonStr(normalized), reservation);
        } catch (RuntimeException ex) {
            compensateReservation(mrp, command, ex);
            throw ex;
        }
    }

    private void validateMultilevelSubstituteBoundary(PlanMatMrpEntity mrp) {
        if (StrUtil.isBlank(mrp.getBomPathSnapshot())) {
            throw new CheckedException("MRP记录缺少BOM路径证据，不能批准替代；请重新执行MRP");
        }
        try {
            boolean expanded = JSONUtil.toList(mrp.getBomPathSnapshot(), PlanBomRequirementPathDTO.class).stream()
                    .anyMatch(path -> Boolean.TRUE.equals(path.getChildBomExploded()));
            if (expanded) {
                throw new CheckedException("自制中间件已展开下层需求，不能按普通物料替代直接批准；"
                        + "请走BOM/工艺变更，或先撤回下层供应后受控重算MRP");
            }
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("MRP记录的BOM路径证据无法核验，不能批准替代；请重新执行MRP");
        }
    }

    @Override
    public Boolean reallocateReservation(PlanMatReservationReallocateDTO command) {
        if (command == null || command.getMrpId() == null || StrUtil.isBlank(command.getRequestId())
                || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("预留批次重配缺少MRP记录、请求号或原因");
        }
        if (command.getRequestId().length() > 64 || command.getReason().length() > 500) {
            throw new CheckedException("预留批次重配请求号最多64位、原因最多500位");
        }
        PlanMatMrpEntity mrp = Optional.ofNullable(getById(command.getMrpId()))
                .orElseThrow(() -> new CheckedException("MRP记录不存在"));
        if (Boolean.TRUE.equals(mrp.getStatus())) {
            throw new CheckedException("MRP记录已下发，不能自动重配预留批次");
        }
        if (!Set.of(MrpSubstituteStatusEnum.APPROVED.getCode(),
                MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode()).contains(mrp.getSubstituteStatus())) {
            throw new CheckedException("只有未下发的已批准或部分批准替代方案可以重配批次");
        }
        if (StrUtil.isBlank(mrp.getMaterialReservationNo())
                || !"ACTIVE".equals(mrp.getMaterialReservationStatus())) {
            throw new CheckedException("MRP记录没有有效的WMS正式预留");
        }
        WmsMaterialReservationReallocateDTO reallocate = new WmsMaterialReservationReallocateDTO();
        reallocate.setRequestId(command.getRequestId());
        reallocate.setSourceType("PLAN_MRP_SUBSTITUTE");
        reallocate.setSourceId(mrp.getId());
        reallocate.setReason(command.getReason());
        R<Boolean> response = remoteMaterialReservationService.reallocate(reallocate);
        if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
            throw new CheckedException("WMS预留批次重配失败：" + (response == null ? "无响应" : response.getMsg()));
        }
        return Boolean.TRUE;
    }

    private Boolean updateDecision(PlanMatMrpEntity mrp, PlanMatSubstituteDecisionDTO command,
                                   MomUser user, LocalDateTime decisionTime, String substituteStatus,
                                   String readinessStatus, BigDecimal remaining, BigDecimal supplyNum,
                                   String allocationJson) {
        return updateDecision(mrp, command, user, decisionTime, substituteStatus, readinessStatus,
                remaining, supplyNum, allocationJson, null);
    }

    private Boolean updateDecision(PlanMatMrpEntity mrp, PlanMatSubstituteDecisionDTO command,
                                   MomUser user, LocalDateTime decisionTime, String substituteStatus,
                                   String readinessStatus, BigDecimal remaining, BigDecimal supplyNum,
                                   String allocationJson, WmsMaterialReservationResultDTO reservation) {
        boolean updated = update(new LambdaUpdateWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, mrp.getId())
                .eq(PlanMatMrpEntity::getSubstituteStatus, mrp.getSubstituteStatus())
                .set(PlanMatMrpEntity::getSubstituteStatus, substituteStatus)
                .set(PlanMatMrpEntity::getReadinessStatus, readinessStatus)
                .set(PlanMatMrpEntity::getRemainingDemandNum, remaining)
                .set(PlanMatMrpEntity::getNum, supplyNum)
                .set(PlanMatMrpEntity::getSubstituteAllocation, allocationJson)
                .set(PlanMatMrpEntity::getSubstituteDecisionRequestId, command.getRequestId())
                .set(PlanMatMrpEntity::getSubstituteDecisionReason, command.getReason())
                .set(PlanMatMrpEntity::getSubstituteDecisionBy, user.getUsername())
                .set(PlanMatMrpEntity::getSubstituteDecisionByName, user.getName())
                .set(PlanMatMrpEntity::getSubstituteDecisionTime, decisionTime)
                .set(reservation != null, PlanMatMrpEntity::getMaterialReservationNo,
                        reservation == null ? null : reservation.getReservationNo())
                .set(reservation != null, PlanMatMrpEntity::getMaterialReservationStatus,
                        reservation == null ? null : reservation.getStatus())
                .set(reservation != null, PlanMatMrpEntity::getMaterialReservationSnapshot,
                        reservation == null ? null : JSONUtil.toJsonStr(reservation))
                .set(reservation != null, PlanMatMrpEntity::getMaterialReservationTime, decisionTime));
        if (!updated) {
            throw new CheckedException("替代建议已被其他人员处理，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    private Boolean cancelDecision(PlanMatMrpEntity mrp, PlanMatSubstituteDecisionDTO command,
                                   MomUser user, LocalDateTime cancelTime) {
        Set<String> cancellable = Set.of(MrpSubstituteStatusEnum.APPROVED.getCode(),
                MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode(), MrpSubstituteStatusEnum.REJECTED.getCode());
        if (!cancellable.contains(mrp.getSubstituteStatus())) {
            throw new CheckedException("只能撤销未下达的已批准、部分批准或已拒绝决策");
        }
        if (StrUtil.isNotBlank(mrp.getMaterialReservationNo())) {
            WmsMaterialReservationReleaseDTO release = new WmsMaterialReservationReleaseDTO();
            release.setRequestId(command.getRequestId());
            release.setSourceType("PLAN_MRP_SUBSTITUTE");
            release.setSourceId(mrp.getId());
            release.setReason(command.getReason());
            R<Boolean> released = remoteMaterialReservationService.release(release);
            if (released == null || !released.isOk() || !Boolean.TRUE.equals(released.getData())) {
                throw new CheckedException("WMS物料预留释放失败：" + (released == null ? "无响应" : released.getMsg()));
            }
        }
        BigDecimal netDemand = Optional.ofNullable(mrp.getNetDemandNum()).orElse(BigDecimal.ZERO);
        boolean updated = update(new LambdaUpdateWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, mrp.getId())
                .eq(PlanMatMrpEntity::getStatus, false)
                .eq(PlanMatMrpEntity::getSubstituteStatus, mrp.getSubstituteStatus())
                .set(PlanMatMrpEntity::getSubstituteStatus, MrpSubstituteStatusEnum.CANCELLED.getCode())
                .set(PlanMatMrpEntity::getReadinessStatus, netDemand.compareTo(BigDecimal.ZERO) > 0
                        ? MrpReadinessStatusEnum.SHORTAGE.getCode() : MrpReadinessStatusEnum.READY.getCode())
                .set(PlanMatMrpEntity::getRemainingDemandNum, netDemand)
                .set(PlanMatMrpEntity::getNum, adjustLotSize(mrp, netDemand))
                .set(PlanMatMrpEntity::getSubstituteCancelRequestId, command.getRequestId())
                .set(PlanMatMrpEntity::getSubstituteCancelReason, command.getReason())
                .set(PlanMatMrpEntity::getSubstituteCancelBy, user.getUsername())
                .set(PlanMatMrpEntity::getSubstituteCancelByName, user.getName())
                .set(PlanMatMrpEntity::getSubstituteCancelTime, cancelTime)
                .set(StrUtil.isNotBlank(mrp.getMaterialReservationNo()),
                        PlanMatMrpEntity::getMaterialReservationStatus, "RELEASED"));
        if (!updated) {
            throw new CheckedException("替代决策状态已变更，请刷新后重试");
        }
        return Boolean.TRUE;
    }

    private WmsMaterialReservationResultDTO createReservation(PlanMatMrpEntity mrp,
                                                              PlanMatSubstituteDecisionDTO command,
                                                              List<PlanMatSubstituteAllocationDTO> allocations) {
        WmsMaterialReservationCreateDTO reserve = new WmsMaterialReservationCreateDTO();
        reserve.setRequestId(command.getRequestId());
        reserve.setSourceType("PLAN_MRP_SUBSTITUTE");
        reserve.setSourceId(mrp.getId());
        reserve.setSourceNo(mrp.getPlanDemandNo());
        reserve.setFactoryCode(mrp.getFactoryCode());
        reserve.setWarehouseCode(mrp.getDemandWarehouseCode());
        reserve.setWarehouseName(mrp.getDemandWarehouseName());
        reserve.setCustomerCode(mrp.getCustomerCode());
        reserve.setRequiredDate(mrp.getDemandDate());
        reserve.setAllocations(allocations.stream().map(item -> new WmsMaterialReservationAllocationDTO()
                .setRequirementMatCode(mrp.getMatCode())
                .setRequirementMatName(mrp.getMatName())
                .setActualMatCode(item.getSubstituteCode())
                .setActualMatName(item.getSubstituteName())
                .setActualMatUnit(item.getSubstituteUnit())
                .setSubstituteRatio(item.getSubstituteRatio())
                .setRequirementNum(item.getRequirementNum())
                .setActualNum(item.getActualNum())
                .setApplicableCustomerCodes(item.getApplicableCustomerCodes())
                .setCertifiedSupplierCodes(item.getCertifiedSupplierCodes())
                .setSupplierCertificateRef(item.getSupplierCertificateRef())
                .setSupplierCertificateValidEnd(item.getSupplierCertificateValidEnd())
                .setInspectionLevel(item.getInspectionLevel())
                .setReferenceUnitPrice(item.getReferenceUnitPrice())
                .setCurrency(item.getCurrency())
                .setAllowedQualityResults(item.getAllowedQualityResults())
                .setComplianceMode(item.getComplianceMode())
                .setDeviationPermitNo(item.getDeviationPermitNo())
                .setDeviationReason(item.getDeviationReason())
                .setDeviationValidEnd(item.getDeviationValidEnd())).toList());
        R<WmsMaterialReservationResultDTO> response = remoteMaterialReservationService.reserve(reserve);
        if (response == null || !response.isOk() || response.getData() == null
                || StrUtil.isBlank(response.getData().getReservationNo())) {
            throw new CheckedException("WMS物料正式预留失败：" + (response == null ? "无响应" : response.getMsg()));
        }
        return response.getData();
    }

    private void compensateReservation(PlanMatMrpEntity mrp, PlanMatSubstituteDecisionDTO command,
                                       RuntimeException original) {
        try {
            WmsMaterialReservationReleaseDTO release = new WmsMaterialReservationReleaseDTO();
            String request = command.getRequestId() + "-C";
            release.setRequestId(request.length() > 64 ? request.substring(0, 64) : request);
            release.setSourceType("PLAN_MRP_SUBSTITUTE");
            release.setSourceId(mrp.getId());
            String reason = "PLAN决策落库失败自动补偿：" + original.getMessage();
            release.setReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            remoteMaterialReservationService.release(release);
        } catch (Exception compensationError) {
            original.addSuppressed(compensationError);
        }
    }

    private BigDecimal adjustLotSize(PlanMatMrpEntity material, BigDecimal net) {
        if (net == null || net.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        int minOrderQuantity = Math.max(0, Objects.requireNonNullElse(material.getMinOrderQuantity(), 0));
        BigDecimal adjusted = net.max(BigDecimal.valueOf(minOrderQuantity));
        int lotSize = Math.max(1, Objects.requireNonNullElse(material.getLotSize(), 1));
        BigDecimal lot = BigDecimal.valueOf(lotSize);
        return adjusted.divide(lot, 0, RoundingMode.UP).multiply(lot);
    }

    /**
     * 批准前预校验WMS实时库存；最终并发一致性由随后创建的WMS库存行正式预留保证。
     */
    private void validateCurrentAvailable(PlanMatMrpEntity mrp, List<PlanMatSubstituteAllocationDTO> allocations) {
        if (CollUtil.isEmpty(allocations)) {
            return;
        }
        Map<String, BigDecimal> requestedActual = allocations.stream().collect(Collectors.groupingBy(
                PlanMatSubstituteAllocationDTO::getSubstituteCode,
                Collectors.reducing(BigDecimal.ZERO, PlanMatSubstituteAllocationDTO::getActualNum, BigDecimal::add)));
        Set<String> materialCodes = requestedActual.keySet();
        WmsLiveStockByProductCodeDto query = new WmsLiveStockByProductCodeDto();
        query.setProductCodeSet(materialCodes);
        query.setWarehouseCode(mrp.getDemandWarehouseCode());
        List<WmsLiveStockEntity> liveStocks = Optional.ofNullable(
                        remoteLiveStockService.getLiveStockByProductCodeList(query).getData())
                .orElse(List.of());
        liveStocks = liveStocks.stream().filter(this::isGenerallyUsableStock).toList();
        Map<String, BigDecimal> physicalStock = liveStocks.stream().collect(Collectors.groupingBy(
                WmsLiveStockEntity::getMatCode,
                Collectors.reducing(BigDecimal.ZERO,
                        item -> item.getMatNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(item.getMatNum()),
                        BigDecimal::add)));
        List<MdmMaterialEntity> materials = Optional.ofNullable(
                        remoteMaterialService.getListByCodes(new ArrayList<>(materialCodes)).getData())
                .orElse(List.of());
        Map<String, BigDecimal> safetyStock = materials.stream().collect(Collectors.toMap(
                MdmMaterialEntity::getMaterialCode,
                item -> Optional.ofNullable(item.getMinStock()).orElse(BigDecimal.ZERO),
                (left, right) -> left));
        Map<String, BigDecimal> available = new HashMap<>();
        for (String code : materialCodes) {
            available.put(code, physicalStock.getOrDefault(code, BigDecimal.ZERO)
                    .subtract(safetyStock.getOrDefault(code, BigDecimal.ZERO)).max(BigDecimal.ZERO));
        }

        List<PlanMatMrpEntity> otherOpenPlans = list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getStatus, false)
                .eq(PlanMatMrpEntity::getDemandWarehouseCode, mrp.getDemandWarehouseCode())
                .ne(PlanMatMrpEntity::getId, mrp.getId()));
        for (PlanMatMrpEntity other : otherOpenPlans) {
            deductPlanAvailable(available, other.getMatCode(), other.getStockAllocatedNum());
            if (MrpSubstituteStatusEnum.PENDING.getCode().equals(other.getSubstituteStatus())
                    && StrUtil.isNotBlank(other.getSubstituteSuggestion())) {
                try {
                    JSONUtil.toList(other.getSubstituteSuggestion(), PlanMatSubstituteCandidateDTO.class)
                            .forEach(candidate -> deductPlanAvailable(available, candidate.getSubstituteCode(),
                                    candidate.getSuggestedActualNum()));
                } catch (Exception ex) {
                    throw new CheckedException("MRP记录[" + other.getId() + "]的替代建议快照损坏，不能安全批准");
                }
            }
            if ((MrpSubstituteStatusEnum.APPROVED.getCode().equals(other.getSubstituteStatus())
                    || MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode().equals(other.getSubstituteStatus()))
                    && StrUtil.isNotBlank(other.getSubstituteAllocation())) {
                try {
                    JSONUtil.toList(other.getSubstituteAllocation(), PlanMatSubstituteAllocationDTO.class)
                            .forEach(allocation -> deductPlanAvailable(available, allocation.getSubstituteCode(),
                                    allocation.getActualNum()));
                } catch (Exception ex) {
                    throw new CheckedException("MRP记录[" + other.getId() + "]的替代分配快照损坏，不能安全批准");
                }
            }
        }
        LocalDate requiredDate = Optional.ofNullable(mrp.getDemandDate()).orElse(LocalDate.now());
        for (PlanMatSubstituteAllocationDTO allocation : allocations) {
            validateAllocationCompliance(mrp.getCustomerCode(), requiredDate, allocation);
            BigDecimal totalPhysical = physicalStock.getOrDefault(allocation.getSubstituteCode(), BigDecimal.ZERO);
            BigDecimal compliantPhysical = liveStocks.stream()
                    .filter(stock -> Objects.equals(allocation.getSubstituteCode(), stock.getMatCode()))
                    .filter(stock -> isCompliantStock(stock, allocation))
                    .map(this::stockQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal unavailable = totalPhysical.subtract(
                    available.getOrDefault(allocation.getSubstituteCode(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            BigDecimal compliantAvailable = compliantPhysical.subtract(unavailable).max(BigDecimal.ZERO)
                    .min(available.getOrDefault(allocation.getSubstituteCode(), BigDecimal.ZERO));
            if (allocation.getActualNum().compareTo(compliantAvailable) > 0) {
                throw new CheckedException("替代料[" + allocation.getSubstituteCode()
                        + "]符合客户、供应商、质量及许可约束的实时库存不足，请重新执行MRP");
            }
        }
        for (Map.Entry<String, BigDecimal> entry : requestedActual.entrySet()) {
            if (entry.getValue().compareTo(available.getOrDefault(entry.getKey(), BigDecimal.ZERO)) > 0) {
                throw new CheckedException("替代料[" + entry.getKey()
                        + "]当前扣除安全库存和其他计划占用后不足，请重新执行MRP");
            }
        }
    }

    /**
     * 到货/完工尚未回写到MRP时，禁止继续批准替代造成重复供给。
     */
    private void validateSupplyEvidenceBeforeSubstitute(PlanMatMrpEntity mrp) {
        PlanMrpShortageQueryDTO query = new PlanMrpShortageQueryDTO();
        query.setFactoryCode(mrp.getFactoryCode());
        query.setPlanDemandNo(mrp.getPlanDemandNo());
        PlanMrpShortageLineDTO current = shortageAnalysis(query).getLines().stream()
                .filter(line -> Objects.equals(line.getMrpId(), mrp.getId()))
                .findFirst().orElseThrow(() -> new CheckedException("无法取得当前MRP供应证据，替代批准已故障关闭"));
        if (Boolean.FALSE.equals(current.getSupplyEvidenceComplete())) {
            throw new CheckedException("当前供应证据不完整，必须恢复供应证据并重新分析后再批准替代");
        }
        if ("REFRESH_MRP_RECONCILE".equals(current.getRecommendedActionCode())) {
            throw new CheckedException("已有到货或完工事实但MRP仍显示缺口，必须先完成入库对账并重算MRP，禁止批准替代");
        }
    }

    /**
     * 主料在MRP后新增可用库存时，旧缺口和替代数量都已失真，必须先重算MRP。
     */
    private void validateMainMaterialShortageStillCurrent(PlanMatMrpEntity mrp) {
        WmsLiveStockByProductCodeDto stockQuery = new WmsLiveStockByProductCodeDto();
        stockQuery.setProductCodeSet(Set.of(mrp.getMatCode()));
        stockQuery.setWarehouseCode(mrp.getDemandWarehouseCode());
        R<List<WmsLiveStockEntity>> stockResponse = remoteLiveStockService.getLiveStockByProductCodeList(stockQuery);
        if (stockResponse == null || !stockResponse.isOk() || stockResponse.getData() == null) {
            throw new CheckedException("无法复核主料实时库存，替代批准已故障关闭");
        }
        BigDecimal physical = stockResponse.getData().stream().filter(this::isGenerallyUsableStock)
                .map(this::stockQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        R<List<MdmMaterialEntity>> materialResponse = remoteMaterialService.getListByCodes(List.of(mrp.getMatCode()));
        if (materialResponse == null || !materialResponse.isOk() || CollUtil.isEmpty(materialResponse.getData())) {
            throw new CheckedException("无法复核主料安全库存，替代批准已故障关闭");
        }
        BigDecimal safety = materialResponse.getData().stream()
                .filter(item -> Objects.equals(mrp.getMatCode(), item.getMaterialCode()))
                .map(MdmMaterialEntity::getMinStock).filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO);
        Map<String, BigDecimal> available = new HashMap<>();
        available.put(mrp.getMatCode(), physical.subtract(safety).max(BigDecimal.ZERO));
        List<PlanMatMrpEntity> otherOpenPlans = list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getFactoryCode, mrp.getFactoryCode())
                .eq(PlanMatMrpEntity::getStatus, false)
                .eq(PlanMatMrpEntity::getDemandWarehouseCode, mrp.getDemandWarehouseCode())
                .ne(PlanMatMrpEntity::getId, mrp.getId()));
        for (PlanMatMrpEntity other : otherOpenPlans) {
            deductPlanAvailable(available, other.getMatCode(), other.getStockAllocatedNum());
            try {
                if (StrUtil.isNotBlank(other.getSubstituteSuggestion())
                        && MrpSubstituteStatusEnum.PENDING.getCode().equals(other.getSubstituteStatus())) {
                    JSONUtil.toList(other.getSubstituteSuggestion(), PlanMatSubstituteCandidateDTO.class)
                            .forEach(candidate -> deductPlanAvailable(available, candidate.getSubstituteCode(), candidate.getSuggestedActualNum()));
                }
                if (StrUtil.isNotBlank(other.getSubstituteAllocation()) && Set.of(
                        MrpSubstituteStatusEnum.APPROVED.getCode(), MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode()).contains(other.getSubstituteStatus())) {
                    JSONUtil.toList(other.getSubstituteAllocation(), PlanMatSubstituteAllocationDTO.class)
                            .forEach(allocation -> deductPlanAvailable(available, allocation.getSubstituteCode(), allocation.getActualNum()));
                }
            } catch (Exception ex) {
                throw new CheckedException("其他计划[" + other.getPlanDemandNo() + "]替代占用快照损坏，无法安全批准替代，请先修复数据");
            }
        }
        BigDecimal gross = Optional.ofNullable(mrp.getGrossDemandNum())
                .orElse(optional(mrp.getNetDemandNum()).add(optional(mrp.getStockAllocatedNum())));
        BigDecimal currentShortage = gross.subtract(available.getOrDefault(mrp.getMatCode(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
        BigDecimal frozenShortage = optional(mrp.getNetDemandNum());
        if (currentShortage.compareTo(frozenShortage) < 0) {
            throw new CheckedException("主料实时可用库存已增加，MRP净缺口由" + frozenShortage.stripTrailingZeros().toPlainString()
                    + "降至" + currentShortage.stripTrailingZeros().toPlainString() + "，必须重算MRP后再决定替代");
        }
    }

    private void validateAllocationCompliance(String customerCode, LocalDate requiredDate,
                                              PlanMatSubstituteAllocationDTO allocation) {
        if (CollUtil.isNotEmpty(allocation.getApplicableCustomerCodes())
                && !allocation.getApplicableCustomerCodes().contains(customerCode)) {
            throw new CheckedException("替代料[" + allocation.getSubstituteCode() + "]未授权给当前客户");
        }
        LocalDate validationDate = requiredDate.isAfter(LocalDate.now()) ? requiredDate : LocalDate.now();
        if (CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())
                && (StrUtil.isBlank(allocation.getSupplierCertificateRef())
                || allocation.getSupplierCertificateValidEnd() == null
                || validationDate.isAfter(allocation.getSupplierCertificateValidEnd()))) {
            throw new CheckedException("替代料[" + allocation.getSubstituteCode() + "]供应商认证缺失或已过期");
        }
        if ("DEVIATION".equalsIgnoreCase(allocation.getComplianceMode())
                && (StrUtil.isBlank(allocation.getDeviationPermitNo())
                || StrUtil.isBlank(allocation.getDeviationReason())
                || allocation.getDeviationValidEnd() == null
                || validationDate.isAfter(allocation.getDeviationValidEnd()))) {
            throw new CheckedException("替代料[" + allocation.getSubstituteCode() + "]偏离许可缺失或已过期");
        }
    }

    private boolean isCompliantStock(WmsLiveStockEntity stock, PlanMatSubstituteAllocationDTO allocation) {
        if (CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())
                && !allocation.getCertifiedSupplierCodes().contains(stock.getSupplierCode())) {
            return false;
        }
        String quality = StrUtil.blankToDefault(StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        return CollUtil.isEmpty(allocation.getAllowedQualityResults())
                || allocation.getAllowedQualityResults().contains(quality);
    }

    private boolean isGenerallyUsableStock(WmsLiveStockEntity stock) {
        if (stock == null || stock.getStatus() == null || stock.getStatus() != 0
                || stock.getMatNum() == null || stock.getMatNum() <= 0) {
            return false;
        }
        if (stock.getMatValidEndDate() != null && stock.getMatValidEndDate().isBefore(LocalDate.now())) {
            return false;
        }
        String result = StrUtil.blankToDefault(StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        if (Set.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE").contains(result)) {
            return true;
        }
        boolean qualityRequired = StrUtil.isNotBlank(stock.getIsQualityTest()) && !"0".equals(stock.getIsQualityTest());
        return !qualityRequired && StrUtil.isBlank(result);
    }

    private BigDecimal stockQuantity(WmsLiveStockEntity stock) {
        return stock == null || stock.getMatNum() == null
                ? BigDecimal.ZERO : BigDecimal.valueOf(stock.getMatNum());
    }

    private void deductPlanAvailable(Map<String, BigDecimal> available, String materialCode, BigDecimal quantity) {
        if (StrUtil.isBlank(materialCode) || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0
                || !available.containsKey(materialCode)) {
            return;
        }
        available.compute(materialCode, (code, current) ->
                Optional.ofNullable(current).orElse(BigDecimal.ZERO).subtract(quantity).max(BigDecimal.ZERO));
    }

    /**
     * 下发任务
     *
     * @param ids MRP运算记录表ids
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean issueByIds(Long[] ids) {
        if (ids == null || ids.length == 0) {
            throw new CheckedException("请选择需要下达的MRP记录");
        }
        List<Long> mrpIds = Arrays.stream(ids).filter(Objects::nonNull).distinct().toList();
        if (mrpIds.size() != ids.length) {
            throw new CheckedException("MRP下达记录不能为空或重复");
        }
        // 下达、WMS幂等预留和本地供应单生成必须串行，避免同一MRP并发生成重复下游单据。
        List<PlanMatMrpEntity> planMatMrpEntities = this.list(
                new LambdaQueryWrapper<PlanMatMrpEntity>().in(PlanMatMrpEntity::getId, mrpIds)
                        .orderByAsc(PlanMatMrpEntity::getId).last("FOR UPDATE"));
        if (planMatMrpEntities.size() != mrpIds.size()) {
            throw new CheckedException("部分MRP记录不存在，请刷新后重试");
        }

        for (PlanMatMrpEntity planMatMrpEntity : planMatMrpEntities) {
            requireNoPendingDemandChange(planMatMrpEntity);
            if (Boolean.TRUE.equals(planMatMrpEntity.getStatus())) {
                throw new CheckedException("MRP记录[" + planMatMrpEntity.getId() + "]已经下达");
            }
            if (StrUtil.isBlank(planMatMrpEntity.getDemandWarehouseCode())) {
                throw new CheckedException("物料[" + planMatMrpEntity.getMatCode()
                        + "]缺少需求仓库，请重新运算MRP后再下达");
            }
            if (Set.of(MrpSubstituteStatusEnum.PENDING.getCode(),
                            MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode(),
                            MrpSubstituteStatusEnum.POLICY_CONFLICT.getCode())
                    .contains(planMatMrpEntity.getSubstituteStatus())) {
                throw new CheckedException("物料[" + planMatMrpEntity.getMatCode()
                        + "]存在待处理替代建议，拒绝原建议或完成受控重算后才能下达");
            }
            if ((MrpSubstituteStatusEnum.APPROVED.getCode().equals(planMatMrpEntity.getSubstituteStatus())
                    || MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode().equals(planMatMrpEntity.getSubstituteStatus()))
                    && (!"ACTIVE".equals(planMatMrpEntity.getMaterialReservationStatus())
                    || StrUtil.isBlank(planMatMrpEntity.getMaterialReservationNo()))) {
                throw new CheckedException("物料[" + planMatMrpEntity.getMatCode() + "]替代方案尚未形成有效WMS预留，禁止下达");
            }
            if (planMatMrpEntity.getNum() != null && planMatMrpEntity.getNum().compareTo(BigDecimal.ZERO) > 0
                    && MatAttributeEnum.findByCode(planMatMrpEntity.getType()) == null) {
                throw new CheckedException("物料[" + planMatMrpEntity.getMatCode() + "]属性未知");
            }
        }
        requireActiveReservations(planMatMrpEntities);

        List<PlanMatMrpEntity> newlyReservedMainMaterials = new ArrayList<>();
        try {
            for (PlanMatMrpEntity planMatMrpEntity : planMatMrpEntities) {
                reserveMainMaterialForIssue(planMatMrpEntity, newlyReservedMainMaterials);
            }
            requireActiveMainReservations(planMatMrpEntities);
            for (PlanMatMrpEntity planMatMrpEntity : planMatMrpEntities) {
                if (planMatMrpEntity.getNum() == null
                        || planMatMrpEntity.getNum().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                MatAttributeEnum matAttributeEnum = MatAttributeEnum.findByCode(planMatMrpEntity.getType());
                switch (matAttributeEnum) {
                    case MES -> mesHandler(planMatMrpEntity);
                    case PMS -> pmsHandler(planMatMrpEntity);
                    case OUT -> outHandler(planMatMrpEntity);
                }
            }
            // 修改MRP运算数据的状态为已下发
            this.update(new LambdaUpdateWrapper<PlanMatMrpEntity>().in(PlanMatMrpEntity::getId, mrpIds)
                    .set(PlanMatMrpEntity::getStatus, true));
            return Boolean.TRUE;
        } catch (RuntimeException ex) {
            compensateMainReservations(newlyReservedMainMaterials, ex);
            throw ex;
        }
    }

    private void requireNoPendingDemandChange(PlanMatMrpEntity mrp) {
        if (demandChangeMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandChangeEntity>()
                .eq(PlnProductDemandChangeEntity::getDemandNo, mrp.getPlanDemandNo())
                .eq(PlnProductDemandChangeEntity::getFactoryCode, mrp.getFactoryCode())
                .eq(PlnProductDemandChangeEntity::getStatus, "SUBMITTED")) != 0) {
            throw new CheckedException("产品需求[" + mrp.getPlanDemandNo()
                    + "]有待复核变更，禁止变更MRP替代决策或下达供应");
        }
    }

    private void reserveMainMaterialForIssue(PlanMatMrpEntity mrp,
                                             List<PlanMatMrpEntity> newlyReserved) {
        BigDecimal quantity = Optional.ofNullable(mrp.getStockAllocatedNum()).orElse(BigDecimal.ZERO);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<PlanMrpInventoryPeggingEntity> peggingLines = inventoryPeggingService.listByMrpId(mrp.getId());
        if (peggingLines.stream().anyMatch(item -> item.getSourceStockId() == null
                || item.getPeggedNum() == null || item.getPeggedNum().signum() <= 0
                || StrUtil.isBlank(item.getEvidenceFingerprint()))) {
            throw new CheckedException("主料[" + mrp.getMatCode() + "]库存Pegging明细不完整，请重新执行MRP");
        }
        BigDecimal pegged = peggingLines.stream().filter(item -> "ON_HAND".equals(item.getSourceType()))
                .filter(item -> Set.of("PLANNED", "RESERVED").contains(item.getStatus()))
                .map(PlanMrpInventoryPeggingEntity::getPeggedNum).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (peggingLines.isEmpty() || pegged.compareTo(quantity) != 0
                || StrUtil.isBlank(mrp.getInventoryPeggingFingerprint())) {
            throw new CheckedException("主料[" + mrp.getMatCode()
                    + "]缺少完整的库存Pegging证据，请重新执行MRP后再下达");
        }
        validateInventoryPeggingEvidence(mrp, peggingLines);
        List<WmsMaterialReservationStockAllocationDTO> stockAllocations = peggingLines.stream()
                .filter(item -> "ON_HAND".equals(item.getSourceType()))
                .collect(Collectors.groupingBy(PlanMrpInventoryPeggingEntity::getSourceStockId,
                        LinkedHashMap::new, Collectors.toList())).entrySet().stream()
                .map(entry -> new WmsMaterialReservationStockAllocationDTO()
                        .setStockId(entry.getKey())
                        .setAllocatedNum(entry.getValue().stream()
                                .map(PlanMrpInventoryPeggingEntity::getPeggedNum)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .setAllocationSequence(entry.getValue().stream()
                                .map(PlanMrpInventoryPeggingEntity::getAllocationSequence)
                                .filter(Objects::nonNull).min(Integer::compareTo).orElse(null)))
                .sorted(Comparator.comparing(WmsMaterialReservationStockAllocationDTO::getAllocationSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        boolean hadReservation = StrUtil.isNotBlank(mrp.getMainMaterialReservationNo());
        WmsMaterialReservationCreateDTO command = new WmsMaterialReservationCreateDTO();
        command.setRequestId("PLAN-MRP-MAIN-" + mrp.getId());
        command.setSourceType("PLAN_MRP_MAIN");
        command.setSourceId(mrp.getId());
        command.setSourceNo(mrp.getPlanDemandNo());
        command.setFactoryCode(mrp.getFactoryCode());
        command.setWarehouseCode(mrp.getDemandWarehouseCode());
        command.setWarehouseName(mrp.getDemandWarehouseName());
        command.setCustomerCode(mrp.getCustomerCode());
        command.setRequiredDate(mrp.getDemandDate());
        command.setAllocations(List.of(new WmsMaterialReservationAllocationDTO()
                .setRequirementMatCode(mrp.getMatCode())
                .setRequirementMatName(mrp.getMatName())
                .setActualMatCode(mrp.getMatCode())
                .setActualMatName(mrp.getMatName())
                .setActualMatUnit(mrp.getMatUnit())
                .setSubstituteRatio(BigDecimal.ONE)
                .setRequirementNum(quantity)
                .setActualNum(quantity)
                .setInspectionLevel("NORMAL")
                .setComplianceMode("STANDARD")
                .setStockAllocations(stockAllocations)));
        R<WmsMaterialReservationResultDTO> response = remoteMaterialReservationService.reserve(command);
        if (response == null || !response.isOk() || response.getData() == null
                || StrUtil.isBlank(response.getData().getReservationNo())) {
            throw new CheckedException("主料[" + mrp.getMatCode() + "]正式预留失败："
                    + (response == null ? "WMS无响应" : response.getMsg()));
        }
        if (!hadReservation) {
            newlyReserved.add(mrp);
        }
        WmsMaterialReservationResultDTO reservation = response.getData();
        mrp.setMainMaterialReservationNo(reservation.getReservationNo());
        mrp.setMainMaterialReservationStatus(reservation.getStatus());
        mrp.setMainMaterialReservationSnapshot(JSONUtil.toJsonStr(reservation));
        mrp.setMainMaterialReservationTime(LocalDateTime.now());
        mrp.setInventoryPeggingStatus("RESERVED");
        if (!updateById(mrp)) {
            throw new CheckedException("主料[" + mrp.getMatCode() + "]预留结果写回失败");
        }
        inventoryPeggingService.markReserved(mrp.getId(), reservation.getReservationNo());
    }

    private void compensateMainReservations(List<PlanMatMrpEntity> records, RuntimeException original) {
        for (PlanMatMrpEntity record : records) {
            try {
                WmsMaterialReservationReleaseDTO release = new WmsMaterialReservationReleaseDTO();
                release.setRequestId("PLAN-MRP-MAIN-COMP-" + record.getId());
                release.setSourceType("PLAN_MRP_MAIN");
                release.setSourceId(record.getId());
                release.setReason("MRP下达失败，自动释放本次主料预留");
                R<Boolean> response = remoteMaterialReservationService.release(release);
                if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
                    original.addSuppressed(new CheckedException("主料[" + record.getMatCode()
                            + "]下达失败后的WMS预留释放未成功："
                            + (response == null ? "WMS无响应" : response.getMsg())));
                }
            } catch (Exception compensationError) {
                original.addSuppressed(compensationError);
            }
        }
    }

    private void requireActiveMainReservations(List<PlanMatMrpEntity> records) {
        List<PlanMatMrpEntity> reserved = records.stream()
                .filter(item -> Optional.ofNullable(item.getStockAllocatedNum()).orElse(BigDecimal.ZERO)
                        .compareTo(BigDecimal.ZERO) > 0).toList();
        if (reserved.isEmpty()) {
            return;
        }
        WmsMaterialReservationStatusQueryDTO query = new WmsMaterialReservationStatusQueryDTO();
        query.setSourceType("PLAN_MRP_MAIN");
        query.setSourceIds(reserved.stream().map(PlanMatMrpEntity::getId).collect(Collectors.toSet()));
        R<Map<Long, String>> response;
        try {
            response = remoteMaterialReservationService.statuses(query);
        } catch (Exception ex) {
            throw new CheckedException("WMS主料预留状态不可用，MRP下达已阻止", ex);
        }
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("WMS主料预留状态不可用，MRP下达已阻止");
        }
        Set<String> reservationNos = new LinkedHashSet<>();
        for (PlanMatMrpEntity record : reserved) {
            String status = response.getData().get(record.getId());
            record.setMainMaterialReservationStatus(status);
            if (!"ACTIVE".equals(status) || StrUtil.isBlank(record.getMainMaterialReservationNo())) {
                throw new CheckedException("主料[" + record.getMatCode() + "]正式预留不存在或已失效");
            }
            reservationNos.add(record.getMainMaterialReservationNo());
        }
        Map<String, WmsMaterialReservationLedgerDTO> ledgers = fetchReservationLedgers(reservationNos, true);
        for (PlanMatMrpEntity record : reserved) {
            WmsMaterialReservationLedgerDTO ledger = ledgers.get(record.getMainMaterialReservationNo());
            List<String> issues = ledger == null ? new ArrayList<>(List.of("HEADER_STATUS_BROKEN"))
                    : new ArrayList<>(Optional.ofNullable(ledger.getIntegrityIssues()).orElse(List.of()));
            if (ledger == null || !Objects.equals(record.getDemandWarehouseCode(), ledger.getWarehouseCode())) {
                issues.add("WAREHOUSE_SCOPE_BROKEN");
            }
            if (!issues.isEmpty()) {
                throw new CheckedException("主料[" + record.getMatCode() + "]预留健康检查未通过："
                        + issues.stream().map(this::reservationRiskLabel).distinct().collect(Collectors.joining("；")));
            }
        }
    }

    private void requireActiveReservations(List<PlanMatMrpEntity> records) {
        Set<Long> approvedIds = records.stream()
                .filter(item -> MrpSubstituteStatusEnum.APPROVED.getCode().equals(item.getSubstituteStatus())
                        || MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode().equals(item.getSubstituteStatus()))
                .map(PlanMatMrpEntity::getId).collect(Collectors.toSet());
        if (approvedIds.isEmpty()) {
            return;
        }
        WmsMaterialReservationStatusQueryDTO query = new WmsMaterialReservationStatusQueryDTO();
        query.setSourceType("PLAN_MRP_SUBSTITUTE");
        query.setSourceIds(approvedIds);
        R<Map<Long, String>> response;
        try {
            response = remoteMaterialReservationService.statuses(query);
        } catch (Exception ex) {
            throw new CheckedException("WMS预留状态不可用，为避免无预留下达，本次操作已阻止", ex);
        }
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("WMS预留状态查询失败，为避免无预留下达，本次操作已阻止");
        }
        for (PlanMatMrpEntity record : records) {
            if (!approvedIds.contains(record.getId())) {
                continue;
            }
            String actualStatus = response.getData().get(record.getId());
            record.setMaterialReservationStatus(actualStatus);
            if (!"ACTIVE".equals(actualStatus) || StrUtil.isBlank(record.getMaterialReservationNo())) {
                throw new CheckedException("物料[" + record.getMatCode() + "]的WMS正式预留状态为["
                        + Objects.requireNonNullElse(actualStatus, "不存在") + "]，禁止下达");
            }
        }
        Set<String> reservationNos = records.stream().filter(record -> approvedIds.contains(record.getId()))
                .map(PlanMatMrpEntity::getMaterialReservationNo).filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, WmsMaterialReservationLedgerDTO> ledgers = fetchReservationLedgers(reservationNos, true);
        for (PlanMatMrpEntity record : records) {
            if (!approvedIds.contains(record.getId())) {
                continue;
            }
            WmsMaterialReservationLedgerDTO ledger = ledgers.get(record.getMaterialReservationNo());
            if (ledger == null) {
                throw new CheckedException("物料[" + record.getMatCode() + "]的WMS预留账本不存在，禁止下达");
            }
            List<String> issues = Optional.ofNullable(ledger.getIntegrityIssues()).orElse(List.of());
            if (!Objects.equals(record.getDemandWarehouseCode(), ledger.getWarehouseCode())) {
                issues = new ArrayList<>(issues);
                issues.add("WAREHOUSE_SCOPE_BROKEN");
            }
            if (!issues.isEmpty()) {
                throw new CheckedException("物料[" + record.getMatCode() + "]的WMS预留健康检查未通过："
                        + issues.stream().map(this::reservationRiskLabel).distinct().collect(Collectors.joining("；")));
            }
        }
    }

    private Map<String, WmsMaterialReservationLedgerDTO> fetchReservationLedgers(Set<String> reservationNos,
                                                                                 boolean failClosed) {
        if (CollUtil.isEmpty(reservationNos)) {
            return Map.of();
        }
        try {
            R<List<WmsMaterialReservationLedgerDTO>> response = remoteMaterialReservationService.ledgers(
                    new WmsMaterialReservationLedgerQueryDTO().setReservationNos(reservationNos));
            if (response == null || !response.isOk() || response.getData() == null) {
                if (failClosed) {
                    throw new CheckedException("WMS预留健康账本查询失败，为避免风险预留下达，本次操作已阻止");
                }
                return null;
            }
            return response.getData().stream().collect(Collectors.toMap(
                    WmsMaterialReservationLedgerDTO::getReservationNo, Function.identity(), (left, right) -> left));
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            if (failClosed) {
                throw new CheckedException("WMS预留健康账本不可用，为避免风险预留下达，本次操作已阻止", ex);
            }
            return null;
        }
    }

    private String reservationRiskLabel(String code) {
        return switch (StrUtil.blankToDefault(code, "UNKNOWN")) {
            case "DETAIL_QUANTITY_BROKEN" -> "预留数量账不平";
            case "RETURN_LINEAGE_BROKEN" -> "退料回补代次断链";
            case "ACTIVE_RESERVED_STOCK_UNAVAILABLE" -> "预留库存冻结、过期或未放行";
            case "ACTIVE_RESERVED_STOCK_DEFICIT" -> "预留余额超过实物库存";
            case "WAREHOUSE_SCOPE_BROKEN" -> "需求仓、预留主表与库存批次仓库不一致";
            case "COMPLIANCE_SNAPSHOT_MISSING" -> "缺少替代合规快照";
            case "COMPLIANCE_SNAPSHOT_BROKEN" -> "替代合规快照损坏";
            case "CUSTOMER_AUTHORIZATION_DRIFT" -> "客户授权不匹配";
            case "SUPPLIER_CERTIFICATION_INVALID" -> "供应商认证依据不完整";
            case "SUPPLIER_CERTIFICATION_EXPIRED" -> "供应商认证已到期";
            case "SUPPLIER_BATCH_COMPLIANCE_DRIFT" -> "预留批次供应商不在认证范围";
            case "QUALITY_RESULT_COMPLIANCE_DRIFT" -> "预留批次质量结论不符合授权";
            case "DEVIATION_PERMIT_INVALID" -> "偏离许可依据不完整";
            case "DEVIATION_PERMIT_EXPIRED" -> "偏离许可已到期";
            case "HEADER_STATUS_BROKEN" -> "预留主状态与余额不一致";
            default -> code;
        };
    }

    /**
     * 完成数量处理
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean completeNumHandle(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList) {
        Map<String, PlanMatMrpEntity> matMrpMap = this.list(new LambdaQueryWrapper<PlanMatMrpEntity>().in(PlanMatMrpEntity::getPlanDemandNo,
                        omsOrderLifeCycleDtoList.stream().map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList()))).stream()
                .collect(Collectors.toMap(
                        entity -> entity.getPlanDemandNo() + "_" + entity.getMatCode(),
                        Function.identity(),
                        (v1, v2) -> v1));
        // mrp 数据处理
        List<PlanMatMrpEntity> mrpList = new ArrayList<>();
        // 客户订单完成数量修改
        List<OmsOrderLifeCycleDTO> plnProductDemandList = new ArrayList<>();
        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : omsOrderLifeCycleDtoList) {
            PlanMatMrpEntity matMrp = matMrpMap.get(omsOrderLifeCycleDTO.getNo() + "_" + omsOrderLifeCycleDTO.getProductCode());
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                matMrp.setCompleteNum(Optional.ofNullable(matMrp.getCompleteNum()).orElse(BigDecimal.ZERO)
                        .add(omsOrderLifeCycleDTO.getFinishNum()));
                mrpList.add(matMrp);
            }
            if (matMrp.getSubitemBomId() != null && matMrp.getSubitemBomId() == 0) {
                plnProductDemandList.add(omsOrderLifeCycleDTO);
            }
        }
        // 如果有产品数量修改则修改客户订单完成数量
        if (plnProductDemandList.size() > 0) {
            customerOrderHandle(plnProductDemandList);
        }

        if (mrpList.size() > 0) {
            this.updateBatchById(mrpList);
        }
        return Boolean.TRUE;
    }

    /*
     * 客户订单完成数量处理
     */
    private void customerOrderHandle(List<OmsOrderLifeCycleDTO> plnProductDemandList) {
        Map<String, PlnProductDemandEntity> plnProductDemandMap = plnProductDemandMapper.selectList(new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .in(PlnProductDemandEntity::getDemandNo, plnProductDemandList.stream()
                                .map(OmsOrderLifeCycleDTO::getNo).collect(Collectors.toList()))).stream()
                .collect(Collectors.toMap(PlnProductDemandEntity::getDemandNo, Function.identity()));

        for (OmsOrderLifeCycleDTO omsOrderLifeCycleDTO : plnProductDemandList) {
            PlnProductDemandEntity productDemand = plnProductDemandMap.get(omsOrderLifeCycleDTO.getNo());
            if (omsOrderLifeCycleDTO.getFinishNum() != null) {
                BigDecimal num = Optional.ofNullable(productDemand.getCompleteNum()).orElse(BigDecimal.ZERO)
                        .add(omsOrderLifeCycleDTO.getFinishNum());
                plnProductDemandMapper.update(new LambdaUpdateWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getDemandNo, omsOrderLifeCycleDTO.getNo())
                        .set(PlnProductDemandEntity::getCompleteNum, num));
            }
        }
    }

    /*
     * 创建生产工单
     */
    private void mesHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueEntity plnIssueEntity = new PlnIssueEntity();
        plnIssueEntity.setIssueNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueEntity.setNum(planMatMrpEntity.getNum());
        plnIssueEntity.setIssueTime(LocalDateTime.now());
        // demandDate是父项实际需要该物料的到达/完工日；orderDate只是按提前期倒排的建议释放日。
        plnIssueEntity.setDemandTime(planMatMrpEntity.getDemandDate());
        plnIssueEntity.setStatus("0");
        plnIssueEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueEntity.setType("mrp");
        plnIssueMapper.insert(plnIssueEntity);
        planMatMrpEntity.setSupplySourceType("SELF_ISSUE");
        planMatMrpEntity.setSupplySourceId(plnIssueEntity.getId());
        planMatMrpEntity.setSupplySourceNo(plnIssueEntity.getIssueNo());
        planMatMrpEntity.setSupplySourceQuantity(plnIssueEntity.getNum());
        planMatMrpEntity.setSupplySourceStatus("CREATED");
        if (!updateById(planMatMrpEntity)) throw new CheckedException("MRP下层自制供应来源回写失败");
    }

    /*
     * 创建采购计划
     */
    private void pmsHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueMonthEntity plnIssueMonthEntity = new PlnIssueMonthEntity();
        plnIssueMonthEntity.setIssueMonthNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueMonthEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueMonthEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueMonthEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueMonthEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueMonthEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueMonthEntity.setMaterialAttribute(planMatMrpEntity.getType());
        plnIssueMonthEntity.setNum(planMatMrpEntity.getNum());
        plnIssueMonthEntity.setIssueTime(LocalDate.from(LocalDateTime.now()));
        plnIssueMonthEntity.setStatus("0");
        plnIssueMonthEntity.setDemandTime(planMatMrpEntity.getDemandDate());
        plnIssueMonthEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueMonthEntity.setType("mrp");
        plnIssueMonthMapper.insert(plnIssueMonthEntity);
        persistIssueMonthSupplySource(planMatMrpEntity, plnIssueMonthEntity, "PURCHASE_ISSUE_MONTH");
    }

    /*
     * 创建外协任务
     */
    private void outHandler(PlanMatMrpEntity planMatMrpEntity) {
        PlnIssueMonthEntity plnIssueMonthEntity = new PlnIssueMonthEntity();
        plnIssueMonthEntity.setIssueMonthNo(planMatMrpEntity.getPlanDemandNo());
        plnIssueMonthEntity.setCustomerName(planMatMrpEntity.getCustomerName());
        plnIssueMonthEntity.setCustomerCode(planMatMrpEntity.getCustomerCode());
        plnIssueMonthEntity.setProductCode(planMatMrpEntity.getMatCode());
        plnIssueMonthEntity.setProductName(planMatMrpEntity.getMatName());
        plnIssueMonthEntity.setSpecificationModel(planMatMrpEntity.getMatSpec());
        plnIssueMonthEntity.setMaterialAttribute(planMatMrpEntity.getType());
        plnIssueMonthEntity.setNum(planMatMrpEntity.getNum());
        plnIssueMonthEntity.setIssueTime(LocalDate.from(LocalDateTime.now()));
        plnIssueMonthEntity.setStatus("0");
        plnIssueMonthEntity.setDemandTime(planMatMrpEntity.getDemandDate());
        plnIssueMonthEntity.setFactoryCode(planMatMrpEntity.getFactoryCode());
        plnIssueMonthEntity.setType("mrp");
        plnIssueMonthMapper.insert(plnIssueMonthEntity);
        persistIssueMonthSupplySource(planMatMrpEntity, plnIssueMonthEntity, "OUTSOURCE_ISSUE_MONTH");
    }

    private void persistIssueMonthSupplySource(PlanMatMrpEntity mrp, PlnIssueMonthEntity source, String sourceType) {
        mrp.setSupplySourceType(sourceType);
        mrp.setSupplySourceId(source.getId());
        mrp.setSupplySourceNo(source.getIssueMonthNo());
        mrp.setSupplySourceQuantity(source.getNum());
        mrp.setSupplySourceStatus("CREATED");
        if (!updateById(mrp)) throw new CheckedException("MRP采购/外协供应来源回写失败");
    }

    private record OperationImpactEvidence(Map<String, List<PlnSchedulingOperationEntity>> operationsByIssue,
                                           Set<String> issuesWithAnySchedule,
                                           Set<String> assignmentKeys,
                                           boolean materialAssignmentsVerified) {
    }

    private record SupplyMoment(LocalDateTime time, String precision) {
    }

    private record ProductionLot(BigDecimal openQuantity, SupplyMoment moment) {
    }
}
