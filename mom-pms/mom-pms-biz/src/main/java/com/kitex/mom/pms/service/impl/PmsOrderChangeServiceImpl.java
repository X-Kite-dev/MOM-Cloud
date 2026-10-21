package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialAuthorizationService;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.dto.PmsDeliveryCommitmentCancelDTO;
import com.kitex.mom.pms.dto.PmsOmsSupplyWithdrawalDTO;
import com.kitex.mom.pms.dto.PmsOrderChangeDTO;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.enums.PmsOrderStatusEnum;
import com.kitex.mom.pms.mapper.*;
import com.kitex.mom.pms.service.*;
import com.kitex.mom.wms.enums.PurchaseReceiptStateEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 已审批采购订单受控变更。支持整单撤回、指定订单行撤回和订单通用变更；所有路径均执行
 * 申请复核分岗、事实指纹防陈旧、数量守恒、合同/收料门禁、计划回退和不可变操作审计。
 */
@Service
@RequiredArgsConstructor
public class PmsOrderChangeServiceImpl implements PmsOrderChangeService {
    private static final Set<String> ELIGIBLE = Set.of(PmsOrderStatusEnum.CHECK_SUCCESS.getCode(),
            PmsOrderStatusEnum.RECEIVING.getCode(), PmsOrderStatusEnum.PART_RECEIVE.getCode());
    private static final Set<String> LIVE_REQUEST = Set.of("SUBMITTED");

    private final PmsOrderChangeRequestMapper requestMapper;
    private final PmsOrderChangeLineMapper lineMapper;
    private final PmsOrderChangeOperationMapper operationMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper detailMapper;
    private final WmsPurchaseReceiptMapper receiptMapper;
    private final WmsPurchaseReceiptDetailMapper receiptDetailMapper;
    private final PmsContractMapper contractMapper;
    private final PmsContractDetailMapper contractDetailMapper;
    private final PmsBusinessReconciliationMapper reconciliationMapper;
    private final PmsInvoiceReconciliationMapper invoiceReconciliationMapper;
    private final PmsPaymentReconciliationMapper paymentReconciliationMapper;
    private final PmsDeliveryCommitmentMapper commitmentMapper;
    private final PmsPlanService planService;
    private final PmsDeliveryCommitmentService deliveryCommitmentService;
    private final PmsPlanCommitmentEventOutboxService planEventOutboxService;
    private final PmsAgentPurchaseStatusOutboxService agentStatusOutboxService;
    private final PmsPriceService priceService;
    private final PmsHistoryPriceService historyPriceService;
    private final RemoteSupplierService supplierService;
    private final RemoteMaterialAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PmsOrderChangeDTO.Preview previewCancelRemaining(Long orderId) {
        require(orderId != null, "采购订单不能为空");
        PmsOrderEntity order = orderMapper.selectById(orderId);
        requireOrderScope(order);
        return capture(order, null, false);
    }

    @Override
    public PmsOrderChangeDTO.Preview previewCancelLines(PmsOrderChangeDTO.LineCancelPreviewCommand command) {
        require(command != null && command.getPmsOrderId() != null, "行级供给撤回必须指定采购订单");
        Set<Long> detailIds = normalizeDetailIds(command.getOrderDetailIds());
        PmsOrderEntity order = orderMapper.selectById(command.getPmsOrderId());
        requireOrderScope(order);
        return captureLines(order, detailIds, null, false);
    }

