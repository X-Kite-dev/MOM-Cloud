package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.dto.OmsSalesReturnEventCommand;
import com.kitex.mom.oms.entity.OmsOrderBaseEntity;
import com.kitex.mom.oms.entity.OmsOrderBaseTrackEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.entity.OmsSalesReturnEventEntity;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.mapper.OmsOrderBaseMapper;
import com.kitex.mom.plan.mapper.OmsSalesReturnEventMapper;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.kitex.mom.wms.api.feign.RemoteDeliverService;
import com.kitex.mom.wms.dto.WmsOmsSalesReturnLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 销售退货实收OMS永久收件箱：冲减原交付行，并结合客户异常解决账重算履约终态。
 */
@Service
@RequiredArgsConstructor
public class OmsSalesReturnEventService {
    private final OmsSalesReturnEventMapper eventMapper;
    private final OmsOrderBaseTrackService trackService;
    private final OmsOrderDetailsService detailsService;
    private final OmsOrderBaseMapper baseMapper;
    private final RemoteDeliverService deliverService;
    private final OmsDeliveryEventService deliveryEventService;

    public OmsSalesReturnEventCommand.Receipt findReceipt(String requestId) {
        String key = required(requestId, 64, "WMS退货请求号");
        OmsSalesReturnEventEntity event = eventMapper.selectOne(new LambdaQueryWrapper<OmsSalesReturnEventEntity>()
                .eq(OmsSalesReturnEventEntity::getRequestId, key));
        if (event == null) return null;
        requireFactory(event.getFactoryCode());
        return receipt(event);
    }

