package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.oms.dto.OmsCustomerReceiptEventCommand;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.dto.WmsOmsCustomerReceiptLedger;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsPlanBaseMapper;
import com.kitex.mom.wms.mapper.WmsOmsCustomerReceiptOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 客户实收可靠投递：固定事实、原号重试、永久回执和只读对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsOmsCustomerReceiptOutboxService {
    private final WmsOmsCustomerReceiptOutboxMapper mapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final com.kitex.mom.wms.mapper.WmsDeliverGoodsBaseMapper deliveryMapper;
    private final RemoteOmsOrderService omsService;
    private final PlatformTransactionManager transactionManager;

    public void enqueue(WmsShipmentCustodyEntity custody, List<WmsShipmentReceiptDetailEntity> rows) {
        require(custody != null && custody.getId() != null && custody.getOutBillId() != null && custody.getReceiptTime() != null && !rows.isEmpty(), "客户实收Outbox缺少保管链或实收明细");
        WmsShipmentReceiptDetailEntity first = rows.get(0);
        WmsDeliverGoodsPlanBaseEntity plan = planMapper.selectById(first.getPlanId());
        require(plan != null && plan.getOrderCodeDetails() != null, "客户实收无法对应OMS订单行");
        require(rows.stream().allMatch(x -> Objects.equals(x.getPlanId(), plan.getId()) && Objects.equals(x.getOrderCode(), plan.getOrderCode()) && Objects.equals(x.getOrderDetailId(), plan.getOrderCodeDetails()) && Objects.equals(x.getProductCode(), plan.getProductCode())), "同一实收事件包含不同计划或订单行");
        BigDecimal signed = rows.stream().map(WmsShipmentReceiptDetailEntity::getSignedNum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exception = rows.stream().map(x -> x.getRejectedNum().add(x.getDamagedNum()).add(x.getLostNum())).reduce(BigDecimal.ZERO, BigDecimal::add);
        WmsDeliverGoodsBaseEntity delivery = deliveryMapper.selectById(custody.getDeliveryId());
        require(delivery != null, "客户实收发货单来源缺失");
        String shipmentType = delivery.getExceptionResolutionCaseId() != null ? "REPLACEMENT" : "NORMAL";
        Long caseId = delivery.getExceptionResolutionCaseId();
        require(!"REPLACEMENT".equals(shipmentType) || ("1".equals(delivery.getReissueSign()) && delivery.getOriginalDeliveryId() != null), "异常补发客户实收缺少处置单或原发货来源");
        BigDecimal resolved = "REPLACEMENT".equals(shipmentType) ? signed : BigDecimal.ZERO;
        OmsCustomerReceiptEventCommand.Command c = new OmsCustomerReceiptEventCommand.Command().setRequestId("WMS-CUSTOMER-RECEIPT:" + custody.getId() + ":" + plan.getId()).setCustodyId(custody.getId()).setCustodyNo(custody.getCustodyNo()).setOutBillId(custody.getOutBillId()).setOutBillNo(custody.getOutBillNo()).setPlanId(plan.getId()).setPlanCode(plan.getDeliverPlanCode()).setOrderCode(plan.getOrderCode()).setOrderDetailId(plan.getOrderCodeDetails()).setProductCode(plan.getProductCode()).setSignedNum(signed).setExceptionNum(exception).setResolvedExceptionNum(resolved).setShipmentType(shipmentType).setResolutionCaseId(caseId).setReceiptTime(custody.getReceiptTime()).setFactoryCode(custody.getFactoryCode());
        String fp = hash(facts(c));
        WmsOmsCustomerReceiptOutboxEntity old = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getRequestId, c.getRequestId()), false);
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fp), "同一客户实收请求号已有不同事实");
            return;
        }
        WmsOmsCustomerReceiptOutboxEntity row = new WmsOmsCustomerReceiptOutboxEntity().setRequestId(c.getRequestId()).setRequestFingerprint(fp).setCustodyId(c.getCustodyId()).setCustodyNo(c.getCustodyNo()).setOutBillId(c.getOutBillId()).setOutBillNo(c.getOutBillNo()).setPlanId(c.getPlanId()).setPlanCode(c.getPlanCode()).setOrderCode(c.getOrderCode()).setOrderDetailId(c.getOrderDetailId()).setProductCode(c.getProductCode()).setSignedNum(signed).setExceptionNum(exception).setResolvedExceptionNum(resolved).setShipmentType(shipmentType).setResolutionCaseId(caseId).setReceiptTime(c.getReceiptTime()).setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now()).setReconcileStatus("PENDING").setNextReconcileTime(LocalDateTime.now()).setFactoryCode(c.getFactoryCode());
        require(mapper.insert(row) == 1, "客户实收OMS Outbox保存失败");
    }

    public List<WmsOmsCustomerReceiptOutboxEntity> listByCustody(Long id) {
        require(id != null, "保管链ID不能为空");
        String f = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getCustodyId, id).eq(StrUtil.isNotBlank(f), WmsOmsCustomerReceiptOutboxEntity::getFactoryCode, f).orderByAsc(WmsOmsCustomerReceiptOutboxEntity::getId));
    }

    public WmsOmsCustomerReceiptLedger snapshot(String orderCode, Long detailsId, String requestId) {
        WmsOmsCustomerReceiptOutboxEntity e = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getRequestId, requestId), false);
        require(e != null && Objects.equals(e.getOrderCode(), orderCode) && Objects.equals(e.getOrderDetailId(), detailsId), "WMS客户实收Outbox缺少指定同源请求");
        requireFactory(e.getFactoryCode());
        return new WmsOmsCustomerReceiptLedger().setRequestId(e.getRequestId()).setRequestFingerprint(e.getRequestFingerprint()).setCustodyId(e.getCustodyId()).setOutBillId(e.getOutBillId()).setPlanId(e.getPlanId()).setPlanCode(e.getPlanCode()).setOrderCode(e.getOrderCode()).setOrderDetailId(e.getOrderDetailId()).setProductCode(e.getProductCode()).setSignedNum(e.getSignedNum()).setExceptionNum(e.getExceptionNum()).setResolvedExceptionNum(Optional.ofNullable(e.getResolvedExceptionNum()).orElse(BigDecimal.ZERO)).setShipmentType(StrUtil.blankToDefault(e.getShipmentType(), "NORMAL")).setResolutionCaseId(e.getResolutionCaseId()).setFactoryCode(e.getFactoryCode());
    }

    public WmsOmsCustomerReceiptOutboxEntity retry(Long id) {
        WmsOmsCustomerReceiptOutboxEntity e = mapper.selectById(id);
        require(e != null, "客户实收事件不存在");
        requireFactory(e.getFactoryCode());
        return dispatch(id);
    }

    public int processDue(int limit) {
        List<WmsOmsCustomerReceiptOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))), new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().in(WmsOmsCustomerReceiptOutboxEntity::getStatus, "PENDING", "FAILED").le(WmsOmsCustomerReceiptOutboxEntity::getNextAttemptTime, LocalDateTime.now()).orderByAsc(WmsOmsCustomerReceiptOutboxEntity::getNextAttemptTime)).getRecords();
        int n = 0;
        for (WmsOmsCustomerReceiptOutboxEntity e : due)
            try {
                WmsOmsCustomerReceiptOutboxEntity x = e.getTenantId() == null ? dispatch(e.getId()) : TenantBroker.applyAs(e.getTenantId(), i -> dispatch(e.getId()));
                if ("SUCCEEDED".equals(x.getStatus())) n++;
            } catch (Exception ex) {
                log.error("客户实收OMS投递失败,eventId={}", e.getId(), ex);
            }
        return n;
    }

    public WmsOmsCustomerReceiptOutboxEntity reconcile(Long id) {
        WmsOmsCustomerReceiptOutboxEntity row = mapper.selectById(id);
        require(row != null && "SUCCEEDED".equals(row.getStatus()), "仅成功客户实收事件可对账");
        requireFactory(row.getFactoryCode());
        String result = "MATCHED", error = null;
        try {
            R<OmsCustomerReceiptEventCommand.Receipt> r = omsService.customerReceiptEventReceipt(row.getRequestId());
            OmsCustomerReceiptEventCommand.Receipt p = r == null ? null : r.getData();
            WmsOmsCustomerReceiptLedger ledger = tx().execute(s -> snapshot(row.getOrderCode(), row.getOrderDetailId(), row.getRequestId()));
            if (r == null || !r.isOk() || p == null || !"SUCCEEDED".equals(p.getStatus()) || !Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint()) || !Objects.equals(p.getRequestFingerprint(), row.getOmsReceiptFingerprint()) || p.getOmsSignedNum() == null || p.getOmsExceptionNum() == null || p.getOmsResolvedExceptionNum() == null || row.getOmsSignedNum() == null || row.getOmsExceptionNum() == null || row.getOmsResolvedExceptionNum() == null || p.getOmsSignedNum().compareTo(row.getOmsSignedNum()) != 0 || p.getOmsExceptionNum().compareTo(row.getOmsExceptionNum()) != 0 || p.getOmsResolvedExceptionNum().compareTo(row.getOmsResolvedExceptionNum()) != 0 || !Objects.equals(ledger.getRequestFingerprint(), row.getRequestFingerprint())) {
                result = "MISMATCHED";
                error = "OMS客户实收永久回执或WMS冻结来源账不一致";
            }
        } catch (Exception e) {
            result = "CHECK_FAILED";
            error = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        }
        String rr = result, ee = error;
        return tx().execute(s -> {
            WmsOmsCustomerReceiptOutboxEntity x = lock(id);
            x.setReconcileStatus(rr).setReconcileError(ee).setReconciledTime(LocalDateTime.now()).setNextReconcileTime("MATCHED".equals(rr) ? LocalDateTime.now().plusDays(1) : LocalDateTime.now().plusMinutes(30));
            require(mapper.updateById(x) == 1, "客户实收对账保存失败");
            return x;
        });
    }

    public int processReconciliationDue(int limit) {
        List<WmsOmsCustomerReceiptOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))), new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getStatus, "SUCCEEDED").le(WmsOmsCustomerReceiptOutboxEntity::getNextReconcileTime, LocalDateTime.now()).orderByAsc(WmsOmsCustomerReceiptOutboxEntity::getNextReconcileTime)).getRecords();
        int n = 0;
        for (WmsOmsCustomerReceiptOutboxEntity e : due)
            try {
                if (e.getTenantId() == null) reconcile(e.getId());
                else TenantBroker.applyAs(e.getTenantId(), i -> reconcile(e.getId()));
                n++;
            } catch (Exception ex) {
                log.error("客户实收OMS对账失败,eventId={}", e.getId(), ex);
            }
        return n;
    }

    private WmsOmsCustomerReceiptOutboxEntity dispatch(Long id) {
        WmsOmsCustomerReceiptOutboxEntity row = mapper.selectById(id);
        require(row != null, "客户实收事件不存在");
        requireFactory(row.getFactoryCode());
        if ("SUCCEEDED".equals(row.getStatus())) return row;
        try {
            OmsCustomerReceiptEventCommand.Command c = command(row);
            require(Objects.equals(row.getRequestFingerprint(), hash(facts(c))), "客户实收Outbox冻结事实已损坏");
            R<OmsCustomerReceiptEventCommand.Receipt> r = omsService.receiveCustomerReceiptEvent(c);
            OmsCustomerReceiptEventCommand.Receipt p = r == null ? null : r.getData();
            require(r != null && r.isOk() && p != null && "SUCCEEDED".equals(p.getStatus()) && Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint()) && p.getOmsSignedNum() != null && p.getOmsExceptionNum() != null && p.getOmsResolvedExceptionNum() != null, "OMS未返回匹配的客户实收永久回执");
            return tx().execute(s -> {
                WmsOmsCustomerReceiptOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    x.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null).setOmsSignedNum(p.getOmsSignedNum()).setOmsExceptionNum(p.getOmsExceptionNum()).setOmsResolvedExceptionNum(p.getOmsResolvedExceptionNum()).setOmsReceiptFingerprint(p.getRequestFingerprint()).setCompletedTime(p.getCompletedTime());
                    require(mapper.updateById(x) == 1, "客户实收回执保存失败");
                }
                return x;
            });
        } catch (Exception e) {
            return tx().execute(s -> {
                WmsOmsCustomerReceiptOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    int a = Optional.ofNullable(x.getAttemptCount()).orElse(0) + 1;
                    String m = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
                    x.setStatus(a >= 5 ? "NEEDS_MANUAL" : "FAILED").setAttemptCount(a).setNextAttemptTime(a >= 5 ? null : LocalDateTime.now().plusSeconds(Math.min(1800, 60L << Math.min(a, 5)))).setLastError(m.substring(0, Math.min(1000, m.length())));
                    require(mapper.updateById(x) == 1, "客户实收失败原因保存失败");
                }
                return x;
            });
        }
    }

    private OmsCustomerReceiptEventCommand.Command command(WmsOmsCustomerReceiptOutboxEntity r) {
        return new OmsCustomerReceiptEventCommand.Command().setRequestId(r.getRequestId()).setCustodyId(r.getCustodyId()).setCustodyNo(r.getCustodyNo()).setOutBillId(r.getOutBillId()).setOutBillNo(r.getOutBillNo()).setPlanId(r.getPlanId()).setPlanCode(r.getPlanCode()).setOrderCode(r.getOrderCode()).setOrderDetailId(r.getOrderDetailId()).setProductCode(r.getProductCode()).setSignedNum(r.getSignedNum()).setExceptionNum(r.getExceptionNum()).setResolvedExceptionNum(Optional.ofNullable(r.getResolvedExceptionNum()).orElse(BigDecimal.ZERO)).setShipmentType(StrUtil.blankToDefault(r.getShipmentType(), "NORMAL")).setResolutionCaseId(r.getResolutionCaseId()).setReceiptTime(r.getReceiptTime()).setFactoryCode(r.getFactoryCode());
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

    private WmsOmsCustomerReceiptOutboxEntity lock(Long id) {
        WmsOmsCustomerReceiptOutboxEntity e = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerReceiptOutboxEntity>().eq(WmsOmsCustomerReceiptOutboxEntity::getId, id).last("FOR UPDATE"));
        require(e != null, "客户实收事件不存在");
        return e;
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("客户实收事实指纹生成失败");
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void requireFactory(String f) {
        String s = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(s) || Objects.equals(s, f)), "当前工厂无权操作客户实收事件");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
