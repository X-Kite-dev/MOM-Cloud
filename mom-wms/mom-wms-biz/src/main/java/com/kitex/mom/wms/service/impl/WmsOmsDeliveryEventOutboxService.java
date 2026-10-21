package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.oms.dto.OmsDeliveryEventCommand;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.dto.WmsOmsDeliveryLedger;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsOmsDeliveryEventOutboxEntity;
import com.kitex.mom.wms.entity.WmsOmsShipmentCorrectionOutboxEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsPlanBaseMapper;
import com.kitex.mom.wms.mapper.WmsOmsDeliveryEventOutboxMapper;
import com.kitex.mom.wms.mapper.WmsOmsShipmentCorrectionOutboxMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
 * WMS销售出库同事务Outbox；异步投递OMS永久收件箱，失败留账并有界重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsOmsDeliveryEventOutboxService {
    private final WmsOmsDeliveryEventOutboxMapper mapper;
    private final WmsOutBillMapper billMapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final WmsOmsShipmentCorrectionOutboxMapper correctionMapper;
    private final RemoteOmsOrderService omsService;
    private final PlatformTransactionManager transactionManager;

    /**
     * 必须在出库审核的WMS事务内调用，出库事实和待投递事实一起提交。
     */
    public void enqueue(WmsOutBillEntity bill, WmsDeliverGoodsPlanBaseEntity plan, BigDecimal shippedNum) {
        enqueue(bill, plan, shippedNum, "NORMAL", null);
    }

    /**
     * 补发实际出库仍留永久事件，但不再次累计OMS原订单净交付量。
     */
    public void enqueueReplacement(WmsOutBillEntity bill, WmsDeliverGoodsPlanBaseEntity plan,
                                   BigDecimal shippedNum, Long resolutionCaseId) {
        require(resolutionCaseId != null, "补发出库缺少客户异常处置单来源");
        enqueue(bill, plan, shippedNum, "REPLACEMENT", resolutionCaseId);
    }

    private void enqueue(WmsOutBillEntity bill, WmsDeliverGoodsPlanBaseEntity plan,
                         BigDecimal shippedNum, String shipmentType, Long resolutionCaseId) {
        require(bill != null && bill.getId() != null && StrUtil.isNotBlank(bill.getBillNo())
                        && bill.getOutTime() != null && plan != null && plan.getId() != null
                        && plan.getOrderCodeDetails() != null && StrUtil.isNotBlank(plan.getOrderCode())
                        && StrUtil.isNotBlank(plan.getFactoryCode()) && StrUtil.isNotBlank(plan.getProductCode())
                        && shippedNum != null && shippedNum.signum() > 0,
                "OMS来源发运Outbox缺少出库单、计划、订单行或正数量");
        require(fitsLedgerQuantity(shippedNum),
                "出库数量超过OMS发运账本支持的24位/6位小数精度，禁止静默舍入");
        require(Objects.equals(bill.getFactoryCode(), plan.getFactoryCode()),
                "出库单与OMS来源发货计划工厂不一致");
        OmsDeliveryEventCommand.Command command = new OmsDeliveryEventCommand.Command()
                .setRequestId(("REPLACEMENT".equals(shipmentType) ? "WMS-REPL:" : "WMS-SHIP:")
                        + bill.getId() + ":" + plan.getId())
                .setWmsBillId(bill.getId()).setWmsBillNo(bill.getBillNo())
                .setWmsPlanId(plan.getId()).setWmsPlanCode(plan.getDeliverPlanCode())
                .setOrderCode(plan.getOrderCode()).setOrderDetailId(plan.getOrderCodeDetails())
                .setFactoryCode(plan.getFactoryCode()).setProductCode(plan.getProductCode())
                .setShippedNum(shippedNum).setShippedTime(bill.getOutTime().truncatedTo(ChronoUnit.MICROS))
                .setShipmentType(shipmentType).setResolutionCaseId(resolutionCaseId);
        String fingerprint = hash(facts(command));
        WmsOmsDeliveryEventOutboxEntity old = mapper.selectOne(
                new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                        .eq(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, bill.getId())
                        .eq(WmsOmsDeliveryEventOutboxEntity::getWmsPlanId, plan.getId()));
        if (old != null) {
            require(Objects.equals(old.getRequestId(), command.getRequestId())
                            && Objects.equals(old.getRequestFingerprint(), fingerprint),
                    "同一出库单/计划已有不同OMS回传事实，禁止覆盖");
            return;
        }
        WmsOmsDeliveryEventOutboxEntity saved = new WmsOmsDeliveryEventOutboxEntity()
                .setRequestId(command.getRequestId()).setRequestFingerprint(fingerprint)
                .setWmsBillId(command.getWmsBillId()).setWmsBillNo(command.getWmsBillNo())
                .setWmsPlanId(command.getWmsPlanId()).setWmsPlanCode(command.getWmsPlanCode())
                .setOrderCode(command.getOrderCode()).setOrderDetailId(command.getOrderDetailId())
                .setProductCode(command.getProductCode()).setShippedNum(command.getShippedNum())
                .setShippedTime(command.getShippedTime()).setShipmentType(command.getShipmentType())
                .setResolutionCaseId(command.getResolutionCaseId()).setStatus("PENDING")
                .setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setReconcileStatus("PENDING").setNextReconcileTime(LocalDateTime.now())
                .setFactoryCode(command.getFactoryCode());
        require(mapper.insert(saved) == 1, "WMS发运事实Outbox保存失败");
    }

    public boolean hasBillEvents(Long billId) {
        return billId != null && mapper.selectCount(new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                .eq(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, billId)) > 0;
    }

    /**
     * 锁定计划后读取全部Outbox，排除并行出库使基线在两次查询间漂移。
     */
    @Transactional(rollbackFor = Exception.class)
    public WmsOmsDeliveryLedger snapshot(String orderCode, Long detailsId, String requestId) {
        require(StrUtil.isNotBlank(orderCode) && detailsId != null, "OMS交付行来源不能为空");
        List<WmsDeliverGoodsPlanBaseEntity> plans = planMapper.selectList(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, orderCode)
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, detailsId)
                        .last("FOR SHARE"));
        require(plans.size() == 1, "WMS发货计划缺失或订单交付行来源不唯一");
        WmsDeliverGoodsPlanBaseEntity plan = plans.get(0);
        requireFactory(plan.getFactoryCode());
        require(plan.getDeliveredNum() != null && plan.getDeliverNum() != null
                        && plan.getDeliveredNum().signum() >= 0
                        && fitsLedgerQuantity(plan.getDeliveredNum())
                        && plan.getDeliveredNum().compareTo(plan.getDeliverNum()) <= 0,
                "WMS发货计划实际量或目标量非法，须先核对历史发货");
        List<WmsOmsDeliveryEventOutboxEntity> events = mapper.selectList(
                new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                        .eq(WmsOmsDeliveryEventOutboxEntity::getWmsPlanId, plan.getId())
                        .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getId));
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal replacementTotal = BigDecimal.ZERO;
        int normalCount = 0;
        int replacementCount = 0;
        WmsOmsDeliveryEventOutboxEntity requested = null;
        for (WmsOmsDeliveryEventOutboxEntity event : events) {
            require(Objects.equals(event.getFactoryCode(), plan.getFactoryCode())
                            && Objects.equals(event.getOrderCode(), plan.getOrderCode())
                            && Objects.equals(event.getOrderDetailId(), plan.getOrderCodeDetails())
                            && Objects.equals(event.getProductCode(), plan.getProductCode())
                            && event.getShippedNum() != null && event.getShippedNum().signum() > 0,
                    "WMS计划对应Outbox来源或数量存在歧义");
            String type = StrUtil.blankToDefault(event.getShipmentType(), "NORMAL");
            require("NORMAL".equals(type) || ("REPLACEMENT".equals(type) && event.getResolutionCaseId() != null),
                    "WMS发运Outbox类型或异常处置来源非法");
            if ("REPLACEMENT".equals(type)) {
                replacementTotal = replacementTotal.add(event.getShippedNum());
                replacementCount++;
            } else {
                total = total.add(event.getShippedNum());
                normalCount++;
            }
            if (Objects.equals(event.getRequestId(), requestId)) requested = event;
        }
        require(StrUtil.isBlank(requestId) || requested != null,
                "WMS正式出库Outbox缺少指定发运请求号");
        BigDecimal corrected = correctionMapper.selectList(
                        new LambdaQueryWrapper<WmsOmsShipmentCorrectionOutboxEntity>()
                                .eq(WmsOmsShipmentCorrectionOutboxEntity::getWmsPlanId, plan.getId()))
                .stream().map(WmsOmsShipmentCorrectionOutboxEntity::getCorrectedNum)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal baseline = plan.getDeliveredNum().add(corrected).subtract(total);
        require(baseline.signum() >= 0 && fitsLedgerQuantity(baseline),
                "WMS正式出库事件总量超过计划累计量或历史基线精度非法");
        WmsOmsDeliveryLedger ledger = new WmsOmsDeliveryLedger()
                .setPlanId(plan.getId()).setPlanCode(plan.getDeliverPlanCode())
                .setOrderCode(plan.getOrderCode()).setOrderDetailId(plan.getOrderCodeDetails())
                .setFactoryCode(plan.getFactoryCode()).setProductCode(plan.getProductCode())
                .setPlanDeliveredNum(plan.getDeliveredNum()).setOutboxShippedNum(total)
                .setOutboxReplacementNum(replacementTotal)
                .setOutboxCorrectedNum(corrected)
                .setLegacyBaselineNum(baseline).setOutboxCount(normalCount)
                .setReplacementOutboxCount(replacementCount);
        if (requested != null) ledger.setRequestId(requested.getRequestId())
                .setRequestFingerprint(requested.getRequestFingerprint())
                .setRequestWmsBillId(requested.getWmsBillId())
                .setRequestShippedNum(requested.getShippedNum())
                .setRequestShipmentType(StrUtil.blankToDefault(requested.getShipmentType(), "NORMAL"))
                .setRequestResolutionCaseId(requested.getResolutionCaseId());
        return ledger;
    }

    public List<WmsOmsDeliveryEventOutboxEntity> listByBill(Long billId) {
        require(billId != null, "出库单ID不能为空");
        String factory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                .eq(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, billId)
                .eq(StrUtil.isNotBlank(factory), WmsOmsDeliveryEventOutboxEntity::getFactoryCode, factory)
                .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getId));
    }

    public List<WmsOmsDeliveryEventOutboxEntity> listByDelivery(Long deliveryId) {
        require(deliveryId != null, "发货单ID不能为空");
        List<Long> billIds = billMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                        .eq(WmsOutBillEntity::getDocumentId, deliveryId)
                        .eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode()))
                .stream().map(WmsOutBillEntity::getId).toList();
        if (billIds.isEmpty()) return List.of();
        String factory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                .in(WmsOmsDeliveryEventOutboxEntity::getWmsBillId, billIds)
                .eq(StrUtil.isNotBlank(factory), WmsOmsDeliveryEventOutboxEntity::getFactoryCode, factory)
                .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getShippedTime));
    }

    public WmsOmsDeliveryEventOutboxEntity retry(Long id) {
        WmsOmsDeliveryEventOutboxEntity row = mapper.selectById(id);
        require(row != null, "OMS发运回传事件不存在");
        requireFactory(row.getFactoryCode());
        return dispatch(id);
    }

    /**
     * 独立只读核对OMS永久收件箱；异常仅标记差异，绝不再次累计交付量。
     */
    public WmsOmsDeliveryEventOutboxEntity reconcile(Long id) {
        WmsOmsDeliveryEventOutboxEntity row = mapper.selectById(id);
        require(row != null && "SUCCEEDED".equals(row.getStatus()), "仅已回传成功的发运事件可对账");
        requireFactory(row.getFactoryCode());
        String outcome;
        String error = null;
        try {
            R<OmsDeliveryEventCommand.Receipt> response = omsService.deliveryEventReceipt(row.getRequestId());
            require(response != null && response.isOk(), "OMS永久收件箱查询失败");
            OmsDeliveryEventCommand.Receipt receipt = response.getData();
            if (receipt == null) {
                outcome = "MISMATCHED";
                error = "OMS永久收件箱缺少已确认的发运请求号";
            } else if (!"SUCCEEDED".equals(receipt.getStatus())
                    || !Objects.equals(receipt.getRequestId(), row.getRequestId())
                    || !Objects.equals(receipt.getOrderDetailId(), row.getOrderDetailId())
                    || !Objects.equals(receipt.getRequestFingerprint(), row.getRequestFingerprint())
                    || !Objects.equals(receipt.getRequestFingerprint(), row.getOmsReceiptFingerprint())
                    || receipt.getOmsDeliveredNum() == null
                    || row.getOmsDeliveredNum() == null
                    || receipt.getOmsDeliveredNum().compareTo(row.getOmsDeliveredNum()) != 0
                    || receipt.getCompletedTime() == null) {
                outcome = "MISMATCHED";
                error = "OMS永久收件回执与WMS冻结请求或原确认回执不一致";
            } else {
                String discrepancy = lineDiscrepancy(row);
                outcome = discrepancy == null ? "MATCHED" : "MISMATCHED";
                error = discrepancy;
            }
        } catch (Exception ex) {
            outcome = "CHECK_FAILED";
            error = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        }
        String result = outcome;
        String reason = error;
        return tx().execute(status -> {
            WmsOmsDeliveryEventOutboxEntity locked = lock(id);
            requireFactory(locked.getFactoryCode());
            require("SUCCEEDED".equals(locked.getStatus()), "发运事件对账期间状态已改变");
            LocalDateTime now = LocalDateTime.now();
            locked.setReconcileStatus(result)
                    .setReconcileError(reason == null ? null : reason.substring(0, Math.min(1000, reason.length())))
                    .setReconciledTime(now)
                    .setNextReconcileTime("MATCHED".equals(result) ? now.plusDays(1) : now.plusMinutes(30));
            require(mapper.updateById(locked) == 1, "WMS发运对账结果保存失败");
            return locked;
        });
    }

    private String lineDiscrepancy(WmsOmsDeliveryEventOutboxEntity row) {
        WmsOmsDeliveryLedger wmsLedger;
        try {
            wmsLedger = tx().execute(status -> snapshot(row.getOrderCode(), row.getOrderDetailId(), null));
        } catch (CheckedException ex) {
            return "WMS计划与出库Outbox数量账异常：" + ex.getMessage();
        }
        R<OmsDeliveryEventCommand.LineLedger> lineResponse = omsService.deliveryLineLedger(
                row.getOrderCode(), row.getOrderDetailId());
        require(lineResponse != null, "OMS交付行数量账查询失败");
        OmsDeliveryEventCommand.LineLedger omsLedger = lineResponse.getData();
        if (!lineResponse.isOk() || omsLedger == null
                || !Objects.equals(omsLedger.getOrderCode(), row.getOrderCode())
                || !Objects.equals(omsLedger.getOrderDetailId(), row.getOrderDetailId())
                || !Objects.equals(omsLedger.getFactoryCode(), row.getFactoryCode())
                || !Objects.equals(omsLedger.getProductCode(), row.getProductCode())
                || wmsLedger == null || wmsLedger.getLegacyBaselineNum() == null
                || omsLedger.getLegacyBaselineNum() == null
                || wmsLedger.getLegacyBaselineNum().compareTo(omsLedger.getLegacyBaselineNum()) != 0
                || omsLedger.getInboxShippedNum() == null
                || omsLedger.getInboxReplacementNum() == null
                || omsLedger.getInboxReturnedNum() == null
                || omsLedger.getInboxCorrectedNum() == null
                || omsLedger.getOmsDeliveredNum() == null
                || omsLedger.getOrderDeliverNum() == null
                || omsLedger.getInboxCount() == null || wmsLedger.getOutboxCount() == null
                || omsLedger.getReplacementInboxCount() == null || wmsLedger.getReplacementOutboxCount() == null
                || omsLedger.getReturnInboxCount() == null
                || omsLedger.getCorrectionInboxCount() == null
                || omsLedger.getInboxCount() > wmsLedger.getOutboxCount()
                || omsLedger.getReplacementInboxCount() > wmsLedger.getReplacementOutboxCount()
                || omsLedger.getInboxShippedNum().compareTo(wmsLedger.getOutboxShippedNum()) > 0
                || wmsLedger.getOutboxReplacementNum() == null
                || omsLedger.getInboxReplacementNum().compareTo(wmsLedger.getOutboxReplacementNum()) > 0
                || wmsLedger.getOutboxCorrectedNum() == null
                || omsLedger.getInboxCorrectedNum().compareTo(wmsLedger.getOutboxCorrectedNum()) > 0
                || omsLedger.getOmsDeliveredNum().compareTo(
                omsLedger.getLegacyBaselineNum().add(omsLedger.getInboxShippedNum())
                        .subtract(omsLedger.getInboxReturnedNum())
                        .subtract(omsLedger.getInboxCorrectedNum())) != 0
                || omsLedger.getOmsDeliveredNum().compareTo(omsLedger.getOrderDeliverNum()) > 0) {
            return "OMS交付行、发运/退货/纠错收件箱与WMS计划/Outbox总量或历史基线不一致";
        }
        return null;
    }

    public int processReconciliationDue(int limit) {
        List<WmsOmsDeliveryEventOutboxEntity> due = mapper.selectPage(
                new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                        .eq(WmsOmsDeliveryEventOutboxEntity::getStatus, "SUCCEEDED")
                        .le(WmsOmsDeliveryEventOutboxEntity::getNextReconcileTime, LocalDateTime.now())
                        .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getNextReconcileTime)
                        .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getId)).getRecords();
        int checked = 0;
        for (WmsOmsDeliveryEventOutboxEntity row : due) {
            try {
                if (row.getTenantId() == null) reconcile(row.getId());
                else TenantBroker.applyAs(row.getTenantId(), ignored -> reconcile(row.getId()));
                checked++;
            } catch (RuntimeException ex) {
                log.error("WMS→OMS发运回执对账异常，eventId={}，tenantId={}",
                        row.getId(), row.getTenantId(), ex);
            }
        }
        return checked;
    }

    public int processDue(int limit) {
        List<WmsOmsDeliveryEventOutboxEntity> due = mapper.selectPage(
                new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                        .in(WmsOmsDeliveryEventOutboxEntity::getStatus, "PENDING", "FAILED")
                        .le(WmsOmsDeliveryEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(WmsOmsDeliveryEventOutboxEntity::getNextAttemptTime)).getRecords();
        int completed = 0;
        for (WmsOmsDeliveryEventOutboxEntity row : due) {
            try {
                WmsOmsDeliveryEventOutboxEntity result = row.getTenantId() == null
                        ? dispatch(row.getId())
                        : TenantBroker.applyAs(row.getTenantId(), ignored -> dispatch(row.getId()));
                if ("SUCCEEDED".equals(result.getStatus())) completed++;
            } catch (RuntimeException ex) {
                log.error("WMS→OMS发运回传调度异常，eventId={}，tenantId={}",
                        row.getId(), row.getTenantId(), ex);
            }
        }
        return completed;
    }

    private WmsOmsDeliveryEventOutboxEntity dispatch(Long id) {
        WmsOmsDeliveryEventOutboxEntity row = mapper.selectById(id);
        require(row != null, "OMS发运回传事件不存在");
        requireFactory(row.getFactoryCode());
        if ("SUCCEEDED".equals(row.getStatus())) return row;
        try {
            OmsDeliveryEventCommand.Command command = command(row);
            require(Objects.equals(row.getRequestFingerprint(), hash(facts(command))),
                    "WMS发运Outbox冻结事实已损坏");
            R<OmsDeliveryEventCommand.Receipt> response = omsService.receiveDeliveryEvent(command);
            OmsDeliveryEventCommand.Receipt receipt = response == null ? null : response.getData();
            require(response != null && response.isOk() && receipt != null
                            && "SUCCEEDED".equals(receipt.getStatus())
                            && Objects.equals(receipt.getRequestId(), row.getRequestId())
                            && Objects.equals(receipt.getOrderDetailId(), row.getOrderDetailId())
                            && Objects.equals(receipt.getRequestFingerprint(), row.getRequestFingerprint())
                            && receipt.getOmsDeliveredNum() != null,
                    "OMS未返回匹配的发运永久回执");
            return tx().execute(status -> {
                WmsOmsDeliveryEventOutboxEntity locked = lock(id);
                if (!"SUCCEEDED".equals(locked.getStatus())) {
                    locked.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null)
                            .setOmsDeliveredNum(receipt.getOmsDeliveredNum())
                            .setOmsReceiptFingerprint(receipt.getRequestFingerprint())
                            .setCompletedTime(receipt.getCompletedTime());
                    require(mapper.updateById(locked) == 1, "WMS发运OMS回执保存失败");
                }
                return locked;
            });
        } catch (Exception ex) {
            return tx().execute(status -> {
                WmsOmsDeliveryEventOutboxEntity locked = lock(id);
                if (!"SUCCEEDED".equals(locked.getStatus())) {
                    int attempts = (locked.getAttemptCount() == null ? 0 : locked.getAttemptCount()) + 1;
                    String safe = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
                    locked.setStatus(attempts >= 5 ? "NEEDS_MANUAL" : "FAILED")
                            .setAttemptCount(attempts)
                            .setNextAttemptTime(attempts >= 5 ? null : LocalDateTime.now()
                                    .plusSeconds(Math.min(1800, 60L << Math.min(attempts, 5))))
                            .setLastError(safe.substring(0, Math.min(1000, safe.length())));
                    require(mapper.updateById(locked) == 1, "WMS发运OMS失败原因保存失败");
                }
                return locked;
            });
        }
    }

    private WmsOmsDeliveryEventOutboxEntity lock(Long id) {
        WmsOmsDeliveryEventOutboxEntity row = mapper.selectOne(
                new LambdaQueryWrapper<WmsOmsDeliveryEventOutboxEntity>()
                        .eq(WmsOmsDeliveryEventOutboxEntity::getId, id).last("FOR UPDATE"));
        require(row != null, "OMS发运回传事件不存在");
        return row;
    }

    private OmsDeliveryEventCommand.Command command(WmsOmsDeliveryEventOutboxEntity row) {
        return new OmsDeliveryEventCommand.Command()
                .setRequestId(row.getRequestId()).setWmsBillId(row.getWmsBillId())
                .setWmsBillNo(row.getWmsBillNo()).setWmsPlanId(row.getWmsPlanId())
                .setWmsPlanCode(row.getWmsPlanCode()).setOrderCode(row.getOrderCode())
                .setOrderDetailId(row.getOrderDetailId()).setFactoryCode(row.getFactoryCode())
                .setProductCode(row.getProductCode()).setShippedNum(row.getShippedNum())
                .setShippedTime(row.getShippedTime())
                .setShipmentType(StrUtil.blankToDefault(row.getShipmentType(), "NORMAL"))
                .setResolutionCaseId(row.getResolutionCaseId());
    }

    private String facts(OmsDeliveryEventCommand.Command c) {
        List<Object> facts = new java.util.ArrayList<>(Arrays.asList(c.getWmsBillId(), c.getWmsBillNo(),
                c.getWmsPlanId(), c.getWmsPlanCode(), c.getOrderCode(), c.getOrderDetailId(),
                c.getFactoryCode(), c.getProductCode(),
                c.getShippedNum().stripTrailingZeros().toPlainString(), c.getShippedTime()));
        if ("REPLACEMENT".equals(c.getShipmentType())) {
            facts.add(c.getShipmentType());
            facts.add(c.getResolutionCaseId());
        }
        return JSONUtil.toJsonStr(facts);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("WMS发运事实指纹生成失败");
        }
    }

    private boolean fitsLedgerQuantity(BigDecimal quantity) {
        if (quantity == null) return false;
        BigDecimal normalized = quantity.stripTrailingZeros();
        return normalized.scale() <= 6
                && normalized.precision() - normalized.scale() <= 18;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected)
                || Objects.equals(selected, factory)), "当前工厂无权续跑该OMS回传事件");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
