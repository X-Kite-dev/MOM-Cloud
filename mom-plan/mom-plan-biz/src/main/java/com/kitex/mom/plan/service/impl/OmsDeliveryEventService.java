package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.entity.*;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsDeliveryEventMapper;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsSalesReturnEventMapper;
import com.kitex.mom.plan.mapper.OmsShipmentCorrectionEventMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsDeliveryLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * WMS销售出库事实OMS收件箱：原号原事实重放，订单行加量与回执原子提交。
 */
@Service
@RequiredArgsConstructor
public class OmsDeliveryEventService {
    private final OmsDeliveryEventMapper eventMapper;
    private final OmsSalesReturnEventMapper returnEventMapper;
    private final OmsShipmentCorrectionEventMapper correctionEventMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseMapper baseMapper;
    private final RemoteDeliverService deliverService;

    /**
     * 只读对账，不通过重放命令改变OMS交付数量。
     */
    public OmsDeliveryEventCommand.Receipt findReceipt(String requestId) {
        String key = required(requestId, 64, "WMS发运请求号");
        OmsDeliveryEventEntity event = eventMapper.selectOne(new LambdaQueryWrapper<OmsDeliveryEventEntity>()
                .eq(OmsDeliveryEventEntity::getRequestId, key));
        if (event == null) return null;
        requireFactory(event.getFactoryCode());
        return receipt(event);
    }