    @Override
    public PmsOrderChangeDTO.Preview previewAmendment(PmsOrderChangeDTO.AmendPreviewCommand command) {
        require(command != null && command.getPmsOrderId() != null, "采购订单通用变更必须指定订单");
        PmsOrderEntity order = orderMapper.selectById(command.getPmsOrderId());
        requireOrderScope(order);
        return captureAmendment(order, command, null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsOrderChangeDTO.Detail submitCancelRemaining(PmsOrderChangeDTO.SubmitCommand command) {
        require(command != null && command.getPmsOrderId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "取消剩余量申请必须提供订单、请求号、证据指纹和原因");
        String requestId = bounded(command.getRequestId(), 128, "提交请求号");
        String reason = bounded(command.getReason(), 500, "申请原因");
        String submitFingerprint = hash(command.getPmsOrderId() + "|CANCEL_REMAINING|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsOrderChangeOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                        .eq(PmsOrderChangeOperationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("SUBMIT".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getRequestFingerprint(), submitFingerprint),
                    "订单变更提交请求号已被不同动作或载荷使用");
            return detail(duplicate.getChangeRequestId());
        }
        PmsOrderEntity order = lockOrder(command.getPmsOrderId());
        PmsOrderChangeDTO.Preview preview = capture(order, null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "采购订单或下游事实已变化，请刷新撤回预览后重试");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        MomUser user = currentUser();
        PmsOrderChangeRequestEntity request = new PmsOrderChangeRequestEntity()
                .setRequestNo("POC-" + IdUtil.getSnowflakeNextIdStr()).setChangeType("CANCEL_REMAINING")
                .setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setOriginalOrderStatus(order.getStatus()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(submitFingerprint)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setReason(reason).setTotalOrderQuantity(preview.getTotalOrderQuantity())
                .setTotalArrivalQuantity(preview.getTotalArrivalQuantity())
                .setTotalCancelledQuantity(preview.getTotalCancellableQuantity())
                .setReceiptCount(preview.getReceiptCount()).setActiveReceiptCount(preview.getActiveReceiptCount())
                .setContractCount(preview.getContractCount()).setSettlementCount(preview.getSettlementCount())
                .setActiveCommitmentCount(preview.getActiveCommitmentCount())
                .setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(order.getFactoryCode());
        require(requestMapper.insert(request) == 1, "采购订单变更申请保存失败");
        for (PmsOrderChangeLineEntity previewLine : preview.getLines()) {
            PmsOrderChangeLineEntity line = copyLine(previewLine).setChangeRequestId(request.getId());
            require(lineMapper.insert(line) == 1, "采购订单变更行证据保存失败");
        }
        operation(request, requestId, submitFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsOrderChangeDTO.Detail submitCancelLines(PmsOrderChangeDTO.LineCancelSubmitCommand command) {
        require(command != null && command.getPmsOrderId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "行级供给撤回必须提供订单、订单行、请求号、证据指纹和原因");
        Set<Long> detailIds = normalizeDetailIds(command.getOrderDetailIds());
        String requestId = bounded(command.getRequestId(), 128, "提交请求号");
        String reason = bounded(command.getReason(), 500, "申请原因");
        String submitFingerprint = hash(command.getPmsOrderId() + "|CANCEL_LINES|" + detailIds + "|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsOrderChangeOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                        .eq(PmsOrderChangeOperationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("SUBMIT".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getRequestFingerprint(), submitFingerprint),
                    "订单行撤回提交请求号已被不同动作或载荷使用");
            return detail(duplicate.getChangeRequestId());
        }
        PmsOrderEntity order = lockOrder(command.getPmsOrderId());
        PmsOrderChangeDTO.Preview preview = captureLines(order, detailIds, null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "采购订单行、合同或收料事实已变化，请刷新撤回预览后重试");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        MomUser user = currentUser();
        PmsOrderChangeRequestEntity request = new PmsOrderChangeRequestEntity()
                .setRequestNo("POL-" + IdUtil.getSnowflakeNextIdStr()).setChangeType("CANCEL_LINES")
                .setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setOriginalOrderStatus(order.getStatus()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(submitFingerprint)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setReason(reason).setTotalOrderQuantity(preview.getTotalOrderQuantity())
                .setTotalArrivalQuantity(preview.getTotalArrivalQuantity())
                .setTotalCancelledQuantity(preview.getTotalCancellableQuantity())
                .setReceiptCount(preview.getReceiptCount()).setActiveReceiptCount(preview.getActiveReceiptCount())
                .setContractCount(preview.getContractCount()).setSettlementCount(preview.getSettlementCount())
                .setActiveCommitmentCount(preview.getActiveCommitmentCount())
                .setSupplyAffectedLineCount(preview.getLines().size())
                .setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(order.getFactoryCode());
        require(requestMapper.insert(request) == 1, "采购订单行撤回申请保存失败");
        for (PmsOrderChangeLineEntity previewLine : preview.getLines()) {
            require(lineMapper.insert(copyLine(previewLine).setChangeRequestId(request.getId())) == 1,
                    "采购订单行撤回证据保存失败");
        }
        operation(request, requestId, submitFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsOrderChangeDTO.Detail submitAmendment(PmsOrderChangeDTO.AmendSubmitCommand command) {
        require(command != null && command.getPmsOrderId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "订单变更申请必须提供订单、请求号、证据指纹和原因");
        String requestId = bounded(command.getRequestId(), 128, "提交请求号");
        String reason = bounded(command.getReason(), 500, "申请原因");
        String submitFingerprint = hash(command.getPmsOrderId() + "|AMEND_ORDER|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsOrderChangeOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                        .eq(PmsOrderChangeOperationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("SUBMIT".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getRequestFingerprint(), submitFingerprint),
                    "订单变更提交请求号已被不同动作或载荷使用");
            return detail(duplicate.getChangeRequestId());
        }
        PmsOrderEntity order = lockOrder(command.getPmsOrderId());
        PmsOrderChangeDTO.Preview preview = captureAmendment(order, command, null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "采购订单、目标凭据或下游事实已变化，请刷新变更预览后重试");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        MomUser user = currentUser();
        PmsOrderChangeRequestEntity request = new PmsOrderChangeRequestEntity()
                .setRequestNo("POA-" + IdUtil.getSnowflakeNextIdStr()).setChangeType("AMEND_ORDER")
                .setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setOriginalOrderStatus(order.getStatus()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(submitFingerprint)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setReason(reason).setChangeVersion(preview.getChangeVersion())
                .setOriginalSupplierCode(preview.getOriginalSupplierCode()).setOriginalSupplierName(preview.getOriginalSupplierName())
                .setTargetSupplierCode(preview.getTargetSupplierCode()).setTargetSupplierName(preview.getTargetSupplierName())
                .setTotalOrderQuantity(preview.getTotalOrderQuantity()).setTotalTargetQuantity(preview.getTotalTargetQuantity())
                .setTotalQuantityDelta(preview.getTotalQuantityDelta()).setTotalArrivalQuantity(preview.getTotalArrivalQuantity())
                .setTotalCancelledQuantity(BigDecimal.ZERO).setReceiptCount(preview.getReceiptCount())
                .setActiveReceiptCount(preview.getActiveReceiptCount()).setContractCount(preview.getContractCount())
                .setSettlementCount(preview.getSettlementCount()).setActiveCommitmentCount(preview.getActiveCommitmentCount())
                .setSupplyAffectedLineCount(preview.getSupplyAffectedLineCount())
                .setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(order.getFactoryCode());
        require(requestMapper.insert(request) == 1, "采购订单通用变更申请保存失败");
        for (PmsOrderChangeLineEntity previewLine : preview.getLines()) {
            require(lineMapper.insert(copyLine(previewLine).setChangeRequestId(request.getId())) == 1,
                    "采购订单变更行证据保存失败");
        }
        operation(request, requestId, submitFingerprint, "SUBMIT", null, "SUBMITTED", reason, user);
        return detail(request.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsOrderChangeDTO.Detail review(PmsOrderChangeDTO.ReviewCommand command) {
        require(command != null && command.getChangeRequestId() != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getApproved() != null && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
                        && StrUtil.isNotBlank(command.getReason()),
                "订单变更复核必须提供申请、请求号、决定、证据指纹和意见");
        String requestId = bounded(command.getRequestId(), 128, "复核请求号");
        String reason = bounded(command.getReason(), 500, "复核意见");
        String fingerprint = hash(command.getChangeRequestId() + "|" + command.getApproved() + "|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason);
        requestMapper.lockKey("REVIEW:" + requestId);
        PmsOrderChangeOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                        .eq(PmsOrderChangeOperationEntity::getRequestId, requestId));
        if (replay != null) {
            require(Objects.equals(replay.getChangeRequestId(), command.getChangeRequestId())
                            && Objects.equals(replay.getRequestFingerprint(), fingerprint),
                    "订单变更复核请求号已被不同申请或载荷使用");
            return detail(command.getChangeRequestId());
        }
        PmsOrderChangeRequestEntity request = requireRequest(command.getChangeRequestId(), true);
        require("SUBMITTED".equals(request.getStatus()), "仅待复核的订单变更申请允许审批");
        require(Objects.equals(request.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "订单变更申请证据指纹不一致，请刷新后重试");
        MomUser reviewer = currentUser();
        require(!Objects.equals(request.getApplicantCode(), userCode(reviewer)), "订单变更申请人与复核人必须分离");
        String before = request.getStatus();
        request.setReviewRequestId(requestId).setReviewFingerprint(fingerprint).setApproved(command.getApproved())
                .setReviewReason(reason).setReviewerCode(userCode(reviewer)).setReviewerName(userName(reviewer))
                .setReviewedTime(LocalDateTime.now());
        if (!Boolean.TRUE.equals(command.getApproved())) {
            request.setStatus("REJECTED");
            require(requestMapper.updateById(request) == 1, "订单变更驳回状态保存失败");
            operation(request, requestId, fingerprint, "REVIEW_REJECT", before, "REJECTED", reason, reviewer);
            return detail(request.getId());
        }
        PmsOrderEntity order = lockOrder(request.getPmsOrderId());
        PmsOrderChangeDTO.Preview current;
        if ("AMEND_ORDER".equals(request.getChangeType())) {
            List<PmsOrderChangeLineEntity> frozenLines = lineMapper.selectList(
                    new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                            .eq(PmsOrderChangeLineEntity::getChangeRequestId, request.getId())
                            .orderByAsc(PmsOrderChangeLineEntity::getOrderDetailId));
            current = captureAmendment(order, frozenAmendCommand(request, frozenLines), request.getId(), true);
        } else if ("CANCEL_LINES".equals(request.getChangeType())) {
            Set<Long> frozenIds = lineMapper.selectList(new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                            .eq(PmsOrderChangeLineEntity::getChangeRequestId, request.getId()))
                    .stream().map(PmsOrderChangeLineEntity::getOrderDetailId)
                    .collect(Collectors.toCollection(TreeSet::new));
            current = captureLines(order, frozenIds, request.getId(), true);
        } else {
            require("CANCEL_REMAINING".equals(request.getChangeType()), "未知采购订单变更类型");
            current = capture(order, request.getId(), true);
        }
        if (!Objects.equals(request.getEvidenceFingerprint(), current.getEvidenceFingerprint())
                || !Boolean.TRUE.equals(current.getEligible())) {
            request.setStatus("STALE").setApproved(false)
                    .setResultSnapshot(json(Map.of("blockers", current.getBlockers(),
                            "currentEvidenceFingerprint", current.getEvidenceFingerprint())));
            require(requestMapper.updateById(request) == 1, "订单变更失效状态保存失败");
            operation(request, requestId, fingerprint, "REVIEW_STALE", before, "STALE",
                    current.getBlockers().isEmpty() ? "订单或下游事实已变化" : String.join("；", current.getBlockers()), reviewer);
            return detail(request.getId());
        }
        if ("AMEND_ORDER".equals(request.getChangeType())) {
            executeAmendment(request, order, current, reviewer, requestId, fingerprint, reason);
        } else {
            executeCancellation(request, order, current, reviewer, requestId, fingerprint, reason);
        }
        return detail(request.getId());
    }

    private void executeCancellation(PmsOrderChangeRequestEntity request, PmsOrderEntity order,
                                     PmsOrderChangeDTO.Preview evidence, MomUser reviewer,
                                     String reviewRequestId, String reviewFingerprint, String reviewReason) {
        Map<Long, PmsOrderChangeLineEntity> frozenLines = lineMapper.selectList(
                        new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                                .eq(PmsOrderChangeLineEntity::getChangeRequestId, request.getId()))
                .stream().collect(Collectors.toMap(PmsOrderChangeLineEntity::getOrderDetailId, value -> value));
        require(frozenLines.size() == evidence.getLines().size(), "订单变更冻结行数量不完整");
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId())
                .in(PmsOrderDetailEntity::getId, frozenLines.keySet()).orderByAsc(PmsOrderDetailEntity::getId)
                .last("FOR UPDATE"));
        require(details.size() == frozenLines.size(), "订单变更冻结行与当前订单明细不一致");
        List<PmsPlanEntity> rollbacks = new ArrayList<>();
        for (PmsOrderDetailEntity detail : details) {
            PmsOrderChangeLineEntity line = frozenLines.get(detail.getId());
            require(line != null, "订单变更冻结行与当前订单明细不一致");
            BigDecimal ordered = decimal(detail.getOrderNum());
            BigDecimal arrived = decimal(detail.getArrivalNum());
            BigDecimal outstanding = ordered.subtract(arrived).max(BigDecimal.ZERO);
            require(outstanding.compareTo(line.getCancelledQuantity()) == 0,
                    "订单行未到货数量与冻结证据不一致");
            detail.setCancelledNum(outstanding.doubleValue());
            detail.setCancellationRequestId(request.getId());
            detail.setLastChangeRequestId(request.getId());
            detail.setChangeVersion(Objects.requireNonNullElse(detail.getChangeVersion(), 0) + 1);
            require(detailMapper.updateById(detail) == 1, "采购订单行取消数量保存失败");
            if (!Boolean.TRUE.equals(order.getType()) && outstanding.signum() > 0) {
                require(StrUtil.isNotBlank(detail.getPmsPlanNo()), "计划型订单行缺少采购计划号，无法释放占用");
                PmsPlanEntity rollback = new PmsPlanEntity();
                rollback.setPmsPlanNo(detail.getPmsPlanNo());
                rollback.setPlanOrderNum(outstanding.doubleValue());
                rollbacks.add(rollback);
            }
        }
        List<PmsContractEntity> activeContracts = contractMapper.selectList(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getPmsOrderNo, order.getPmsOrderNo())
                .and(value -> value.isNull(PmsContractEntity::getStatus)
                        .or().ne(PmsContractEntity::getStatus, PmsContractStatusEnum.TERMINATED.getCode()))
                .orderByAsc(PmsContractEntity::getId).last("FOR UPDATE"));
        List<PmsContractDetailEntity> contractLines = activeContracts.isEmpty() ? List.of()
                : contractDetailMapper.selectList(new LambdaQueryWrapper<PmsContractDetailEntity>()
                .in(PmsContractDetailEntity::getContractId,
                        activeContracts.stream().map(PmsContractEntity::getId).toList())
                .in(PmsContractDetailEntity::getOrderDetailId, frozenLines.keySet())
                .orderByAsc(PmsContractDetailEntity::getId).last("FOR UPDATE"));
        for (PmsContractDetailEntity contractLine : contractLines) {
            PmsOrderChangeLineEntity line = frozenLines.get(contractLine.getOrderDetailId());
            require(line != null && decimal(contractLine.getCancelledNum()).signum() == 0
                            && contractLine.getCancellationRequestId() == null,
                    "合同条款撤回事实已变化，禁止执行旧方案");
            contractLine.setCancelledNum(line.getCancelledQuantity());
            contractLine.setCancellationRequestId(request.getId());
            contractLine.setChangeVersion(Objects.requireNonNullElse(contractLine.getChangeVersion(), 0) + 1);
            require(contractDetailMapper.updateById(contractLine) == 1, "合同条款供给撤回结果保存失败");
        }
        List<PmsOrderDetailEntity> allDetails = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId)
                .last("FOR UPDATE"));
        BigDecimal arrivedTotal = allDetails.stream().map(value -> decimal(value.getArrivalNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean hasOpenLine = allDetails.stream().anyMatch(value -> decimal(value.getOrderNum())
                .subtract(decimal(value.getArrivalNum())).subtract(decimal(value.getCancelledNum())).signum() > 0);
        String resultStatus = hasOpenLine && "CANCEL_LINES".equals(request.getChangeType()) ? order.getStatus()
                : arrivedTotal.signum() == 0 ? PmsOrderStatusEnum.CANCELLED.getCode()
                : PmsOrderStatusEnum.PART_CANCELLED.getCode();
        order.setStatus(resultStatus);
        order.setLastChangeRequestId(request.getId());
        order.setCancelledTime(LocalDateTime.now());
        order.setCancelReason(request.getReason());
        require(orderMapper.updateById(order) == 1, "采购订单取消终态保存失败");
        planService.rollbackPlanOrderNum(rollbacks);

        List<PmsDeliveryCommitmentEntity> activeCommitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, order.getId())
                        .in(PmsDeliveryCommitmentEntity::getOrderDetailId, frozenLines.keySet())
                        .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE").orderByAsc(PmsDeliveryCommitmentEntity::getId));
        for (PmsDeliveryCommitmentEntity commitment : activeCommitments) {
            PmsDeliveryCommitmentCancelDTO cancel = new PmsDeliveryCommitmentCancelDTO();
            cancel.setCommitmentId(commitment.getId());
            cancel.setRequestId("POC-" + request.getId() + '-' + commitment.getId());
            cancel.setReason(compact("订单变更单" + request.getRequestNo() + "取消剩余供给：" + request.getReason(), 500));
            deliveryCommitmentService.cancel(cancel);
        }
        for (PmsOrderChangeLineEntity line : frozenLines.values()) {
            if (line.getCancelledQuantity().signum() > 0 && StrUtil.isNotBlank(line.getPmsPlanNo())) {
                planEventOutboxService.enqueueOrderRemainingCancelled(request, line);
            }
        }
        if ("AGENT_SHORTAGE".equals(order.getSourceType())) {
            agentStatusOutboxService.enqueueCancelled(order, request.getReason());
        }
        request.setStatus("SUCCEEDED").setResultOrderStatus(resultStatus).setCompletedTime(LocalDateTime.now())
                .setResultSnapshot(json(Map.of("orderStatus", resultStatus,
                        "cancelledQuantity", evidence.getTotalCancellableQuantity(),
                        "arrivalQuantity", evidence.getTotalArrivalQuantity(),
                        "cancelledCommitmentCount", activeCommitments.size(),
                        "rolledBackPlanCount", rollbacks.size(),
                        "contractLineCount", contractLines.size())));
        require(requestMapper.updateById(request) == 1, "订单变更执行结果保存失败");
        operation(request, reviewRequestId, reviewFingerprint, "REVIEW_APPROVE_EXECUTE", "SUBMITTED", "SUCCEEDED",
                reviewReason + "；取消未到货量=" + evidence.getTotalCancellableQuantity(), reviewer);
    }

    private void executeAmendment(PmsOrderChangeRequestEntity request, PmsOrderEntity order,
                                  PmsOrderChangeDTO.Preview evidence, MomUser reviewer,
                                  String reviewRequestId, String reviewFingerprint, String reviewReason) {
        Map<Long, PmsOrderChangeLineEntity> frozenLines = lineMapper.selectList(
                        new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                                .eq(PmsOrderChangeLineEntity::getChangeRequestId, request.getId()))
                .stream().collect(Collectors.toMap(PmsOrderChangeLineEntity::getOrderDetailId, value -> value));
        require(frozenLines.size() == evidence.getLines().size(), "订单通用变更冻结行数量不完整");
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId)
                .last("FOR UPDATE"));
        require(details.size() == frozenLines.size(), "订单通用变更冻结行与当前订单明细不一致");

        Map<String, PmsPlanEntity> adjustmentByPlan = new LinkedHashMap<>();
        List<PmsHistoryPriceEntity> historyPrices = new ArrayList<>();
        BigDecimal arrivedTotal = BigDecimal.ZERO;
        for (PmsOrderDetailEntity detail : details) {
            PmsOrderChangeLineEntity line = frozenLines.get(detail.getId());
            require(line != null && line.getTargetOrderQuantity() != null && line.getTargetDeliveryDate() != null
                            && line.getTargetPrice() != null && line.getTargetPriceId() != null
                            && line.getTargetAuthorizationId() != null,
                    "订单通用变更冻结行缺少数量、交期、价格或认证证据");
            require(normalizeDecimal(decimal(detail.getOrderNum())).compareTo(line.getOrderQuantitySnapshot()) == 0
                            && Objects.equals(detail.getDeliveryTime(), line.getDeliveryDate())
                            && Objects.equals(detail.getPriceId(), line.getOriginalPriceId())
                            && Objects.equals(detail.getMaterialAuthorizationId(), line.getOriginalAuthorizationId()),
                    "订单行事实已变化，禁止执行旧变更方案");
            BigDecimal delta = normalizeDecimal(line.getQuantityDelta());
            if (!Boolean.TRUE.equals(order.getType()) && delta.signum() != 0) {
                require(StrUtil.isNotBlank(line.getPmsPlanNo()), "计划型订单变更行缺少采购计划号");
                PmsPlanEntity allocation = adjustmentByPlan.computeIfAbsent(line.getPmsPlanNo(), ignored -> {
                    PmsPlanEntity value = new PmsPlanEntity();
                    value.setPmsPlanNo(line.getPmsPlanNo());
                    value.setPlanOrderNum(0D);
                    return value;
                });
                allocation.setPlanOrderNum(allocation.getPlanOrderNum() + delta.doubleValue());
            }
            detail.setOrderNum(line.getTargetOrderQuantity().doubleValue());
            detail.setDeliveryTime(line.getTargetDeliveryDate());
            detail.setPrice(line.getTargetPrice());
            detail.setPriceId(line.getTargetPriceId());
            detail.setMaterialAuthorizationId(line.getTargetAuthorizationId());
            detail.setLastChangeRequestId(request.getId());
            detail.setChangeVersion(request.getChangeVersion());
            require(detailMapper.updateById(detail) == 1, "采购订单行通用变更保存失败");
            arrivedTotal = arrivedTotal.add(decimal(detail.getArrivalNum()));

            PmsHistoryPriceEntity history = new PmsHistoryPriceEntity();
            history.setPmsOrderNo(order.getPmsOrderNo());
            history.setPmsOrderDetailId(detail.getId());
            history.setSupplierCode(request.getTargetSupplierCode());
            history.setSupplierName(request.getTargetSupplierName());
            history.setMatCode(detail.getMatCode());
            history.setMatName(detail.getMatName());
            history.setMatUnit(detail.getMatUnit());
            history.setMatSpec(detail.getMatSpec());
            history.setArrivalNum(line.getTargetOrderQuantity().doubleValue());
            history.setPrice(line.getTargetPrice());
            history.setFactoryCode(order.getFactoryCode());
            historyPrices.add(history);
        }
        List<PmsPlanEntity> increases = adjustmentByPlan.values().stream()
                .filter(value -> value.getPlanOrderNum() > 0D).toList();
        List<PmsPlanEntity> decreases = adjustmentByPlan.values().stream()
                .filter(value -> value.getPlanOrderNum() < 0D).map(value -> {
                    PmsPlanEntity rollback = new PmsPlanEntity();
                    rollback.setPmsPlanNo(value.getPmsPlanNo());
                    rollback.setPlanOrderNum(Math.abs(value.getPlanOrderNum()));
                    return rollback;
                }).toList();
        if (!increases.isEmpty()) planService.updatePlanOrderNum(increases);
        if (!decreases.isEmpty()) planService.rollbackPlanOrderNum(decreases);
        require(historyPriceService.saveBatch(historyPrices), "采购订单变更后历史价格保存失败");

        String resultStatus = arrivedTotal.signum() > 0
                ? PmsOrderStatusEnum.PART_RECEIVE.getCode() : PmsOrderStatusEnum.CHECK_SUCCESS.getCode();
        order.setSupplierCode(request.getTargetSupplierCode());
        order.setSupplierName(request.getTargetSupplierName());
        order.setStatus(resultStatus);
        order.setContractStatus(false);
        order.setChangeVersion(request.getChangeVersion());
        order.setLastChangeRequestId(request.getId());
        order.setLastChangedTime(LocalDateTime.now());
        order.setLastChangeReason(request.getReason());
        require(orderMapper.updateById(order) == 1, "采购订单通用变更主单保存失败");

        List<PmsDeliveryCommitmentEntity> activeCommitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, order.getId())
                        .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE").orderByAsc(PmsDeliveryCommitmentEntity::getId));
        Map<Long, List<PmsDeliveryCommitmentEntity>> commitmentsByLine = activeCommitments.stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentEntity::getOrderDetailId));
        int cancelledCommitmentCount = 0;
        int publishedAmendmentEvents = 0;
        for (PmsOrderChangeLineEntity line : frozenLines.values()) {
            if (!Boolean.TRUE.equals(line.getSupplyAffected())) continue;
            List<PmsDeliveryCommitmentEntity> commitments = commitmentsByLine.getOrDefault(line.getOrderDetailId(), List.of());
            for (PmsDeliveryCommitmentEntity commitment : commitments) {
                PmsDeliveryCommitmentCancelDTO cancel = new PmsDeliveryCommitmentCancelDTO();
                cancel.setCommitmentId(commitment.getId());
                cancel.setRequestId("POA-" + request.getId() + '-' + commitment.getId());
                cancel.setReason(compact("订单变更单" + request.getRequestNo() + "改变供应事实：" + request.getReason(), 500));
                deliveryCommitmentService.cancel(cancel);
                cancelledCommitmentCount++;
            }
            BigDecimal targetOutstanding = line.getTargetOrderQuantity().subtract(line.getArrivalQuantitySnapshot())
                    .max(BigDecimal.ZERO);
            if (commitments.isEmpty() && targetOutstanding.signum() > 0 && StrUtil.isNotBlank(line.getPmsPlanNo())) {
                planEventOutboxService.enqueueOrderAmended(request, line);
                publishedAmendmentEvents++;
            }
        }
        request.setStatus("SUCCEEDED").setResultOrderStatus(resultStatus).setCompletedTime(LocalDateTime.now())
                .setResultSnapshot(json(Map.of("orderStatus", resultStatus, "changeVersion", request.getChangeVersion(),
                        "targetSupplierCode", request.getTargetSupplierCode(), "targetOrderQuantity", evidence.getTotalTargetQuantity(),
                        "quantityDelta", evidence.getTotalQuantityDelta(), "changedLineCount", frozenLines.size(),
                        "cancelledCommitmentCount", cancelledCommitmentCount,
                        "publishedAmendmentEventCount", publishedAmendmentEvents)));
        require(requestMapper.updateById(request) == 1, "采购订单通用变更执行结果保存失败");
        operation(request, reviewRequestId, reviewFingerprint, "REVIEW_APPROVE_EXECUTE", "SUBMITTED", "SUCCEEDED",
                reviewReason + "；变更版本=" + request.getChangeVersion() + "；数量差额=" + evidence.getTotalQuantityDelta(), reviewer);
    }

