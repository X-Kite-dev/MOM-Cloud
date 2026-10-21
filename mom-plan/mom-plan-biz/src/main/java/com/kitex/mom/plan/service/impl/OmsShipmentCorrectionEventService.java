package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.dto.OmsShipmentCorrectionEventCommand;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.entity.OmsShipmentCorrectionEventEntity;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsShipmentCorrectionEventMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsShipmentCorrectionLedger;
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
 * 发运记账纠错OMS永久收件箱；与客户退货语义和证据链完全分离。
 */
@Service
@RequiredArgsConstructor
public class OmsShipmentCorrectionEventService {
    private final OmsShipmentCorrectionEventMapper eventMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseMapper baseMapper;
    private final RemoteDeliverService deliverService;
    private final OmsDeliveryEventService deliveryEventService;

    public OmsShipmentCorrectionEventCommand.Receipt findReceipt(String requestId) {
        String key = required(requestId, 64, "WMS发运纠错请求号");
        OmsShipmentCorrectionEventEntity e = eventMapper.selectOne(new LambdaQueryWrapper<OmsShipmentCorrectionEventEntity>().eq(OmsShipmentCorrectionEventEntity::getRequestId, key));
        if (e == null) return null;
        requireFactory(e.getFactoryCode());
        return receipt(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsShipmentCorrectionEventCommand.Receipt receive(OmsShipmentCorrectionEventCommand.Command c) {
        require(c != null && c.getCorrectionId() != null && c.getWmsOutBillId() != null && c.getWmsPlanId() != null
                        && c.getOrderDetailId() != null && c.getCorrectedNum() != null && c.getCorrectedNum().signum() > 0 && c.getCorrectedTime() != null,
                "WMS发运纠错缺少纠错单、出库单、计划、交付行、正数量或执行时间");
        String requestId = required(c.getRequestId(), 64, "WMS发运纠错请求号");
        String orderCode = required(c.getOrderCode(), 100, "订单号");
        String factory = required(c.getFactoryCode(), 100, "工厂");
        requireFactory(factory);
        require(StrUtil.isNotBlank(c.getCorrectionNo()) && StrUtil.isNotBlank(c.getWmsOutBillNo()) && StrUtil.isNotBlank(c.getWmsPlanCode()) && StrUtil.isNotBlank(c.getProductCode()), "发运纠错单号、出库单号、计划号或产品缺失");
        String fingerprint = hash(facts(c));
        eventMapper.lockKey("REQUEST:" + requestId);
        OmsShipmentCorrectionEventEntity old = eventMapper.selectOne(new LambdaQueryWrapper<OmsShipmentCorrectionEventEntity>().eq(OmsShipmentCorrectionEventEntity::getRequestId, requestId));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint), "发运纠错请求号已被不同事实使用");
            return receipt(old);
        }
        require(eventMapper.selectCount(new LambdaQueryWrapper<OmsShipmentCorrectionEventEntity>().eq(OmsShipmentCorrectionEventEntity::getCorrectionId, c.getCorrectionId()).eq(OmsShipmentCorrectionEventEntity::getWmsPlanId, c.getWmsPlanId())) == 0, "同一纠错单/计划已冲减");
        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>().eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode).last("FOR UPDATE"), false);
        require(track != null && trackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>().eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode)) == 1 && Objects.equals(track.getFactoryCode(), factory), "纠错事实无法对应唯一同工厂OMS跟踪订单");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>().eq(OmsOrderDetailsEntity::getId, c.getOrderDetailId()).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), orderCode) && Objects.equals(detail.getFactoryCode(), factory) && Objects.equals(detail.getProductCode(), c.getProductCode()), "纠错事实与OMS订单行不一致");
        OmsDeliveryEventCommand.LineLedger current = deliveryEventService.lineLedger(orderCode, detail.getId());
        require(current != null && current.getOmsDeliveredNum() != null && current.getInboxCorrectedNum() != null && current.getOmsDeliveredNum().compareTo(detail.getDeliveredNum()) == 0, "OMS交付行与永久事件账不守恒");
        R<WmsOmsShipmentCorrectionLedger> response = deliverService.omsShipmentCorrectionLedger(orderCode, detail.getId(), requestId);
        WmsOmsShipmentCorrectionLedger ledger = response == null ? null : response.getData();
        require(response != null && response.isOk() && ledger != null && Objects.equals(ledger.getPlanId(), c.getWmsPlanId())
                && Objects.equals(ledger.getPlanCode(), c.getWmsPlanCode()) && Objects.equals(ledger.getOrderCode(), orderCode)
                && Objects.equals(ledger.getOrderDetailId(), detail.getId()) && Objects.equals(ledger.getFactoryCode(), factory)
                && Objects.equals(ledger.getProductCode(), c.getProductCode()) && Objects.equals(ledger.getRequestId(), requestId)
                && Objects.equals(ledger.getRequestCorrectionId(), c.getCorrectionId()) && Objects.equals(ledger.getRequestFingerprint(), fingerprint)
                && ledger.getRequestCorrectedNum() != null && ledger.getRequestCorrectedNum().compareTo(c.getCorrectedNum()) == 0
                && ledger.getPlanDeliveredNum() != null && ledger.getPlanDeliveredNum().signum() >= 0, "WMS发运纠错Outbox、计划与OMS命令不一致");
        require(ledger.getOutboxCorrectedNum() != null && ledger.getOutboxCorrectedNum().compareTo(current.getInboxCorrectedNum().add(c.getCorrectedNum())) >= 0, "OMS已收纠错超过WMS Outbox总量");
        BigDecimal after = detail.getDeliveredNum().subtract(c.getCorrectedNum());
        require(after.signum() >= 0, "发运纠错将使OMS交付量小于零");
        detail.setDeliveredNum(after);
        if (after.compareTo(detail.getDeliverNum()) < 0) detail.setState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS纠错交付量保存失败");
        List<OmsOrderBaseEntity> bases = baseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>().eq(OmsOrderBaseEntity::getOrderCode, orderCode).last("FOR UPDATE"));
        require(bases.size() == 1 && Objects.equals(bases.get(0).getFactoryCode(), factory), "OMS订单主记录缺失、重复或跨工厂");
        OmsOrderBaseEntity base = bases.get(0);
        base.setOrderState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(baseMapper.updateById(base) == 1, "OMS订单纠错后恢复交付中失败");
        track.setOrderState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(trackService.updateById(track), "OMS跟踪纠错后恢复交付中失败");
        OmsShipmentCorrectionEventEntity saved = new OmsShipmentCorrectionEventEntity().setRequestId(requestId).setRequestFingerprint(fingerprint).setCorrectionId(c.getCorrectionId()).setCorrectionNo(c.getCorrectionNo()).setWmsOutBillId(c.getWmsOutBillId()).setWmsOutBillNo(c.getWmsOutBillNo()).setWmsPlanId(c.getWmsPlanId()).setWmsPlanCode(c.getWmsPlanCode()).setOrderCode(orderCode).setOrderDetailId(detail.getId()).setProductCode(c.getProductCode()).setCorrectedNum(c.getCorrectedNum()).setCorrectedTime(c.getCorrectedTime()).setOmsDeliveredNum(after).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now()).setFactoryCode(factory);
        require(eventMapper.insert(saved) == 1, "OMS发运纠错永久回执保存失败");
        return receipt(saved);
    }

    private OmsShipmentCorrectionEventCommand.Receipt receipt(OmsShipmentCorrectionEventEntity e) {
        return new OmsShipmentCorrectionEventCommand.Receipt().setRequestId(e.getRequestId()).setOrderDetailId(e.getOrderDetailId()).setStatus(e.getStatus()).setOmsDeliveredNum(e.getOmsDeliveredNum()).setRequestFingerprint(e.getRequestFingerprint()).setCompletedTime(e.getCompletedTime());
    }

    private String facts(OmsShipmentCorrectionEventCommand.Command c) {
        return JSONUtil.toJsonStr(Arrays.asList(c.getCorrectionId(), c.getCorrectionNo(), c.getWmsOutBillId(), c.getWmsOutBillNo(), c.getWmsPlanId(), c.getWmsPlanCode(), c.getOrderCode(), c.getOrderDetailId(), c.getFactoryCode(), c.getProductCode(), c.getCorrectedNum().stripTrailingZeros().toPlainString(), c.getCorrectedTime()));
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("OMS发运纠错指纹生成失败");
        }
    }

    private String required(String v, int max, String label) {
        String s = v == null ? null : v.trim();
        require(StrUtil.isNotBlank(s) && s.length() <= max, label + "不能为空且不能超过" + max + "字");
        return s;
    }

    private void requireFactory(String f) {
        String s = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(s) || Objects.equals(s, f)), "当前工厂无权接收该发运纠错事实");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