    /**
     * 交付行与收件箱同事务取数，供WMS定时核对总量。
     */
    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryEventCommand.LineLedger lineLedger(String orderCode, Long detailsId) {
        require(StrUtil.isNotBlank(orderCode) && detailsId != null, "OMS交付行来源不能为空");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, detailsId).last("FOR SHARE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), orderCode)
                        && detail.getDeliveredNum() != null && detail.getDeliverNum() != null,
                "OMS交付行不存在或数量基线不完整");
        requireFactory(detail.getFactoryCode());
        InboxTotals inbox = inboxTotals(detail);
        ReturnTotals returns = returnTotals(detail);
        CorrectionTotals corrections = correctionTotals(detail);
        BigDecimal baseline = inbox.normalCount() == 0
                ? detail.getDeliveredNum().add(returns.returned()).add(corrections.corrected()) : inbox.baseline();
        require(detail.getDeliveredNum().compareTo(baseline.add(inbox.normalShipped())
                        .subtract(returns.returned()).subtract(corrections.corrected())) == 0,
                "OMS交付行累计量与发运/退货/纠错永久收件箱及历史基线不守恒");
        return new OmsDeliveryEventCommand.LineLedger()
                .setOrderCode(detail.getOrderCode()).setOrderDetailId(detail.getId())
                .setFactoryCode(detail.getFactoryCode()).setProductCode(detail.getProductCode())
                .setOrderDeliverNum(detail.getDeliverNum()).setOmsDeliveredNum(detail.getDeliveredNum())
                .setInboxShippedNum(inbox.normalShipped()).setInboxReplacementNum(inbox.replacementShipped())
                .setInboxReturnedNum(returns.returned())
                .setInboxCorrectedNum(corrections.corrected())
                .setLegacyBaselineNum(baseline).setInboxCount(inbox.normalCount())
                .setReplacementInboxCount(inbox.replacementCount())
                .setReturnInboxCount(returns.count()).setCorrectionInboxCount(corrections.count());
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsDeliveryEventCommand.Receipt receive(OmsDeliveryEventCommand.Command command) {
        require(command != null && command.getWmsBillId() != null && command.getWmsPlanId() != null
                        && command.getOrderDetailId() != null && command.getShippedNum() != null
                        && command.getShippedNum().signum() > 0 && command.getShippedTime() != null,
                "WMS发运事实缺少出库单、计划、交付行、正数量或出库时间");
        String requestId = required(command.getRequestId(), 64, "WMS发运请求号");
        String orderCode = required(command.getOrderCode(), 100, "订单号");
        String factoryCode = required(command.getFactoryCode(), 100, "工厂");
        requireFactory(factoryCode);
        String shipmentType = StrUtil.blankToDefault(command.getShipmentType(), "NORMAL");
        require("NORMAL".equals(shipmentType) || "REPLACEMENT".equals(shipmentType), "WMS发运类型非法");
        require(!"REPLACEMENT".equals(shipmentType) || command.getResolutionCaseId() != null,
                "补发事件缺少客户异常处置单来源");
        require(StrUtil.isNotBlank(command.getWmsBillNo())
                        && StrUtil.isNotBlank(command.getWmsPlanCode())
                        && StrUtil.isNotBlank(command.getProductCode()),
                "WMS发运事实缺少出库单号、计划号或产品编码");
        String fingerprint = hash(facts(command));
        eventMapper.lockKey("REQUEST:" + requestId);
        OmsDeliveryEventEntity old = eventMapper.selectOne(new LambdaQueryWrapper<OmsDeliveryEventEntity>()
                .eq(OmsDeliveryEventEntity::getRequestId, requestId));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint),
                    "WMS发运请求号已被不同出库事实使用");
            requireFactory(old.getFactoryCode());
            return receipt(old);
        }
        require(eventMapper.selectCount(new LambdaQueryWrapper<OmsDeliveryEventEntity>()
                        .eq(OmsDeliveryEventEntity::getWmsBillId, command.getWmsBillId())
                        .eq(OmsDeliveryEventEntity::getWmsPlanId, command.getWmsPlanId())) == 0,
                "同一WMS出库单/计划已由另一请求号回传，禁止重复累计");

        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode).last("FOR UPDATE"), false);
        require(track != null && trackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                        .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode)) == 1
                        && Objects.equals(track.getFactoryCode(), factoryCode),
                "WMS发运事实无法对应唯一同工厂OMS跟踪订单");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, command.getOrderDetailId()).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), orderCode)
                        && Objects.equals(detail.getFactoryCode(), factoryCode)
                        && Objects.equals(detail.getProductCode(), command.getProductCode()),
                "WMS发运事实与OMS原交付行ID、订单、工厂或产品不一致");
        require(OrderStateEnum.ISSUED.getCode().equals(detail.getState())
                        || OrderStateEnum.ISSUED_DELIVER.getCode().equals(detail.getState()),
                "OMS交付行尚未下发WMS或已终结，不能接收新的发运事实");
        require(detail.getDeliveredNum() != null && detail.getDeliverNum() != null
                        && detail.getDeliverNum().signum() > 0,
                "OMS订单数量或已交付基线缺失，须先对账");

        R<WmsOmsDeliveryLedger> response = deliverService.omsDeliveryLedger(
                orderCode, detail.getId(), requestId);
        require(response != null && response.isOk() && response.getData() != null,
                "WMS来源发货计划与出库事件数量账不可核验");
        WmsOmsDeliveryLedger ledger = response.getData();
        require(Objects.equals(ledger.getPlanId(), command.getWmsPlanId())
                        && Objects.equals(ledger.getPlanCode(), command.getWmsPlanCode())
                        && Objects.equals(ledger.getOrderCode(), orderCode)
                        && Objects.equals(ledger.getOrderDetailId(), detail.getId())
                        && Objects.equals(ledger.getFactoryCode(), factoryCode)
                        && Objects.equals(ledger.getProductCode(), command.getProductCode())
                        && Objects.equals(ledger.getRequestId(), requestId)
                        && Objects.equals(ledger.getRequestWmsBillId(), command.getWmsBillId())
                        && Objects.equals(ledger.getRequestFingerprint(), fingerprint)
                        && ledger.getRequestShippedNum() != null
                        && ledger.getRequestShippedNum().compareTo(command.getShippedNum()) == 0
                        && Objects.equals(StrUtil.blankToDefault(ledger.getRequestShipmentType(), "NORMAL"), shipmentType)
                        && Objects.equals(ledger.getRequestResolutionCaseId(), command.getResolutionCaseId())
                        && ledger.getLegacyBaselineNum() != null
                        && ledger.getOutboxShippedNum() != null && ledger.getOutboxReplacementNum() != null,
                "WMS计划、正式出库Outbox与OMS回传命令不一致");
        InboxTotals inbox = inboxTotals(detail);
        ReturnTotals returns = returnTotals(detail);
        CorrectionTotals corrections = correctionTotals(detail);
        BigDecimal baseline = inbox.normalCount() == 0 ? ledger.getLegacyBaselineNum() : inbox.baseline();
        require(baseline.compareTo(ledger.getLegacyBaselineNum()) == 0
                        && detail.getDeliveredNum().compareTo(
                        baseline.add(inbox.normalShipped()).subtract(returns.returned()).subtract(corrections.corrected())) == 0
                        && ledger.getOutboxShippedNum().compareTo(inbox.normalShipped()
                        .add("NORMAL".equals(shipmentType) ? command.getShippedNum() : BigDecimal.ZERO)) >= 0
                        && ledger.getOutboxReplacementNum().compareTo(inbox.replacementShipped()
                        .add("REPLACEMENT".equals(shipmentType) ? command.getShippedNum() : BigDecimal.ZERO)) >= 0,
                "OMS历史交付基线或已收发运/退货事件与WMS计划总量不守恒");
        BigDecimal after = detail.getDeliveredNum();
        if ("NORMAL".equals(shipmentType)) {
            after = after.add(command.getShippedNum());
            require(after.compareTo(detail.getDeliverNum()) <= 0,
                    "OMS订单交付量将超过订单数量，须先走超交审批或对账");
        } else {
            BigDecimal rawException = Optional.ofNullable(detail.getCustomerExceptionNum()).orElse(BigDecimal.ZERO);
            require(rawException.signum() > 0
                            && inbox.replacementShipped().add(command.getShippedNum()).compareTo(rawException) <= 0,
                    "补发累计超过客户已登记异常量，禁止重复补发");
        }
        detail.setDeliveredNum(after);
        // V1.68起仓库出库只代表进入物流交付，不再冒充客户已签收。
        detail.setState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS交付行发运数量保存失败");

        List<OmsOrderBaseEntity> bases = baseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getOrderCode, orderCode).last("FOR UPDATE"));
        require(bases.size() == 1 && Objects.equals(bases.get(0).getFactoryCode(), factoryCode),
                "OMS订单主记录缺失、重复或跨工厂");
        List<OmsOrderDetailsEntity> lines = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode));
        require(!lines.isEmpty() && lines.stream().allMatch(line -> Objects.equals(line.getFactoryCode(), factoryCode)
                        && line.getDeliverNum() != null && line.getDeliveredNum() != null),
                "OMS交付行数量或工厂不完整，禁止更新订单履约终态");
        OmsOrderBaseEntity base = bases.get(0);
        base.setOrderState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(baseMapper.updateById(base) == 1, "OMS订单物流交付状态保存失败");
        track.setOrderState(OrderStateEnum.ISSUED_DELIVER.getCode());
        require(trackService.updateById(track), "OMS订单跟踪物流交付状态保存失败");
        OmsDeliveryEventEntity saved = new OmsDeliveryEventEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setWmsBillId(command.getWmsBillId()).setWmsBillNo(command.getWmsBillNo())
                .setWmsPlanId(command.getWmsPlanId()).setWmsPlanCode(command.getWmsPlanCode())
                .setOrderCode(orderCode).setOrderDetailId(detail.getId())
                .setProductCode(command.getProductCode()).setShippedNum(command.getShippedNum())
                .setShippedTime(command.getShippedTime()).setShipmentType(shipmentType)
                .setResolutionCaseId(command.getResolutionCaseId()).setOmsDeliveredNum(after)
                .setLegacyBaselineNum(baseline)
                .setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now())
                .setFactoryCode(factoryCode);
        require(eventMapper.insert(saved) == 1, "OMS发运事实永久回执保存失败");
        return receipt(saved);
    }

    private String facts(OmsDeliveryEventCommand.Command c) {
        List<Object> facts = new ArrayList<>(Arrays.asList(c.getWmsBillId(), c.getWmsBillNo(),
                c.getWmsPlanId(), c.getWmsPlanCode(), c.getOrderCode(), c.getOrderDetailId(),
                c.getFactoryCode(), c.getProductCode(),
                c.getShippedNum().stripTrailingZeros().toPlainString(), c.getShippedTime()));
        if ("REPLACEMENT".equals(c.getShipmentType())) {
            facts.add(c.getShipmentType());
            facts.add(c.getResolutionCaseId());
        }
        return JSONUtil.toJsonStr(facts);
    }

    private InboxTotals inboxTotals(OmsOrderDetailsEntity detail) {
        List<OmsDeliveryEventEntity> events = eventMapper.selectList(
                new LambdaQueryWrapper<OmsDeliveryEventEntity>()
                        .eq(OmsDeliveryEventEntity::getOrderDetailId, detail.getId()));
        BigDecimal normal = BigDecimal.ZERO;
        BigDecimal replacement = BigDecimal.ZERO;
        BigDecimal baseline = null;
        int normalCount = 0;
        int replacementCount = 0;
        for (OmsDeliveryEventEntity event : events) {
            require(Objects.equals(event.getOrderCode(), detail.getOrderCode())
                            && Objects.equals(event.getFactoryCode(), detail.getFactoryCode())
                            && Objects.equals(event.getProductCode(), detail.getProductCode())
                            && "SUCCEEDED".equals(event.getStatus())
                            && event.getShippedNum() != null && event.getShippedNum().signum() > 0
                            && event.getLegacyBaselineNum() != null
                            && event.getLegacyBaselineNum().signum() >= 0,
                    "OMS交付行永久收件箱来源、状态或历史基线异常");
            String type = StrUtil.blankToDefault(event.getShipmentType(), "NORMAL");
            require("NORMAL".equals(type) || ("REPLACEMENT".equals(type) && event.getResolutionCaseId() != null),
                    "OMS发运事件类型或处置来源非法");
            if ("NORMAL".equals(type)) {
                if (baseline == null) baseline = event.getLegacyBaselineNum();
                else require(baseline.compareTo(event.getLegacyBaselineNum()) == 0,
                        "OMS同一交付行历史基线不一致");
                normal = normal.add(event.getShippedNum());
                normalCount++;
            } else {
                replacement = replacement.add(event.getShippedNum());
                replacementCount++;
            }
        }
        return new InboxTotals(normal, replacement, baseline, normalCount, replacementCount);
    }

    private ReturnTotals returnTotals(OmsOrderDetailsEntity detail) {
        List<OmsSalesReturnEventEntity> events = returnEventMapper.selectList(
                new LambdaQueryWrapper<OmsSalesReturnEventEntity>()
                        .eq(OmsSalesReturnEventEntity::getOrderDetailId, detail.getId()));
        BigDecimal total = BigDecimal.ZERO;
        for (OmsSalesReturnEventEntity event : events) {
            require(Objects.equals(event.getOrderCode(), detail.getOrderCode())
                            && Objects.equals(event.getFactoryCode(), detail.getFactoryCode())
                            && Objects.equals(event.getProductCode(), detail.getProductCode())
                            && "SUCCEEDED".equals(event.getStatus())
                            && event.getReturnedNum() != null && event.getReturnedNum().signum() > 0,
                    "OMS交付行退货永久收件箱来源、状态或数量异常");
            total = total.add(event.getReturnedNum());
        }
        return new ReturnTotals(total, events.size());
    }

    private CorrectionTotals correctionTotals(OmsOrderDetailsEntity detail) {
        List<OmsShipmentCorrectionEventEntity> events = correctionEventMapper.selectList(
                new LambdaQueryWrapper<OmsShipmentCorrectionEventEntity>()
                        .eq(OmsShipmentCorrectionEventEntity::getOrderDetailId, detail.getId()));
        BigDecimal total = BigDecimal.ZERO;
        for (OmsShipmentCorrectionEventEntity event : events) {
            require(Objects.equals(event.getOrderCode(), detail.getOrderCode())
                            && Objects.equals(event.getFactoryCode(), detail.getFactoryCode())
                            && Objects.equals(event.getProductCode(), detail.getProductCode())
                            && "SUCCEEDED".equals(event.getStatus())
                            && event.getCorrectedNum() != null && event.getCorrectedNum().signum() > 0,
                    "OMS交付行纠错永久收件箱来源、状态或数量异常");
            total = total.add(event.getCorrectedNum());
        }
        return new CorrectionTotals(total, events.size());
    }

    private OmsDeliveryEventCommand.Receipt receipt(OmsDeliveryEventEntity entity) {
        return new OmsDeliveryEventCommand.Receipt().setRequestId(entity.getRequestId())
                .setOrderDetailId(entity.getOrderDetailId()).setStatus(entity.getStatus())
                .setOmsDeliveredNum(entity.getOmsDeliveredNum())
                .setRequestFingerprint(entity.getRequestFingerprint())
                .setCompletedTime(entity.getCompletedTime());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("OMS发运事实指纹生成失败");
        }
    }

    private String required(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max,
                label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected)
                        || Objects.equals(selected, factory)),
                "当前工厂无权接收该发运事实");
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    private record InboxTotals(BigDecimal normalShipped, BigDecimal replacementShipped,
                               BigDecimal baseline, int normalCount, int replacementCount) {
    }

    private record ReturnTotals(BigDecimal returned, int count) {
    }

    private record CorrectionTotals(BigDecimal corrected, int count) {
    }
}
