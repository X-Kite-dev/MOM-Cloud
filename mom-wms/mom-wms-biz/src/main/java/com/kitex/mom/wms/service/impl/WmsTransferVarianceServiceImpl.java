package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.vo.WmsTransferVarianceCaseVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WmsTransferVarianceServiceImpl implements WmsTransferVarianceService {
    private static final String IN_TRANSIT = "IN_TRANSIT";
    private static final String PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";
    private static final String RECEIVED = "RECEIVED";
    private static final String DISCREPANCY_PENDING = "DISCREPANCY_PENDING";
    private static final String DISCREPANCY_RESOLVED = "DISCREPANCY_RESOLVED";
    private static final Set<String> OPEN_RECEIPT_STATES = Set.of(IN_TRANSIT, PARTIALLY_RECEIVED);
    private static final Set<String> REASON_CODES = Set.of("COUNT_SHORT", "COUNT_OVER", "DAMAGE",
            "QUALITY_REJECTED", "PACKING_ERROR", "TRANSPORT_LOSS", "OTHER");
    private static final Set<String> RESPONSIBLE_TYPES = Set.of("SOURCE_WAREHOUSE", "CARRIER",
            "TARGET_WAREHOUSE", "QUALITY", "UNKNOWN");

    private final WmsAllocationBillMapper billMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsContainerBarcodeMapper containerBarcodeMapper;
    private final WmsTransferReceiptMapper receiptMapper;
    private final WmsTransferReceiptLineMapper receiptLineMapper;
    private final WmsTransferVarianceCaseMapper caseMapper;
    private final WmsTransferVarianceLineMapper varianceLineMapper;
    private final WmsTransferVarianceOperationMapper operationMapper;
    private final WmsTransferVariancePolicyService policyService;
    private final WmsTransferRoutePolicyService routePolicyService;
    private final WmsAllocationBillService allocationBillService;
    private final WmsLiveStockService liveStockService;
    private final RemoteWarehouseService remoteWarehouseService;
    private final RemoteWarehouseLocationService remoteWarehouseLocationService;
    private final PermissionService permissionService;
    private final WmsPlanShortageEventOutboxService planShortageEventOutboxService;
    private final WmsWarehouseBusinessGuard warehouseBusinessGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsTransferReceiptDTO.Result receive(WmsTransferReceiptDTO.Command command) {
        validateReceipt(command);
        String snapshot = receiptSnapshot(command);
        String requestId = command.getRequestId().trim();
        WmsAllocationBillEntity bill = lockBill(command.getAllocationBillId());
        requireFactory(bill.getFactoryCode());
        requireWarehousePermission("wms_wmsAllocationReceive_rec", bill.getNewWarehouseCode(), "无权接收至该仓库");
        WmsTransferReceiptEntity duplicate = receiptMapper.selectOne(new LambdaQueryWrapper<WmsTransferReceiptEntity>()
                .eq(WmsTransferReceiptEntity::getRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getRequestSnapshot(), snapshot), "接收幂等号已被不同请求使用");
            require(Objects.equals(duplicate.getAllocationBillId(), bill.getId())
                    && Objects.equals(duplicate.getFactoryCode(), bill.getFactoryCode()), "接收幂等号不属于当前调拨单");
            require(Objects.equals(duplicate.getReceiverCode(), currentUserCode()), "接收幂等号不属于当前操作人");
            return receiptResult(duplicate);
        }
        require(Boolean.TRUE.equals(bill.getSourceDeducted()), "历史调拨单仍须使用原全量接收入口");
        require(OPEN_RECEIPT_STATES.contains(bill.getAllocationState()), "当前调拨状态不允许继续接收");
        require(warehouseBusinessGuard.check(bill.getNewWarehouseCode(), "allocation", true),
                "目标仓未配置调拨接收业务");

        List<WmsInoutFlowEntity> issues = issueFlows(bill.getId());
        Map<Long, WmsInoutFlowEntity> issueById = issues.stream()
                .collect(Collectors.toMap(WmsInoutFlowEntity::getId, Function.identity()));
        Map<Long, BigDecimal> receivedBefore = receivedByIssue(bill.getId());
        Map<Long, WmsTransferReceiptDTO.Line> requested = new LinkedHashMap<>();
        for (WmsTransferReceiptDTO.Line line : command.getLines()) {
            require(line != null && line.getSourceFlowId() != null
                    && requested.put(line.getSourceFlowId(), line) == null, "接收明细来源流水不能为空或重复");
            require(issueById.containsKey(line.getSourceFlowId()), "接收明细不属于当前调拨单");
            require(line.getActualReceivedQuantity() != null
                    && line.getActualReceivedQuantity().compareTo(BigDecimal.ZERO) >= 0, "实收数量不能小于0");
        }
        List<Long> openIssueIds = issues.stream().filter(issue -> remaining(issue, receivedBefore).signum() > 0)
                .map(WmsInoutFlowEntity::getId).toList();
        if (Boolean.TRUE.equals(command.getFinalReceipt())) {
            require(requested.keySet().containsAll(openIssueIds), "最终接收必须逐行确认全部未结在途明细，短收行请填写0");
        } else {
            require(requested.values().stream().anyMatch(line -> line.getActualReceivedQuantity().signum() > 0),
                    "非最终接收至少有一行实收数量大于0");
        }

        int sequence = Math.toIntExact(receiptMapper.selectCount(new LambdaQueryWrapper<WmsTransferReceiptEntity>()
                .eq(WmsTransferReceiptEntity::getAllocationBillId, bill.getId()))) + 1;
        MomUser user = SecurityUtils.getUser();
        WmsTransferReceiptEntity receipt = new WmsTransferReceiptEntity()
                .setReceiptNo("TR-" + IdUtil.getSnowflakeNextIdStr()).setAllocationBillId(bill.getId())
                .setAllocationBillNo(bill.getBillNo()).setRequestId(requestId)
                .setRequestSnapshot(snapshot).setReceiptSequence(sequence)
                .setFinalReceipt(Boolean.TRUE.equals(command.getFinalReceipt())).setReceiptStatus("PROCESSING")
                .setLineCount(requested.size()).setReceiverCode(user.getUsername()).setReceiverName(operatorName(user))
                .setReceiptTime(LocalDateTime.now()).setFactoryCode(bill.getFactoryCode());
        require(receiptMapper.insert(receipt) == 1, "分批接收记录保存失败");

        MdmWarehouseEntity target = requireWarehouse(bill.getNewWarehouseCode(), bill.getFactoryCode());
        Map<Long, BigDecimal> acceptedThisReceipt = new HashMap<>();
        Map<Long, BigDecimal> overThisReceipt = new HashMap<>();
        for (Map.Entry<Long, WmsTransferReceiptDTO.Line> entry : requested.entrySet()) {
            WmsInoutFlowEntity issue = issueById.get(entry.getKey());
            WmsTransferReceiptDTO.Line input = entry.getValue();
            BigDecimal before = receivedBefore.getOrDefault(issue.getId(), BigDecimal.ZERO);
            BigDecimal remaining = quantity(issue).subtract(before).max(BigDecimal.ZERO);
            BigDecimal actual = input.getActualReceivedQuantity();
            BigDecimal accepted = actual.min(remaining);
            BigDecimal over = actual.subtract(accepted);
            require(Boolean.TRUE.equals(command.getFinalReceipt()) || over.signum() == 0,
                    "溢收只能在最终接收时申报，避免在途数量和隔离实物失配");
            MdmWarehouseLocationEntity site = null;
            if (actual.signum() > 0) {
                site = requireLocation(input.getTargetSiteCode(), target.getWarehouseCode(), bill.getFactoryCode());
                requireContainer(input.getTargetContainerCode(), target);
            }
            if (accepted.signum() > 0) {
                WmsInoutFlowEntity acceptedSource = quantityCopy(issue, accepted);
                liveStockService.transferIn(acceptedSource, target.getWarehouseCode(), target.getWarehouseName(),
                        site.getLocationCode(), site.getLocationName(), input.getTargetContainerCode());
                WmsInoutFlowEntity flow = inboundFlow(bill, acceptedSource, issue.getId(),
                        target.getWarehouseCode(), target.getWarehouseName(), site.getLocationCode(),
                        site.getLocationName(), input.getTargetContainerCode(), "RECEIVE", requestId);
                flow.setTransferReceiptId(receipt.getId());
                require(flowMapper.insert(flow) == 1, "调拨分批接收入库流水保存失败");
            }
            BigDecimal after = before.add(accepted);
            WmsTransferReceiptLineEntity receiptLine = new WmsTransferReceiptLineEntity()
                    .setReceiptId(receipt.getId()).setAllocationBillId(bill.getId()).setSourceFlowId(issue.getId())
                    .setMaterialCode(issue.getMatCode()).setMaterialName(issue.getMatName())
                    .setMaterialBatch(issue.getMatBatch()).setMaterialUnit(issue.getMatUnit())
                    .setIssuedQuantity(quantity(issue)).setReceivedBeforeQuantity(before)
                    .setActualReceivedQuantity(actual).setAcceptedQuantity(accepted).setOverQuantity(over)
                    .setRemainingAfterQuantity(quantity(issue).subtract(after).max(BigDecimal.ZERO))
                    .setTargetWarehouseCode(target.getWarehouseCode())
                    .setTargetSiteCode(site == null ? null : site.getLocationCode())
                    .setTargetSiteName(site == null ? null : site.getLocationName())
                    .setTargetContainerCode(StrUtil.trim(input.getTargetContainerCode()))
                    .setFactoryCode(bill.getFactoryCode());
            require(receiptLineMapper.insert(receiptLine) == 1, "调拨分批接收明细保存失败");
            acceptedThisReceipt.put(issue.getId(), accepted);
            overThisReceipt.put(issue.getId(), over);
        }

        Map<Long, BigDecimal> receivedAfter = new HashMap<>(receivedBefore);
        acceptedThisReceipt.forEach((key, value) -> receivedAfter.merge(key, value, BigDecimal::add));
        if (!Boolean.TRUE.equals(command.getFinalReceipt())) {
            bill.setAllocationState(PARTIALLY_RECEIVED).setLastReceiptTime(receipt.getReceiptTime());
            receipt.setReceiptStatus("PARTIAL");
        } else {
            boolean shortExists = issues.stream().anyMatch(issue -> remaining(issue, receivedAfter).signum() > 0);
            boolean overExists = overThisReceipt.values().stream().anyMatch(value -> value.signum() > 0);
            bill.setReceiveRequestId(requestId).setReceiveRequestSnapshot(snapshot)
                    .setLastReceiptTime(receipt.getReceiptTime()).setFinishTime(receipt.getReceiptTime())
                    .setFinishByName(operatorName(user));
            for (WmsInoutFlowEntity issue : issues) liveStockService.releaseTransferSourceContainer(issue);
            if (shortExists || overExists) {
                WmsTransferVarianceCaseEntity variance = createVarianceCase(bill, receipt, issues, receivedAfter,
                        overThisReceipt, command);
                bill.setAllocationState(DISCREPANCY_PENDING).setVarianceCaseId(variance.getId());
                receipt.setReceiptStatus("VARIANCE_PENDING").setVarianceCaseId(variance.getId());
            } else {
                bill.setAllocationState(RECEIVED);
                receipt.setReceiptStatus("COMPLETE");
            }
        }
        require(billMapper.updateById(bill) == 1 && receiptMapper.updateById(receipt) == 1,
                "调拨接收状态更新失败");
        return receiptResult(receipt);
    }

    @Override
    public Page<WmsTransferVarianceCaseEntity> queryCases(Page<WmsTransferVarianceCaseEntity> page,
                                                          WmsTransferVarianceDTO.CaseQuery query) {
        String factory = currentFactory();
        LambdaQueryWrapper<WmsTransferVarianceCaseEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WmsTransferVarianceCaseEntity::getFactoryCode, factory)
                .like(query != null && StrUtil.isNotBlank(query.getCaseNo()), WmsTransferVarianceCaseEntity::getCaseNo,
                        query == null ? null : query.getCaseNo())
                .like(query != null && StrUtil.isNotBlank(query.getAllocationBillNo()),
                        WmsTransferVarianceCaseEntity::getAllocationBillNo,
                        query == null ? null : query.getAllocationBillNo())
                .eq(query != null && StrUtil.isNotBlank(query.getStatus()), WmsTransferVarianceCaseEntity::getStatus,
                        query == null ? null : query.getStatus())
                .eq(query != null && StrUtil.isNotBlank(query.getVarianceType()),
                        WmsTransferVarianceCaseEntity::getVarianceType,
                        query == null ? null : query.getVarianceType())
                .eq(query != null && StrUtil.isNotBlank(query.getResponsibleType()),
                        WmsTransferVarianceCaseEntity::getResponsibleType,
                        query == null ? null : query.getResponsibleType())
                .orderByAsc(WmsTransferVarianceCaseEntity::getStatus)
                .orderByDesc(WmsTransferVarianceCaseEntity::getReportedTime);
        return caseMapper.selectPage(page, wrapper);
    }

    @Override
    public WmsTransferVarianceCaseVO detail(Long caseId) {
        WmsTransferVarianceCaseEntity entity = caseMapper.selectById(caseId);
        require(entity != null, "短溢收差异单不存在");
        requireFactory(entity.getFactoryCode());
        WmsTransferVarianceCaseVO vo = new WmsTransferVarianceCaseVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setLines(varianceLineMapper.selectList(new LambdaQueryWrapper<WmsTransferVarianceLineEntity>()
                .eq(WmsTransferVarianceLineEntity::getCaseId, caseId).orderByAsc(WmsTransferVarianceLineEntity::getId)));
        vo.setReceipts(receiptMapper.selectList(new LambdaQueryWrapper<WmsTransferReceiptEntity>()
                .eq(WmsTransferReceiptEntity::getAllocationBillId, entity.getAllocationBillId())
                .orderByAsc(WmsTransferReceiptEntity::getReceiptSequence)));
        List<Long> receiptIds = vo.getReceipts().stream().map(WmsTransferReceiptEntity::getId).toList();
        vo.setReceiptLines(receiptIds.isEmpty() ? List.of() : receiptLineMapper.selectList(
                new LambdaQueryWrapper<WmsTransferReceiptLineEntity>()
                        .in(WmsTransferReceiptLineEntity::getReceiptId, receiptIds)
                        .orderByAsc(WmsTransferReceiptLineEntity::getReceiptId)
                        .orderByAsc(WmsTransferReceiptLineEntity::getId)));
        vo.setOperations(operationMapper.selectList(new LambdaQueryWrapper<WmsTransferVarianceOperationEntity>()
                .eq(WmsTransferVarianceOperationEntity::getCaseId, caseId)
                .orderByAsc(WmsTransferVarianceOperationEntity::getOperationTime)
                .orderByAsc(WmsTransferVarianceOperationEntity::getId)));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void warehouseReview(WmsTransferVarianceDTO.ReviewCommand command) {
        review(command, "WAREHOUSE", "PENDING_WAREHOUSE_REVIEW");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void planReview(WmsTransferVarianceDTO.ReviewCommand command) {
        review(command, "PLAN", "PENDING_PLAN_REVIEW");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void qualityReview(WmsTransferVarianceDTO.ReviewCommand command) {
        review(command, "QUALITY", "PENDING_QUALITY_REVIEW");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmit(WmsTransferVarianceDTO.ResubmitCommand command) {
        require(command != null && command.getCaseId() != null, "差异单不能为空");
        requireText(command.getRequestId(), "请求号不能为空");
        String snapshot = JSONUtil.toJsonStr(command);
        WmsTransferVarianceCaseEntity entity = lockCase(command.getCaseId());
        if (idempotentOperation(entity, command.getRequestId(), snapshot)) return;
        require("ACTION_REQUIRED".equals(entity.getStatus()), "仅审核退回的差异单允许重新提交");
        require(Objects.equals(entity.getReporterCode(), currentUserCode()), "仅差异申报人可以补充后重新提交");
        validateVarianceContext(command.getReasonCode(), command.getReasonDescription(), command.getResponsibleType());
        String before = entity.getStatus();
        entity.setReasonCode(command.getReasonCode()).setReasonDescription(command.getReasonDescription().trim())
                .setResponsibleType(command.getResponsibleType()).setEvidence(StrUtil.trim(command.getEvidence()));
        String stage = entity.getRejectedStage();
        if ("WAREHOUSE".equals(stage)) {
            entity.setWarehouseDecision(null).setWarehouseReviewComment(null).setWarehouseReviewerCode(null)
                    .setWarehouseReviewerName(null).setWarehouseReviewTime(null).setStatus("PENDING_WAREHOUSE_REVIEW");
        } else if ("PLAN".equals(stage)) {
            entity.setPlanDecision(null).setPlanReviewComment(null).setPlanReviewerCode(null)
                    .setPlanReviewerName(null).setPlanReviewTime(null).setStatus("PENDING_PLAN_REVIEW");
        } else {
            entity.setQualityDecision(null).setQualityReviewComment(null).setQualityReviewerCode(null)
                    .setQualityReviewerName(null).setQualityReviewTime(null).setStatus("PENDING_QUALITY_REVIEW");
        }
        entity.setRejectedStage(null);
        require(caseMapper.updateById(entity) == 1, "差异单重新提交失败");
        saveOperation(entity, command.getRequestId(), snapshot, "RESUBMIT", before, entity.getStatus(),
                "补充原因、责任和证据后重新提交");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispose(WmsTransferVarianceDTO.DispositionCommand command) {
        require(command != null && command.getCaseId() != null, "差异单不能为空");
        requireText(command.getRequestId(), "处置请求号不能为空");
        requireText(command.getRemark(), "处置说明不能为空");
        String snapshot = dispositionSnapshot(command);
        WmsTransferVarianceCaseEntity entity = lockCase(command.getCaseId());
        if (idempotentOperation(entity, command.getRequestId(), snapshot)) return;
        require("PENDING_DISPOSITION".equals(entity.getStatus()), "差异单尚未完成全部审核或已处置");
        require(!Objects.equals(entity.getReporterCode(), currentUserCode()), "差异申报人不能执行最终处置");
        WmsAllocationBillEntity bill = lockBill(entity.getAllocationBillId());
        List<WmsTransferVarianceLineEntity> lines = varianceLines(entity.getId());
        boolean hasShort = lines.stream().anyMatch(line -> positive(line.getShortQuantity()));
        boolean hasOver = lines.stream().anyMatch(line -> positive(line.getOverQuantity()));
        if (hasShort) require(Set.of("SUPPLEMENT_TRANSFER", "CLAIM", "LOSS").contains(command.getShortDisposition()),
                "短收处置必须选择补调、索赔或报损");
        if (hasOver) require(Set.of("ACCEPT_TO_STOCK", "RETURN_TO_SOURCE").contains(command.getOverDisposition()),
                "溢收处置必须选择批准入库或退回源仓");
        if ("CLAIM".equals(command.getShortDisposition()))
            requireText(command.getReferenceNo(), "索赔处置必须填写外部索赔单号");

        Long supplementBillId = null;
        if ("SUPPLEMENT_TRANSFER".equals(command.getShortDisposition())) {
            supplementBillId = createSupplementTransfer(entity, bill, lines, command);
        }
        if (hasOver) disposeOver(entity, bill, lines, command);
        lines.forEach(line -> {
            line.setHeldOverQuantity(BigDecimal.ZERO).setDispositionStatus("RESOLVED");
            varianceLineMapper.updateById(line);
        });
        String before = entity.getStatus();
        entity.setShortDisposition(hasShort ? command.getShortDisposition() : null)
                .setOverDisposition(hasOver ? command.getOverDisposition() : null)
                .setDispositionReferenceNo(StrUtil.trim(command.getReferenceNo()))
                .setDispositionRemark(command.getRemark().trim()).setSupplementAllocationBillId(supplementBillId)
                .setResolvedBy(currentUserCode()).setResolvedByName(currentUserName())
                .setResolvedTime(LocalDateTime.now()).setStatus("RESOLVED");
        bill.setAllocationState(DISCREPANCY_RESOLVED);
        require(caseMapper.updateById(entity) == 1 && billMapper.updateById(bill) == 1, "差异处置状态更新失败");
        saveOperation(entity, command.getRequestId(), snapshot, "DISPOSE", before, entity.getStatus(),
                "短收=" + StrUtil.blankToDefault(command.getShortDisposition(), "无") + "，溢收="
                        + StrUtil.blankToDefault(command.getOverDisposition(), "无") + "，说明=" + command.getRemark());
    }

    private void review(WmsTransferVarianceDTO.ReviewCommand command, String stage, String expectedStatus) {
        require(command != null && command.getCaseId() != null, "差异单不能为空");
        requireText(command.getRequestId(), "审核请求号不能为空");
        require(Set.of("APPROVE", "REJECT").contains(command.getDecision()), "审核结论必须为APPROVE或REJECT");
        requireText(command.getComment(), "审核意见不能为空");
        String snapshot = reviewSnapshot(command, stage);
        WmsTransferVarianceCaseEntity entity = lockCase(command.getCaseId());
        if (idempotentOperation(entity, command.getRequestId(), snapshot)) return;
        require(expectedStatus.equals(entity.getStatus()), "当前差异单不在" + stage + "审核阶段");
        String reviewer = currentUserCode();
        require(!Objects.equals(entity.getReporterCode(), reviewer), "差异申报人与审核人必须独立");
        require(!Objects.equals(entity.getWarehouseReviewerCode(), reviewer)
                        && !Objects.equals(entity.getPlanReviewerCode(), reviewer)
                        && !Objects.equals(entity.getQualityReviewerCode(), reviewer),
                "仓库、计划和质量审核必须由不同人员完成");
        String before = entity.getStatus();
        if ("REJECT".equals(command.getDecision())) {
            setReview(entity, stage, command);
            entity.setRejectedStage(stage).setStatus("ACTION_REQUIRED");
        } else {
            if ("WAREHOUSE".equals(stage) && hasOver(entity.getId())) applyOverSourceAdjustments(entity, command);
            setReview(entity, stage, command);
            if ("WAREHOUSE".equals(stage) && Boolean.TRUE.equals(entity.getPlanReviewRequired())) {
                entity.setStatus("PENDING_PLAN_REVIEW");
            } else if (("WAREHOUSE".equals(stage) || "PLAN".equals(stage))
                    && Boolean.TRUE.equals(entity.getQualityReviewRequired())) {
                entity.setStatus("PENDING_QUALITY_REVIEW");
            } else {
                entity.setStatus("PENDING_DISPOSITION");
            }
        }
        require(caseMapper.updateById(entity) == 1, "差异审核状态更新失败");
        saveOperation(entity, command.getRequestId(), snapshot, stage + "_REVIEW", before, entity.getStatus(),
                command.getDecision() + "：" + command.getComment().trim());
    }

    private void setReview(WmsTransferVarianceCaseEntity entity, String stage,
                           WmsTransferVarianceDTO.ReviewCommand command) {
        LocalDateTime now = LocalDateTime.now();
        if ("WAREHOUSE".equals(stage)) entity.setWarehouseDecision(command.getDecision())
                .setWarehouseReviewComment(command.getComment().trim()).setWarehouseReviewerCode(currentUserCode())
                .setWarehouseReviewerName(currentUserName()).setWarehouseReviewTime(now);
        else if ("PLAN".equals(stage)) entity.setPlanDecision(command.getDecision())
                .setPlanReviewComment(command.getComment().trim()).setPlanReviewerCode(currentUserCode())
                .setPlanReviewerName(currentUserName()).setPlanReviewTime(now);
        else entity.setQualityDecision(command.getDecision()).setQualityReviewComment(command.getComment().trim())
                    .setQualityReviewerCode(currentUserCode()).setQualityReviewerName(currentUserName())
                    .setQualityReviewTime(now);
    }

    private void applyOverSourceAdjustments(WmsTransferVarianceCaseEntity entity,
                                            WmsTransferVarianceDTO.ReviewCommand command) {
        List<WmsTransferVarianceLineEntity> overLines = varianceLines(entity.getId()).stream()
                .filter(line -> positive(line.getOverQuantity())).toList();
        require(command.getOverSourceAdjustments() != null && !command.getOverSourceAdjustments().isEmpty(),
                "溢收事实确认时必须逐行选择源仓库存并补扣，防止源仓产生账实虚高");
        Map<Long, List<WmsTransferVarianceDTO.SourceAdjustment>> byLine = command.getOverSourceAdjustments().stream()
                .peek(item -> require(item != null && item.getVarianceLineId() != null && item.getSourceStockId() != null
                        && positive(item.getQuantity()), "溢收源仓补扣明细无效"))
                .collect(Collectors.groupingBy(WmsTransferVarianceDTO.SourceAdjustment::getVarianceLineId));
        require(byLine.keySet().equals(overLines.stream().map(WmsTransferVarianceLineEntity::getId)
                .collect(Collectors.toSet())), "溢收源仓补扣必须覆盖全部且仅覆盖溢收明细");
        Set<Long> stockIds = new HashSet<>();
        Map<Long, WmsInoutFlowEntity> issues = issueFlows(entity.getAllocationBillId()).stream()
                .collect(Collectors.toMap(WmsInoutFlowEntity::getId, Function.identity()));
        for (WmsTransferVarianceLineEntity line : overLines) {
            List<WmsTransferVarianceDTO.SourceAdjustment> adjustments = byLine.get(line.getId());
            BigDecimal total = adjustments.stream().map(WmsTransferVarianceDTO.SourceAdjustment::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(total.compareTo(line.getOverQuantity()) == 0, "物料[" + line.getMaterialCode() + "]源仓补扣数量必须等于溢收数量");
            WmsInoutFlowEntity original = issues.get(line.getSourceFlowId());
            require(original != null, "溢收明细缺少原发起流水");
            for (WmsTransferVarianceDTO.SourceAdjustment adjustment : adjustments) {
                require(stockIds.add(adjustment.getSourceStockId()), "同一源库存行不能在溢收补扣中重复使用");
                WmsLiveStockEntity stock = liveStockService.transferOut(adjustment.getSourceStockId(),
                        adjustment.getQuantity().doubleValue(), original.getWarehouseCode());
                require(sameTraceAttributes(original, stock), "源仓补扣库存与溢收物料的批次、供应商、效期或质量属性不一致");
                WmsInoutFlowEntity flow = outboundAdjustmentFlow(entity, line, stock,
                        adjustment.getSourceStockId(), command.getRequestId());
                require(flowMapper.insert(flow) == 1, "溢收源仓补扣流水保存失败");
            }
        }
    }

    private Long createSupplementTransfer(WmsTransferVarianceCaseEntity entity, WmsAllocationBillEntity sourceBill,
                                          List<WmsTransferVarianceLineEntity> lines,
                                          WmsTransferVarianceDTO.DispositionCommand command) {
        requireText(command.getSupplementRequestId(), "补调请求号不能为空");
        requireText(command.getSupplementBillNo(), "补调单号不能为空");
        List<WmsTransferVarianceLineEntity> shortLines = lines.stream().filter(line -> positive(line.getShortQuantity())).toList();
        require(command.getSupplementAllocations() != null && !command.getSupplementAllocations().isEmpty(),
                "补调必须选择源仓实时库存行");
        Map<Long, List<WmsTransferVarianceDTO.SupplementAllocation>> byLine = command.getSupplementAllocations().stream()
                .peek(item -> require(item != null && item.getVarianceLineId() != null && item.getSourceStockId() != null
                        && positive(item.getQuantity()), "补调库存分配无效"))
                .collect(Collectors.groupingBy(WmsTransferVarianceDTO.SupplementAllocation::getVarianceLineId));
        require(byLine.keySet().equals(shortLines.stream().map(WmsTransferVarianceLineEntity::getId)
                .collect(Collectors.toSet())), "补调库存分配必须覆盖全部且仅覆盖短收明细");
        Map<Long, WmsTransferVarianceLineEntity> lineById = shortLines.stream()
                .collect(Collectors.toMap(WmsTransferVarianceLineEntity::getId, Function.identity()));
        List<WmsDetailDTO> details = new ArrayList<>();
        Set<Long> stockIds = new HashSet<>();
        for (Map.Entry<Long, List<WmsTransferVarianceDTO.SupplementAllocation>> entry : byLine.entrySet()) {
            WmsTransferVarianceLineEntity line = lineById.get(entry.getKey());
            BigDecimal total = entry.getValue().stream().map(WmsTransferVarianceDTO.SupplementAllocation::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(total.compareTo(line.getShortQuantity()) == 0, "物料[" + line.getMaterialCode() + "]补调数量必须等于短收数量");
            for (WmsTransferVarianceDTO.SupplementAllocation allocation : entry.getValue()) {
                require(stockIds.add(allocation.getSourceStockId()), "同一源库存行不能在补调中重复使用");
                WmsDetailDTO detail = new WmsDetailDTO();
                detail.setSourceStockId(allocation.getSourceStockId());
                detail.setOperateNum(allocation.getQuantity().doubleValue());
                details.add(detail);
            }
        }
        String urgency = StrUtil.blankToDefault(sourceBill.getUrgencyLevel(), "URGENT");
        WmsTransferRoutePolicyDTO.PreviewQuery routeQuery = new WmsTransferRoutePolicyDTO.PreviewQuery();
        routeQuery.setFactoryCode(sourceBill.getFactoryCode());
        routeQuery.setSourceWarehouseCode(sourceBill.getOldWarehouseCode());
        routeQuery.setTargetWarehouseCode(sourceBill.getNewWarehouseCode());
        routeQuery.setUrgencyLevel(urgency);
        routeQuery.setDepartureTime(LocalDateTime.now());
        WmsTransferRoutePolicyDTO.RoutePlan route = routePolicyService.preview(routeQuery);
        require(route != null && route.getPolicyId() != null, "补调执行前未匹配有效路线时效策略");
        WmsAllocationBillAddDTO supplement = new WmsAllocationBillAddDTO();
        supplement.setRequestId(command.getSupplementRequestId().trim());
        supplement.setBillNo(command.getSupplementBillNo().trim());
        supplement.setOldWarehouseCode(sourceBill.getOldWarehouseCode());
        supplement.setNewWarehouseCode(sourceBill.getNewWarehouseCode());
        supplement.setFactoryCode(sourceBill.getFactoryCode());
        supplement.setBillType("variance_supplement");
        supplement.setTransferPurpose("VARIANCE_SUPPLEMENT");
        supplement.setParentAllocationBillId(sourceBill.getId());
        supplement.setVarianceCaseId(entity.getId());
        supplement.setTransferRoutePolicyId(route.getPolicyId());
        supplement.setTransferRoutePolicyCode(route.getPolicyCode());
        supplement.setTransferRoutePolicySnapshot(JSONUtil.toJsonStr(route));
        supplement.setTransportMode(route.getTransportMode());
        supplement.setUrgencyLevel(route.getUrgencyLevel());
        supplement.setEstimatedArrivalTime(route.getEstimatedArrivalTime());
        supplement.setSourceBusinessType(sourceBill.getSourceBusinessType());
        supplement.setSourceBusinessNo(sourceBill.getSourceBusinessNo());
        supplement.setSourceMrpId(sourceBill.getSourceMrpId());
        supplement.setSourceMaterialCode(sourceBill.getSourceMaterialCode());
        supplement.setSourceShortageQuantity(sourceBill.getSourceShortageQuantity());
        supplement.setRemark("短收差异单" + entity.getCaseNo() + "批准补调；" + command.getRemark().trim());
        supplement.setDetails(details);
        require(Boolean.TRUE.equals(allocationBillService.saveAllocationBill(supplement)), "短收补调单创建失败");
        WmsAllocationBillEntity saved = billMapper.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
                .eq(WmsAllocationBillEntity::getRequestId, supplement.getRequestId()));
        require(saved != null, "补调执行成功但单据回读失败");
        planShortageEventOutboxService.supplySupplementForecast(entity.getId(), saved);
        return saved.getId();
    }

    private void disposeOver(WmsTransferVarianceCaseEntity entity, WmsAllocationBillEntity bill,
                             List<WmsTransferVarianceLineEntity> lines,
                             WmsTransferVarianceDTO.DispositionCommand command) {
        Map<Long, WmsTransferVarianceLineEntity> lineById = lines.stream()
                .collect(Collectors.toMap(WmsTransferVarianceLineEntity::getId, Function.identity()));
        List<WmsInoutFlowEntity> adjustments = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getVarianceCaseId, entity.getId())
                .eq(WmsInoutFlowEntity::getTransferAction, "OVER_ISSUE_ADJUST")
                .orderByAsc(WmsInoutFlowEntity::getId));
        BigDecimal adjusted = adjustments.stream().map(this::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal declared = lines.stream().map(line -> value(line.getOverQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(adjusted.compareTo(declared) == 0, "溢收源仓补扣流水不完整，禁止处置");
        MdmWarehouseEntity target = requireWarehouse(bill.getNewWarehouseCode(), bill.getFactoryCode());
        MdmWarehouseEntity source = requireWarehouse(bill.getOldWarehouseCode(), bill.getFactoryCode());
        for (WmsInoutFlowEntity adjustment : adjustments) {
            WmsTransferVarianceLineEntity line = lineById.get(adjustment.getVarianceLineId());
            require(line != null, "溢收补扣流水缺少差异明细");
            if ("ACCEPT_TO_STOCK".equals(command.getOverDisposition())) {
                MdmWarehouseLocationEntity site = requireLocation(line.getTargetSiteCode(), target.getWarehouseCode(), bill.getFactoryCode());
                requireContainer(line.getTargetContainerCode(), target);
                liveStockService.transferIn(adjustment, target.getWarehouseCode(), target.getWarehouseName(),
                        site.getLocationCode(), site.getLocationName(), line.getTargetContainerCode());
                WmsInoutFlowEntity inbound = inboundFlow(bill, adjustment, adjustment.getId(), target.getWarehouseCode(),
                        target.getWarehouseName(), site.getLocationCode(), site.getLocationName(),
                        line.getTargetContainerCode(), "OVER_ACCEPT", command.getRequestId());
                inbound.setVarianceCaseId(entity.getId());
                inbound.setVarianceLineId(line.getId());
                require(flowMapper.insert(inbound) == 1, "溢收批准入库流水保存失败");
                liveStockService.releaseTransferSourceContainer(adjustment);
            } else {
                requireContainer(adjustment.getContainerCode(), source);
                liveStockService.transferIn(adjustment, source.getWarehouseCode(), source.getWarehouseName(),
                        adjustment.getSiteCode(), adjustment.getSiteName(), adjustment.getContainerCode());
                WmsInoutFlowEntity inbound = inboundFlow(bill, adjustment, adjustment.getId(), source.getWarehouseCode(),
                        source.getWarehouseName(), adjustment.getSiteCode(), adjustment.getSiteName(),
                        adjustment.getContainerCode(), "OVER_RETURN", command.getRequestId());
                inbound.setVarianceCaseId(entity.getId());
                inbound.setVarianceLineId(line.getId());
                require(flowMapper.insert(inbound) == 1, "溢收退回源仓流水保存失败");
            }
        }
    }

    private WmsTransferVarianceCaseEntity createVarianceCase(WmsAllocationBillEntity bill,
                                                             WmsTransferReceiptEntity receipt,
                                                             List<WmsInoutFlowEntity> issues,
                                                             Map<Long, BigDecimal> receivedAfter,
                                                             Map<Long, BigDecimal> overThisReceipt,
                                                             WmsTransferReceiptDTO.Command command) {
        validateVarianceContext(command.getReasonCode(), command.getReasonDescription(), command.getResponsibleType());
        Map<Long, WmsTransferReceiptLineEntity> currentLines = receiptLineMapper.selectList(
                        new LambdaQueryWrapper<WmsTransferReceiptLineEntity>()
                                .eq(WmsTransferReceiptLineEntity::getReceiptId, receipt.getId()))
                .stream().collect(Collectors.toMap(WmsTransferReceiptLineEntity::getSourceFlowId, Function.identity()));
        List<WmsTransferVarianceLineEntity> pending = new ArrayList<>();
        boolean outside = false;
        boolean planRequired = false;
        boolean qualityRequired = false;
        Set<String> materials = new HashSet<>();
        for (WmsInoutFlowEntity issue : issues) {
            BigDecimal shortQty = quantity(issue).subtract(receivedAfter.getOrDefault(issue.getId(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            BigDecimal overQty = overThisReceipt.getOrDefault(issue.getId(), BigDecimal.ZERO);
            if (shortQty.signum() == 0 && overQty.signum() == 0) continue;
            WmsTransferVariancePolicyEntity policy = policyService.resolveRequired(bill.getFactoryCode(),
                    bill.getOldWarehouseCode(), bill.getNewWarehouseCode(), issue.getMatCode(), LocalDate.now());
            BigDecimal shortLimit = tolerance(quantity(issue), policy.getShortTolerancePercent(), policy.getShortToleranceQuantity());
            BigDecimal overLimit = tolerance(quantity(issue), policy.getOverTolerancePercent(), policy.getOverToleranceQuantity());
            boolean within = shortQty.compareTo(shortLimit) <= 0 && overQty.compareTo(overLimit) <= 0;
            boolean linePlan = !within && Boolean.TRUE.equals(policy.getPlanReviewOutsideTolerance());
            boolean lineQuality = (overQty.signum() > 0 && Boolean.TRUE.equals(policy.getQualityReviewForOver()))
                    || (Set.of("DAMAGE", "QUALITY_REJECTED").contains(command.getReasonCode())
                    && Boolean.TRUE.equals(policy.getQualityReviewForDamage()));
            WmsTransferReceiptLineEntity current = currentLines.get(issue.getId());
            pending.add(new WmsTransferVarianceLineEntity().setSourceFlowId(issue.getId())
                    .setMaterialCode(issue.getMatCode()).setMaterialName(issue.getMatName())
                    .setMaterialBatch(issue.getMatBatch()).setMaterialUnit(issue.getMatUnit())
                    .setIssuedQuantity(quantity(issue)).setReceivedQuantity(receivedAfter.getOrDefault(issue.getId(), BigDecimal.ZERO))
                    .setShortQuantity(shortQty).setOverQuantity(overQty).setHeldOverQuantity(overQty)
                    .setPolicyId(policy.getId()).setPolicyCode(policy.getPolicyCode())
                    .setPolicySnapshot(JSONUtil.toJsonStr(policy)).setWithinTolerance(within)
                    .setPlanReviewRequired(linePlan).setQualityReviewRequired(lineQuality)
                    .setTargetSiteCode(current == null ? null : current.getTargetSiteCode())
                    .setTargetSiteName(current == null ? null : current.getTargetSiteName())
                    .setTargetContainerCode(current == null ? null : current.getTargetContainerCode())
                    .setDispositionStatus("PENDING").setFactoryCode(bill.getFactoryCode()));
            outside |= !within;
            planRequired |= linePlan;
            qualityRequired |= lineQuality;
            materials.add(issue.getMatCode());
        }
        boolean shortExists = pending.stream().anyMatch(line -> positive(line.getShortQuantity()));
        boolean overExists = pending.stream().anyMatch(line -> positive(line.getOverQuantity()));
        String type = shortExists && overExists ? "MIXED" : shortExists ? "SHORT" : "OVER";
        String severity = qualityRequired ? "QUALITY_RISK" : outside ? "OUTSIDE_TOLERANCE" : "WITHIN_TOLERANCE";
        MomUser user = SecurityUtils.getUser();
        WmsTransferVarianceCaseEntity entity = new WmsTransferVarianceCaseEntity()
                .setCaseNo("TV-" + IdUtil.getSnowflakeNextIdStr()).setAllocationBillId(bill.getId())
                .setAllocationBillNo(bill.getBillNo()).setFinalReceiptId(receipt.getId())
                .setSourceWarehouseCode(bill.getOldWarehouseCode()).setSourceWarehouseName(bill.getOldWarehouseName())
                .setTargetWarehouseCode(bill.getNewWarehouseCode()).setTargetWarehouseName(bill.getNewWarehouseName())
                .setVarianceType(type).setSeverity(severity).setVarianceLineCount(pending.size())
                .setImpactedMaterialCount(materials.size()).setPlanReviewRequired(planRequired)
                .setQualityReviewRequired(qualityRequired).setReasonCode(command.getReasonCode())
                .setReasonDescription(command.getReasonDescription().trim()).setResponsibleType(command.getResponsibleType())
                .setEvidence(StrUtil.trim(command.getEvidence())).setStatus("PENDING_WAREHOUSE_REVIEW")
                .setReporterCode(user.getUsername()).setReporterName(operatorName(user))
                .setReportedTime(LocalDateTime.now()).setFactoryCode(bill.getFactoryCode());
        require(caseMapper.insert(entity) == 1, "短溢收差异单保存失败");
        pending.forEach(line -> {
            line.setCaseId(entity.getId());
            require(varianceLineMapper.insert(line) == 1, "短溢收差异明细保存失败");
        });
        saveOperation(entity, "REPORT:" + receipt.getRequestId(), receipt.getRequestSnapshot(), "REPORT", null,
                entity.getStatus(), "最终接收产生" + type + "差异；逐物料容差已冻结");
        if (bill.getSourceMrpId() != null && shortExists) {
            BigDecimal sourceShortage = pending.stream()
                    .filter(line -> Objects.equals(line.getMaterialCode(), bill.getSourceMaterialCode()))
                    .map(WmsTransferVarianceLineEntity::getShortQuantity).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sourceShortage.signum() > 0) {
                planShortageEventOutboxService.enqueueTransferShortReceived(bill, entity, sourceShortage);
            }
        }
        return entity;
    }

    private WmsTransferReceiptDTO.Result receiptResult(WmsTransferReceiptEntity receipt) {
        WmsAllocationBillEntity bill = billMapper.selectById(receipt.getAllocationBillId());
        WmsTransferVarianceCaseEntity variance = receipt.getVarianceCaseId() == null ? null
                : caseMapper.selectById(receipt.getVarianceCaseId());
        return new WmsTransferReceiptDTO.Result().setReceiptId(receipt.getId()).setReceiptNo(receipt.getReceiptNo())
                .setAllocationBillId(receipt.getAllocationBillId())
                .setAllocationState(bill == null ? null : bill.getAllocationState())
                .setVarianceCaseId(variance == null ? null : variance.getId())
                .setVarianceCaseNo(variance == null ? null : variance.getCaseNo())
                .setVarianceStatus(variance == null ? null : variance.getStatus());
    }

    private void validateReceipt(WmsTransferReceiptDTO.Command command) {
        require(command != null && command.getAllocationBillId() != null, "调拨单不能为空");
        requireText(command.getRequestId(), "接收请求号不能为空");
        require(command.getFinalReceipt() != null, "必须明确是否最终接收");
        require(command.getLines() != null, "接收明细不能为空");
        require(command.getLines().size() <= 500, "单次接收最多500行");
    }

    private void validateVarianceContext(String reasonCode, String reasonDescription, String responsibleType) {
        require(REASON_CODES.contains(reasonCode), "短溢收原因编码无效");
        requireText(reasonDescription, "短溢收原因说明不能为空");
        require(reasonDescription.trim().length() <= 1000, "短溢收原因说明不能超过1000字");
        require(RESPONSIBLE_TYPES.contains(responsibleType), "责任归属无效");
    }

    private String receiptSnapshot(WmsTransferReceiptDTO.Command command) {
        List<Map<String, Object>> lines = command.getLines() == null ? List.of() : command.getLines().stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceFlowId", line.getSourceFlowId());
            item.put("actual", line.getActualReceivedQuantity());
            item.put("site", StrUtil.trim(line.getTargetSiteCode()));
            item.put("container", StrUtil.trim(line.getTargetContainerCode()));
            return item;
        }).sorted(Comparator.comparing(item -> Objects.toString(item.get("sourceFlowId"), ""))).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("billId", command.getAllocationBillId());
        snapshot.put("final", command.getFinalReceipt());
        snapshot.put("reason", command.getReasonCode());
        snapshot.put("reasonDescription", StrUtil.trim(command.getReasonDescription()));
        snapshot.put("responsibleType", command.getResponsibleType());
        snapshot.put("evidence", StrUtil.trim(command.getEvidence()));
        snapshot.put("lines", lines);
        return JSONUtil.toJsonStr(snapshot);
    }

    private String reviewSnapshot(WmsTransferVarianceDTO.ReviewCommand command, String stage) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("caseId", command.getCaseId());
        snapshot.put("stage", stage);
        snapshot.put("decision", command.getDecision());
        snapshot.put("comment", StrUtil.trim(command.getComment()));
        List<Map<String, Object>> adjustments = command.getOverSourceAdjustments() == null ? List.of()
                : command.getOverSourceAdjustments().stream().map(item -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("varianceLineId", item.getVarianceLineId());
            line.put("sourceStockId", item.getSourceStockId());
            line.put("quantity", item.getQuantity());
            return line;
        }).sorted(Comparator.comparing(item -> Objects.toString(item.get("varianceLineId"), "")
                + ":" + Objects.toString(item.get("sourceStockId"), ""))).toList();
        snapshot.put("adjustments", adjustments);
        return JSONUtil.toJsonStr(snapshot);
    }

    private String dispositionSnapshot(WmsTransferVarianceDTO.DispositionCommand command) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("caseId", command.getCaseId());
        snapshot.put("shortDisposition", command.getShortDisposition());
        snapshot.put("overDisposition", command.getOverDisposition());
        snapshot.put("referenceNo", StrUtil.trim(command.getReferenceNo()));
        snapshot.put("remark", StrUtil.trim(command.getRemark()));
        snapshot.put("supplementRequestId", command.getSupplementRequestId());
        snapshot.put("supplementBillNo", command.getSupplementBillNo());
        snapshot.put("allocations", command.getSupplementAllocations());
        return JSONUtil.toJsonStr(snapshot);
    }

    private boolean idempotentOperation(WmsTransferVarianceCaseEntity entity, String requestId, String snapshot) {
        WmsTransferVarianceOperationEntity existing = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsTransferVarianceOperationEntity>()
                        .eq(WmsTransferVarianceOperationEntity::getRequestId, requestId));
        if (existing == null) return false;
        require(Objects.equals(existing.getRequestSnapshot(), snapshot), "差异处理幂等号已被不同请求使用");
        require(Objects.equals(existing.getCaseId(), entity.getId())
                && Objects.equals(existing.getFactoryCode(), entity.getFactoryCode()), "差异处理幂等号不属于当前差异单");
        require(Objects.equals(existing.getOperatorCode(), currentUserCode()), "差异处理幂等号不属于当前操作人");
        return true;
    }

    private void saveOperation(WmsTransferVarianceCaseEntity entity, String requestId, String requestSnapshot,
                               String type, String before, String after, String content) {
        WmsTransferVarianceOperationEntity operation = new WmsTransferVarianceOperationEntity()
                .setCaseId(entity.getId()).setRequestId(requestId).setRequestSnapshot(requestSnapshot)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setOperationContent(content)
                .setOperatorCode(currentUserCode()).setOperatorName(currentUserName())
                .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "差异操作履历保存失败");
    }

    private WmsAllocationBillEntity lockBill(Long id) {
        WmsAllocationBillEntity bill = billMapper.selectOne(new LambdaQueryWrapper<WmsAllocationBillEntity>()
                .eq(WmsAllocationBillEntity::getId, id).last("FOR UPDATE"));
        require(bill != null, "调拨单不存在");
        return bill;
    }

    private WmsTransferVarianceCaseEntity lockCase(Long id) {
        WmsTransferVarianceCaseEntity entity = caseMapper.selectOne(new LambdaQueryWrapper<WmsTransferVarianceCaseEntity>()
                .eq(WmsTransferVarianceCaseEntity::getId, id).last("FOR UPDATE"));
        require(entity != null, "短溢收差异单不存在");
        requireFactory(entity.getFactoryCode());
        return entity;
    }

    private List<WmsInoutFlowEntity> issueFlows(Long billId) {
        List<WmsInoutFlowEntity> result = flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getBillId, billId).eq(WmsInoutFlowEntity::getTransferAction, "ISSUE")
                .orderByAsc(WmsInoutFlowEntity::getId));
        require(!result.isEmpty(), "调拨单缺少发起流水");
        return result;
    }

    private List<WmsTransferVarianceLineEntity> varianceLines(Long caseId) {
        return varianceLineMapper.selectList(new LambdaQueryWrapper<WmsTransferVarianceLineEntity>()
                .eq(WmsTransferVarianceLineEntity::getCaseId, caseId).orderByAsc(WmsTransferVarianceLineEntity::getId));
    }

    private boolean hasOver(Long caseId) {
        return varianceLines(caseId).stream().anyMatch(line -> positive(line.getOverQuantity()));
    }

    private Map<Long, BigDecimal> receivedByIssue(Long billId) {
        Map<Long, BigDecimal> result = new HashMap<>();
        flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                        .eq(WmsInoutFlowEntity::getBillId, billId)
                        .eq(WmsInoutFlowEntity::getTransferAction, "RECEIVE"))
                .forEach(flow -> result.merge(flow.getSourceFlowId(), quantity(flow), BigDecimal::add));
        return result;
    }

    private BigDecimal remaining(WmsInoutFlowEntity issue, Map<Long, BigDecimal> received) {
        return quantity(issue).subtract(received.getOrDefault(issue.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    private BigDecimal tolerance(BigDecimal issued, BigDecimal percent, BigDecimal absolute) {
        BigDecimal percentage = issued.multiply(value(percent)).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return percentage.max(value(absolute));
    }

    private WmsInoutFlowEntity quantityCopy(WmsInoutFlowEntity source, BigDecimal quantity) {
        WmsInoutFlowEntity copy = new WmsInoutFlowEntity();
        BeanUtils.copyProperties(source, copy);
        copy.setMatNum(quantity.doubleValue());
        return copy;
    }

    private WmsInoutFlowEntity inboundFlow(WmsAllocationBillEntity bill, WmsInoutFlowEntity source, Long sourceFlowId,
                                           String warehouseCode, String warehouseName, String siteCode, String siteName,
                                           String containerCode, String action, String requestId) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        BeanUtils.copyProperties(source, flow);
        clearIdentity(flow);
        flow.setBillId(bill.getId());
        flow.setBillNo(bill.getBillNo());
        flow.setSourceFlowId(sourceFlowId);
        flow.setWarehouseCode(warehouseCode);
        flow.setWarehouseName(warehouseName);
        flow.setSiteCode(siteCode);
        flow.setSiteName(siteName);
        flow.setContainerCode(containerCode);
        flow.setOperateType(InOutEnum.IN.getCode());
        flow.setBillType(InBillTypeEnum.ALLOCATION.getCode());
        flow.setTransferAction(action);
        flow.setOperationRequestId(requestId);
        flow.setFactoryCode(bill.getFactoryCode());
        return flow;
    }

    private WmsInoutFlowEntity outboundAdjustmentFlow(WmsTransferVarianceCaseEntity entity,
                                                      WmsTransferVarianceLineEntity line,
                                                      WmsLiveStockEntity stock, Long sourceStockId, String requestId) {
        WmsInoutFlowEntity flow = new WmsInoutFlowEntity();
        BeanUtils.copyProperties(stock, flow);
        clearIdentity(flow);
        flow.setBillId(entity.getAllocationBillId());
        flow.setBillNo(entity.getAllocationBillNo());
        flow.setSourceStockId(sourceStockId);
        flow.setSourceFlowId(line.getSourceFlowId());
        flow.setTransferAction("OVER_ISSUE_ADJUST");
        flow.setOperationRequestId(requestId);
        flow.setVarianceCaseId(entity.getId());
        flow.setVarianceLineId(line.getId());
        flow.setStockInTime(stock.getInTime());
        flow.setOperateType(InOutEnum.OUT.getCode());
        flow.setBillType(OutBillTypeEnum.ALLOCATION_OUT.getCode());
        flow.setFactoryCode(entity.getFactoryCode());
        return flow;
    }

    private void clearIdentity(WmsInoutFlowEntity flow) {
        flow.setId(null);
        flow.setCreateBy(null);
        flow.setCreateTime(null);
        flow.setUpdateBy(null);
        flow.setUpdateTime(null);
        flow.setDelFlag(null);
        flow.setTransferReceiptId(null);
        flow.setVarianceCaseId(null);
        flow.setVarianceLineId(null);
        flow.setOperationRequestId(null);
    }

    private boolean sameTraceAttributes(WmsInoutFlowEntity expected, WmsLiveStockEntity actual) {
        return Objects.equals(expected.getMatCode(), actual.getMatCode())
                && Objects.equals(expected.getMatBatch(), actual.getMatBatch())
                && Objects.equals(expected.getSupplierCode(), actual.getSupplierCode())
                && Objects.equals(expected.getMatProduceDate(), actual.getMatProduceDate())
                && Objects.equals(expected.getMatValidPeriod(), actual.getMatValidPeriod())
                && Objects.equals(expected.getMatValidEndDate(), actual.getMatValidEndDate())
                && Objects.equals(expected.getQualityStatus(), actual.getQualityStatus())
                && Objects.equals(expected.getQualityResult(), actual.getQualityResult())
                && Objects.equals(expected.getQualityHoldNo(), actual.getQualityHoldNo())
                && Objects.equals(expected.getQualitySourceType(), actual.getQualitySourceType())
                && Objects.equals(expected.getQualitySourceId(), actual.getQualitySourceId())
                && Objects.equals(expected.getIsQualityTest(), actual.getIsQualityTest())
                && Objects.equals(expected.getQualityTaskId(), actual.getQualityTaskId())
                && Objects.equals(expected.getExpirationStatus(), actual.getExpirationStatus())
                && Objects.equals(expected.getSlowMaterialStatus(), actual.getSlowMaterialStatus());
    }

    private MdmWarehouseEntity requireWarehouse(String code, String factory) {
        com.kitex.common.core.util.R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(code);
        MdmWarehouseEntity warehouse = response == null ? null : response.getData();
        require(response != null && response.isOk() && warehouse != null && Boolean.TRUE.equals(warehouse.getStatus()),
                "仓库[" + code + "]不存在或未启用");
        require(StrUtil.isBlank(warehouse.getFactoryCode()) || Objects.equals(factory, warehouse.getFactoryCode()),
                "仓库[" + code + "]不属于当前工厂");
        return warehouse;
    }

    private MdmWarehouseLocationEntity requireLocation(String code, String warehouse, String factory) {
        requireText(code, "接收库位不能为空");
        com.kitex.common.core.util.R<MdmWarehouseLocationEntity> response = remoteWarehouseLocationService.getByCode(code.trim());
        MdmWarehouseLocationEntity site = response == null ? null : response.getData();
        require(response != null && response.isOk() && site != null && Boolean.TRUE.equals(site.getStatus()),
                "库位[" + code + "]不存在或未启用");
        require(Objects.equals(warehouse, site.getWarehouseCode()), "接收库位不属于目标仓库");
        require(StrUtil.isBlank(site.getFactoryCode()) || Objects.equals(factory, site.getFactoryCode()), "接收库位不属于当前工厂");
        return site;
    }

    private void requireContainer(String code, MdmWarehouseEntity warehouse) {
        boolean required = Objects.equals(warehouse.getIsContainer(), 1);
        require(!required || StrUtil.isNotBlank(code), "仓库[" + warehouse.getWarehouseCode() + "]必须指定容器");
        if (StrUtil.isBlank(code)) return;
        WmsContainerBarcodeEntity container = containerBarcodeMapper.selectOne(
                new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
                        .eq(WmsContainerBarcodeEntity::getCode, code.trim()).last("LIMIT 1"));
        require(container != null && Objects.equals(warehouse.getWarehouseCode(), container.getWarehouseCode()),
                "容器[" + code + "]不存在或不属于仓库[" + warehouse.getWarehouseCode() + "]");
        require(StrUtil.isBlank(container.getFactoryCode()) || StrUtil.isBlank(warehouse.getFactoryCode())
                        || Objects.equals(warehouse.getFactoryCode(), container.getFactoryCode()),
                "容器[" + code + "]不属于当前工厂");
    }

    private void requireWarehousePermission(String permission, String warehouseCode, String message) {
        require(permissionService.hasPermission(permission, permission + "_" + warehouseCode), message);
    }

    private void requireFactory(String factory) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factory), "业务数据超出当前工厂范围");
    }

    private String currentFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        requireText(factory, "当前工厂不能为空");
        return factory.trim();
    }

    private String currentUserCode() {
        return SecurityUtils.getUser().getUsername();
    }

    private String currentUserName() {
        return operatorName(SecurityUtils.getUser());
    }

    private String operatorName(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private BigDecimal quantity(WmsInoutFlowEntity flow) {
        return flow.getMatNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(flow.getMatNum());
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void requireText(String value, String message) {
        require(StrUtil.isNotBlank(value), message);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
