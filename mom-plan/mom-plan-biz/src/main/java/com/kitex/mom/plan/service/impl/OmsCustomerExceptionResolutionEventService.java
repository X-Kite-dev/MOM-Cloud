package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsCustomerExceptionResolutionCommand;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsCustomerExceptionResolutionEventMapper;
import com.kitex.mom.plan.mapper.OmsCustomerReceiptEventMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsSalesReturnEventMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsCustomerExceptionResolutionLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 客户异常退款/让步结案收件箱：只冲销未解决异常，不伪造补发或退货。
 */
@Service
@RequiredArgsConstructor
public class OmsCustomerExceptionResolutionEventService {
    private final OmsCustomerExceptionResolutionEventMapper mapper;
    private final OmsCustomerReceiptEventMapper receiptMapper;
    private final OmsSalesReturnEventMapper salesReturnEventMapper;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseMapper baseMapper;
    private final OmsOrderBaseTrackService trackService;
    private final RemoteDeliverService deliverService;

    public OmsCustomerExceptionResolutionCommand.Receipt findReceipt(String requestId) {
        OmsCustomerExceptionResolutionEventEntity row = mapper.selectOne(new LambdaQueryWrapper<OmsCustomerExceptionResolutionEventEntity>().eq(OmsCustomerExceptionResolutionEventEntity::getRequestId, required(requestId, 64, "异常结案请求号")));
        if (row == null) return null;
        requireFactory(row.getFactoryCode());
        return receipt(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsCustomerExceptionResolutionCommand.Receipt receive(OmsCustomerExceptionResolutionCommand.Command c) {
        require(c != null && c.getResolutionCaseId() != null && c.getOrderDetailId() != null && c.getRefundNum() != null && c.getConcessionNum() != null && c.getRefundNum().signum() >= 0 && c.getConcessionNum().signum() >= 0 && c.getRefundNum().add(c.getConcessionNum()).signum() > 0 && c.getSettlementTime() != null, "客户异常结案缺少处置单、订单行、正数量或结算时间");
        String request = required(c.getRequestId(), 64, "异常结案请求号"), order = required(c.getOrderCode(), 100, "订单号"), factory = required(c.getFactoryCode(), 100, "工厂");
        requireFactory(factory);
        required(c.getCaseNo(), 100, "处置单号");
        required(c.getProductCode(), 100, "产品编码");
        required(c.getEvidence(), 500, "结算证据");
        require(c.getRefundNum().signum() == 0 || StrUtil.isNotBlank(c.getRefundReference()), "退款量大于零时退款凭证号必填");
        require(c.getConcessionNum().signum() == 0 || StrUtil.isNotBlank(c.getConcessionReference()), "让步量大于零时让步凭证号必填");
        String fp = hash(facts(c));
        mapper.lockKey("CUSTOMER-EXCEPTION-RESOLUTION:" + request);
        OmsCustomerExceptionResolutionEventEntity old = mapper.selectOne(new LambdaQueryWrapper<OmsCustomerExceptionResolutionEventEntity>().eq(OmsCustomerExceptionResolutionEventEntity::getRequestId, request));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fp), "异常结案请求号已被不同事实使用");
            return receipt(old);
        }
        R<WmsOmsCustomerExceptionResolutionLedger> response = deliverService.omsCustomerExceptionResolutionLedger(order, c.getOrderDetailId(), request);
        WmsOmsCustomerExceptionResolutionLedger ledger = response != null && response.isOk() ? response.getData() : null;
        require(ledger != null && Objects.equals(ledger.getRequestFingerprint(), fp) && Objects.equals(ledger.getResolutionCaseId(), c.getResolutionCaseId()) && Objects.equals(ledger.getCaseNo(), c.getCaseNo()) && Objects.equals(ledger.getOrderCode(), order) && Objects.equals(ledger.getOrderDetailId(), c.getOrderDetailId()) && Objects.equals(ledger.getProductCode(), c.getProductCode()) && Objects.equals(ledger.getFactoryCode(), factory) && same(ledger.getRefundNum(), c.getRefundNum()) && same(ledger.getConcessionNum(), c.getConcessionNum()) && same(ledger.getRefundAmount(), c.getRefundAmount()) && same(ledger.getConcessionAmount(), c.getConcessionAmount()) && Objects.equals(ledger.getRefundReference(), c.getRefundReference()) && Objects.equals(ledger.getConcessionReference(), c.getConcessionReference()) && Objects.equals(ledger.getEvidence(), c.getEvidence()) && Objects.equals(ledger.getSettlementTime(), c.getSettlementTime()), "WMS客户异常结案Outbox与OMS命令不一致");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getId, c.getOrderDetailId()).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), order) && Objects.equals(detail.getProductCode(), c.getProductCode()) && Objects.equals(detail.getFactoryCode(), factory), "异常结案无法对应OMS订单行");
        BigDecimal raw = zero(detail.getCustomerExceptionNum()), resolved = zero(detail.getCustomerResolvedExceptionNum()), settled = zero(detail.getCustomerSettlementResolvedNum());
        BigDecimal receiptResolved = receiptMapper.selectList(new LambdaQueryWrapper<OmsCustomerReceiptEventEntity>().eq(OmsCustomerReceiptEventEntity::getOrderDetailId, detail.getId())).stream().map(x -> zero(x.getResolvedExceptionNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal eventResolved = mapper.selectList(new LambdaQueryWrapper<OmsCustomerExceptionResolutionEventEntity>().eq(OmsCustomerExceptionResolutionEventEntity::getOrderDetailId, detail.getId())).stream().map(x -> x.getRefundNum().add(x.getConcessionNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(resolved.compareTo(receiptResolved.add(eventResolved)) == 0 && settled.compareTo(eventResolved) == 0, "OMS异常已解决累计与永久收件箱不一致");
        BigDecimal increment = c.getRefundNum().add(c.getConcessionNum());
        require(increment.compareTo(raw.subtract(resolved)) <= 0, "退款/让步结案量超过尚未解决异常量");
        BigDecimal afterResolved = resolved.add(increment), afterSettled = settled.add(increment);
        detail.setCustomerResolvedExceptionNum(afterResolved);
        detail.setCustomerSettlementResolvedNum(afterSettled);
        boolean complete = canComplete(detail, afterResolved, afterSettled);
        detail.setState(complete ? OrderStateEnum.COMPLETE.getCode() : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS客户异常结案累计保存失败");
        OmsCustomerExceptionResolutionEventEntity saved = new OmsCustomerExceptionResolutionEventEntity().setRequestId(request).setRequestFingerprint(fp).setResolutionCaseId(c.getResolutionCaseId()).setCaseNo(c.getCaseNo()).setOrderCode(order).setOrderDetailId(detail.getId()).setProductCode(c.getProductCode()).setRefundNum(c.getRefundNum()).setConcessionNum(c.getConcessionNum()).setRefundAmount(zero(c.getRefundAmount())).setConcessionAmount(zero(c.getConcessionAmount())).setRefundReference(c.getRefundReference()).setConcessionReference(c.getConcessionReference()).setEvidence(c.getEvidence()).setSettlementTime(c.getSettlementTime()).setOmsResolvedExceptionNum(afterResolved).setOmsSettlementResolvedNum(afterSettled).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now()).setFactoryCode(factory);
        require(mapper.insert(saved) == 1, "OMS客户异常结案永久回执保存失败");
        refreshOrder(order, factory);
        return receipt(saved);
    }

    private void refreshOrder(String order, String factory) {
        List<OmsOrderDetailsEntity> lines = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getOrderCode, order).last("FOR UPDATE"));
        boolean complete = !lines.isEmpty() && lines.stream().allMatch(x -> canComplete(x, zero(x.getCustomerResolvedExceptionNum()), zero(x.getCustomerSettlementResolvedNum())));
        List<OmsOrderBaseEntity> bases = baseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>().eq(OmsOrderBaseEntity::getOrderCode, order).last("FOR UPDATE"));
        require(bases.size() == 1 && Objects.equals(bases.get(0).getFactoryCode(), factory), "OMS订单主记录缺失、重复或跨工厂");
        bases.get(0).setOrderState(complete ? OrderStateEnum.COMPLETE.getCode() : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(baseMapper.updateById(bases.get(0)) == 1, "OMS订单异常结案状态保存失败");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>().eq(OmsOrderBaseTrackEntity::getOrderCode, order).last("FOR UPDATE"), false);
        require(track != null && Objects.equals(track.getFactoryCode(), factory), "OMS订单跟踪记录缺失或跨工厂");
        track.setOrderState(bases.get(0).getOrderState());
        require(trackService.updateById(track), "OMS订单跟踪异常结案状态保存失败");
    }

    private boolean canComplete(OmsOrderDetailsEntity x, BigDecimal resolved, BigDecimal settled) {
        BigDecimal exceptionReturned = salesReturnEventMapper.selectList(new LambdaQueryWrapper<OmsSalesReturnEventEntity>().eq(OmsSalesReturnEventEntity::getOrderDetailId, x.getId()).isNotNull(OmsSalesReturnEventEntity::getResolutionCaseId)).stream().map(OmsSalesReturnEventEntity::getReturnedNum).map(this::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returnCredit = resolved.min(exceptionReturned);
        return x.getDeliverNum() != null && x.getDeliveredNum() != null && x.getDeliveredNum().add(returnCredit).compareTo(x.getDeliverNum()) >= 0 && zero(x.getCustomerExceptionNum()).subtract(resolved).signum() == 0 && zero(x.getCustomerSignedNum()).add(settled).compareTo(x.getDeliverNum()) >= 0;
    }

    private OmsCustomerExceptionResolutionCommand.Receipt receipt(OmsCustomerExceptionResolutionEventEntity x) {
        return new OmsCustomerExceptionResolutionCommand.Receipt().setRequestId(x.getRequestId()).setRequestFingerprint(x.getRequestFingerprint()).setStatus(x.getStatus()).setOmsResolvedExceptionNum(x.getOmsResolvedExceptionNum()).setOmsSettlementResolvedNum(x.getOmsSettlementResolvedNum()).setCompletedTime(x.getCompletedTime());
    }

    private String facts(OmsCustomerExceptionResolutionCommand.Command c) {
        return JSONUtil.toJsonStr(Arrays.asList(c.getResolutionCaseId(), c.getCaseNo(), c.getOrderCode(), c.getOrderDetailId(), c.getProductCode(), decimal(c.getRefundNum()), decimal(c.getConcessionNum()), decimal(zero(c.getRefundAmount())), decimal(zero(c.getConcessionAmount())), c.getRefundReference(), c.getConcessionReference(), c.getEvidence(), c.getSettlementTime(), c.getFactoryCode()));
    }

    private boolean same(BigDecimal a, BigDecimal b) {
        return zero(a).compareTo(zero(b)) == 0;
    }

    private BigDecimal zero(BigDecimal x) {
        return x == null ? BigDecimal.ZERO : x;
    }

    private String decimal(BigDecimal x) {
        return x.stripTrailingZeros().toPlainString();
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("客户异常结案指纹生成失败");
        }
    }

    private String required(String v, int max, String label) {
        String x = StrUtil.trim(v);
        require(StrUtil.isNotBlank(x) && x.length() <= max, label + "不能为空且不能超过" + max + "字");
        return x;
    }

    private void requireFactory(String f) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(selected) || Objects.equals(selected, f)), "当前工厂无权接收客户异常结案事实");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