    @Transactional(rollbackFor = Exception.class)
    public OmsSalesReturnEventCommand.Receipt receive(OmsSalesReturnEventCommand.Command command) {
        require(command != null && command.getWmsInBillId() != null && command.getWmsInBillDetailId() != null
                        && command.getWmsReturnId() != null && command.getWmsReturnDetailId() != null
                        && command.getWmsPlanId() != null && command.getOrderDetailId() != null
                        && command.getReturnedNum() != null && command.getReturnedNum().signum() > 0
                        && command.getReturnedTime() != null,
                "WMS退货事实缺少入库、退货申请、原计划、交付行、正数量或实收时间");
        String requestId = required(command.getRequestId(), 64, "WMS退货请求号");
        String orderCode = required(command.getOrderCode(), 100, "订单号");
        String factoryCode = required(command.getFactoryCode(), 100, "工厂");
        requireFactory(factoryCode);
        require(StrUtil.isNotBlank(command.getWmsInBillNo()) && StrUtil.isNotBlank(command.getWmsPlanCode())
                && StrUtil.isNotBlank(command.getProductCode()), "WMS退货事实缺少入库单号、计划号或产品编码");
        String fingerprint = hash(facts(command));
        eventMapper.lockKey("REQUEST:" + requestId);
        OmsSalesReturnEventEntity old = eventMapper.selectOne(new LambdaQueryWrapper<OmsSalesReturnEventEntity>()
                .eq(OmsSalesReturnEventEntity::getRequestId, requestId));
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint), "WMS退货请求号已被不同事实使用");
            requireFactory(old.getFactoryCode());
            return receipt(old);
        }
        require(eventMapper.selectCount(new LambdaQueryWrapper<OmsSalesReturnEventEntity>()
                        .eq(OmsSalesReturnEventEntity::getWmsInBillDetailId, command.getWmsInBillDetailId())) == 0,
                "同一WMS退货入库明细已由另一请求号冲减，禁止重复");

        OmsOrderBaseTrackEntity track = trackService.getOne(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode).last("FOR UPDATE"), false);
        require(track != null && trackService.count(new LambdaQueryWrapper<OmsOrderBaseTrackEntity>()
                        .eq(OmsOrderBaseTrackEntity::getOrderCode, orderCode)) == 1
                        && Objects.equals(track.getFactoryCode(), factoryCode),
                "WMS退货事实无法对应唯一同工厂OMS跟踪订单");
        OmsOrderDetailsEntity detail = detailsService.getOne(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getId, command.getOrderDetailId()).last("FOR UPDATE"), false);
        require(detail != null && Objects.equals(detail.getOrderCode(), orderCode)
                        && Objects.equals(detail.getFactoryCode(), factoryCode)
                        && Objects.equals(detail.getProductCode(), command.getProductCode()),
                "WMS退货事实与OMS原交付行ID、订单、工厂或产品不一致");
        require(detail.getDeliveredNum() != null && detail.getDeliverNum() != null
                        && detail.getDeliveredNum().signum() >= 0,
                "OMS交付行数量基线缺失，须先对账");
        OmsDeliveryEventCommand.LineLedger currentLedger = deliveryEventService.lineLedger(orderCode, detail.getId());
        require(currentLedger != null && currentLedger.getOmsDeliveredNum() != null
                        && currentLedger.getInboxReturnedNum() != null
                        && currentLedger.getOmsDeliveredNum().compareTo(detail.getDeliveredNum()) == 0,
                "OMS交付行与发运/退货永久收件箱数量账不守恒");

        R<WmsOmsSalesReturnLedger> response = deliverService.omsSalesReturnLedger(orderCode, detail.getId(), requestId);
        require(response != null && response.isOk() && response.getData() != null,
                "WMS退货实收与原发货计划数量账不可核验");
        WmsOmsSalesReturnLedger ledger = response.getData();
        require(Objects.equals(ledger.getPlanId(), command.getWmsPlanId())
                        && Objects.equals(ledger.getPlanCode(), command.getWmsPlanCode())
                        && Objects.equals(ledger.getOrderCode(), orderCode)
                        && Objects.equals(ledger.getOrderDetailId(), detail.getId())
                        && Objects.equals(ledger.getFactoryCode(), factoryCode)
                        && Objects.equals(ledger.getProductCode(), command.getProductCode())
                        && Objects.equals(ledger.getRequestId(), requestId)
                        && Objects.equals(ledger.getRequestWmsInBillDetailId(), command.getWmsInBillDetailId())
                        && Objects.equals(ledger.getRequestResolutionCaseId(), command.getResolutionCaseId())
                        && Objects.equals(ledger.getRequestFingerprint(), fingerprint)
                        && ledger.getRequestReturnedNum() != null
                        && ledger.getRequestReturnedNum().compareTo(command.getReturnedNum()) == 0
                        && ledger.getPlanReturnedNum() != null && ledger.getPlanDeliveredNum() != null
                        && ledger.getPlanReturnedNum().compareTo(ledger.getPlanDeliveredNum()) <= 0,
                "WMS退货Outbox、原发货计划与OMS冲减命令不一致");

        BigDecimal alreadyReturned = currentLedger.getInboxReturnedNum();
        require(ledger.getOutboxReturnedNum() != null
                        && ledger.getOutboxReturnedNum().compareTo(alreadyReturned.add(command.getReturnedNum())) >= 0,
                "OMS已收退货事件超过WMS退货Outbox总量");
        BigDecimal after = detail.getDeliveredNum().subtract(command.getReturnedNum());
        require(after.signum() >= 0, "销售退货冲减将使OMS已交付量小于零，禁止超退");
        detail.setDeliveredNum(after);
        BigDecimal currentExceptionReturn = command.getResolutionCaseId() == null
                ? BigDecimal.ZERO : command.getReturnedNum();
        detail.setState(canComplete(detail, currentExceptionReturn) ? OrderStateEnum.COMPLETE.getCode()
                : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(detailsService.updateById(detail), "OMS交付行退货冲减保存失败");

        List<OmsOrderBaseEntity> bases = baseMapper.selectList(new LambdaQueryWrapper<OmsOrderBaseEntity>()
                .eq(OmsOrderBaseEntity::getOrderCode, orderCode).last("FOR UPDATE"));
        require(bases.size() == 1 && Objects.equals(bases.get(0).getFactoryCode(), factoryCode),
                "OMS订单主记录缺失、重复或跨工厂");
        List<OmsOrderDetailsEntity> lines = detailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getOrderCode, orderCode));
        require(!lines.isEmpty() && lines.stream().allMatch(line -> Objects.equals(line.getFactoryCode(), factoryCode)
                        && line.getDeliverNum() != null && line.getDeliveredNum() != null),
                "OMS交付行数量或工厂不完整，禁止恢复订单状态");
        boolean orderComplete = lines.stream().allMatch(line -> canComplete(line,
                Objects.equals(line.getId(), detail.getId()) ? currentExceptionReturn : BigDecimal.ZERO));
        OmsOrderBaseEntity base = bases.get(0);
        base.setOrderState(orderComplete ? OrderStateEnum.COMPLETE.getCode()
                : OrderStateEnum.ISSUED_DELIVER.getCode());
        require(baseMapper.updateById(base) == 1, "OMS订单退货后履约状态保存失败");
        track.setOrderState(base.getOrderState());
        require(trackService.updateById(track), "OMS订单跟踪退货后履约状态保存失败");

        OmsSalesReturnEventEntity saved = new OmsSalesReturnEventEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setWmsInBillId(command.getWmsInBillId()).setWmsInBillDetailId(command.getWmsInBillDetailId())
                .setWmsInBillNo(command.getWmsInBillNo()).setWmsReturnId(command.getWmsReturnId())
                .setResolutionCaseId(command.getResolutionCaseId())
                .setWmsReturnDetailId(command.getWmsReturnDetailId()).setWmsPlanId(command.getWmsPlanId())
                .setWmsPlanCode(command.getWmsPlanCode()).setOrderCode(orderCode)
                .setOrderDetailId(detail.getId()).setProductCode(command.getProductCode())
                .setReturnedNum(command.getReturnedNum()).setReturnedTime(command.getReturnedTime())
                .setOmsDeliveredNum(after).setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now())
                .setFactoryCode(factoryCode);
        require(eventMapper.insert(saved) == 1, "OMS退货事实永久回执保存失败");
        return receipt(saved);
    }

    private OmsSalesReturnEventCommand.Receipt receipt(OmsSalesReturnEventEntity entity) {
        return new OmsSalesReturnEventCommand.Receipt().setRequestId(entity.getRequestId())
                .setOrderDetailId(entity.getOrderDetailId()).setStatus(entity.getStatus())
                .setOmsDeliveredNum(entity.getOmsDeliveredNum())
                .setRequestFingerprint(entity.getRequestFingerprint())
                .setCompletedTime(entity.getCompletedTime());
    }

    private String facts(OmsSalesReturnEventCommand.Command c) {
        List<Object> values = new ArrayList<>(Arrays.asList(c.getWmsInBillId(), c.getWmsInBillDetailId(), c.getWmsInBillNo(),
                c.getWmsReturnId(), c.getWmsReturnDetailId(), c.getWmsPlanId(), c.getWmsPlanCode(),
                c.getOrderCode(), c.getOrderDetailId(), c.getFactoryCode(), c.getProductCode(),
                c.getReturnedNum().stripTrailingZeros().toPlainString(), c.getReturnedTime()));
        if (c.getResolutionCaseId() != null) values.add(c.getResolutionCaseId());
        return JSONUtil.toJsonStr(values);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("OMS退货事实指纹生成失败");
        }
    }

    private String required(String value, int max, String label) {
        String trimmed = value == null ? null : value.trim();
        require(StrUtil.isNotBlank(trimmed) && trimmed.length() <= max, label + "不能为空且不能超过" + max + "字");
        return trimmed;
    }

    private boolean canComplete(OmsOrderDetailsEntity line, BigDecimal pendingExceptionReturn) {
        BigDecimal resolved = zero(line.getCustomerResolvedExceptionNum());
        BigDecimal exceptionReturned = eventMapper.selectList(new LambdaQueryWrapper<OmsSalesReturnEventEntity>()
                        .eq(OmsSalesReturnEventEntity::getOrderDetailId, line.getId())
                        .isNotNull(OmsSalesReturnEventEntity::getResolutionCaseId))
                .stream().map(OmsSalesReturnEventEntity::getReturnedNum).map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(zero(pendingExceptionReturn));
        BigDecimal exceptionReturnCredit = resolved.min(exceptionReturned);
        return line.getDeliverNum() != null && line.getDeliveredNum() != null
                && line.getDeliveredNum().add(exceptionReturnCredit).compareTo(line.getDeliverNum()) >= 0
                && zero(line.getCustomerExceptionNum()).subtract(resolved).signum() == 0
                && zero(line.getCustomerSignedNum()).add(zero(line.getCustomerSettlementResolvedNum()))
                .compareTo(line.getDeliverNum()) >= 0;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(selected, factory)),
                "当前工厂无权接收该退货事实");
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
