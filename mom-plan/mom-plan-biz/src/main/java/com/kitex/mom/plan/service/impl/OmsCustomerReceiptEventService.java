package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsCustomerReceiptEventCommand;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsCustomerReceiptEventMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsCustomerReceiptLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OMS客户实收永久收件箱；客户实收账与仓库净出库账分离。
 */
@Service
@RequiredArgsConstructor
public class OmsCustomerReceiptEventService {
    private final OmsCustomerReceiptEventMapper eventMapper;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderBaseMapper baseMapper;
    private final RemoteDeliverService deliverService;
    private final OmsDeliveryEventService deliveryEventService;
    private final com.kitex.mom.plan.mapper.OmsCustomerExceptionResolutionEventMapper exceptionResolutionMapper;
    private final com.kitex.mom.plan.mapper.OmsSalesReturnEventMapper salesReturnEventMapper;

    public OmsCustomerReceiptEventCommand.Receipt findReceipt(String requestId) {
        String key = required(requestId, 64, "客户实收请求号");
        OmsCustomerReceiptEventEntity e = eventMapper.selectOne(new LambdaQueryWrapper<OmsCustomerReceiptEventEntity>().eq(OmsCustomerReceiptEventEntity::getRequestId, key));
        if (e == null) return null;
        requireFactory(e.getFactoryCode());
        return receipt(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsCustomerReceiptEventCommand.Receipt receive(OmsCustomerReceiptEventCommand.Command c) {
        require(c != null && c.getCustodyId() != null && c.getOutBillId() != null && c.getPlanId() != null && c.getOrderDetailId() != null && c.getSignedNum() != null && c.getSignedNum().signum() >= 0 && c.getExceptionNum() != null && c.getExceptionNum().signum() >= 0 && c.getSignedNum().add(c.getExceptionNum()).signum() > 0 && c.getReceiptTime() != null, "客户实收事件缺少保管链、出库、计划、订单行、正数量或实收时间");
        String request = required(c.getRequestId(), 64, "客户实收请求号"), order = required(c.getOrderCode(), 100, "订单号"), factory = required(c.getFactoryCode(), 100, "工厂");
        requireFactory(factory);
        String shipmentType = StrUtil.blankToDefault(c.getShipmentType(), "NORMAL");
        BigDecimal resolvedIncrement = Optional.ofNullable(c.getResolvedExceptionNum()).orElse(BigDecimal.ZERO);
        require(("NORMAL".equals(shipmentType) && resolvedIncrement.signum() == 0 && c.getResolutionCaseId() == null) || ("REPLACEMENT".equals(shipmentType) && c.getResolutionCaseId() != null && resolvedIncrement.signum() >= 0 && resolvedIncrement.compareTo(c.getSignedNum()) == 0), "客户实收类型、处置来源或异常冲销量非法");
        require(StrUtil.isNotBlank(c.getCustodyNo()) && StrUtil.isNotBlank(c.getOutBillNo()) && StrUtil.isNotBlank(c.getPlanCode()) && StrUtil.isNotBlank(c.getProductCode()), "客户实收保管链、出库、计划或产品编码缺失");
        String fp = hash(facts(c));
        eventMapper.lockKey("CUSTOMER-RECEIPT:" + request);
        OmsCustomerReceiptEventEntity old = eventMapper.selectOne(new LambdaQueryWrapper<OmsCustomerReceiptEventEntity>().eq(OmsCustomerReceiptEventEntity::getRequestId, request));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fp), "客户实收请求号已被不同事实使用");
            return receipt(old);
        }
        WmsOmsCustomerReceiptLedger ledger = data(deliverService.omsCustomerReceiptLedger(order, c.getOrderDetailId(), request));
        require(ledger != null && Objects.equals(ledger.getRequestId(), request) && Objects.equals(ledger.getRequestFingerprint(), fp) && Objects.equals(ledger.getCustodyId(), c.getCustodyId()) && Objects.equals(ledger.getOutBillId(), c.getOutBillId()) && Objects.equals(ledger.getPlanId(), c.getPlanId()) && Objects.equals(ledger.getPlanCode(), c.getPlanCode()) && Objects.equals(ledger.getOrderCode(), order) && Objects.equals(ledger.getOrderDetailId(), c.getOrderDetailId()) && Objects.equals(ledger.getProductCode(), c.getProductCode()) && Objects.equals(ledger.getFactoryCode(), factory) && ledger.getSignedNum() != null && ledger.getSignedNum().compareTo(c.getSignedNum()) == 0 && ledger.getExceptionNum() != null && ledger.getExceptionNum().compareTo(c.getExceptionNum()) == 0 && ledger.getResolvedExceptionNum() != null && ledger.getResolvedExceptionNum().compareTo(resolvedIncrement) == 0 && Objects.equals(StrUtil.blankToDefault(ledger.getShipmentType(), "NORMAL"), shipmentType) && Objects.equals(ledger.getResolutionCaseId(), c.getResolutionCaseId()), "WMS客户实收Outbox与OMS命令不一致");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getId, c.getOrderDetailId()).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), order) && Objects.equals(detail.getProductCode(), c.getProductCode()) && Objects.equals(detail.getFactoryCode(), factory), "客户实收无法对应OMS订单行");
        BigDecimal signed = Optional.ofNullable(detail.getCustomerSignedNum()).orElse(BigDecimal.ZERO), exception = Optional.ofNullable(detail.getCustomerExceptionNum()).orElse(BigDecimal.ZERO), resolved = Optional.ofNullable(detail.getCustomerResolvedExceptionNum()).orElse(BigDecimal.ZERO), settled = Optional.ofNullable(detail.getCustomerSettlementResolvedNum()).orElse(BigDecimal.ZERO);
        BigDecimal eventSigned = sum(order, detail.getId(), 0), eventException = sum(order, detail.getId(), 1), receiptResolved = sum(order, detail.getId(), 2), settlementResolved = exceptionResolutionMapper.selectList(new LambdaQueryWrapper<OmsCustomerExceptionResolutionEventEntity>().eq(OmsCustomerExceptionResolutionEventEntity::getOrderDetailId, detail.getId())).stream().map(x -> x.getRefundNum().add(x.getConcessionNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(signed.compareTo(eventSigned) == 0 && exception.compareTo(eventException) == 0 && resolved.compareTo(receiptResolved.add(settlementResolved)) == 0 && settled.compareTo(settlementResolved) == 0, "OMS客户实收/异常结案累计字段与永久收件箱不一致");
        require(resolvedIncrement.compareTo(exception.subtract(resolved)) <= 0, "补发签收冲销量超过此前尚未解决的客户异常量");
        BigDecimal afterSigned = signed.add(c.getSignedNum()), afterException = exception.add(c.getExceptionNum()), afterResolved = resolved.add(resolvedIncrement);
        require(afterResolved.compareTo(afterException) <= 0, "客户异常已解决累计量超过异常累计量");
        OmsDeliveryEventCommand.LineLedger deliveryLedger = deliveryEventService.lineLedger(order, detail.getId());
        BigDecimal grossShipped = deliveryLedger.getLegacyBaselineNum().add(deliveryLedger.getInboxShippedNum()).subtract(deliveryLedger.getInboxCorrectedNum()).add(Optional.ofNullable(deliveryLedger.getInboxReplacementNum()).orElse(BigDecimal.ZERO));
        require(afterSigned.add(afterException).compareTo(grossShipped) <= 0, "客户实收累计超过OMS可核验的正常及补发实物流量");
        detail.setCustomerSignedNum(afterSigned);
        detail.setCustomerExceptionNum(afterException);
        detail.setCustomerResolvedExceptionNum(afterResolved);
        if (canComplete(detail, afterResolved, settled)) detail.setState(OrderStateEnum.COMPLETE.getCode());
        else detail.setState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS客户实收累计保存失败");
        OmsCustomerReceiptEventEntity saved = new OmsCustomerReceiptEventEntity().setRequestId(request).setRequestFingerprint(fp).setCustodyId(c.getCustodyId()).setCustodyNo(c.getCustodyNo()).setOutBillId(c.getOutBillId()).setOutBillNo(c.getOutBillNo()).setPlanId(c.getPlanId()).setPlanCode(c.getPlanCode()).setOrderCode(order).setOrderDetailId(detail.getId()).setProductCode(c.getProductCode()).setSignedNum(c.getSignedNum()).setExceptionNum(c.getExceptionNum()).setResolvedExceptionNum(resolvedIncrement).setShipmentType(shipmentType).setResolutionCaseId(c.getResolutionCaseId()).setOmsSignedNum(afterSigned).setOmsExceptionNum(afterException).setOmsResolvedExceptionNum(afterResolved).setReceiptTime(c.getReceiptTime()).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now()).setFactoryCode(factory);
        require(eventMapper.insert(saved) == 1, "OMS客户实收永久收件箱保存失败");
        List<OmsOrderDetailsEntity> all = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getOrderCode, order).last("FOR UPDATE"));
        boolean complete = !all.isEmpty() && all.stream().allMatch(x -> canComplete(x, Optional.ofNullable(x.getCustomerResolvedExceptionNum()).orElse(BigDecimal.ZERO), Optional.ofNullable(x.getCustomerSettlementResolvedNum()).orElse(BigDecimal.ZERO)));
        List<OmsOrderBaseEntity> bases = baseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>().eq(OmsOrderBaseEntity::getOrderCode, order).last("FOR UPDATE"));
        require(bases.size() == 1 && Objects.equals(bases.get(0).getFactoryCode(), factory), "OMS订单主记录缺失、重复或跨工厂");
        OmsOrderBaseEntity base = bases.get(0);
        base.setOrderState(complete ? OrderStateEnum.COMPLETE.getCode() : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(baseMapper.updateById(base) == 1, "OMS订单客户实收状态保存失败");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>().eq(OmsOrderBaseTrackEntity::getOrderCode, order).last("FOR UPDATE"), false);
        require(track != null && Objects.equals(track.getFactoryCode(), factory), "OMS订单跟踪记录缺失或跨工厂");
        track.setOrderState(base.getOrderState());
        require(trackService.updateById(track), "OMS订单跟踪客户实收状态保存失败");
        return receipt(saved);
    }

    private BigDecimal sum(String order, Long detailId, int type) {
        return eventMapper.selectList(new LambdaQueryWrapper<OmsCustomerReceiptEventEntity>().eq(OmsCustomerReceiptEventEntity::getOrderCode, order).eq(OmsCustomerReceiptEventEntity::getOrderDetailId, detailId)).stream().map(x -> type == 0 ? x.getSignedNum() : type == 1 ? x.getExceptionNum() : Optional.ofNullable(x.getResolvedExceptionNum()).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private WmsOmsCustomerReceiptLedger data(R<WmsOmsCustomerReceiptLedger> r) {
        return r != null && r.isOk() ? r.getData() : null;
    }

    private OmsCustomerReceiptEventCommand.Receipt receipt(OmsCustomerReceiptEventEntity e) {
        return new OmsCustomerReceiptEventCommand.Receipt().setRequestId(e.getRequestId()).setRequestFingerprint(e.getRequestFingerprint()).setStatus(e.getStatus()).setOmsSignedNum(e.getOmsSignedNum()).setOmsExceptionNum(e.getOmsExceptionNum()).setOmsResolvedExceptionNum(Optional.ofNullable(e.getOmsResolvedExceptionNum()).orElse(BigDecimal.ZERO)).setCompletedTime(e.getCompletedTime());
    }

    private String facts(OmsCustomerReceiptEventCommand.Command c) {
        List<Object> values = new ArrayList<>(Arrays.asList(c.getCustodyId(), c.getCustodyNo(), c.getOutBillId(), c.getOutBillNo(), c.getPlanId(), c.getPlanCode(), c.getOrderCode(), c.getOrderDetailId(), c.getProductCode(), c.getSignedNum().stripTrailingZeros().toPlainString(), c.getExceptionNum().stripTrailingZeros().toPlainString(), c.getReceiptTime(), c.getFactoryCode()));
        if ("REPLACEMENT".equals(c.getShipmentType())) {
            values.add(c.getResolvedExceptionNum().stripTrailingZeros().toPlainString());
            values.add(c.getShipmentType());
            values.add(c.getResolutionCaseId());
        }
        return JSONUtil.toJsonStr(values);
    }

    private boolean canComplete(OmsOrderDetailsEntity x, BigDecimal resolved, BigDecimal settled) {
        BigDecimal exceptionReturned = salesReturnEventMapper.selectList(new LambdaQueryWrapper<OmsSalesReturnEventEntity>().eq(OmsSalesReturnEventEntity::getOrderDetailId, x.getId()).isNotNull(OmsSalesReturnEventEntity::getResolutionCaseId)).stream().map(OmsSalesReturnEventEntity::getReturnedNum).map(v -> Optional.ofNullable(v).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal returnCredit = resolved.min(exceptionReturned);
        return x.getDeliverNum() != null && x.getDeliveredNum() != null && x.getDeliveredNum().add(returnCredit).compareTo(x.getDeliverNum()) >= 0 && Optional.ofNullable(x.getCustomerExceptionNum()).orElse(BigDecimal.ZERO).subtract(resolved).signum() == 0 && Optional.ofNullable(x.getCustomerSignedNum()).orElse(BigDecimal.ZERO).add(settled).compareTo(x.getDeliverNum()) >= 0;
    }

    private String required(String v, int max, String label) {
        String s = StrUtil.trim(v);
        require(StrUtil.isNotBlank(s) && s.length() <= max, label + "不能为空且不能超过" + max + "字");
        return s;
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("OMS客户实收指纹生成失败");
        }
    }

    private void requireFactory(String f) {
        String s = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(s) || Objects.equals(s, f)), "当前工厂无权接收客户实收事实");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
