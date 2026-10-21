package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.qms.api.feign.RemoteQmsFinishedGoodsDecisionReversalService;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalDTO;
import com.kitex.mom.qms.dto.QmsFinishedGoodsDecisionReversalExecutionEvent;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionCommand;
import com.kitex.mom.wms.dto.WmsFinishedGoodsReceiptCorrectionView;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.*;
import com.kitex.mom.wms.util.WmsStockAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成品实收入库整单红冲：申请冻结、异岗复核、精确实物冲减和MES可靠回写。
 */
@Service
@RequiredArgsConstructor
public class WmsFinishedGoodsReceiptCorrectionServiceImpl
        extends ServiceImpl<WmsFinishedGoodsReceiptCorrectionMapper, WmsFinishedGoodsReceiptCorrectionEntity>
        implements WmsFinishedGoodsReceiptCorrectionService {

    private static final Set<String> TERMINAL_BILLS = Set.of("2", "12");
    private static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN", "STALE", "EXECUTED");
    private static final String MES_PREFIX = "MES_TASK_IN_WMS:";

    private final WmsFinishedGoodsReceiptCorrectionDetailMapper detailMapper;
    private final WmsFinishedGoodsReceiptCorrectionOperationMapper operationMapper;
    private final WmsInNoticeMapper noticeMapper;
    private final WmsInNoticeDetailMapper noticeDetailMapper;
    private final WmsInBillMapper inBillMapper;
    private final WmsInBillDetailMapper inBillDetailMapper;
    private final WmsLiveStockMapper stockMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsMaterialReservationService materialReservationService;
    private final WmsSalesStockReservationService salesReservationService;
    private final WmsLiveStockService liveStockService;
    private final WmsFinishedGoodsReceiptReverseOutboxService outboxService;
    private final WmsQmsFinishedGoodsDecisionReversalOutboxService qmsExecutionOutboxService;
    private final RemoteQmsFinishedGoodsDecisionReversalService qualityDecisionReversalService;

    @Override
    @Transactional(readOnly = true)
    public Page<WmsFinishedGoodsReceiptCorrectionEntity> page(SearchPage page,
                                                              WmsFinishedGoodsReceiptCorrectionEntity query) {
        String factory = requireFactory();
        WmsFinishedGoodsReceiptCorrectionEntity filter = query == null
                ? new WmsFinishedGoodsReceiptCorrectionEntity() : query;
        return page(page.toMpPage(), new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                .eq(WmsFinishedGoodsReceiptCorrectionEntity::getFactoryCode, factory)
                .like(StrUtil.isNotBlank(filter.getCorrectionNo()),
                        WmsFinishedGoodsReceiptCorrectionEntity::getCorrectionNo, filter.getCorrectionNo())
                .eq(filter.getNoticeId() != null,
                        WmsFinishedGoodsReceiptCorrectionEntity::getNoticeId, filter.getNoticeId())
                .eq(StrUtil.isNotBlank(filter.getStatus()),
                        WmsFinishedGoodsReceiptCorrectionEntity::getStatus, filter.getStatus())
                .orderByDesc(WmsFinishedGoodsReceiptCorrectionEntity::getAppliedTime)
                .orderByDesc(WmsFinishedGoodsReceiptCorrectionEntity::getId));
    }

    @Override
    @Transactional(readOnly = true)
    public WmsFinishedGoodsReceiptCorrectionView preview(Long noticeId) {
        Proof proof = proof(noticeId, false);
        WmsFinishedGoodsReceiptCorrectionEntity active = latest(noticeId);
        return toView(proof, active, active == null ? List.of() : details(active.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public WmsFinishedGoodsReceiptCorrectionView detail(Long correctionId) {
        WmsFinishedGoodsReceiptCorrectionEntity correction = requireCorrection(correctionId, false);
        Proof proof = proof(correction.getNoticeId(), false);
        return toView(proof, correction, details(correctionId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsFinishedGoodsReceiptCorrectionEntity apply(WmsFinishedGoodsReceiptCorrectionCommand command) {
        validateApply(command);
        String requestId = command.getRequestId().trim();
        String requestFingerprint = commandFingerprint(command);
        WmsFinishedGoodsReceiptCorrectionEntity replay = getOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                        .eq(WmsFinishedGoodsReceiptCorrectionEntity::getRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), command.getNoticeId())
                            && (StrUtil.isBlank(replay.getRequestFingerprint())
                            ? Objects.equals(replay.getReason(), command.getReason().trim())
                            : Objects.equals(replay.getRequestFingerprint(), requestFingerprint)),
                    "红冲申请永久请求号已使用且载荷不一致");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        noticeMapper.lockSourceRequest("FG_RECEIPT_CORRECTION:" + command.getNoticeId());
        replay = getOne(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                .eq(WmsFinishedGoodsReceiptCorrectionEntity::getRequestId, requestId), false);
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), command.getNoticeId())
                            && (StrUtil.isBlank(replay.getRequestFingerprint())
                            ? Objects.equals(replay.getReason(), command.getReason().trim())
                            : Objects.equals(replay.getRequestFingerprint(), requestFingerprint)),
                    "红冲申请永久请求号已使用且载荷不一致");
            requireFactory(replay.getFactoryCode());
            return replay;
        }
        Proof proof = proof(command.getNoticeId(), true);
        WmsFinishedGoodsReceiptCorrectionEntity active = getOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                        .eq(WmsFinishedGoodsReceiptCorrectionEntity::getNoticeId, command.getNoticeId())
                        .notIn(WmsFinishedGoodsReceiptCorrectionEntity::getStatus, CLOSED)
                        .last("FOR UPDATE"), false);
        require(active == null, "该成品入库通知已有待复核红冲申请");
        require(proof.blockers().isEmpty(), String.join("；", proof.blockers()));
        List<SelectedStock> selected = selectStocks(proof, command.getStockSelections());
        BigDecimal selectedQuantity = selected.stream().map(SelectedStock::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String reversalMode = reversalMode(proof, selected, selectedQuantity);
        String evidenceFingerprint = selectionEvidenceFingerprint(proof, selected);
        QmsFinishedGoodsDecisionReversalDTO.Authorization qualityAuthorization =
                consumeQualityAuthorization(proof, command.getQmsDecisionReversalId(),
                        command.getQmsDecisionAuthorizationCode(), requestId);
        MomUser user = user();
        LocalDateTime now = LocalDateTime.now();
        WmsFinishedGoodsReceiptCorrectionEntity row = new WmsFinishedGoodsReceiptCorrectionEntity()
                .setCorrectionNo("FGR-" + command.getNoticeId() + "-"
                        + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + "-"
                        + hash(requestId).substring(0, 8))
                .setRequestId(requestId).setRequestFingerprint(requestFingerprint)
                .setNoticeId(command.getNoticeId())
                .setSourceMesInboundRequestId(proof.sourceMesRequestId())
                .setSourceRequestFingerprint(proof.notice().getSourceRequestFingerprint())
                .setEvidenceFingerprint(evidenceFingerprint).setStatus("SUBMITTED")
                .setQmsDecisionReversalId(qualityAuthorization == null ? null
                        : qualityAuthorization.getReversalId())
                .setQmsDecisionAuthorizationCode(qualityAuthorization == null ? null
                        : qualityAuthorization.getAuthorizationCode())
                .setQmsDecisionEvidenceFingerprint(qualityAuthorization == null ? null
                        : qualityAuthorization.getQmsEvidenceFingerprint())
                .setReason(command.getReason().trim()).setTotalQuantity(selectedQuantity)
                .setReversalMode(reversalMode).setSourceStockQuantityBefore(proof.stockQuantity())
                .setStockLineCount(selected.size()).setApplicantCode(user.getUsername())
                .setApplicantName(name(user)).setAppliedTime(now).setMesReceiptStatus("WAITING_APPROVAL")
                .setQmsExecutionReceiptStatus(qualityAuthorization == null
                        ? "NOT_REQUIRED" : "WAITING_EXECUTION")
                .setFactoryCode(proof.notice().getFactoryCode());
        require(save(row), "成品实收入库红冲申请保存失败");
        for (SelectedStock selection : selected) {
            WmsLiveStockEntity stock = selection.stock();
            WmsFinishedGoodsReceiptCorrectionDetailEntity detail = new WmsFinishedGoodsReceiptCorrectionDetailEntity()
                    .setCorrectionId(row.getId()).setStockId(stock.getId())
                    .setSourceInBillId(stock.getSourceInBillId())
                    .setSourceInBillDetailId(stock.getSourceInBillDetailId())
                    .setMatCode(stock.getMatCode()).setMatName(stock.getMatName()).setMatSpec(stock.getMatSpec())
                    .setMatBatch(stock.getMatBatch()).setMatBarcode(stock.getMatBarcode())
                    .setMatUnit(stock.getMatUnit()).setSourceStockQuantity(quantity(stock.getMatNum()))
                    .setQuantity(selection.quantity()).setWarehouseCode(stock.getWarehouseCode())
                    .setWarehouseName(stock.getWarehouseName()).setSiteCode(stock.getSiteCode())
                    .setSiteName(stock.getSiteName()).setContainerCode(stock.getContainerCode())
                    .setProduceDate(stock.getMatProduceDate()).setQualityStatus(stock.getQualityStatus())
                    .setQualityResult(stock.getQualityResult()).setQualityHoldNo(stock.getQualityHoldNo())
                    .setSourceQualityTaskIds(stock.getSourceQualityTaskIds())
                    .setSourceQualityEvidenceFingerprint(stock.getSourceQualityEvidenceFingerprint())
                    .setSourceQualityEvidenceSnapshot(stock.getSourceQualityEvidenceSnapshot())
                    .setLineFingerprint(stockFingerprint(stock)).setFactoryCode(stock.getFactoryCode());
            require(detailMapper.insert(detail) == 1, "成品红冲库存冻结明细保存失败");
        }
        operation(row, requestId, requestFingerprint, "SUBMIT", null, "SUBMITTED",
                JSONUtil.toJsonStr(Map.of("reason", row.getReason(), "evidenceFingerprint", evidenceFingerprint,
                        "reversalMode", reversalMode, "stockLineCount", selected.size(),
                        "quantity", selectedQuantity)), user, now);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsFinishedGoodsReceiptCorrectionEntity withdraw(Long correctionId,
                                                            WmsFinishedGoodsReceiptCorrectionCommand command) {
        WmsFinishedGoodsReceiptCorrectionEntity row = requireCorrection(correctionId, true);
        String commandId = decisionCommandId(command);
        String commandFingerprint = commandFingerprint(command);
        if (replayedOperation(row.getId(), commandId, commandFingerprint, "WITHDRAW") != null) return row;
        require("SUBMITTED".equals(row.getStatus()), "只有待复核红冲申请可以撤回");
        MomUser user = user();
        require(Objects.equals(row.getApplicantCode(), user.getUsername()), "只有原申请人可以撤回红冲申请");
        String reason = requiredReason(command == null ? null : command.getReason(), "撤回原因");
        LocalDateTime now = LocalDateTime.now();
        row.setStatus("WITHDRAWN").setReviewedTime(now).setRejectReason(reason);
        require(updateById(row), "红冲申请撤回失败");
        operation(row, commandId, commandFingerprint, "WITHDRAW", "SUBMITTED", "WITHDRAWN",
                reason, user, now);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsFinishedGoodsReceiptCorrectionEntity reject(Long correctionId,
                                                          WmsFinishedGoodsReceiptCorrectionCommand command) {
        WmsFinishedGoodsReceiptCorrectionEntity row = requireCorrection(correctionId, true);
        String commandId = decisionCommandId(command);
        String commandFingerprint = commandFingerprint(command);
        if (replayedOperation(row.getId(), commandId, commandFingerprint, "REJECT") != null) return row;
        require("SUBMITTED".equals(row.getStatus()), "只有待复核红冲申请可以驳回");
        MomUser reviewer = user();
        require(!Objects.equals(row.getApplicantCode(), reviewer.getUsername()), "申请人与复核人必须分离");
        String reason = requiredReason(command == null ? null : command.getRejectReason(), "驳回原因");
        LocalDateTime now = LocalDateTime.now();
        row.setStatus("REJECTED").setReviewerCode(reviewer.getUsername()).setReviewerName(name(reviewer))
                .setReviewedTime(now).setRejectReason(reason);
        require(updateById(row), "红冲申请驳回保存失败");
        operation(row, commandId, commandFingerprint, "REJECT", "SUBMITTED", "REJECTED",
                reason, reviewer, now);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsFinishedGoodsReceiptCorrectionEntity approve(Long correctionId) {
        WmsFinishedGoodsReceiptCorrectionEntity snapshot = requireCorrection(correctionId, false);
        noticeMapper.lockSourceRequest("FG_RECEIPT_CORRECTION:" + snapshot.getNoticeId());
        WmsFinishedGoodsReceiptCorrectionEntity row = requireCorrection(correctionId, true);
        String commandId = "APPROVE:" + row.getId();
        String commandFingerprint = hash("APPROVE|" + row.getId());
        WmsFinishedGoodsReceiptCorrectionOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionOperationEntity>()
                        .eq(WmsFinishedGoodsReceiptCorrectionOperationEntity::getCommandId, commandId), false);
        if (replay != null) {
            require(Objects.equals(replay.getCorrectionId(), row.getId())
                            && Objects.equals(replay.getCommandFingerprint(), commandFingerprint)
                            && Set.of("STALE", "APPROVE_EXECUTE").contains(replay.getOperationType()),
                    "批准命令号已经被不同红冲事实使用");
            return row;
        }
        require("SUBMITTED".equals(row.getStatus()), "只有待复核红冲申请可以批准执行");
        MomUser reviewer = user();
        require(!Objects.equals(row.getApplicantCode(), reviewer.getUsername()), "申请人与复核人必须分离");
        Proof current = proof(row.getNoticeId(), true);
        List<WmsFinishedGoodsReceiptCorrectionDetailEntity> frozenDetails = details(row.getId());
        List<SelectedStock> selected = frozenSelections(current, frozenDetails);
        LocalDateTime now = LocalDateTime.now();
        String currentEvidence = selectionEvidenceFingerprint(current, selected);
        if (!current.blockers().isEmpty() || !Objects.equals(row.getEvidenceFingerprint(), currentEvidence)) {
            String drift = current.blockers().isEmpty() ? "通知、入库单或库存证据在申请后发生变化"
                    : String.join("；", current.blockers());
            row.setStatus("STALE").setReviewerCode(reviewer.getUsername()).setReviewerName(name(reviewer))
                    .setReviewedTime(now).setRejectReason(drift);
            require(updateById(row), "红冲申请失效状态保存失败");
            operation(row, commandId, commandFingerprint, "STALE",
                    "SUBMITTED", "STALE", drift, reviewer, now);
            return row;
        }
        consumeQualityAuthorization(current, row.getQmsDecisionReversalId(),
                row.getQmsDecisionAuthorizationCode(), row.getRequestId());
        Map<Long, WmsInBillEntity> bills = current.bills().stream()
                .collect(Collectors.toMap(WmsInBillEntity::getId, Function.identity()));
        Map<Long, WmsInoutFlowEntity> inboundByDetail = current.inboundFlows().stream()
                .collect(Collectors.toMap(WmsInoutFlowEntity::getSourceInBillDetailId,
                        Function.identity(), (left, right) -> left));
        Map<Long, BigDecimal> reversedByNoticeDetail = new HashMap<>();
        for (SelectedStock selection : selected) {
            WmsLiveStockEntity stock = selection.stock();
            BigDecimal stockQuantity = quantity(stock.getMatNum());
            BigDecimal reverseQuantity = selection.quantity();
            BigDecimal balance = stockQuantity.subtract(reverseQuantity);
            if (balance.signum() == 0) {
                require(stockMapper.deleteById(stock.getId()) == 1,
                        "冻结库存行已被并发移动，红冲事务回滚");
            } else {
                stock.setMatNum(balance.doubleValue());
                require(stockMapper.updateById(stock) == 1,
                        "冻结库存行已被并发扣减，红冲事务回滚");
            }
            WmsInBillEntity sourceBill = bills.get(stock.getSourceInBillId());
            WmsInoutFlowEntity sourceFlow = inboundByDetail.get(stock.getSourceInBillDetailId());
            WmsInBillDetailEntity sourceDetail = current.billDetails().stream()
                    .filter(item -> Objects.equals(item.getId(), stock.getSourceInBillDetailId()))
                    .findFirst().orElse(null);
            require(sourceDetail != null && sourceDetail.getNoticeDetailId() != null,
                    "冻结库存缺少原通知明细来源，红冲事务回滚");
            reversedByNoticeDetail.merge(sourceDetail.getNoticeDetailId(), reverseQuantity, BigDecimal::add);
            WmsInoutFlowEntity reverse = new WmsInoutFlowEntity();
            org.springframework.beans.BeanUtils.copyProperties(stock, reverse);
            reverse.setId(null);
            reverse.setBillId(stock.getSourceInBillId());
            reverse.setBillDetailId(stock.getSourceInBillDetailId());
            reverse.setSourceStockId(stock.getId());
            reverse.setSourceFlowId(sourceFlow == null ? null : sourceFlow.getId());
            reverse.setBillNo(sourceBill == null ? null : sourceBill.getBillNo());
            reverse.setBillType(InBillTypeEnum.PRODUCT.getCode());
            reverse.setOperateType(InOutEnum.OUT.getCode());
            reverse.setMatNum(reverseQuantity.doubleValue());
            reverse.setStockProofType("MES_FINISHED_GOODS_RECEIPT_REVERSAL");
            reverse.setOperationRequestId("FINISHED-GOODS-REVERSE:" + row.getId() + ":" + stock.getId());
            reverse.setStockInTime(stock.getInTime());
            reverse.setFactoryCode(row.getFactoryCode());
            require(flowMapper.insert(reverse) == 1, "成品实物红冲反向流水保存失败");
            if (balance.signum() == 0 && StrUtil.isNotBlank(stock.getContainerCode())) {
                WmsInoutFlowEntity containerProof = new WmsInoutFlowEntity();
                containerProof.setContainerCode(stock.getContainerCode());
                containerProof.setMatCode(stock.getMatCode());
                liveStockService.releaseTransferSourceContainer(containerProof);
            }
        }
        for (WmsInNoticeDetailEntity detail : current.noticeDetails()) {
            BigDecimal increment = reversedByNoticeDetail.getOrDefault(detail.getId(), BigDecimal.ZERO);
            if (increment.signum() == 0) continue;
            BigDecimal reversed = zero(detail.getFinishedGoodsReversedNum()).add(increment);
            require(reversed.compareTo(quantity(detail.getInNum())) <= 0,
                    "通知明细累计红冲超过原实收数量");
            detail.setFinishedGoodsReversedNum(reversed);
            require(noticeDetailMapper.updateById(detail) == 1, "成品入库通知明细累计红冲保存失败");
        }
        WmsInNoticeEntity notice = current.notice();
        BigDecimal cumulativeReversed = zero(notice.getFinishedGoodsReversedNum()).add(row.getTotalQuantity());
        require(cumulativeReversed.compareTo(current.receivedQuantity()) <= 0,
                "通知累计红冲超过原实收数量");
        notice.setFinishedGoodsReversedNum(cumulativeReversed);
        notice.setFinishedGoodsReversalStatus(cumulativeReversed.compareTo(current.receivedQuantity()) == 0
                ? "REVERSED" : "PARTIALLY_REVERSED");
        require(noticeMapper.updateById(notice) == 1, "成品入库通知累计红冲保存失败");

        row.setStatus("EXECUTED").setReviewerCode(reviewer.getUsername()).setReviewerName(name(reviewer))
                .setReviewedTime(now).setExecutedTime(now).setRejectReason(null)
                .setSourceStockQuantityAfter(current.stockQuantity().subtract(row.getTotalQuantity()));
        require(updateById(row), "成品实物红冲执行状态保存失败");
        MesFinishedGoodsReceiptReverseEvent event = new MesFinishedGoodsReceiptReverseEvent()
                .setVersion(2).setEventId("WMS-FG-REVERSE:" + row.getId())
                .setCorrectionId(row.getId()).setWmsNoticeId(row.getNoticeId())
                .setSourceMesInboundRequestId(row.getSourceMesInboundRequestId())
                .setSourceRequestFingerprint(row.getSourceRequestFingerprint())
                .setEvidenceFingerprint(row.getEvidenceFingerprint()).setReversalMode(row.getReversalMode())
                .setReverseQuantity(row.getTotalQuantity())
                .setReason(row.getReason()).setApplicantCode(row.getApplicantCode())
                .setReviewerCode(reviewer.getUsername()).setFactoryCode(row.getFactoryCode()).setExecutedTime(now);
        event.setFingerprint(event.calculateFingerprint());
        outboxService.enqueue(row, event);
        if (row.getQmsDecisionReversalId() != null) {
            qmsExecutionOutboxService.enqueue(row, qmsExecutionEvent(row));
        }
        operation(row, commandId, commandFingerprint,
                "APPROVE_EXECUTE", "SUBMITTED", "EXECUTED", JSONUtil.toJsonStr(Map.of(
                        "quantity", row.getTotalQuantity(), "stockLineCount", row.getStockLineCount(),
                        "eventId", event.getEventId())), reviewer, now);
        return row;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WmsFinishedGoodsReceiptCorrectionOperationEntity> operations(Long correctionId) {
        requireCorrection(correctionId, false);
        return operationMapper.selectList(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionOperationEntity>()
                .eq(WmsFinishedGoodsReceiptCorrectionOperationEntity::getCorrectionId, correctionId)
                .orderByAsc(WmsFinishedGoodsReceiptCorrectionOperationEntity::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retry(Long correctionId) {
        WmsFinishedGoodsReceiptCorrectionEntity row = requireCorrection(correctionId, true);
        require("EXECUTED".equals(row.getStatus()), "只有已执行红冲申请可以续投跨服务回执");
        if (row.getQmsDecisionReversalId() != null) {
            qmsExecutionOutboxService.enqueue(row, qmsExecutionEvent(row));
        }
        return outboxService.resetCorrection(correctionId)
                + qmsExecutionOutboxService.resetCorrection(correctionId);
    }

    private QmsFinishedGoodsDecisionReversalExecutionEvent qmsExecutionEvent(
            WmsFinishedGoodsReceiptCorrectionEntity row) {
        require(row.getExecutedTime() != null && row.getTotalQuantity() != null
                        && row.getTotalQuantity().signum() > 0,
                "成品红冲执行事实不完整，不能生成QMS永久结果");
        List<String> sourceFingerprints = details(row.getId()).stream()
                .map(WmsFinishedGoodsReceiptCorrectionDetailEntity::getSourceQualityEvidenceFingerprint)
                .filter(StrUtil::isNotBlank).distinct().toList();
        require(sourceFingerprints.size() == 1, "红冲冻结明细没有唯一QMS来源证据指纹");
        QmsFinishedGoodsDecisionReversalExecutionEvent event =
                new QmsFinishedGoodsDecisionReversalExecutionEvent()
                        .setEventId("WMS-QMS-FG-REVERSE:" + row.getId())
                        .setReversalId(row.getQmsDecisionReversalId())
                        .setWmsCorrectionId(row.getId()).setWmsCorrectionRequestId(row.getRequestId())
                        .setWmsNoticeId(row.getNoticeId())
                        .setSourceQualityEvidenceFingerprint(sourceFingerprints.get(0))
                        .setQmsEvidenceFingerprint(row.getQmsDecisionEvidenceFingerprint())
                        .setReverseQuantity(row.getTotalQuantity()).setFactoryCode(row.getFactoryCode())
                        .setExecutedTime(row.getExecutedTime().truncatedTo(ChronoUnit.MICROS));
        return event.setFingerprint(event.calculateFingerprint());
    }

    private Proof proof(Long noticeId, boolean lock) {
        String factory = requireFactory();
        WmsInNoticeEntity notice = noticeMapper.selectOne(new LambdaQueryWrapper<WmsInNoticeEntity>()
                .eq(WmsInNoticeEntity::getId, noticeId).eq(WmsInNoticeEntity::getFactoryCode, factory)
                .last(lock ? "FOR UPDATE" : ""), false);
        require(notice != null, "当前工厂下不存在该成品入库通知");
        List<String> blockers = new ArrayList<>();
        if (!InBillTypeEnum.PRODUCT.getCode().equals(notice.getBillType())) blockers.add("仅MES成品入库通知可红冲");
        String mesPrefix = StrUtil.startWith(notice.getSourceRequestId(), "MES_TASK_IN_WMS_V2:")
                ? "MES_TASK_IN_WMS_V2:" : MES_PREFIX;
        String sourceRequest = StrUtil.startWith(notice.getSourceRequestId(), mesPrefix)
                ? StrUtil.removePrefix(notice.getSourceRequestId(), mesPrefix) : null;
        if (StrUtil.isBlank(sourceRequest)) blockers.add("通知缺少MES成品入库永久请求号，历史数据禁止猜测来源");
        if (StrUtil.isBlank(notice.getSourceRequestFingerprint())
                || notice.getSourceRequestFingerprint().length() != 64) blockers.add("通知缺少MES原入库命令指纹");
        BigDecimal expected = quantity(notice.getNoticeInNum());
        BigDecimal received = quantity(notice.getAllInNum());
        if (expected.signum() <= 0) blockers.add("成品入库通知数量必须大于0");
        if (!"2".equals(notice.getState()) || received.compareTo(expected) != 0
                || quantity(notice.getAllNotInNum()).signum() != 0)
            blockers.add("通知必须已经全量实收并处于已完成状态");

        List<WmsInNoticeDetailEntity> noticeDetails = noticeDetailMapper.selectList(
                new LambdaQueryWrapper<WmsInNoticeDetailEntity>().eq(WmsInNoticeDetailEntity::getNoticeId, noticeId)
                        .orderByAsc(WmsInNoticeDetailEntity::getId).last(lock ? "FOR UPDATE" : ""));
        if (noticeDetails.isEmpty()) blockers.add("成品入库通知没有明细");
        BigDecimal noticeDetailTotal = noticeDetails.stream().map(item -> quantity(item.getNoticeInNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (noticeDetailTotal.compareTo(expected) != 0) blockers.add("通知表头与明细数量不守恒");

        List<WmsInBillEntity> bills = inBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, noticeId).orderByAsc(WmsInBillEntity::getId)
                .last(lock ? "FOR UPDATE" : ""));
        if (bills.isEmpty() || bills.stream().anyMatch(item -> !TERMINAL_BILLS.contains(item.getState()))) {
            blockers.add("通知下所有入库单必须已经通过或完成");
        }
        List<Long> billIds = bills.stream().map(WmsInBillEntity::getId).toList();
        List<WmsInBillDetailEntity> billDetails = billIds.isEmpty() ? List.of()
                : inBillDetailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                .in(WmsInBillDetailEntity::getBillId, billIds).orderByAsc(WmsInBillDetailEntity::getId)
                .last(lock ? "FOR UPDATE" : ""));
        BigDecimal billed = billDetails.stream().map(item -> quantity(item.getMatNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (billed.compareTo(expected) != 0) blockers.add("已完成入库单明细与通知数量不守恒");

        List<WmsLiveStockEntity> stocks = StrUtil.isBlank(sourceRequest) ? List.of()
                : stockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getSourceMesInboundRequestId, sourceRequest)
                .eq(WmsLiveStockEntity::getFactoryCode, factory).orderByAsc(WmsLiveStockEntity::getId)
                .last(lock ? "LIMIT 10001 FOR UPDATE" : "LIMIT 10001"));
        if (stocks.size() > 10000) blockers.add("来源库存超过10000行安全上限，禁止截断红冲");
        if (stocks.isEmpty()) blockers.add("当前库存没有MES成品入库精确来源，历史库存禁止自动红冲");
        Set<Long> billDetailIds = billDetails.stream().map(WmsInBillDetailEntity::getId).collect(Collectors.toSet());
        Map<Long, WmsInBillDetailEntity> detailById = billDetails.stream()
                .collect(Collectors.toMap(WmsInBillDetailEntity::getId, Function.identity()));
        if (stocks.stream().anyMatch(stock -> stock.getSourceInBillId() == null
                || stock.getSourceInBillDetailId() == null || !billIds.contains(stock.getSourceInBillId())
                || !billDetailIds.contains(stock.getSourceInBillDetailId()))) {
            blockers.add("当前库存的首次入库单或明细来源不完整");
        }
        if (stocks.stream().anyMatch(stock -> StrUtil.isBlank(stock.getSourceQualityTaskIds())
                || StrUtil.isBlank(stock.getSourceQualityEvidenceFingerprint())
                || stock.getSourceQualityEvidenceFingerprint().length() != 64
                || StrUtil.isBlank(stock.getSourceQualityEvidenceSnapshot()))) {
            blockers.add("来源库存缺少业务发生时冻结的QMS质量证据，历史数据禁止按物料或批次反推");
        }
        if (stocks.stream().anyMatch(stock -> !validQualityTaskIds(stock.getSourceQualityTaskIds()))) {
            blockers.add("来源库存的QMS任务集合格式损坏，禁止继续红冲");
        }
        Set<String> qualityFingerprints = stocks.stream()
                .map(WmsLiveStockEntity::getSourceQualityEvidenceFingerprint)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        if (qualityFingerprints.size() > 1) blockers.add("同一成品入库通知存在多个QMS质量证据版本，禁止混用授权");
        if (stocks.stream().anyMatch(stock -> {
            WmsInBillDetailEntity source = detailById.get(stock.getSourceInBillDetailId());
            return source != null && (!Objects.equals(source.getMatCode(), stock.getMatCode())
                    || !Objects.equals(StrUtil.nullToEmpty(source.getMatBatch()), StrUtil.nullToEmpty(stock.getMatBatch()))
                    || !Objects.equals(source.getMatUnit(), stock.getMatUnit())
                    || !Objects.equals(source.getSourceQualityTaskIds(), stock.getSourceQualityTaskIds())
                    || !Objects.equals(source.getSourceQualityEvidenceFingerprint(),
                    stock.getSourceQualityEvidenceFingerprint()));
        })) blockers.add("来源库存与原入库明细的物料、批次、单位或质量证据不一致");
        BigDecimal stockTotal = stocks.stream().map(item -> quantity(item.getMatNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (stockTotal.signum() <= 0) blockers.add("当前没有仍在库的精确来源成品可供红冲");
        Set<Long> stockIds = stocks.stream().map(WmsLiveStockEntity::getId).collect(Collectors.toSet());
        Map<Long, BigDecimal> productionReservedByStock = stocks.stream().collect(Collectors.toMap(
                WmsLiveStockEntity::getId,
                stock -> materialReservationService.activeReservedNum(stock.getId())));
        Map<Long, BigDecimal> salesReservedByStock = stockIds.isEmpty() ? Map.of()
                : salesReservationService.activeReservedNums(stockIds);
        BigDecimal productionReserved = productionReservedByStock.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal salesReserved = salesReservedByStock.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean anySelectable = stocks.stream().anyMatch(stock -> selectableQuantity(stock,
                productionReservedByStock.getOrDefault(stock.getId(), BigDecimal.ZERO),
                salesReservedByStock.getOrDefault(stock.getId(), BigDecimal.ZERO)).signum() > 0);
        if (!stocks.isEmpty() && !anySelectable) {
            blockers.add("来源库存均处于不可用、全量预留或条码单件被预留状态，当前无可红冲实物");
        }
        List<WmsInoutFlowEntity> inboundFlows = billDetailIds.isEmpty() || StrUtil.isBlank(sourceRequest) ? List.of()
                : flowMapper.selectList(new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .in(WmsInoutFlowEntity::getSourceInBillDetailId, billDetailIds)
                .eq(WmsInoutFlowEntity::getSourceMesInboundRequestId, sourceRequest)
                .eq(WmsInoutFlowEntity::getStockProofType, "MES_FINISHED_GOODS_RECEIPT")
                .eq(WmsInoutFlowEntity::getOperateType, InOutEnum.IN.getCode())
                .orderByAsc(WmsInoutFlowEntity::getId).last(lock ? "FOR UPDATE" : ""));
        Set<Long> evidenced = inboundFlows.stream().map(WmsInoutFlowEntity::getSourceInBillDetailId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!evidenced.containsAll(billDetailIds))
            blockers.add("原实收入库缺少新版精确流水证据，禁止按历史普通流水推断");
        Map<Long, BigDecimal> flowQuantityByDetail = inboundFlows.stream().collect(Collectors.groupingBy(
                WmsInoutFlowEntity::getSourceInBillDetailId, Collectors.reducing(BigDecimal.ZERO,
                        item -> quantity(item.getMatNum()), BigDecimal::add)));
        if (billDetails.stream().anyMatch(detail -> flowQuantityByDetail
                .getOrDefault(detail.getId(), BigDecimal.ZERO).compareTo(quantity(detail.getMatNum())) != 0)) {
            blockers.add("原实收入库流水与入库单明细数量不守恒");
        }
        if (inboundFlows.stream().anyMatch(flow -> flow.getSourceInBillId() == null
                || !billIds.contains(flow.getSourceInBillId())
                || !Objects.equals(flow.getBillDetailId(), flow.getSourceInBillDetailId()))) {
            blockers.add("原实收入库流水的入库单或明细来源不一致");
        }
        String fingerprint = evidenceFingerprint(notice, noticeDetails, bills, billDetails, stocks,
                inboundFlows, productionReserved, salesReserved);
        return new Proof(notice, noticeDetails, bills, billDetails, stocks, inboundFlows,
                sourceRequest, expected, received, stockTotal, productionReservedByStock,
                salesReservedByStock, blockers, fingerprint);
    }

    private WmsFinishedGoodsReceiptCorrectionView toView(Proof proof,
                                                         WmsFinishedGoodsReceiptCorrectionEntity correction,
                                                         List<WmsFinishedGoodsReceiptCorrectionDetailEntity> frozen) {
        List<String> blockers = new ArrayList<>(proof.blockers());
        List<Long> qualityTaskIds = qualityTaskIds(proof.stocks());
        QmsFinishedGoodsDecisionReversalDTO.Authorization authorization = null;
        if (!qualityTaskIds.isEmpty() && blockers.stream().noneMatch(item -> item.contains("QMS质量证据"))) {
            try {
                authorization = queryQualityAuthorization(proof, qualityTaskIds);
                if (!Boolean.TRUE.equals(authorization.getEligible())) blockers.add(authorization.getMessage());
            } catch (Exception exception) {
                blockers.add("QMS质量逆向授权服务不可用，故障关闭：" + rootMessage(exception));
            }
        }
        if (correction != null && !CLOSED.contains(correction.getStatus())) {
            blockers.add("该通知已有待复核红冲申请：" + correction.getCorrectionNo());
        }
        List<WmsFinishedGoodsReceiptCorrectionView.StockOption> stockOptions = proof.stocks().stream()
                .map(stock -> stockOption(proof, stock)).toList();
        BigDecimal selectableQuantity = stockOptions.stream()
                .map(WmsFinishedGoodsReceiptCorrectionView.StockOption::getSelectableQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        WmsFinishedGoodsReceiptCorrectionView view = new WmsFinishedGoodsReceiptCorrectionView()
                .setNoticeId(proof.notice().getId())
                .setSourceMesInboundRequestId(proof.sourceMesRequestId())
                .setSourceRequestFingerprint(proof.notice().getSourceRequestFingerprint())
                .setNoticeQuantity(proof.noticeQuantity()).setReceivedQuantity(proof.receivedQuantity())
                .setCurrentStockQuantity(proof.stockQuantity()).setCurrentStockLineCount(proof.stocks().size())
                .setSelectableStockQuantity(selectableQuantity).setStockOptions(stockOptions)
                .setSourceQualityTaskIds(qualityTaskIds)
                .setSourceQualityEvidenceFingerprint(proof.stocks().stream()
                        .map(WmsLiveStockEntity::getSourceQualityEvidenceFingerprint)
                        .filter(StrUtil::isNotBlank).distinct().findFirst().orElse(null))
                .setQualityAuthorizationRequired(!qualityTaskIds.isEmpty())
                .setEligible(blockers.isEmpty()).setEvidenceFingerprint(proof.fingerprint())
                .setBlockers(blockers).setCorrection(correction).setDetails(frozen);
        if (authorization != null) view.setQualityAuthorizationId(authorization.getReversalId())
                .setQualityAuthorizationNo(authorization.getReversalNo())
                .setQualityAuthorizationStatus(authorization.getStatus())
                .setQualityAuthorizationCode(authorization.getAuthorizationCode())
                .setQualityAuthorizationMessage(authorization.getMessage())
                .setQualityAuthorizationEvidenceFingerprint(authorization.getQmsEvidenceFingerprint())
                .setQualityAuthorizationExpiresAt(authorization.getAuthorizationExpiresAt());
        return view;
    }

    private WmsFinishedGoodsReceiptCorrectionView.StockOption stockOption(
            Proof proof, WmsLiveStockEntity stock) {
        BigDecimal productionReserved = proof.productionReservedByStock()
                .getOrDefault(stock.getId(), BigDecimal.ZERO);
        BigDecimal salesReserved = proof.salesReservedByStock()
                .getOrDefault(stock.getId(), BigDecimal.ZERO);
        BigDecimal selectable = selectableQuantity(stock, productionReserved, salesReserved);
        String blocker = null;
        if (!WmsStockAvailability.isUsable(stock)) {
            blocker = "库存锁定、过期、待检或质量状态不可用";
        } else if (StrUtil.isNotBlank(stock.getMatBarcode())
                && productionReserved.add(salesReserved).signum() > 0) {
            blocker = "条码/SN单件已有活动预留，不能拆分红冲";
        } else if (selectable.signum() <= 0) {
            blocker = "库存已被生产或销售全量预留";
        }
        return new WmsFinishedGoodsReceiptCorrectionView.StockOption()
                .setStockId(stock.getId()).setMatCode(stock.getMatCode()).setMatName(stock.getMatName())
                .setMatBatch(stock.getMatBatch()).setMatBarcode(stock.getMatBarcode())
                .setMatUnit(stock.getMatUnit()).setWarehouseCode(stock.getWarehouseCode())
                .setWarehouseName(stock.getWarehouseName()).setSiteCode(stock.getSiteCode())
                .setSiteName(stock.getSiteName()).setContainerCode(stock.getContainerCode())
                .setQuantity(quantity(stock.getMatNum())).setProductionReservedQuantity(productionReserved)
                .setSalesReservedQuantity(salesReserved).setSelectableQuantity(selectable)
                .setWholeLineRequired(StrUtil.isNotBlank(stock.getMatBarcode()))
                .setSelectable(blocker == null).setBlocker(blocker);
    }

    private QmsFinishedGoodsDecisionReversalDTO.Authorization queryQualityAuthorization(
            Proof proof, List<Long> taskIds) {
        QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery query =
                new QmsFinishedGoodsDecisionReversalDTO.AuthorizationQuery()
                        .setWmsNoticeId(proof.notice().getId()).setSourceQualityTaskIds(taskIds)
                        .setSourceQualityEvidenceFingerprint(qualityFingerprint(proof.stocks()))
                        .setFactoryCode(proof.notice().getFactoryCode());
        R<QmsFinishedGoodsDecisionReversalDTO.Authorization> response =
                qualityDecisionReversalService.authorization(query);
        require(response != null && response.isOk() && response.getData() != null,
                "QMS未返回质量逆向授权状态");
        return response.getData();
    }

    private QmsFinishedGoodsDecisionReversalDTO.Authorization consumeQualityAuthorization(
            Proof proof, Long authorizationId, String authorizationCode, String correctionRequestId) {
        List<Long> taskIds = qualityTaskIds(proof.stocks());
        if (taskIds.isEmpty()) return null;
        require(authorizationId != null && StrUtil.isNotBlank(authorizationCode),
                "来源成品使用了QMS质量判定，必须先完成异岗逆向审批并携带有效授权");
        QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand command =
                new QmsFinishedGoodsDecisionReversalDTO.ConsumeCommand();
        command.setAuthorizationId(authorizationId);
        command.setAuthorizationCode(authorizationCode.trim());
        command.setWmsCorrectionRequestId(correctionRequestId);
        command.setWmsNoticeId(proof.notice().getId());
        command.setSourceQualityTaskIds(taskIds);
        command.setSourceQualityEvidenceFingerprint(qualityFingerprint(proof.stocks()));
        command.setFactoryCode(proof.notice().getFactoryCode());
        R<QmsFinishedGoodsDecisionReversalDTO.Authorization> response =
                qualityDecisionReversalService.consume(command);
        require(response != null && response.isOk() && response.getData() != null
                        && Boolean.TRUE.equals(response.getData().getEligible()),
                "QMS质量逆向授权消费失败或已失效");
        return response.getData();
    }

    private String qualityFingerprint(List<WmsLiveStockEntity> stocks) {
        List<String> values = stocks.stream().map(WmsLiveStockEntity::getSourceQualityEvidenceFingerprint)
                .filter(StrUtil::isNotBlank).distinct().toList();
        require(values.size() == 1, "当前来源库存没有唯一QMS质量证据指纹");
        return values.get(0);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StrUtil.blankToDefault(current.getMessage(), current.getClass().getSimpleName());
    }

    private String evidenceFingerprint(WmsInNoticeEntity notice, List<WmsInNoticeDetailEntity> noticeDetails,
                                       List<WmsInBillEntity> bills, List<WmsInBillDetailEntity> billDetails,
                                       List<WmsLiveStockEntity> stocks, List<WmsInoutFlowEntity> inboundFlows,
                                       BigDecimal productionReserved, BigDecimal salesReserved) {
        List<Object> facts = new ArrayList<>();
        facts.add(Arrays.asList(notice.getId(), notice.getState(), notice.getSourceRequestId(),
                notice.getSourceRequestFingerprint(), number(quantity(notice.getNoticeInNum())),
                number(quantity(notice.getAllInNum())), number(quantity(notice.getAllNotInNum())),
                notice.getFinishedGoodsReversalStatus(), number(zero(notice.getFinishedGoodsReversedNum())),
                notice.getFactoryCode()));
        noticeDetails.forEach(item -> facts.add(Arrays.asList("NOTICE_LINE", item.getId(), item.getMatCode(),
                number(quantity(item.getNoticeInNum())), number(quantity(item.getInNum())),
                number(quantity(item.getNotInNum())), number(zero(item.getFinishedGoodsReversedNum())))));
        bills.forEach(item -> facts.add(Arrays.asList("BILL", item.getId(), item.getBillNo(), item.getState())));
        billDetails.forEach(item -> facts.add(Arrays.asList("BILL_LINE", item.getId(), item.getBillId(),
                item.getNoticeDetailId(), item.getMatCode(), String.valueOf(item.getMatBatch()),
                item.getMatBarcode(), number(quantity(item.getMatNum())), item.getSourceQualityTaskIds(),
                item.getSourceQualityEvidenceFingerprint())));
        stocks.forEach(item -> facts.add(Arrays.asList("STOCK", stockFingerprint(item))));
        inboundFlows.forEach(item -> facts.add(Arrays.asList("IN_FLOW", item.getId(), item.getBillId(),
                item.getBillDetailId(), item.getSourceInBillId(), item.getSourceInBillDetailId(),
                item.getSourceMesInboundRequestId(), item.getStockProofType(), item.getOperateType(),
                item.getMatCode(), String.valueOf(item.getMatBatch()), item.getMatBarcode(), item.getMatUnit(),
                number(quantity(item.getMatNum())), item.getSourceQualityTaskIds(),
                item.getSourceQualityEvidenceFingerprint())));
        facts.add(Arrays.asList("RESERVATION", number(productionReserved), number(salesReserved)));
        return hash(JSONUtil.toJsonStr(facts));
    }

    private String stockFingerprint(WmsLiveStockEntity stock) {
        return hash(JSONUtil.toJsonStr(Arrays.asList(stock.getId(), stock.getSourceInBillId(),
                stock.getSourceInBillDetailId(), stock.getSourceMesInboundRequestId(), stock.getMatCode(),
                stock.getMatBatch(), stock.getMatBarcode(), stock.getMatUnit(), number(quantity(stock.getMatNum())),
                stock.getWarehouseCode(), stock.getSiteCode(), stock.getContainerCode(), stock.getInTime(),
                stock.getQualityStatus(), stock.getQualityResult(), stock.getQualityHoldNo(), stock.getStatus(),
                stock.getSourceQualityTaskIds(), stock.getSourceQualityEvidenceFingerprint(),
                stock.getSourceQualityEvidenceSnapshot(), stock.getExpirationStatus(), stock.getFactoryCode())));
    }

    private boolean validQualityTaskIds(String value) {
        if (StrUtil.isBlank(value)) return false;
        try {
            return JSONUtil.toList(value, Long.class).stream().allMatch(Objects::nonNull);
        } catch (Exception exception) {
            return false;
        }
    }

    private List<Long> qualityTaskIds(List<WmsLiveStockEntity> stocks) {
        SortedSet<Long> result = new TreeSet<>();
        for (WmsLiveStockEntity stock : stocks) {
            if (!validQualityTaskIds(stock.getSourceQualityTaskIds())) continue;
            result.addAll(JSONUtil.toList(stock.getSourceQualityTaskIds(), Long.class));
        }
        return new ArrayList<>(result);
    }

    private List<SelectedStock> selectStocks(Proof proof,
                                             List<WmsFinishedGoodsReceiptCorrectionCommand.StockSelection> requested) {
        List<WmsFinishedGoodsReceiptCorrectionCommand.StockSelection> selections = requested;
        if (selections == null || selections.isEmpty()) {
            require(proof.stockQuantity().compareTo(proof.noticeQuantity()) == 0,
                    "部分红冲必须明确选择库存行和数量，空选择只兼容原整单全量申请");
            selections = proof.stocks().stream().map(stock -> {
                WmsFinishedGoodsReceiptCorrectionCommand.StockSelection item =
                        new WmsFinishedGoodsReceiptCorrectionCommand.StockSelection();
                item.setStockId(stock.getId());
                item.setQuantity(quantity(stock.getMatNum()));
                return item;
            }).toList();
        }
        require(!selections.isEmpty() && selections.size() <= 10000,
                "本次红冲必须选择1至10000条精确库存");
        Map<Long, WmsLiveStockEntity> stockById = proof.stocks().stream()
                .collect(Collectors.toMap(WmsLiveStockEntity::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        List<SelectedStock> result = new ArrayList<>();
        for (WmsFinishedGoodsReceiptCorrectionCommand.StockSelection requestedLine : selections) {
            require(requestedLine != null && requestedLine.getStockId() != null
                            && requestedLine.getQuantity() != null
                            && requestedLine.getQuantity().signum() > 0,
                    "红冲库存选择必须包含库存ID和大于0的数量");
            require(seen.add(requestedLine.getStockId()), "同一库存行不能重复选择");
            WmsLiveStockEntity stock = stockById.get(requestedLine.getStockId());
            require(stock != null, "所选库存不属于当前MES成品入库来源或已经移动");
            BigDecimal stockQuantity = quantity(stock.getMatNum());
            BigDecimal selectedQuantity = requestedLine.getQuantity().stripTrailingZeros();
            BigDecimal selectable = selectableQuantity(stock,
                    proof.productionReservedByStock().getOrDefault(stock.getId(), BigDecimal.ZERO),
                    proof.salesReservedByStock().getOrDefault(stock.getId(), BigDecimal.ZERO));
            require(selectedQuantity.compareTo(selectable) <= 0,
                    "库存[" + stock.getId() + "]本次可红冲数量仅为" + number(selectable));
            if (StrUtil.isNotBlank(stock.getMatBarcode())) {
                require(selectedQuantity.compareTo(stockQuantity) == 0,
                        "条码/SN[" + stock.getMatBarcode() + "]必须按完整库存行红冲，禁止拆分数量");
            }
            result.add(new SelectedStock(stock, selectedQuantity));
        }
        require(result.stream().map(SelectedStock::quantity).reduce(BigDecimal.ZERO, BigDecimal::add).signum() > 0,
                "本次红冲选择数量必须大于0");
        return result;
    }

    private List<SelectedStock> frozenSelections(Proof proof,
                                                 List<WmsFinishedGoodsReceiptCorrectionDetailEntity> frozen) {
        require(frozen != null && !frozen.isEmpty(), "红冲申请缺少冻结库存明细");
        List<WmsFinishedGoodsReceiptCorrectionCommand.StockSelection> selections = frozen.stream().map(detail -> {
            WmsFinishedGoodsReceiptCorrectionCommand.StockSelection item =
                    new WmsFinishedGoodsReceiptCorrectionCommand.StockSelection();
            item.setStockId(detail.getStockId());
            item.setQuantity(detail.getQuantity());
            return item;
        }).toList();
        List<SelectedStock> result = selectStocks(proof, selections);
        Map<Long, WmsFinishedGoodsReceiptCorrectionDetailEntity> detailByStock = frozen.stream()
                .collect(Collectors.toMap(WmsFinishedGoodsReceiptCorrectionDetailEntity::getStockId,
                        Function.identity()));
        for (SelectedStock selection : result) {
            WmsFinishedGoodsReceiptCorrectionDetailEntity detail = detailByStock.get(selection.stock().getId());
            require(detail != null
                            && Objects.equals(detail.getLineFingerprint(), stockFingerprint(selection.stock()))
                            && zero(detail.getSourceStockQuantity()).compareTo(
                            quantity(selection.stock().getMatNum())) == 0,
                    "冻结库存行在申请后发生数量、位置、容器、质量或来源漂移");
        }
        return result;
    }

    private String reversalMode(Proof proof, List<SelectedStock> selected, BigDecimal selectedQuantity) {
        if (selectedQuantity.compareTo(proof.noticeQuantity()) == 0
                && zero(proof.notice().getFinishedGoodsReversedNum()).signum() == 0) {
            return "FULL_RECEIPT";
        }
        long barcoded = selected.stream().filter(item -> StrUtil.isNotBlank(item.stock().getMatBarcode())).count();
        if (barcoded == selected.size()) return "SN_EXACT";
        if (barcoded == 0) return "PARTIAL_BATCH";
        return "MIXED_EXACT";
    }

    private String selectionEvidenceFingerprint(Proof proof, List<SelectedStock> selected) {
        List<Object> facts = new ArrayList<>();
        facts.add(proof.fingerprint());
        selected.stream().sorted(Comparator.comparing(item -> item.stock().getId())).forEach(item ->
                facts.add(Arrays.asList(item.stock().getId(), number(item.quantity()),
                        stockFingerprint(item.stock()),
                        number(proof.productionReservedByStock().getOrDefault(
                                item.stock().getId(), BigDecimal.ZERO)),
                        number(proof.salesReservedByStock().getOrDefault(
                                item.stock().getId(), BigDecimal.ZERO)))));
        return hash(JSONUtil.toJsonStr(facts));
    }

    private BigDecimal selectableQuantity(WmsLiveStockEntity stock,
                                          BigDecimal productionReserved, BigDecimal salesReserved) {
        if (!WmsStockAvailability.isUsable(stock)) return BigDecimal.ZERO;
        BigDecimal physical = quantity(stock.getMatNum());
        BigDecimal reserved = zero(productionReserved).add(zero(salesReserved));
        if (StrUtil.isNotBlank(stock.getMatBarcode()) && reserved.signum() > 0) return BigDecimal.ZERO;
        return physical.subtract(reserved).max(BigDecimal.ZERO);
    }

    private List<WmsFinishedGoodsReceiptCorrectionDetailEntity> details(Long correctionId) {
        return detailMapper.selectList(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionDetailEntity>()
                .eq(WmsFinishedGoodsReceiptCorrectionDetailEntity::getCorrectionId, correctionId)
                .orderByAsc(WmsFinishedGoodsReceiptCorrectionDetailEntity::getId));
    }

    private WmsFinishedGoodsReceiptCorrectionEntity latest(Long noticeId) {
        return getOne(new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                .eq(WmsFinishedGoodsReceiptCorrectionEntity::getNoticeId, noticeId)
                .orderByDesc(WmsFinishedGoodsReceiptCorrectionEntity::getId).last("LIMIT 1"), false);
    }

    private WmsFinishedGoodsReceiptCorrectionEntity requireCorrection(Long id, boolean lock) {
        WmsFinishedGoodsReceiptCorrectionEntity row = getOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionEntity>()
                        .eq(WmsFinishedGoodsReceiptCorrectionEntity::getId, id)
                        .last(lock ? "FOR UPDATE" : ""), false);
        require(row != null, "成品实收入库红冲申请不存在");
        requireFactory(row.getFactoryCode());
        return row;
    }

    private void operation(WmsFinishedGoodsReceiptCorrectionEntity row, String commandId,
                           String fingerprint, String type, String before, String after, String content,
                           MomUser user, LocalDateTime time) {
        require(operationMapper.insert(new WmsFinishedGoodsReceiptCorrectionOperationEntity()
                .setCorrectionId(row.getId()).setCommandId(commandId).setCommandFingerprint(fingerprint)
                .setOperationType(type).setBeforeStatus(before).setAfterStatus(after).setContent(content)
                .setOperatorCode(user.getUsername()).setOperatorName(name(user)).setOperationTime(time)
                .setFactoryCode(row.getFactoryCode())) == 1, "成品实物红冲操作履历保存失败");
    }

    private void validateApply(WmsFinishedGoodsReceiptCorrectionCommand command) {
        require(command != null && command.getNoticeId() != null, "请选择成品入库通知");
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().trim().length() <= 64,
                "申请永久请求号不能为空且不能超过64位");
        requiredReason(command.getReason(), "红冲原因");
    }

    private String requiredReason(String value, String label) {
        String result = StrUtil.trim(value);
        require(StrUtil.isNotBlank(result) && result.length() <= 500, label + "不能为空且不能超过500字");
        return result;
    }

    private String decisionCommandId(WmsFinishedGoodsReceiptCorrectionCommand command) {
        String id = command == null ? null : StrUtil.trim(command.getRequestId());
        require(StrUtil.isNotBlank(id) && id.length() <= 100, "操作永久请求号不能为空且不能超过100位");
        return id;
    }

    private String commandFingerprint(WmsFinishedGoodsReceiptCorrectionCommand command) {
        return hash(JSONUtil.toJsonStr(command));
    }

    private WmsFinishedGoodsReceiptCorrectionOperationEntity replayedOperation(Long correctionId,
                                                                               String commandId, String fingerprint, String operationType) {
        WmsFinishedGoodsReceiptCorrectionOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptCorrectionOperationEntity>()
                        .eq(WmsFinishedGoodsReceiptCorrectionOperationEntity::getCommandId, commandId), false);
        if (replay == null) return null;
        require(Objects.equals(replay.getCorrectionId(), correctionId)
                        && Objects.equals(replay.getCommandFingerprint(), fingerprint)
                        && Objects.equals(replay.getOperationType(), operationType),
                "操作永久请求号已经被不同红冲命令使用");
        return replay;
    }

    private MomUser user() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前实名操作人");
        return user;
    }

    private String name(MomUser user) {
        return StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String requireFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请求头factory-code不能为空，且必须选择有权限的实际工厂");
        return factory;
    }

    private void requireFactory(String factory) {
        require(StrUtil.isNotBlank(factory) && Objects.equals(factory, requireFactory()), "当前工厂无权操作该红冲申请");
    }

    private BigDecimal quantity(Double value) {
        if (value == null) return BigDecimal.ZERO;
        require(Double.isFinite(value) && value >= 0, "库存或单据数量不是有效非负数");
        return BigDecimal.valueOf(value);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    private record Proof(WmsInNoticeEntity notice, List<WmsInNoticeDetailEntity> noticeDetails,
                         List<WmsInBillEntity> bills, List<WmsInBillDetailEntity> billDetails,
                         List<WmsLiveStockEntity> stocks, List<WmsInoutFlowEntity> inboundFlows,
                         String sourceMesRequestId, BigDecimal noticeQuantity,
                         BigDecimal receivedQuantity, BigDecimal stockQuantity,
                         Map<Long, BigDecimal> productionReservedByStock,
                         Map<Long, BigDecimal> salesReservedByStock,
                         List<String> blockers, String fingerprint) {
    }

    private record SelectedStock(WmsLiveStockEntity stock, BigDecimal quantity) {
    }
}
