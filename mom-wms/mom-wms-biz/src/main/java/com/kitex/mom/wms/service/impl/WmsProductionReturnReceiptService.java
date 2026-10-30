package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.inBill.service.WmsProductionReturnInBillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 生产退料实际收货终结与差异处置。
 *
 * <p>
 * 实收只来自已记账入库单；PDA部分收货在终结时完成库存记账。短收与溢收必须申报， 且申报人与处置人分离。溢收不会静默增加库存。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class WmsProductionReturnReceiptService {
    private static final String MES_RETURN_TABLE = "mom_mes.mes_warehouse_stock_feed_judge";
    private static final Set<String> TERMINAL_STATES = Set.of("2", "12");

    private final WmsInNoticeMapper noticeMapper;
    private final WmsInBillMapper billMapper;
    private final WmsInBillDetailMapper detailMapper;
    private final WmsProductionReturnReceiptOperationMapper operationMapper;
    private final WmsMesMaterialReturnReceiptOutboxMapper outboxMapper;
    private final WmsProductionReturnInBillService returnInBillService;
    private final WmsMesMaterialReturnReceiptOutboxService outboxService;

    @Transactional(readOnly = true)
    public WmsProductionReturnReceiptCommand.View detail(Long noticeId) {
        return view(notice(noticeId, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsProductionReturnReceiptCommand.View finalizeReceipt(
            WmsProductionReturnReceiptCommand.Finalize command) {
        validateFinalize(command);
        WmsInNoticeEntity notice = notice(command.getNoticeId(), true);
        WmsProductionReturnReceiptOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), notice.getId())
                    && "FINALIZE".equals(replay.getOperationType()), "收货终结请求号已被其他操作使用");
            return view(noticeMapper.selectById(notice.getId()));
        }
        require(!Set.of("RECEIVED", "CLOSED_WITH_VARIANCE", "VARIANCE_PENDING")
                .contains(notice.getReturnReceiptStatus()), "该生产退料通知已经终结或进入差异处置");

        List<WmsInBillEntity> openBills = openBills(notice.getId());
        require(openBills.stream().noneMatch(b -> Set.of("0", "1").contains(b.getState())),
                "存在PC端待提交或待审核入库单，请先完成或作废后再终结收货");
        LocalDateTime now = LocalDateTime.now();
        for (WmsInBillEntity bill : openBills) {
            require(InOutBillStateEnum.APP_RUNNING.getCode().equals(bill.getState()), "存在不支持终结的入库单状态");
            List<WmsInBillDetailEntity> details = detailMapper.selectList(
                    new LambdaQueryWrapper<WmsInBillDetailEntity>()
                            .eq(WmsInBillDetailEntity::getBillId, bill.getId()));
            require(!details.isEmpty(), "PDA收货单没有实收明细");
            require(detailMapper.update(new LambdaUpdateWrapper<WmsInBillDetailEntity>()
                            .set(WmsInBillDetailEntity::getInTime, now)
                            .in(BaseEntity::getId, details.stream().map(BaseEntity::getId).toList())) == details.size(),
                    "PDA生产退料明细终结失败，整笔收货回滚");
            require(billMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
                    .set(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                    .set(WmsInBillEntity::getInTime, now)
                    .set(WmsInBillEntity::getDetailFinishNum, details.size())
                    .set(WmsInBillEntity::getDetailNotFinishNum, 0)
                    .eq(BaseEntity::getId, bill.getId())) == 1, "PDA生产退料收货单终结失败");
            details.forEach(d -> d.setInTime(now));
            bill.setState(InOutBillStateEnum.FINISHED.getCode());
            bill.setInTime(now);
            bill.setDetails(details);
            returnInBillService.afterInStock(bill);
            // 每张实际入库完成后立即冻结其增量及当时累计，避免多张单都携带最终累计量。
            returnInBillService.handleDocumentBizWhenAdd(bill);
        }

        BigDecimal expected = decimal(notice.getNoticeInNum());
        BigDecimal actual = actual(notice.getId());
        require(actual.compareTo(expected) <= 0, "已记账退料数量超过通知量，禁止自动终结");
        BigDecimal shortage = expected.subtract(actual);
        BigDecimal overage = zero(command.getOverageQuantity());
        require(!(shortage.signum() > 0 && overage.signum() > 0), "短收与溢收不能同时申报");
        require(shortage.signum() == 0 && overage.signum() == 0 || StrUtil.isNotBlank(command.getReason()),
                "存在收货差异时必须填写现场原因");

        MomUser actor = actor();
        String before = StrUtil.blankToDefault(notice.getReturnReceiptStatus(), "WAITING");
        String after = shortage.signum() == 0 && overage.signum() == 0 ? "RECEIVED" : "VARIANCE_PENDING";
        notice.setState(InOutNoticeStateEnum.FINISHED.getCode());
        notice.setCompleteTime(now);
        notice.setReturnReceiptStatus(after);
        notice.setReturnShortageNum(shortage);
        notice.setReturnOverageNum(overage);
        notice.setReturnVarianceReason(StrUtil.trim(command.getReason()));
        notice.setReturnReporterCode(actor.getUsername());
        notice.setReturnReporterName(actor.getName());
        notice.setReturnReportedTime(now);
        notice.setReturnFinalizeRequestId(command.getRequestId().trim());
        require(noticeMapper.updateById(notice) == 1, "生产退料收货终结状态保存失败");
        audit(notice, command.getRequestId(), "FINALIZE", before, after,
                JSONUtil.toJsonStr(view(notice)), actor, now);
        // 最终状态独立于实收增量，包含足量完成和无实物的差异终结。
        outboxService.enqueueNoticeState(notice, command.getRequestId());
        return view(notice);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsProductionReturnReceiptCommand.View dispose(
            WmsProductionReturnReceiptCommand.Disposition command) {
        validateDisposition(command);
        WmsInNoticeEntity notice = notice(command.getNoticeId(), true);
        WmsProductionReturnReceiptOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), notice.getId())
                    && "DISPOSITION".equals(replay.getOperationType()), "差异处置请求号已被其他操作使用");
            return view(noticeMapper.selectById(notice.getId()));
        }
        require("VARIANCE_PENDING".equals(notice.getReturnReceiptStatus()), "只有待处置收货差异可以处理");
        MomUser actor = actor();
        require(!Objects.equals(actor.getUsername(), notice.getReturnReporterCode()), "差异申报人与处置人必须分离");
        BigDecimal shortage = zero(notice.getReturnShortageNum());
        BigDecimal overage = zero(notice.getReturnOverageNum());
        String decision = command.getDecision().trim();
        if (shortage.signum() > 0) {
            require(Set.of("ACCEPT_SHORTAGE", "CONTINUE_RECEIPT").contains(decision), "短收只能接受短收或继续收货");
        } else if (overage.signum() > 0) {
            require("RETURN_OVERAGE".equals(decision), "溢收只能退回未入账实物");
        } else {
            throw new CheckedException("当前通知没有可处置的收货差异");
        }
        LocalDateTime now = LocalDateTime.now();
        String after = "CONTINUE_RECEIPT".equals(decision) ? "RECEIVING" : "CLOSED_WITH_VARIANCE";
        notice.setReturnReceiptStatus(after);
        notice.setReturnDisposition(decision);
        notice.setReturnDispositionReason(command.getReason().trim());
        notice.setReturnReviewerCode(actor.getUsername());
        notice.setReturnReviewerName(actor.getName());
        notice.setReturnReviewedTime(now);
        notice.setReturnDispositionRequestId(command.getRequestId().trim());
        if ("CONTINUE_RECEIPT".equals(decision)) {
            notice.setState(InOutNoticeStateEnum.RUNNING.getCode());
            notice.setCompleteTime(null);
        }
        require(noticeMapper.updateById(notice) == 1, "生产退料收货差异处置保存失败");
        audit(notice, command.getRequestId(), "DISPOSITION", "VARIANCE_PENDING", after,
                decision + "：" + command.getReason().trim(), actor, now);
        outboxService.enqueueNoticeState(notice, command.getRequestId());
        return view(notice);
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsProductionReturnReceiptCommand.View retryDelivery(
            WmsProductionReturnReceiptCommand.RetryDelivery command) {
        require(command != null && command.getNoticeId() != null && validRequest(command.getRequestId())
                        && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
                "人工续跑须提供通知、永久请求号和1至500字原因");
        WmsInNoticeEntity notice = notice(command.getNoticeId(), true);
        WmsProductionReturnReceiptOperationEntity replay = operation(command.getRequestId());
        if (replay != null) {
            require(Objects.equals(replay.getNoticeId(), notice.getId())
                    && "DELIVERY_RETRY".equals(replay.getOperationType()), "回写续跑请求号已被其他操作使用");
            return view(notice);
        }
        int reset = outboxService.resetNotice(notice.getId());
        require(reset > 0, "当前通知没有待续跑的MES实收事件");
        MomUser actor = actor();
        audit(notice, command.getRequestId(), "DELIVERY_RETRY", notice.getReturnReceiptStatus(),
                notice.getReturnReceiptStatus(), "重置" + reset + "条冻结事件：" + command.getReason().trim(),
                actor, LocalDateTime.now());
        return view(notice);
    }

    private WmsProductionReturnReceiptCommand.View view(WmsInNoticeEntity notice) {
        BigDecimal expected = decimal(notice.getNoticeInNum());
        BigDecimal actual = actual(notice.getId());
        WmsMesMaterialReturnReceiptOutboxEntity delivery = outboxMapper.selectOne(
                new LambdaQueryWrapper<WmsMesMaterialReturnReceiptOutboxEntity>()
                        .eq(WmsMesMaterialReturnReceiptOutboxEntity::getNoticeId, notice.getId())
                        .orderByDesc(WmsMesMaterialReturnReceiptOutboxEntity::getId).last("LIMIT 1"));
        WmsProductionReturnReceiptCommand.View result = new WmsProductionReturnReceiptCommand.View().setNotice(notice)
                .setExpectedQuantity(expected).setActualReceivedQuantity(actual)
                .setShortageQuantity(expected.subtract(actual).max(BigDecimal.ZERO))
                .setOverageQuantity(zero(notice.getReturnOverageNum()))
                .setHasOpenBill(!openBills(notice.getId()).isEmpty())
                .setOperations(operationMapper.selectList(
                        new LambdaQueryWrapper<WmsProductionReturnReceiptOperationEntity>()
                                .eq(WmsProductionReturnReceiptOperationEntity::getNoticeId, notice.getId())
                                .orderByAsc(WmsProductionReturnReceiptOperationEntity::getOperationTime)
                                .orderByAsc(WmsProductionReturnReceiptOperationEntity::getId)));
        if (delivery != null) {
            result.setDeliveryEventId(delivery.getEventId()).setDeliveryStatus(delivery.getStatus())
                    .setDeliveryLastError(delivery.getLastError()).setDeliveryAttemptCount(delivery.getAttemptCount());
        }
        return result;
    }

    private WmsInNoticeEntity notice(Long id, boolean lock) {
        require(id != null, "请选择生产退料通知");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请求头必须提供当前工厂编码");
        WmsInNoticeEntity notice = noticeMapper.selectOne(new LambdaQueryWrapper<WmsInNoticeEntity>()
                .eq(WmsInNoticeEntity::getId, id).eq(WmsInNoticeEntity::getFactoryCode, factory)
                .last(lock ? "FOR UPDATE" : ""));
        require(notice != null && InBillTypeEnum.RETURN.getCode().equals(notice.getBillType())
                && MES_RETURN_TABLE.equals(notice.getDocumentTable()), "当前租户和工厂下不存在MES生产退料通知");
        return notice;
    }

    private BigDecimal actual(Long noticeId) {
        List<Long> billIds = billMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                        .select(WmsInBillEntity::getId).eq(WmsInBillEntity::getNoticeId, noticeId)
                        .in(WmsInBillEntity::getState, TERMINAL_STATES))
                .stream().map(WmsInBillEntity::getId).toList();
        if (billIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return detailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                        .select(WmsInBillDetailEntity::getMatNum).in(WmsInBillDetailEntity::getBillId, billIds))
                .stream().map(d -> decimal(d.getMatNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<WmsInBillEntity> openBills(Long noticeId) {
        return billMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, noticeId).notIn(WmsInBillEntity::getState, TERMINAL_STATES));
    }

    private WmsProductionReturnReceiptOperationEntity operation(String requestId) {
        return operationMapper.selectOne(new LambdaQueryWrapper<WmsProductionReturnReceiptOperationEntity>()
                .eq(WmsProductionReturnReceiptOperationEntity::getRequestId, requestId.trim()));
    }

    private void audit(WmsInNoticeEntity notice, String requestId, String type, String before, String after,
                       String detail, MomUser actor, LocalDateTime time) {
        require(operationMapper.insert(new WmsProductionReturnReceiptOperationEntity()
                .setNoticeId(notice.getId()).setRequestId(requestId.trim()).setOperationType(type)
                .setBeforeStatus(before).setAfterStatus(after).setDetail(detail)
                .setOperatorCode(actor.getUsername()).setOperatorName(actor.getName())
                .setOperationTime(time).setFactoryCode(notice.getFactoryCode())) == 1, "生产退料收货履历保存失败");
    }

    private MomUser actor() {
        MomUser user = SecurityUtils.getUser();
        require(user != null && StrUtil.isNotBlank(user.getUsername()), "生产退料收货操作必须登录");
        return user;
    }

    private void validateFinalize(WmsProductionReturnReceiptCommand.Finalize command) {
        require(command != null && command.getNoticeId() != null && validRequest(command.getRequestId())
                        && (command.getOverageQuantity() == null || command.getOverageQuantity().signum() >= 0)
                        && (command.getReason() == null || command.getReason().trim().length() <= 500),
                "终结收货须提供通知、永久请求号，溢收不得为负，原因不超过500字");
    }

    private void validateDisposition(WmsProductionReturnReceiptCommand.Disposition command) {
        require(command != null && command.getNoticeId() != null && validRequest(command.getRequestId())
                        && StrUtil.isNotBlank(command.getDecision()) && StrUtil.isNotBlank(command.getReason())
                        && command.getReason().trim().length() <= 500,
                "差异处置须提供通知、永久请求号、结论和1至500字原因");
    }

    private boolean validRequest(String requestId) {
        return StrUtil.isNotBlank(requestId) && requestId.trim().length() >= 16
                && requestId.trim().length() <= 64 && !requestId.contains(":");
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void require(boolean value, String message) {
        if (!value) {
            throw new CheckedException(message);
        }
    }
}
