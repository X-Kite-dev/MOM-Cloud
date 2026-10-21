package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.oms.dto.OmsShipmentCorrectionEventCommand;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.dto.WmsOmsShipmentCorrectionLedger;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsOmsShipmentCorrectionOutboxEntity;
import com.kitex.mom.wms.entity.WmsSalesShipmentCorrectionEntity;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsPlanBaseMapper;
import com.kitex.mom.wms.mapper.WmsOmsShipmentCorrectionOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 销售发运纠错同事务Outbox；原号重试，成功后永久对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsOmsShipmentCorrectionOutboxService {
    private final WmsOmsShipmentCorrectionOutboxMapper mapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final RemoteOmsOrderService omsService;
    private final PlatformTransactionManager transactionManager;

    public void enqueue(WmsSalesShipmentCorrectionEntity correction, WmsDeliverGoodsPlanBaseEntity plan,
                        BigDecimal correctedNum) {
        require(correction != null && correction.getId() != null && StrUtil.isNotBlank(correction.getCorrectionNo())
                        && correction.getOutBillId() != null && StrUtil.isNotBlank(correction.getOutBillNo())
                        && plan != null && plan.getId() != null && plan.getOrderCodeDetails() != null
                        && StrUtil.isNotBlank(plan.getOrderCode()) && StrUtil.isNotBlank(plan.getFactoryCode())
                        && StrUtil.isNotBlank(plan.getProductCode()) && correctedNum != null && correctedNum.signum() > 0,
                "OMS发运纠错Outbox缺少纠错单、出库单、原计划、订单行或正数量");
        OmsShipmentCorrectionEventCommand.Command c = new OmsShipmentCorrectionEventCommand.Command()
                .setRequestId("WMS-SHIP-CORR:" + correction.getId() + ":" + plan.getId())
                .setCorrectionId(correction.getId()).setCorrectionNo(correction.getCorrectionNo())
                .setWmsOutBillId(correction.getOutBillId()).setWmsOutBillNo(correction.getOutBillNo())
                .setWmsPlanId(plan.getId()).setWmsPlanCode(plan.getDeliverPlanCode())
                .setOrderCode(plan.getOrderCode()).setOrderDetailId(plan.getOrderCodeDetails())
                .setFactoryCode(plan.getFactoryCode()).setProductCode(plan.getProductCode())
                .setCorrectedNum(correctedNum).setCorrectedTime(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        String fingerprint = hash(facts(c));
        WmsOmsShipmentCorrectionOutboxEntity old = mapper.selectOne(new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>()
                .eq(WmsOmsShipmentCorrectionOutboxEntity::getRequestId, c.getRequestId()), false);
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fingerprint), "同一发运纠错请求号已有不同事实");
            return;
        }
        WmsOmsShipmentCorrectionOutboxEntity row = new WmsOmsShipmentCorrectionOutboxEntity()
                .setRequestId(c.getRequestId()).setRequestFingerprint(fingerprint)
                .setCorrectionId(c.getCorrectionId()).setCorrectionNo(c.getCorrectionNo())
                .setWmsOutBillId(c.getWmsOutBillId()).setWmsOutBillNo(c.getWmsOutBillNo())
                .setWmsPlanId(c.getWmsPlanId()).setWmsPlanCode(c.getWmsPlanCode()).setOrderCode(c.getOrderCode())
                .setOrderDetailId(c.getOrderDetailId()).setProductCode(c.getProductCode())
                .setCorrectedNum(c.getCorrectedNum()).setCorrectedTime(c.getCorrectedTime())
                .setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setReconcileStatus("PENDING").setNextReconcileTime(LocalDateTime.now()).setFactoryCode(c.getFactoryCode());
        require(mapper.insert(row) == 1, "销售发运纠错OMS Outbox保存失败");
    }

    public WmsOmsShipmentCorrectionLedger snapshot(String orderCode, Long detailsId, String requestId) {
        List<WmsDeliverGoodsPlanBaseEntity> plans = planMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, orderCode)
                .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, detailsId).last("FOR SHARE"));
        require(plans.size() == 1, "WMS发运纠错原计划缺失或订单行来源不唯一");
        WmsDeliverGoodsPlanBaseEntity plan = plans.get(0);
        requireFactory(plan.getFactoryCode());
        List<WmsOmsShipmentCorrectionOutboxEntity> events = mapper.selectList(
                new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>()
                        .eq(WmsOmsShipmentCorrectionOutboxEntity::getWmsPlanId, plan.getId()).orderByAsc(WmsOmsShipmentCorrectionOutboxEntity::getId));
        BigDecimal total = BigDecimal.ZERO;
        WmsOmsShipmentCorrectionOutboxEntity requested = null;
        for (WmsOmsShipmentCorrectionOutboxEntity e : events) {
            require(Objects.equals(e.getFactoryCode(), plan.getFactoryCode()) && Objects.equals(e.getOrderCode(), orderCode)
                    && Objects.equals(e.getOrderDetailId(), detailsId) && Objects.equals(e.getProductCode(), plan.getProductCode())
                    && e.getCorrectedNum() != null && e.getCorrectedNum().signum() > 0, "WMS发运纠错Outbox来源存在歧义");
            total = total.add(e.getCorrectedNum());
            if (Objects.equals(e.getRequestId(), requestId)) requested = e;
        }
        require(StrUtil.isBlank(requestId) || requested != null, "WMS发运纠错Outbox缺少指定请求号");
        WmsOmsShipmentCorrectionLedger result = new WmsOmsShipmentCorrectionLedger()
                .setPlanId(plan.getId()).setPlanCode(plan.getDeliverPlanCode()).setOrderCode(orderCode)
                .setOrderDetailId(detailsId).setFactoryCode(plan.getFactoryCode()).setProductCode(plan.getProductCode())
                .setPlanDeliveredNum(plan.getDeliveredNum()).setOutboxCorrectedNum(total).setOutboxCount(events.size());
        if (requested != null)
            result.setRequestId(requested.getRequestId()).setRequestFingerprint(requested.getRequestFingerprint())
                    .setRequestCorrectionId(requested.getCorrectionId()).setRequestCorrectedNum(requested.getCorrectedNum());
        return result;
    }

    public List<WmsOmsShipmentCorrectionOutboxEntity> listByCorrection(Long correctionId) {
        require(correctionId != null, "纠错单ID不能为空");
        String factory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>()
                .eq(WmsOmsShipmentCorrectionOutboxEntity::getCorrectionId, correctionId)
                .eq(StrUtil.isNotBlank(factory), WmsOmsShipmentCorrectionOutboxEntity::getFactoryCode, factory)
                .orderByAsc(WmsOmsShipmentCorrectionOutboxEntity::getId));
    }

    public WmsOmsShipmentCorrectionOutboxEntity retry(Long id) {
        WmsOmsShipmentCorrectionOutboxEntity r = mapper.selectById(id);
        require(r != null, "纠错事件不存在");
        requireFactory(r.getFactoryCode());
        return dispatch(id);
    }

    public int processDue(int limit) {
        List<WmsOmsShipmentCorrectionOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>().in(WmsOmsShipmentCorrectionOutboxEntity::getStatus, "PENDING", "FAILED")
                        .le(WmsOmsShipmentCorrectionOutboxEntity::getNextAttemptTime, LocalDateTime.now()).orderByAsc(WmsOmsShipmentCorrectionOutboxEntity::getNextAttemptTime)).getRecords();
        int ok = 0;
        for (WmsOmsShipmentCorrectionOutboxEntity r : due)
            try {
                WmsOmsShipmentCorrectionOutboxEntity x = r.getTenantId() == null ? dispatch(r.getId()) : TenantBroker.applyAs(r.getTenantId(), i -> dispatch(r.getId()));
                if ("SUCCEEDED".equals(x.getStatus())) ok++;
            } catch (Exception e) {
                log.error("发运纠错OMS调度失败,eventId={}", r.getId(), e);
            }
        return ok;
    }

    public WmsOmsShipmentCorrectionOutboxEntity reconcile(Long id) {
        WmsOmsShipmentCorrectionOutboxEntity row = mapper.selectById(id);
        require(row != null && "SUCCEEDED".equals(row.getStatus()), "仅成功纠错事件可对账");
        requireFactory(row.getFactoryCode());
        String result = "MATCHED", error = null;
        try {
            R<OmsShipmentCorrectionEventCommand.Receipt> r = omsService.shipmentCorrectionEventReceipt(row.getRequestId());
            OmsShipmentCorrectionEventCommand.Receipt p = r == null ? null : r.getData();
            if (r == null || !r.isOk() || p == null || !"SUCCEEDED".equals(p.getStatus()) || !Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint())
                    || !Objects.equals(p.getRequestFingerprint(), row.getOmsReceiptFingerprint()) || p.getOmsDeliveredNum() == null || row.getOmsDeliveredNum() == null || p.getOmsDeliveredNum().compareTo(row.getOmsDeliveredNum()) != 0) {
                result = "MISMATCHED";
                error = "OMS纠错永久回执不一致";
            } else {
                WmsOmsShipmentCorrectionLedger ledger = tx().execute(s -> snapshot(row.getOrderCode(), row.getOrderDetailId(), row.getRequestId()));
                if (ledger == null || !Objects.equals(ledger.getRequestFingerprint(), row.getRequestFingerprint())
                        || !Objects.equals(ledger.getRequestCorrectionId(), row.getCorrectionId())
                        || ledger.getRequestCorrectedNum() == null || ledger.getRequestCorrectedNum().compareTo(row.getCorrectedNum()) != 0) {
                    result = "MISMATCHED";
                    error = "WMS纠错来源账与冻结请求不一致";
                }
            }
        } catch (Exception e) {
            result = "CHECK_FAILED";
            error = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        }
        String finalResult = result, finalError = error;
        return tx().execute(s -> {
            WmsOmsShipmentCorrectionOutboxEntity x = lock(id);
            x.setReconcileStatus(finalResult).setReconcileError(finalError).setReconciledTime(LocalDateTime.now()).setNextReconcileTime("MATCHED".equals(finalResult) ? LocalDateTime.now().plusDays(1) : LocalDateTime.now().plusMinutes(30));
            require(mapper.updateById(x) == 1, "纠错对账保存失败");
            return x;
        });
    }

    public int processReconciliationDue(int limit) {
        List<WmsOmsShipmentCorrectionOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))), new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>().eq(WmsOmsShipmentCorrectionOutboxEntity::getStatus, "SUCCEEDED").le(WmsOmsShipmentCorrectionOutboxEntity::getNextReconcileTime, LocalDateTime.now()).orderByAsc(WmsOmsShipmentCorrectionOutboxEntity::getNextReconcileTime)).getRecords();
        int n = 0;
        for (WmsOmsShipmentCorrectionOutboxEntity r : due)
            try {
                if (r.getTenantId() == null) reconcile(r.getId());
                else TenantBroker.applyAs(r.getTenantId(), i -> reconcile(r.getId()));
                n++;
            } catch (Exception e) {
                log.error("发运纠错OMS对账失败,eventId={}", r.getId(), e);
            }
        return n;
    }

    private WmsOmsShipmentCorrectionOutboxEntity dispatch(Long id) {
        WmsOmsShipmentCorrectionOutboxEntity row = mapper.selectById(id);
        require(row != null, "纠错事件不存在");
        requireFactory(row.getFactoryCode());
        if ("SUCCEEDED".equals(row.getStatus())) return row;
        try {
            OmsShipmentCorrectionEventCommand.Command c = command(row);
            require(Objects.equals(row.getRequestFingerprint(), hash(facts(c))), "纠错Outbox冻结事实已损坏");
            R<OmsShipmentCorrectionEventCommand.Receipt> r = omsService.receiveShipmentCorrectionEvent(c);
            OmsShipmentCorrectionEventCommand.Receipt p = r == null ? null : r.getData();
            require(r != null && r.isOk() && p != null && "SUCCEEDED".equals(p.getStatus()) && Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint()) && p.getOmsDeliveredNum() != null, "OMS未返回匹配的纠错永久回执");
            return tx().execute(s -> {
                WmsOmsShipmentCorrectionOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    x.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null).setOmsDeliveredNum(p.getOmsDeliveredNum()).setOmsReceiptFingerprint(p.getRequestFingerprint()).setCompletedTime(p.getCompletedTime());
                    require(mapper.updateById(x) == 1, "纠错回执保存失败");
                }
                return x;
            });
        } catch (Exception e) {
            return tx().execute(s -> {
                WmsOmsShipmentCorrectionOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    int a = (x.getAttemptCount() == null ? 0 : x.getAttemptCount()) + 1;
                    String m = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
                    x.setStatus(a >= 5 ? "NEEDS_MANUAL" : "FAILED").setAttemptCount(a).setNextAttemptTime(a >= 5 ? null : LocalDateTime.now().plusSeconds(Math.min(1800, 60L << Math.min(a, 5)))).setLastError(m.substring(0, Math.min(1000, m.length())));
                    require(mapper.updateById(x) == 1, "纠错失败原因保存失败");
                }
                return x;
            });
        }
    }

    private WmsOmsShipmentCorrectionOutboxEntity lock(Long id) {
        WmsOmsShipmentCorrectionOutboxEntity r = mapper.selectOne(new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>().eq(WmsOmsShipmentCorrectionOutboxEntity::getId, id).last("FOR UPDATE"));
        require(r != null, "纠错事件不存在");
        return r;
    }

    private OmsShipmentCorrectionEventCommand.Command command(WmsOmsShipmentCorrectionOutboxEntity r) {
        return new OmsShipmentCorrectionEventCommand.Command().setRequestId(r.getRequestId()).setCorrectionId(r.getCorrectionId()).setCorrectionNo(r.getCorrectionNo()).setWmsOutBillId(r.getWmsOutBillId()).setWmsOutBillNo(r.getWmsOutBillNo()).setWmsPlanId(r.getWmsPlanId()).setWmsPlanCode(r.getWmsPlanCode()).setOrderCode(r.getOrderCode()).setOrderDetailId(r.getOrderDetailId()).setFactoryCode(r.getFactoryCode()).setProductCode(r.getProductCode()).setCorrectedNum(r.getCorrectedNum()).setCorrectedTime(r.getCorrectedTime());
    }

    private String facts(OmsShipmentCorrectionEventCommand.Command c) {
        return JSONUtil.toJsonStr(Arrays.asList(c.getCorrectionId(), c.getCorrectionNo(), c.getWmsOutBillId(), c.getWmsOutBillNo(), c.getWmsPlanId(), c.getWmsPlanCode(), c.getOrderCode(), c.getOrderDetailId(), c.getFactoryCode(), c.getProductCode(), c.getCorrectedNum().stripTrailingZeros().toPlainString(), c.getCorrectedTime()));
    }

    private String hash(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("纠错事实指纹生成失败");
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void requireFactory(String f) {
        String s = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(s) || Objects.equals(s, f)), "当前工厂无权操作该纠错事件");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