    @Override
    public PmsOrderChangeDTO.Detail detail(Long requestId) {
        PmsOrderChangeRequestEntity request = requireRequest(requestId, false);
        PmsOrderChangeDTO.Detail result = new PmsOrderChangeDTO.Detail();
        org.springframework.beans.BeanUtils.copyProperties(request, result);
        result.setLines(lineMapper.selectList(new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                .eq(PmsOrderChangeLineEntity::getChangeRequestId, requestId).orderByAsc(PmsOrderChangeLineEntity::getId)));
        result.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                .eq(PmsOrderChangeOperationEntity::getChangeRequestId, requestId)
                .orderByAsc(PmsOrderChangeOperationEntity::getOperationTime)
                .orderByAsc(PmsOrderChangeOperationEntity::getId)));
        return result;
    }

    @Override
    public List<PmsOrderChangeRequestEntity> listByOrder(Long orderId) {
        require(orderId != null, "采购订单不能为空");
        PmsOrderEntity order = orderMapper.selectById(orderId);
        requireOrderScope(order);
        return requestMapper.selectList(new LambdaQueryWrapper<PmsOrderChangeRequestEntity>()
                .eq(PmsOrderChangeRequestEntity::getPmsOrderId, orderId)
                .orderByDesc(PmsOrderChangeRequestEntity::getSubmittedTime)
                .orderByDesc(PmsOrderChangeRequestEntity::getId));
    }

    @Override
    public PmsOrderChangeDTO.Preview previewOmsLineWithdrawal(PmsOmsSupplyWithdrawalDTO.PreviewCommand command) {
        require(command != null, "OMS采购供给撤回预览命令不能为空");
        PmsOrderChangeDTO.LineCancelPreviewCommand preview = new PmsOrderChangeDTO.LineCancelPreviewCommand();
        preview.setPmsOrderId(command.getPmsOrderId());
        preview.setOrderDetailIds(command.getOrderDetailIds());
        return previewCancelLines(preview);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsOmsSupplyWithdrawalDTO.Receipt submitOmsLineWithdrawal(
            PmsOmsSupplyWithdrawalDTO.SubmitCommand command) {
        require(command != null && command.getPmsOrderId() != null
                        && command.getOrderDetailIds() != null && !command.getOrderDetailIds().isEmpty()
                        && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint())
                        && StrUtil.isNotBlank(command.getReason())
                        && StrUtil.isNotBlank(command.getSourceReplanId())
                        && StrUtil.isNotBlank(command.getApplicantCode()),
                "OMS采购供给撤回必须提供订单、订单行、请求号、冻结证据、来源级联单和原申请人");
        Set<Long> detailIds = normalizeDetailIds(command.getOrderDetailIds());
        String requestId = bounded(command.getRequestId(), 128, "OMS提交请求号");
        String reason = bounded(command.getReason(), 500, "OMS级联原因");
        String sourceId = bounded(command.getSourceReplanId(), 100, "OMS级联主键");
        String sourceNo = bounded(command.getSourceReplanNo(), 100, "OMS级联单号");
        String applicantCode = bounded(command.getApplicantCode(), 100, "OMS原申请人工号");
        String applicantName = StrUtil.isBlank(command.getApplicantName()) ? applicantCode
                : bounded(command.getApplicantName(), 200, "OMS原申请人姓名");
        String submitFingerprint = hash(command.getPmsOrderId() + "|CANCEL_LINES|" + detailIds + "|"
                + command.getExpectedEvidenceFingerprint().trim() + "|" + reason
                + "|OMS_SUPPLY_REPLAN|" + sourceId + "|" + applicantCode);
        requestMapper.lockKey("SUBMIT:" + requestId);
        PmsOrderChangeOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeOperationEntity>()
                        .eq(PmsOrderChangeOperationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("SUBMIT".equals(duplicate.getOperationType())
                            && Objects.equals(duplicate.getRequestFingerprint(), submitFingerprint),
                    "OMS采购撤回请求号已被不同动作或载荷使用");
            return omsReceipt(requireRequest(duplicate.getChangeRequestId(), false));
        }
        PmsOrderEntity order = lockOrder(command.getPmsOrderId());
        PmsOrderChangeDTO.Preview preview = captureLines(order, detailIds, null, true);
        require(Objects.equals(preview.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint().trim()),
                "采购订单行、合同或收料事实已变化，请重新发起OMS供应级联");
        require(Boolean.TRUE.equals(preview.getEligible()), String.join("；", preview.getBlockers()));
        PmsOrderChangeRequestEntity request = new PmsOrderChangeRequestEntity()
                .setRequestNo("POL-" + IdUtil.getSnowflakeNextIdStr()).setChangeType("CANCEL_LINES")
                .setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setOriginalOrderStatus(order.getStatus()).setStatus("SUBMITTED")
                .setSubmitRequestId(requestId).setSubmitFingerprint(submitFingerprint)
                .setExternalSourceType("OMS_SUPPLY_REPLAN").setExternalSourceId(sourceId)
                .setExternalSourceNo(sourceNo)
                .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                .setReason(reason).setTotalOrderQuantity(preview.getTotalOrderQuantity())
                .setTotalArrivalQuantity(preview.getTotalArrivalQuantity())
                .setTotalCancelledQuantity(preview.getTotalCancellableQuantity())
                .setReceiptCount(preview.getReceiptCount()).setActiveReceiptCount(preview.getActiveReceiptCount())
                .setContractCount(preview.getContractCount()).setSettlementCount(preview.getSettlementCount())
                .setActiveCommitmentCount(preview.getActiveCommitmentCount())
                .setSupplyAffectedLineCount(preview.getLines().size())
                .setApplicantCode(applicantCode).setApplicantName(applicantName)
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(order.getFactoryCode());
        require(requestMapper.insert(request) == 1, "OMS采购订单行撤回申请保存失败");
        for (PmsOrderChangeLineEntity previewLine : preview.getLines()) {
            require(lineMapper.insert(copyLine(previewLine).setChangeRequestId(request.getId())) == 1,
                    "OMS采购订单行撤回证据保存失败");
        }
        operation(request, requestId, submitFingerprint, "SUBMIT", null, "SUBMITTED",
                "来源OMS供应级联[" + sourceNo + "]；" + reason, currentUser());
        return omsReceipt(request);
    }

    @Override
    public PmsOmsSupplyWithdrawalDTO.Receipt getOmsLineWithdrawalReceipt(String requestId) {
        String normalized = bounded(requestId, 128, "OMS提交请求号");
        PmsOrderChangeRequestEntity request = requestMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeRequestEntity>()
                        .eq(PmsOrderChangeRequestEntity::getSubmitRequestId, normalized)
                        .eq(PmsOrderChangeRequestEntity::getExternalSourceType, "OMS_SUPPLY_REPLAN"));
        require(request != null, "OMS采购撤回申请尚未建立");
        requireRequest(request.getId(), false);
        return omsReceipt(request);
    }

    private PmsOmsSupplyWithdrawalDTO.Receipt omsReceipt(PmsOrderChangeRequestEntity request) {
        String message = switch (request.getStatus()) {
            case "SUBMITTED" -> "采购订单行撤回申请已建立，等待采购异岗复核";
            case "SUCCEEDED" -> "采购订单行未到货供给已受控撤回";
            case "REJECTED" -> "采购复核已驳回：" + StrUtil.blankToDefault(request.getReviewReason(), "未填写原因");
            case "STALE" -> "采购订单、合同或现场事实已变化，原撤回申请失效";
            default -> "采购撤回申请状态：" + request.getStatus();
        };
        return new PmsOmsSupplyWithdrawalDTO.Receipt().setChangeRequestId(request.getId())
                .setChangeRequestNo(request.getRequestNo()).setPmsOrderId(request.getPmsOrderId())
                .setPmsOrderNo(request.getPmsOrderNo()).setRequestId(request.getSubmitRequestId())
                .setStatus(request.getStatus()).setEvidenceFingerprint(request.getEvidenceFingerprint())
                .setMessage(message);
    }

    private PmsOrderChangeDTO.Preview captureAmendment(PmsOrderEntity order,
                                                       PmsOrderChangeDTO.AmendPreviewCommand command,
                                                       Long excludeRequestId, boolean lockFacts) {
        requireOrderScope(order);
        List<String> blockers = new ArrayList<>();
        if (!ELIGIBLE.contains(order.getStatus())) blockers.add("只有审核通过、收货中或部分到货订单允许通用变更");
        if ("AGENT_SHORTAGE".equals(order.getSourceType()))
            blockers.add("Agent采购订单须先受控撤回剩余供给，再由PLAN重新下达，禁止直接改写MRP已确认供给");
        String targetSupplierCode = StrUtil.blankToDefault(command.getTargetSupplierCode(), "").trim();
        if (targetSupplierCode.isEmpty()) blockers.add("目标供应商不能为空");

        MdmSupplierEntity targetSupplier = resolveSupplier(targetSupplierCode, order.getFactoryCode(), blockers);
        String targetSupplierName = targetSupplier == null ? null : targetSupplier.getSupplierName();
        boolean supplierChanged = !Objects.equals(order.getSupplierCode(), targetSupplierCode);

        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId)
                .last(lockFacts ? "FOR UPDATE" : ""));
        if (details.isEmpty()) blockers.add("采购订单没有有效明细");
        Map<Long, PmsOrderChangeDTO.AmendLineCommand> commands = new LinkedHashMap<>();
        for (PmsOrderChangeDTO.AmendLineCommand line : Optional.ofNullable(command.getLines()).orElse(List.of())) {
            if (line == null || line.getOrderDetailId() == null) {
                blockers.add("目标变更行存在空订单行");
                continue;
            }
            if (commands.putIfAbsent(line.getOrderDetailId(), line) != null)
                blockers.add("订单行" + line.getOrderDetailId() + "重复提交");
        }
        Set<Long> currentIds = details.stream().map(PmsOrderDetailEntity::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!commands.keySet().equals(currentIds)) blockers.add("通用变更必须完整提交原订单全部明细，且不得加删物料行");

        long activeRequestCount = requestMapper.selectCount(new LambdaQueryWrapper<PmsOrderChangeRequestEntity>()
                .eq(PmsOrderChangeRequestEntity::getPmsOrderId, order.getId())
                .in(PmsOrderChangeRequestEntity::getStatus, LIVE_REQUEST)
                .ne(excludeRequestId != null, PmsOrderChangeRequestEntity::getId, excludeRequestId));
        if (activeRequestCount > 0) blockers.add("采购订单已有待复核变更申请");

        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, order.getPmsOrderNo())
                .orderByAsc(WmsPurchaseReceiptEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        long activeReceipts = receipts.stream().filter(value -> !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(value.getState())).count();
        if (activeReceipts > 0) blockers.add("存在待处理、收料中、质检中或待入库收料单，须先关闭现场流程");

        long contractCount = contractMapper.selectCount(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getPmsOrderNo, order.getPmsOrderNo())
                .and(value -> value.isNull(PmsContractEntity::getStatus)
                        .or().ne(PmsContractEntity::getStatus, PmsContractStatusEnum.TERMINATED.getCode())));
        if (contractCount > 0) blockers.add("采购订单仍关联未终止合同，须先完成合同终止流程");
        long reconciliationCount = reconciliationMapper.selectCount(new LambdaQueryWrapper<PmsBusinessReconciliationEntity>()
                .eq(PmsBusinessReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long invoiceCount = invoiceReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .eq(PmsInvoiceReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long paymentCount = paymentReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsPaymentReconciliationEntity>()
                .eq(PmsPaymentReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long settlementCount = reconciliationCount + invoiceCount + paymentCount;
        if (settlementCount > 0) blockers.add("采购订单已进入对账、发票或付款链路，须先完成差异/冲销流程");

        List<PmsDeliveryCommitmentEntity> activeCommitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, order.getId())
                        .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE").orderByAsc(PmsDeliveryCommitmentEntity::getId));
        Map<Long, List<PmsDeliveryCommitmentEntity>> commitmentsByLine = activeCommitments.stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentEntity::getOrderDetailId));

        List<PmsOrderChangeLineEntity> lines = new ArrayList<>();
        List<PmsOrderChangeDTO.AmendLinePreview> amendLines = new ArrayList<>();
        BigDecimal totalOrder = BigDecimal.ZERO, totalTarget = BigDecimal.ZERO, totalArrival = BigDecimal.ZERO;
        BigDecimal targetOutstandingTotal = BigDecimal.ZERO;
        int supplyAffectedCount = 0;
        boolean anyChange = supplierChanged;
        Map<String, BigDecimal> deltaByPlan = new LinkedHashMap<>();
        for (PmsOrderDetailEntity detail : details) {
            PmsOrderChangeDTO.AmendLineCommand target = commands.get(detail.getId());
            List<String> lineBlockers = new ArrayList<>();
            BigDecimal originalQuantity = decimal(detail.getOrderNum());
            BigDecimal arrival = decimal(detail.getArrivalNum());
            BigDecimal targetQuantity = target == null ? null : normalizeDecimal(target.getTargetOrderQuantity());
            java.time.LocalDate targetDate = target == null ? null : target.getTargetDeliveryDate();
            if (targetQuantity == null || targetQuantity.signum() <= 0) lineBlockers.add("目标订购量必须为正数");
            if (targetQuantity != null && targetQuantity.compareTo(arrival) < 0)
                lineBlockers.add("目标订购量不能小于已到货量" + arrival.stripTrailingZeros().toPlainString());
            if (targetDate == null) lineBlockers.add("目标交期不能为空");
            if (targetQuantity != null && targetQuantity.compareTo(arrival) > 0
                    && targetDate != null && targetDate.isBefore(java.time.LocalDate.now()))
                lineBlockers.add("仍有未到货量时目标交期不能早于今天");

            List<PmsOrderChangeDTO.PriceOption> priceOptions = priceOptions(order.getFactoryCode(), targetSupplierCode,
                    detail.getMatCode(), targetDate);
            PmsOrderChangeDTO.PriceOption selectedPrice = target == null ? null : priceOptions.stream()
                    .filter(value -> Objects.equals(value.getPriceId(), target.getTargetPriceId())).findFirst().orElse(null);
            if (selectedPrice == null) lineBlockers.add("未选择覆盖目标供应商、物料和交期的有效价格");
            List<PmsOrderChangeDTO.AuthorizationOption> authorizationOptions = authorizationOptions(order.getFactoryCode(),
                    targetSupplierCode, detail.getMatCode(), targetDate, lineBlockers);
            PmsOrderChangeDTO.AuthorizationOption selectedAuthorization = target == null ? null : authorizationOptions.stream()
                    .filter(value -> Objects.equals(value.getAuthorizationId(), target.getTargetAuthorizationId()))
                    .findFirst().orElse(null);
            if (selectedAuthorization == null) lineBlockers.add("未选择覆盖目标供应商、物料和交期的有效物料认证");

            BigDecimal targetPrice = selectedPrice == null ? null : normalizeDecimal(selectedPrice.getUnitPrice());
            BigDecimal quantityDelta = targetQuantity == null ? BigDecimal.ZERO : targetQuantity.subtract(originalQuantity);
            boolean quantityChanged = targetQuantity != null && targetQuantity.compareTo(originalQuantity) != 0;
            boolean dateChanged = targetDate != null && !Objects.equals(targetDate, detail.getDeliveryTime());
            boolean priceChanged = targetPrice != null && (detail.getPrice() == null || targetPrice.compareTo(detail.getPrice()) != 0
                    || !Objects.equals(detail.getPriceId(), target.getTargetPriceId()));
            boolean authorizationChanged = target != null
                    && !Objects.equals(detail.getMaterialAuthorizationId(), target.getTargetAuthorizationId());
            boolean supplyAffected = supplierChanged || quantityChanged || dateChanged;
            anyChange = anyChange || quantityChanged || dateChanged || priceChanged || authorizationChanged;
            if (supplyAffected) supplyAffectedCount++;
            List<PmsDeliveryCommitmentEntity> commitments = commitmentsByLine.getOrDefault(detail.getId(), List.of());
            String summary = String.join("、", Arrays.asList(supplierChanged ? "供应商" : null,
                            quantityChanged ? "数量" : null, dateChanged ? "交期" : null,
                            priceChanged ? "价格" : null, authorizationChanged ? "认证" : null).stream()
                    .filter(Objects::nonNull).toList());
            PmsOrderChangeLineEntity line = new PmsOrderChangeLineEntity().setOrderDetailId(detail.getId())
                    .setPmsPlanNo(detail.getPmsPlanNo()).setPlanDemandNo(detail.getOrderNo())
                    .setMaterialCode(detail.getMatCode()).setMaterialName(detail.getMatName())
                    .setMaterialUnit(detail.getMatUnit()).setDeliveryDate(detail.getDeliveryTime()).setTargetDeliveryDate(targetDate)
                    .setOrderQuantitySnapshot(originalQuantity).setTargetOrderQuantity(targetQuantity)
                    .setQuantityDelta(quantityDelta).setArrivalQuantitySnapshot(arrival).setCancelledQuantity(BigDecimal.ZERO)
                    .setOriginalPrice(normalizeDecimal(detail.getPrice())).setTargetPrice(targetPrice)
                    .setOriginalPriceId(detail.getPriceId()).setTargetPriceId(target == null ? null : target.getTargetPriceId())
                    .setOriginalAuthorizationId(detail.getMaterialAuthorizationId())
                    .setTargetAuthorizationId(target == null ? null : target.getTargetAuthorizationId())
                    .setSupplyAffected(supplyAffected).setChangeSummary(StrUtil.blankToDefault(summary, "无变化"))
                    .setActiveCommitmentCount(commitments.size())
                    .setActiveCommitmentIds(commitments.stream().map(value -> String.valueOf(value.getId()))
                            .collect(Collectors.joining(","))).setFactoryCode(detail.getFactoryCode());
            lines.add(line);
            PmsOrderChangeDTO.AmendLinePreview linePreview = new PmsOrderChangeDTO.AmendLinePreview();
            linePreview.setChangeLine(line);
            linePreview.setPriceOptions(priceOptions);
            linePreview.setAuthorizationOptions(authorizationOptions);
            linePreview.setBlockers(lineBlockers);
            amendLines.add(linePreview);
            blockers.addAll(lineBlockers.stream().map(value -> "订单行" + detail.getId() + "：" + value).toList());
            totalOrder = totalOrder.add(originalQuantity);
            totalArrival = totalArrival.add(arrival);
            if (targetQuantity != null) {
                totalTarget = totalTarget.add(targetQuantity);
                targetOutstandingTotal = targetOutstandingTotal.add(targetQuantity.subtract(arrival).max(BigDecimal.ZERO));
                if (!Boolean.TRUE.equals(order.getType()) && StrUtil.isNotBlank(detail.getPmsPlanNo()))
                    deltaByPlan.merge(detail.getPmsPlanNo(), quantityDelta, BigDecimal::add);
            }
            if (!Boolean.TRUE.equals(order.getType()) && StrUtil.isBlank(detail.getPmsPlanNo()))
                blockers.add("计划型订单行" + detail.getId() + "缺少采购计划号");
        }
        if (!anyChange) blockers.add("目标订单与当前已审批订单完全一致，无需提交变更");
        if (totalOrder.subtract(totalArrival).signum() > 0 && targetOutstandingTotal.signum() == 0)
            blockers.add("不能通过通用变更清零全部未到货量，请使用‘取消剩余量’流程保留明确终止状态");

        List<PmsPlanEntity> plans = List.of();
        if (!Boolean.TRUE.equals(order.getType()) && !deltaByPlan.isEmpty()) {
            plans = planService.list(new LambdaQueryWrapper<PmsPlanEntity>()
                    .in(PmsPlanEntity::getPmsPlanNo, deltaByPlan.keySet())
                    .orderByAsc(PmsPlanEntity::getPmsPlanNo).last(lockFacts ? "FOR UPDATE" : ""));
            Map<String, List<PmsPlanEntity>> byNo = plans.stream().collect(Collectors.groupingBy(PmsPlanEntity::getPmsPlanNo));
            for (Map.Entry<String, BigDecimal> entry : deltaByPlan.entrySet()) {
                List<PmsPlanEntity> matched = byNo.getOrDefault(entry.getKey(), List.of());
                if (matched.size() != 1) {
                    blockers.add("采购计划" + entry.getKey() + "不存在或不唯一");
                    continue;
                }
                PmsPlanEntity plan = matched.get(0);
                BigDecimal after = decimal(plan.getPlanOrderNum()).add(entry.getValue());
                if (after.signum() < 0 || after.compareTo(decimal(plan.getPmsNum())) > 0)
                    blockers.add("采购计划" + entry.getKey() + "变更后已下单量" + after
                            + "超出0～计划量" + decimal(plan.getPmsNum()) + "范围");
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("changeType", "AMEND_ORDER");
        snapshot.put("changeVersion", Objects.requireNonNullElse(order.getChangeVersion(), 0) + 1);
        snapshot.put("order", Arrays.asList(order.getId(), order.getPmsOrderNo(), order.getStatus(), order.getType(),
                order.getSupplierCode(), order.getSupplierName(), order.getContractStatus(), order.getFactoryCode(),
                order.getLastChangeRequestId(), order.getChangeVersion()));
        snapshot.put("targetSupplier", targetSupplier == null ? Arrays.asList(targetSupplierCode, null, null, null, null)
                : Arrays.asList(targetSupplier.getId(), targetSupplier.getSupplierCode(), targetSupplier.getSupplierName(),
                targetSupplier.getStatus(), targetSupplier.getCheckStatus(), targetSupplier.getIsDeliver()));
        snapshot.put("lines", lines.stream().map(value -> Arrays.asList(value.getOrderDetailId(), value.getPmsPlanNo(),
                value.getMaterialCode(), value.getDeliveryDate(), value.getTargetDeliveryDate(), value.getOrderQuantitySnapshot(),
                value.getTargetOrderQuantity(), value.getArrivalQuantitySnapshot(), value.getOriginalPrice(), value.getTargetPrice(),
                value.getOriginalPriceId(), value.getTargetPriceId(), value.getOriginalAuthorizationId(),
                value.getTargetAuthorizationId(), value.getSupplyAffected(), value.getActiveCommitmentIds())).toList());
        snapshot.put("plans", plans.stream().map(value -> Arrays.asList(value.getId(), value.getPmsPlanNo(), value.getOrderNo(),
                value.getMatCode(), value.getPmsNum(), value.getPlanOrderNum(), value.getPlanArrivalNum(), value.getStatus())).toList());
        snapshot.put("receipts", receipts.stream().map(value -> Arrays.asList(value.getId(), value.getBillNo(), value.getState())).toList());
        snapshot.put("contractCount", contractCount);
        snapshot.put("settlement", List.of(reconciliationCount, invoiceCount, paymentCount));
        snapshot.put("activeCommitments", activeCommitments.stream().map(value -> Arrays.asList(value.getId(),
                value.getOrderDetailId(), value.getVersionNo(), value.getStatus(), value.getTotalCommittedNum())).toList());
        snapshot.put("activeRequestCount", activeRequestCount);
        String snapshotJson = json(snapshot);
        PmsOrderChangeDTO.Preview preview = new PmsOrderChangeDTO.Preview();
        preview.setPmsOrderId(order.getId());
        preview.setPmsOrderNo(order.getPmsOrderNo());
        preview.setOrderStatus(order.getStatus());
        preview.setChangeType("AMEND_ORDER");
        preview.setChangeVersion(Objects.requireNonNullElse(order.getChangeVersion(), 0) + 1);
        preview.setOriginalSupplierCode(order.getSupplierCode());
        preview.setOriginalSupplierName(order.getSupplierName());
        preview.setTargetSupplierCode(targetSupplierCode);
        preview.setTargetSupplierName(targetSupplierName);
        preview.setEligible(blockers.isEmpty());
        preview.setBlockers(blockers);
        preview.setEvidenceSnapshot(snapshotJson);
        preview.setEvidenceFingerprint(hash(snapshotJson));
        preview.setTotalOrderQuantity(totalOrder);
        preview.setTotalTargetQuantity(totalTarget);
        preview.setTotalQuantityDelta(totalTarget.subtract(totalOrder));
        preview.setTotalArrivalQuantity(totalArrival);
        preview.setTotalCancellableQuantity(BigDecimal.ZERO);
        preview.setReceiptCount(receipts.size());
        preview.setActiveReceiptCount((int) activeReceipts);
        preview.setContractCount((int) contractCount);
        preview.setSettlementCount((int) settlementCount);
        preview.setActiveCommitmentCount(activeCommitments.size());
        preview.setSupplyAffectedLineCount(supplyAffectedCount);
        preview.setLines(lines);
        preview.setAmendLines(amendLines);
        return preview;
    }

    private MdmSupplierEntity resolveSupplier(String supplierCode, String factoryCode, List<String> blockers) {
        if (StrUtil.isBlank(supplierCode)) return null;
        try {
            MdmSupplierEntity query = new MdmSupplierEntity();
            query.setSupplierCode(supplierCode);
            query.setFactoryCode(factoryCode);
            R<MdmSupplierEntity> response = supplierService.getDetails(query);
            MdmSupplierEntity supplier = response == null || !response.isOk() ? null : response.getData();
            if (supplier == null || !Objects.equals(factoryCode, supplier.getFactoryCode())
                    || !Boolean.TRUE.equals(supplier.getStatus()) || !Boolean.TRUE.equals(supplier.getIsDeliver())
                    || !"1".equals(supplier.getCheckStatus())) {
                blockers.add("目标供应商不存在、跨工厂、未审核、未启用或不允许供货");
                return null;
            }
            return supplier;
        } catch (Exception exception) {
            blockers.add("供应商权威主数据暂不可用，禁止提交变更");
            return null;
        }
    }

    private List<PmsOrderChangeDTO.PriceOption> priceOptions(String factoryCode, String supplierCode,
                                                             String materialCode, java.time.LocalDate deliveryDate) {
        if (StrUtil.isBlank(supplierCode) || StrUtil.isBlank(materialCode) || deliveryDate == null) return List.of();
        LocalDateTime start = deliveryDate.atStartOfDay();
        LocalDateTime end = deliveryDate.atTime(java.time.LocalTime.MAX);
        return priceService.list(new LambdaQueryWrapper<PmsPriceEntity>()
                        .eq(PmsPriceEntity::getFactoryCode, factoryCode).eq(PmsPriceEntity::getSupplierCode, supplierCode)
                        .eq(PmsPriceEntity::getMatCode, materialCode).eq(PmsPriceEntity::getStatus, true)
                        .and(value -> value.isNull(PmsPriceEntity::getEffectiveTime).or().le(PmsPriceEntity::getEffectiveTime, end))
                        .and(value -> value.isNull(PmsPriceEntity::getExpirationTime).or().ge(PmsPriceEntity::getExpirationTime, start))
                        .orderByDesc(PmsPriceEntity::getEffectiveTime).orderByDesc(PmsPriceEntity::getId)).stream()
                .filter(value -> positive(value.getUnitPriceWithTax()) || positive(value.getUnitPriceWithoutTax()))
                .map(value -> {
                    PmsOrderChangeDTO.PriceOption option = new PmsOrderChangeDTO.PriceOption();
                    option.setPriceId(value.getId());
                    option.setUnitPriceWithoutTax(value.getUnitPriceWithoutTax());
                    option.setUnitPriceWithTax(value.getUnitPriceWithTax());
                    option.setTaxRate(value.getTaxRate());
                    option.setUnitPrice(normalizeDecimal(positive(value.getUnitPriceWithTax())
                            ? value.getUnitPriceWithTax() : value.getUnitPriceWithoutTax()));
                    option.setEffectiveTime(value.getEffectiveTime());
                    option.setExpirationTime(value.getExpirationTime());
                    return option;
                }).toList();
    }

    private List<PmsOrderChangeDTO.AuthorizationOption> authorizationOptions(String factoryCode, String supplierCode,
                                                                             String materialCode, java.time.LocalDate deliveryDate,
                                                                             List<String> blockers) {
        if (StrUtil.isBlank(supplierCode) || StrUtil.isBlank(materialCode) || deliveryDate == null) return List.of();
        try {
            R<List<MdmMaterialAuthorizationEntity>> response = authorizationService.active(
                    "SUPPLIER_CERTIFICATION", materialCode, supplierCode, deliveryDate);
            if (response == null || !response.isOk() || response.getData() == null) {
                blockers.add("供应商物料认证服务未返回可信证据");
                return List.of();
            }
            return response.getData().stream().filter(value -> Objects.equals(factoryCode, value.getFactoryCode())
                            && Objects.equals(supplierCode, value.getPartnerCode())
                            && Objects.equals(materialCode, value.getMaterialCode()) && "ACTIVE".equals(value.getStatus()))
                    .sorted(Comparator.comparing(MdmMaterialAuthorizationEntity::getId))
                    .map(value -> {
                        PmsOrderChangeDTO.AuthorizationOption option = new PmsOrderChangeDTO.AuthorizationOption();
                        option.setAuthorizationId(value.getId());
                        option.setAuthorizationNo(value.getAuthorizationNo());
                        option.setValidStart(value.getValidStart());
                        option.setValidEnd(value.getValidEnd());
                        option.setInspectionLevel(value.getInspectionLevel());
                        return option;
                    }).toList();
        } catch (Exception exception) {
            blockers.add("供应商物料认证权威服务暂不可用");
            return List.of();
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private PmsOrderChangeDTO.AmendPreviewCommand frozenAmendCommand(PmsOrderChangeRequestEntity request,
                                                                     List<PmsOrderChangeLineEntity> lines) {
        PmsOrderChangeDTO.AmendPreviewCommand command = new PmsOrderChangeDTO.AmendPreviewCommand();
        command.setPmsOrderId(request.getPmsOrderId());
        command.setTargetSupplierCode(request.getTargetSupplierCode());
        command.setLines(lines.stream().map(value -> {
            PmsOrderChangeDTO.AmendLineCommand line = new PmsOrderChangeDTO.AmendLineCommand();
            line.setOrderDetailId(value.getOrderDetailId());
            line.setTargetOrderQuantity(value.getTargetOrderQuantity());
            line.setTargetDeliveryDate(value.getTargetDeliveryDate());
            line.setTargetPriceId(value.getTargetPriceId());
            line.setTargetAuthorizationId(value.getTargetAuthorizationId());
            return line;
        }).toList());
        return command;
    }

    /**
     * 冻结指定订单行的商业供给撤回事实。与整单取消不同，本流程允许订单其他行继续履约；
     * 已签合同条款必须能精确追溯到订单行，目标行存在活动收料时故障关闭。
     */
    private PmsOrderChangeDTO.Preview captureLines(PmsOrderEntity order, Set<Long> selectedIds,
                                                   Long excludeRequestId, boolean lockFacts) {
        requireOrderScope(order);
        require(selectedIds != null && !selectedIds.isEmpty(), "至少选择一条采购订单行");
        List<String> blockers = new ArrayList<>();
        if (!ELIGIBLE.contains(order.getStatus())) blockers.add("只有审核通过、收货中或部分到货订单允许行级撤回");

        List<PmsOrderDetailEntity> allDetails = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId)
                .last(lockFacts ? "FOR UPDATE" : ""));
        Map<Long, PmsOrderDetailEntity> detailById = allDetails.stream()
                .collect(Collectors.toMap(PmsOrderDetailEntity::getId, value -> value));
        if (!detailById.keySet().containsAll(selectedIds)) blockers.add("所选订单行不存在或不属于当前采购订单");
        List<PmsOrderDetailEntity> details = selectedIds.stream().map(detailById::get).filter(Objects::nonNull).toList();

        long activeRequestCount = requestMapper.selectCount(new LambdaQueryWrapper<PmsOrderChangeRequestEntity>()
                .eq(PmsOrderChangeRequestEntity::getPmsOrderId, order.getId())
                .in(PmsOrderChangeRequestEntity::getStatus, LIVE_REQUEST)
                .ne(excludeRequestId != null, PmsOrderChangeRequestEntity::getId, excludeRequestId));
        if (activeRequestCount > 0) blockers.add("采购订单已有待复核变更申请");

        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, order.getPmsOrderNo())
                .orderByAsc(WmsPurchaseReceiptEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        List<WmsPurchaseReceiptDetailEntity> receiptDetails = receipts.isEmpty() ? List.of()
                : receiptDetailMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptDetailEntity>()
                .in(WmsPurchaseReceiptDetailEntity::getBillId, receipts.stream().map(WmsPurchaseReceiptEntity::getId).toList())
                .orderByAsc(WmsPurchaseReceiptDetailEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        Set<Long> activeReceiptIds = receipts.stream()
                .filter(value -> !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(value.getState()))
                .map(WmsPurchaseReceiptEntity::getId).collect(Collectors.toSet());
        List<WmsPurchaseReceiptDetailEntity> activeTargetReceiptLines = receiptDetails.stream()
                .filter(value -> activeReceiptIds.contains(value.getBillId()))
                .filter(value -> value.getOrderDetailId() != null && selectedIds.contains(value.getOrderDetailId())).toList();
        if (!activeTargetReceiptLines.isEmpty())
            blockers.add("目标订单行存在待处理、质检中或待入库收料明细，须先关闭现场流程");
        Set<String> selectedMaterials = details.stream().map(PmsOrderDetailEntity::getMatCode).collect(Collectors.toSet());
        boolean ambiguousReceipt = receiptDetails.stream().filter(value -> activeReceiptIds.contains(value.getBillId()))
                .anyMatch(value -> value.getOrderDetailId() == null && selectedMaterials.contains(value.getMatCode()));
        if (ambiguousReceipt) blockers.add("活动收料明细缺少订单行关联且物料命中目标行，无法安全判定撤回范围");

        List<PmsContractEntity> contracts = contractMapper.selectList(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getPmsOrderNo, order.getPmsOrderNo())
                .and(value -> value.isNull(PmsContractEntity::getStatus)
                        .or().ne(PmsContractEntity::getStatus, PmsContractStatusEnum.TERMINATED.getCode()))
                .orderByAsc(PmsContractEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        List<PmsContractDetailEntity> contractLines = contracts.isEmpty() ? List.of()
                : contractDetailMapper.selectList(new LambdaQueryWrapper<PmsContractDetailEntity>()
                .in(PmsContractDetailEntity::getContractId, contracts.stream().map(PmsContractEntity::getId).toList())
                .in(PmsContractDetailEntity::getOrderDetailId, selectedIds)
                .orderByAsc(PmsContractDetailEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        if (!contracts.isEmpty()) {
            Map<Long, Long> linkedCount = contractLines.stream().collect(Collectors.groupingBy(
                    PmsContractDetailEntity::getOrderDetailId, Collectors.counting()));
            for (Long detailId : selectedIds) {
                if (linkedCount.getOrDefault(detailId, 0L) != 1L)
                    blockers.add("订单行" + detailId + "未唯一关联有效合同条款，须先完成合同明细追溯治理");
            }
        }
        if (contractLines.stream().anyMatch(value -> decimal(value.getCancelledNum()).signum() > 0
                || value.getCancellationRequestId() != null)) {
            blockers.add("目标合同条款已存在受控撤回结果，禁止重复执行");
        }
        Map<Long, PmsContractDetailEntity> contractByOrderDetail = contractLines.stream().collect(Collectors.toMap(
                PmsContractDetailEntity::getOrderDetailId, value -> value, (first, ignored) -> first));

        long reconciliationCount = reconciliationMapper.selectCount(new LambdaQueryWrapper<PmsBusinessReconciliationEntity>()
                .eq(PmsBusinessReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long invoiceCount = invoiceReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .eq(PmsInvoiceReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long paymentCount = paymentReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsPaymentReconciliationEntity>()
                .eq(PmsPaymentReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long settlementCount = reconciliationCount + invoiceCount + paymentCount;
        if (settlementCount > 0) blockers.add("采购订单已进入对账、发票或付款链路，须先完成差异或冲销流程");

        List<PmsDeliveryCommitmentEntity> activeCommitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, order.getId())
                        .in(PmsDeliveryCommitmentEntity::getOrderDetailId, selectedIds)
                        .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE")
                        .orderByAsc(PmsDeliveryCommitmentEntity::getId));
        Map<Long, List<PmsDeliveryCommitmentEntity>> commitmentsByLine = activeCommitments.stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentEntity::getOrderDetailId));
        List<PmsOrderChangeLineEntity> lines = new ArrayList<>();
        BigDecimal totalOrder = BigDecimal.ZERO, totalArrival = BigDecimal.ZERO, totalCancellable = BigDecimal.ZERO;
        for (PmsOrderDetailEntity detail : details) {
            BigDecimal ordered = decimal(detail.getOrderNum());
            BigDecimal arrived = decimal(detail.getArrivalNum());
            BigDecimal alreadyCancelled = decimal(detail.getCancelledNum());
            if (ordered.signum() <= 0 || arrived.signum() < 0 || arrived.add(alreadyCancelled).compareTo(ordered) > 0)
                blockers.add("订单行" + detail.getId() + "订购、到货或已撤回数量不守恒");
            if (alreadyCancelled.signum() > 0 || detail.getCancellationRequestId() != null)
                blockers.add("订单行" + detail.getId() + "已存在撤回结果，禁止重复撤回");
            if (!Boolean.TRUE.equals(order.getType()) && StrUtil.isBlank(detail.getPmsPlanNo()))
                blockers.add("计划型订单行" + detail.getId() + "缺少采购计划号");
            BigDecimal outstanding = ordered.subtract(arrived).subtract(alreadyCancelled).max(BigDecimal.ZERO);
            if (outstanding.signum() <= 0) blockers.add("订单行" + detail.getId() + "已无可撤回未到货量");
            PmsContractDetailEntity contractLine = contractByOrderDetail.get(detail.getId());
            if (contractLine != null && (!Objects.equals(contractLine.getMatCode(), detail.getMatCode())
                    || !Objects.equals(contractLine.getPmsPlanNo(), detail.getPmsPlanNo()))) {
                blockers.add("订单行" + detail.getId() + "与合同条款的物料或采购计划来源不一致");
            }
            if (contractLine != null && (contractLine.getOrderNum() == null
                    || decimal(contractLine.getOrderNum()).compareTo(outstanding) < 0)) {
                blockers.add("订单行" + detail.getId() + "合同条款数量不足以覆盖拟撤回量，须先核对商业数量");
            }
            List<PmsDeliveryCommitmentEntity> commitments = commitmentsByLine.getOrDefault(detail.getId(), List.of());
            lines.add(new PmsOrderChangeLineEntity().setOrderDetailId(detail.getId())
                    .setPmsPlanNo(detail.getPmsPlanNo()).setPlanDemandNo(detail.getOrderNo())
                    .setMaterialCode(detail.getMatCode()).setMaterialName(detail.getMatName())
                    .setMaterialUnit(detail.getMatUnit()).setDeliveryDate(detail.getDeliveryTime())
                    .setOrderQuantitySnapshot(ordered).setArrivalQuantitySnapshot(arrived)
                    .setCancelledQuantity(outstanding)
                    .setActiveCommitmentCount(commitments.size())
                    .setActiveCommitmentIds(commitments.stream().map(value -> String.valueOf(value.getId()))
                            .collect(Collectors.joining(","))).setFactoryCode(detail.getFactoryCode()));
            totalOrder = totalOrder.add(ordered);
            totalArrival = totalArrival.add(arrived);
            totalCancellable = totalCancellable.add(outstanding);
        }
        if (totalCancellable.signum() <= 0) blockers.add("所选订单行没有可撤回的未到货供给");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("order", Arrays.asList(order.getId(), order.getPmsOrderNo(), order.getStatus(), order.getType(),
                order.getSupplierCode(), order.getContractStatus(), order.getFactoryCode(), order.getLastChangeRequestId()));
        snapshot.put("selectedIds", selectedIds);
        snapshot.put("lines", lines.stream().map(value -> Arrays.asList(value.getOrderDetailId(), value.getPmsPlanNo(),
                value.getPlanDemandNo(), value.getMaterialCode(), value.getDeliveryDate(), value.getOrderQuantitySnapshot(),
                value.getArrivalQuantitySnapshot(), value.getCancelledQuantity(), value.getActiveCommitmentIds())).toList());
        snapshot.put("receiptLines", receiptDetails.stream().filter(value -> value.getOrderDetailId() != null
                && selectedIds.contains(value.getOrderDetailId())).map(value -> Arrays.asList(value.getId(), value.getBillId(),
                value.getOrderDetailId(), value.getShouldNum(), value.getActualNum(), value.getInNum())).toList());
        snapshot.put("contracts", contracts.stream().map(value -> Arrays.asList(value.getId(), value.getContractNo(),
                value.getStatus(), value.getCurrentVersion())).toList());
        snapshot.put("contractLines", contractLines.stream().map(value -> Arrays.asList(value.getId(), value.getContractId(),
                value.getOrderDetailId(), value.getPmsPlanNo(), value.getOrderNum(), value.getCancelledNum(),
                value.getChangeVersion())).toList());
        snapshot.put("settlement", List.of(reconciliationCount, invoiceCount, paymentCount));
        snapshot.put("activeCommitments", activeCommitments.stream().map(value -> Arrays.asList(value.getId(),
                value.getOrderDetailId(), value.getVersionNo(), value.getStatus(), value.getTotalCommittedNum())).toList());
        snapshot.put("activeRequestCount", activeRequestCount);
        String snapshotJson = json(snapshot);
        PmsOrderChangeDTO.Preview preview = new PmsOrderChangeDTO.Preview();
        preview.setPmsOrderId(order.getId());
        preview.setPmsOrderNo(order.getPmsOrderNo());
        preview.setOrderStatus(order.getStatus());
        preview.setChangeType("CANCEL_LINES");
        preview.setEligible(blockers.isEmpty());
        preview.setBlockers(blockers);
        preview.setEvidenceSnapshot(snapshotJson);
        preview.setEvidenceFingerprint(hash(snapshotJson));
        preview.setTotalOrderQuantity(totalOrder);
        preview.setTotalArrivalQuantity(totalArrival);
        preview.setTotalCancellableQuantity(totalCancellable);
        preview.setReceiptCount(receipts.size());
        preview.setActiveReceiptCount(activeTargetReceiptLines.size());
        preview.setContractCount(contracts.size());
        preview.setSettlementCount((int) settlementCount);
        preview.setActiveCommitmentCount(activeCommitments.size());
        preview.setSupplyAffectedLineCount(lines.size());
        preview.setLines(lines);
        return preview;
    }

    private Set<Long> normalizeDetailIds(List<Long> detailIds) {
        require(detailIds != null && !detailIds.isEmpty(), "至少选择一条采购订单行");
        require(detailIds.stream().noneMatch(Objects::isNull), "采购订单行不能为空");
        return new TreeSet<>(detailIds);
    }

    private PmsOrderChangeDTO.Preview capture(PmsOrderEntity order, Long excludeRequestId, boolean lockFacts) {
        requireOrderScope(order);
        List<String> blockers = new ArrayList<>();
        if (!ELIGIBLE.contains(order.getStatus())) blockers.add("只有审核通过、收货中或部分到货订单允许申请取消剩余量");
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId)
                .last(lockFacts ? "FOR UPDATE" : ""));
        if (details.isEmpty()) blockers.add("采购订单没有有效明细");

        long activeRequestCount = requestMapper.selectCount(new LambdaQueryWrapper<PmsOrderChangeRequestEntity>()
                .eq(PmsOrderChangeRequestEntity::getPmsOrderId, order.getId())
                .in(PmsOrderChangeRequestEntity::getStatus, LIVE_REQUEST)
                .ne(excludeRequestId != null, PmsOrderChangeRequestEntity::getId, excludeRequestId));
        if (activeRequestCount > 0) blockers.add("采购订单已有待复核变更申请");

        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                .eq(WmsPurchaseReceiptEntity::getPmsOrderNo, order.getPmsOrderNo())
                .orderByAsc(WmsPurchaseReceiptEntity::getId).last(lockFacts ? "FOR UPDATE" : ""));
        long activeReceipts = receipts.stream().filter(value -> !PurchaseReceiptStateEnum.COMPLETED.getCode().equals(value.getState())).count();
        if (activeReceipts > 0) blockers.add("存在待处理、收料中、质检中或待入库的采购收料单，须先关闭其现场流程");

        long contractCount = contractMapper.selectCount(new LambdaQueryWrapper<PmsContractEntity>()
                .eq(PmsContractEntity::getPmsOrderNo, order.getPmsOrderNo())
                .and(value -> value.isNull(PmsContractEntity::getStatus)
                        .or().ne(PmsContractEntity::getStatus,
                                com.kitex.mom.pms.enums.PmsContractStatusEnum.TERMINATED.getCode())));
        if (contractCount > 0) blockers.add("采购订单仍关联未终止合同，须先完成合同终止流程");
        long reconciliationCount = reconciliationMapper.selectCount(new LambdaQueryWrapper<PmsBusinessReconciliationEntity>()
                .eq(PmsBusinessReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long invoiceCount = invoiceReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsInvoiceReconciliationEntity>()
                .eq(PmsInvoiceReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long paymentCount = paymentReconciliationMapper.selectCount(new LambdaQueryWrapper<PmsPaymentReconciliationEntity>()
                .eq(PmsPaymentReconciliationEntity::getPmsOrderNo, order.getPmsOrderNo()));
        long settlementCount = reconciliationCount + invoiceCount + paymentCount;
        if (settlementCount > 0) blockers.add("采购订单已进入对账、发票或付款链路，禁止直接取消剩余量");

        List<PmsDeliveryCommitmentEntity> activeCommitments = commitmentMapper.selectList(
                new LambdaQueryWrapper<PmsDeliveryCommitmentEntity>()
                        .eq(PmsDeliveryCommitmentEntity::getPmsOrderId, order.getId())
                        .eq(PmsDeliveryCommitmentEntity::getStatus, "ACTIVE").orderByAsc(PmsDeliveryCommitmentEntity::getId));
        Map<Long, List<PmsDeliveryCommitmentEntity>> commitmentsByLine = activeCommitments.stream()
                .collect(Collectors.groupingBy(PmsDeliveryCommitmentEntity::getOrderDetailId));
        List<PmsOrderChangeLineEntity> lines = new ArrayList<>();
        BigDecimal totalOrder = BigDecimal.ZERO;
        BigDecimal totalArrival = BigDecimal.ZERO;
        BigDecimal totalCancellable = BigDecimal.ZERO;
        for (PmsOrderDetailEntity detail : details) {
            BigDecimal ordered = decimal(detail.getOrderNum());
            BigDecimal arrived = decimal(detail.getArrivalNum());
            if (ordered.signum() <= 0) blockers.add("订单行" + detail.getId() + "订购数量不是正数");
            if (arrived.signum() < 0 || arrived.compareTo(ordered) > 0)
                blockers.add("订单行" + detail.getId() + "到货数量越界");
            if (decimal(detail.getCancelledNum()).signum() > 0 || detail.getCancellationRequestId() != null)
                blockers.add("订单行" + detail.getId() + "已存在取消结果，禁止重复撤回");
            if (!Boolean.TRUE.equals(order.getType()) && StrUtil.isBlank(detail.getPmsPlanNo()))
                blockers.add("计划型订单行" + detail.getId() + "缺少采购计划号");
            BigDecimal outstanding = ordered.subtract(arrived).max(BigDecimal.ZERO);
            totalOrder = totalOrder.add(ordered);
            totalArrival = totalArrival.add(arrived);
            totalCancellable = totalCancellable.add(outstanding);
            List<PmsDeliveryCommitmentEntity> commitments = commitmentsByLine.getOrDefault(detail.getId(), List.of());
            lines.add(new PmsOrderChangeLineEntity().setOrderDetailId(detail.getId())
                    .setPmsPlanNo(detail.getPmsPlanNo()).setPlanDemandNo(detail.getOrderNo())
                    .setMaterialCode(detail.getMatCode()).setMaterialName(detail.getMatName())
                    .setMaterialUnit(detail.getMatUnit()).setDeliveryDate(detail.getDeliveryTime())
                    .setOrderQuantitySnapshot(ordered).setArrivalQuantitySnapshot(arrived)
                    .setCancelledQuantity(outstanding).setActiveCommitmentCount(commitments.size())
                    .setActiveCommitmentIds(commitments.stream().map(value -> String.valueOf(value.getId()))
                            .collect(Collectors.joining(","))).setFactoryCode(detail.getFactoryCode()));
        }
        if (totalCancellable.signum() <= 0) blockers.add("采购订单已无可取消的未到货数量");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("order", Arrays.asList(order.getId(), order.getPmsOrderNo(), order.getStatus(), order.getType(),
                order.getSupplierCode(), order.getContractStatus(), order.getFactoryCode(), order.getLastChangeRequestId()));
        snapshot.put("lines", lines.stream().map(value -> Arrays.asList(value.getOrderDetailId(), value.getPmsPlanNo(),
                value.getPlanDemandNo(), value.getMaterialCode(), value.getDeliveryDate(), value.getOrderQuantitySnapshot(),
                value.getArrivalQuantitySnapshot(), value.getCancelledQuantity(), value.getActiveCommitmentIds())).toList());
        snapshot.put("receipts", receipts.stream().map(value -> Arrays.asList(value.getId(), value.getBillNo(), value.getState())).toList());
        snapshot.put("contractCount", contractCount);
        snapshot.put("settlement", List.of(reconciliationCount, invoiceCount, paymentCount));
        snapshot.put("activeCommitments", activeCommitments.stream().map(value -> Arrays.asList(value.getId(),
                value.getOrderDetailId(), value.getVersionNo(), value.getStatus(), value.getTotalCommittedNum())).toList());
        snapshot.put("activeRequestCount", activeRequestCount);
        String snapshotJson = json(snapshot);
        PmsOrderChangeDTO.Preview preview = new PmsOrderChangeDTO.Preview();
        preview.setPmsOrderId(order.getId());
        preview.setPmsOrderNo(order.getPmsOrderNo());
        preview.setOrderStatus(order.getStatus());
        preview.setEligible(blockers.isEmpty());
        preview.setBlockers(blockers);
        preview.setEvidenceSnapshot(snapshotJson);
        preview.setEvidenceFingerprint(hash(snapshotJson));
        preview.setTotalOrderQuantity(totalOrder);
        preview.setTotalArrivalQuantity(totalArrival);
        preview.setTotalCancellableQuantity(totalCancellable);
        preview.setReceiptCount(receipts.size());
        preview.setActiveReceiptCount((int) activeReceipts);
        preview.setContractCount((int) contractCount);
        preview.setSettlementCount((int) settlementCount);
        preview.setActiveCommitmentCount(activeCommitments.size());
        preview.setLines(lines);
        return preview;
    }

    private PmsOrderEntity lockOrder(Long id) {
        PmsOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getId, id).last("FOR UPDATE"));
        requireOrderScope(order);
        return order;
    }

    private void requireOrderScope(PmsOrderEntity order) {
        require(order != null, "采购订单不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, order.getFactoryCode()), "采购订单超出当前工厂数据范围");
    }

    private PmsOrderChangeRequestEntity requireRequest(Long id, boolean lock) {
        require(id != null, "订单变更申请不能为空");
        PmsOrderChangeRequestEntity request = lock ? requestMapper.lockById(id) : requestMapper.selectById(id);
        require(request != null, "订单变更申请不存在");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(factory) || Objects.equals(factory, request.getFactoryCode()), "订单变更申请超出当前工厂数据范围");
        return request;
    }

    private void operation(PmsOrderChangeRequestEntity request, String requestId, String fingerprint,
                           String type, String before, String after, String content, MomUser user) {
        require(operationMapper.insert(new PmsOrderChangeOperationEntity().setChangeRequestId(request.getId())
                .setRequestId(requestId).setRequestFingerprint(fingerprint).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setContent(bounded(content, 1000, "操作说明"))
                .setOperatorCode(userCode(user)).setOperatorName(userName(user)).setOperationTime(LocalDateTime.now())
                .setFactoryCode(request.getFactoryCode())) == 1, "订单变更操作履历保存失败");
    }

    private PmsOrderChangeLineEntity copyLine(PmsOrderChangeLineEntity source) {
        return new PmsOrderChangeLineEntity().setOrderDetailId(source.getOrderDetailId())
                .setPmsPlanNo(source.getPmsPlanNo()).setPlanDemandNo(source.getPlanDemandNo())
                .setMaterialCode(source.getMaterialCode()).setMaterialName(source.getMaterialName())
                .setMaterialUnit(source.getMaterialUnit()).setDeliveryDate(source.getDeliveryDate())
                .setTargetDeliveryDate(source.getTargetDeliveryDate())
                .setOrderQuantitySnapshot(source.getOrderQuantitySnapshot())
                .setTargetOrderQuantity(source.getTargetOrderQuantity()).setQuantityDelta(source.getQuantityDelta())
                .setArrivalQuantitySnapshot(source.getArrivalQuantitySnapshot())
                .setCancelledQuantity(source.getCancelledQuantity())
                .setOriginalPrice(source.getOriginalPrice()).setTargetPrice(source.getTargetPrice())
                .setOriginalPriceId(source.getOriginalPriceId()).setTargetPriceId(source.getTargetPriceId())
                .setOriginalAuthorizationId(source.getOriginalAuthorizationId())
                .setTargetAuthorizationId(source.getTargetAuthorizationId())
                .setSupplyAffected(source.getSupplyAffected()).setChangeSummary(source.getChangeSummary())
                .setActiveCommitmentCount(source.getActiveCommitmentCount())
                .setActiveCommitmentIds(source.getActiveCommitmentIds()).setFactoryCode(source.getFactoryCode());
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal normalizeDecimal(BigDecimal value) {
        if (value == null) return null;
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("订单变更证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("订单变更证据摘要生成失败");
        }
    }

    private String bounded(String value, int max, String field) {
        String text = StrUtil.blankToDefault(value, "").trim();
        require(!text.isEmpty() && text.length() <= max, field + "不能为空且不能超过" + max + "字符");
        return text;
    }

    private String compact(String value, int max) {
        String text = StrUtil.blankToDefault(value, "").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private MomUser currentUser() {
        return SecurityUtils.getUser();
    }

    private String userCode(MomUser user) {
        return user == null ? "UNKNOWN" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "未知用户" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }
}
