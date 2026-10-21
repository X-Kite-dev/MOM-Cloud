package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.oms.dto.OmsSalesReturnEventCommand;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.dto.WmsOmsSalesReturnLedger;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsPlanBaseMapper;
import com.kitex.mom.wms.mapper.WmsOmsSalesReturnEventOutboxMapper;
import com.kitex.mom.wms.mapper.WmsShipmentExceptionResolutionMapper;
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
import java.util.*;

/**
 * 销售退货实收入库同事务Outbox；OMS按原订单行反向恢复，失败可原号续跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsOmsSalesReturnEventOutboxService {
    private final WmsOmsSalesReturnEventOutboxMapper mapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final WmsShipmentExceptionResolutionMapper exceptionResolutionMapper;
    private final RemoteOmsOrderService omsService;
    private final PlatformTransactionManager transactionManager;

    /**
     * 必须在销售退货入库审核事务内调用。每一条实收入库明细形成唯一不可变事实。
     */
    public void enqueue(WmsInBillEntity bill, WmsInBillDetailEntity billDetail,
                        WmsDeliverGoodsReturnEntity salesReturn,
                        WmsDeliverGoodsReturnDetailsEntity returnDetail,
                        WmsDeliverGoodsPlanBaseEntity plan, BigDecimal returnedNum) {
        require(bill != null && bill.getId() != null && StrUtil.isNotBlank(bill.getBillNo())
                        && billDetail != null && billDetail.getId() != null
                        && salesReturn != null && salesReturn.getId() != null
                        && returnDetail != null && returnDetail.getId() != null
                        && plan != null && plan.getId() != null && plan.getOrderCodeDetails() != null
                        && StrUtil.isNotBlank(plan.getOrderCode()) && StrUtil.isNotBlank(plan.getFactoryCode())
                        && StrUtil.isNotBlank(plan.getProductCode()) && bill.getInTime() != null
                        && returnedNum != null && returnedNum.signum() > 0,
                "OMS退货Outbox缺少退货入库、原发货计划、订单行或正数量");
        require(fitsLedgerQuantity(returnedNum), "退货数量超过OMS退货账本支持的24位/6位小数精度");
        require(Objects.equals(bill.getFactoryCode(), plan.getFactoryCode())
                        && Objects.equals(salesReturn.getFactoryCode(), plan.getFactoryCode())
                        && Objects.equals(returnDetail.getProductCode(), plan.getProductCode()),
                "退货入库、退货申请与原发货计划工厂或产品不一致");
        WmsShipmentExceptionResolutionEntity resolution = exceptionResolutionMapper.selectOne(
                new LambdaQueryWrapper<WmsShipmentExceptionResolutionEntity>()
                        .eq(WmsShipmentExceptionResolutionEntity::getReturnId, salesReturn.getId()), false);
        if (resolution != null) require(Objects.equals(resolution.getFactoryCode(), plan.getFactoryCode()),
                "客户异常处置退货与原发货计划工厂不一致");
        OmsSalesReturnEventCommand.Command command = new OmsSalesReturnEventCommand.Command()
                .setRequestId("WMS-RETURN:" + billDetail.getId())
                .setWmsInBillId(bill.getId()).setWmsInBillDetailId(billDetail.getId())
                .setWmsInBillNo(bill.getBillNo()).setWmsReturnId(salesReturn.getId())
                .setResolutionCaseId(resolution == null ? null : resolution.getId())
                .setWmsReturnDetailId(returnDetail.getId()).setWmsPlanId(plan.getId())
                .setWmsPlanCode(plan.getDeliverPlanCode()).setOrderCode(plan.getOrderCode())
                .setOrderDetailId(plan.getOrderCodeDetails()).setFactoryCode(plan.getFactoryCode())
                .setProductCode(plan.getProductCode()).setReturnedNum(returnedNum)
                .setReturnedTime(bill.getInTime().truncatedTo(ChronoUnit.MICROS));
        String fingerprint = hash(facts(command));
        WmsOmsSalesReturnEventOutboxEntity old = mapper.selectOne(
                new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                        .eq(WmsOmsSalesReturnEventOutboxEntity::getWmsInBillDetailId, billDetail.getId()));
        if (old != null) {
            require(Objects.equals(old.getRequestId(), command.getRequestId())
                            && Objects.equals(old.getRequestFingerprint(), fingerprint),
                    "同一退货入库明细已有不同OMS冲减事实，禁止覆盖");
            return;
        }
        WmsOmsSalesReturnEventOutboxEntity row = new WmsOmsSalesReturnEventOutboxEntity()
                .setRequestId(command.getRequestId()).setRequestFingerprint(fingerprint)
                .setWmsInBillId(command.getWmsInBillId()).setWmsInBillDetailId(command.getWmsInBillDetailId())
                .setWmsInBillNo(command.getWmsInBillNo()).setWmsReturnId(command.getWmsReturnId())
                .setResolutionCaseId(command.getResolutionCaseId())
                .setWmsReturnDetailId(command.getWmsReturnDetailId()).setWmsPlanId(command.getWmsPlanId())
                .setWmsPlanCode(command.getWmsPlanCode()).setOrderCode(command.getOrderCode())
                .setOrderDetailId(command.getOrderDetailId()).setProductCode(command.getProductCode())
                .setReturnedNum(command.getReturnedNum()).setReturnedTime(command.getReturnedTime())
                .setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setReconcileStatus("PENDING").setNextReconcileTime(LocalDateTime.now())
                .setFactoryCode(command.getFactoryCode());
        require(mapper.insert(row) == 1, "销售退货OMS冲减Outbox保存失败");
    }

    /**
     * 锁定原计划并冻结退货累计，供OMS验证来源事实。
     */
    @Transactional(rollbackFor = Exception.class)
    public WmsOmsSalesReturnLedger snapshot(String orderCode, Long detailsId, String requestId) {
        require(StrUtil.isNotBlank(orderCode) && detailsId != null, "OMS退货交付行来源不能为空");
        List<WmsDeliverGoodsPlanBaseEntity> plans = planMapper.selectList(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, orderCode)
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, detailsId).last("FOR SHARE"));
        require(plans.size() == 1, "WMS退货原发货计划缺失或订单交付行来源不唯一");
        WmsDeliverGoodsPlanBaseEntity plan = plans.get(0);
        requireFactory(plan.getFactoryCode());
        require(plan.getDeliveredNum() != null && plan.getReturnNum() != null
                        && plan.getDeliveredNum().signum() >= 0 && plan.getReturnNum().signum() >= 0
                        && plan.getReturnNum().compareTo(plan.getDeliveredNum()) <= 0,
                "WMS原发货计划已发或退货累计量非法");
        List<WmsOmsSalesReturnEventOutboxEntity> events = mapper.selectList(
                new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                        .eq(WmsOmsSalesReturnEventOutboxEntity::getWmsPlanId, plan.getId())
                        .orderByAsc(WmsOmsSalesReturnEventOutboxEntity::getId));
        BigDecimal total = BigDecimal.ZERO;
        WmsOmsSalesReturnEventOutboxEntity requested = null;
        for (WmsOmsSalesReturnEventOutboxEntity event : events) {
            require(Objects.equals(event.getFactoryCode(), plan.getFactoryCode())
                            && Objects.equals(event.getOrderCode(), plan.getOrderCode())
                            && Objects.equals(event.getOrderDetailId(), plan.getOrderCodeDetails())
                            && Objects.equals(event.getProductCode(), plan.getProductCode())
                            && event.getReturnedNum() != null && event.getReturnedNum().signum() > 0,
                    "WMS原计划对应退货Outbox来源或数量存在歧义");
            total = total.add(event.getReturnedNum());
            if (Objects.equals(event.getRequestId(), requestId)) requested = event;
        }
        require(StrUtil.isBlank(requestId) || requested != null, "WMS退货Outbox缺少指定请求号");
        BigDecimal legacy = plan.getReturnNum().subtract(total);
        require(legacy.signum() >= 0 && fitsLedgerQuantity(legacy),
                "WMS退货Outbox总量超过计划退货累计量或历史基线非法");
        WmsOmsSalesReturnLedger ledger = new WmsOmsSalesReturnLedger()
                .setPlanId(plan.getId()).setPlanCode(plan.getDeliverPlanCode())
                .setOrderCode(plan.getOrderCode()).setOrderDetailId(plan.getOrderCodeDetails())
                .setFactoryCode(plan.getFactoryCode()).setProductCode(plan.getProductCode())
                .setPlanDeliveredNum(plan.getDeliveredNum()).setPlanReturnedNum(plan.getReturnNum())
                .setOutboxReturnedNum(total).setLegacyReturnBaselineNum(legacy).setOutboxCount(events.size());
        if (requested != null) ledger.setRequestId(requested.getRequestId())
                .setRequestFingerprint(requested.getRequestFingerprint())
                .setRequestWmsInBillDetailId(requested.getWmsInBillDetailId())
                .setRequestResolutionCaseId(requested.getResolutionCaseId())
                .setRequestReturnedNum(requested.getReturnedNum());
        return ledger;
    }

    public List<WmsOmsSalesReturnEventOutboxEntity> listByInBill(Long billId) {
        require(billId != null, "退货入库单ID不能为空");
        String factory = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                .eq(WmsOmsSalesReturnEventOutboxEntity::getWmsInBillId, billId)
                .eq(StrUtil.isNotBlank(factory), WmsOmsSalesReturnEventOutboxEntity::getFactoryCode, factory)
                .orderByAsc(WmsOmsSalesReturnEventOutboxEntity::getId));
    }

    public WmsOmsSalesReturnEventOutboxEntity retry(Long id) {
        WmsOmsSalesReturnEventOutboxEntity row = mapper.selectById(id);
        require(row != null, "OMS退货冲减事件不存在");
        requireFactory(row.getFactoryCode());
        return dispatch(id);
    }

    public WmsOmsSalesReturnEventOutboxEntity reconcile(Long id) {
        WmsOmsSalesReturnEventOutboxEntity row = mapper.selectById(id);
        require(row != null && "SUCCEEDED".equals(row.getStatus()), "仅成功退货事件可对账");
        requireFactory(row.getFactoryCode());
        String outcome;
        String error = null;
        try {
            R<OmsSalesReturnEventCommand.Receipt> response = omsService.salesReturnEventReceipt(row.getRequestId());
            require(response != null && response.isOk(), "OMS退货永久收件箱查询失败");
            OmsSalesReturnEventCommand.Receipt receipt = response.getData();
            if (receipt == null || !"SUCCEEDED".equals(receipt.getStatus())
                    || !Objects.equals(receipt.getRequestId(), row.getRequestId())
                    || !Objects.equals(receipt.getOrderDetailId(), row.getOrderDetailId())
                    || !Objects.equals(receipt.getRequestFingerprint(), row.getRequestFingerprint())
                    || !Objects.equals(receipt.getRequestFingerprint(), row.getOmsReceiptFingerprint())
                    || receipt.getOmsDeliveredNum() == null || row.getOmsDeliveredNum() == null
                    || receipt.getOmsDeliveredNum().compareTo(row.getOmsDeliveredNum()) != 0
                    || receipt.getCompletedTime() == null) {
                outcome = "MISMATCHED";
                error = "OMS退货永久回执与WMS冻结事实或原确认回执不一致";
            } else {
                WmsOmsSalesReturnLedger ledger = tx().execute(s -> snapshot(row.getOrderCode(), row.getOrderDetailId(), row.getRequestId()));
                outcome = ledger != null && Objects.equals(ledger.getRequestFingerprint(), row.getRequestFingerprint())
                        ? "MATCHED" : "MISMATCHED";
                if (!"MATCHED".equals(outcome)) error = "WMS退货来源账与冻结请求不一致";
            }
        } catch (Exception ex) {
            outcome = "CHECK_FAILED";
            error = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        }
        String result = outcome;
        String reason = error;
        return tx().execute(status -> {
            WmsOmsSalesReturnEventOutboxEntity locked = lock(id);
            LocalDateTime now = LocalDateTime.now();
            locked.setReconcileStatus(result)
                    .setReconcileError(reason == null ? null : reason.substring(0, Math.min(1000, reason.length())))
                    .setReconciledTime(now)
                    .setNextReconcileTime("MATCHED".equals(result) ? now.plusDays(1) : now.plusMinutes(30));
            require(mapper.updateById(locked) == 1, "退货事件对账结果保存失败");
            return locked;
        });
    }

    public int processDue(int limit) {
        List<WmsOmsSalesReturnEventOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                        .in(WmsOmsSalesReturnEventOutboxEntity::getStatus, "PENDING", "FAILED")
                        .le(WmsOmsSalesReturnEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(WmsOmsSalesReturnEventOutboxEntity::getNextAttemptTime)).getRecords();
        int completed = 0;
        for (WmsOmsSalesReturnEventOutboxEntity row : due) {
            try {
                WmsOmsSalesReturnEventOutboxEntity result = row.getTenantId() == null ? dispatch(row.getId())
                        : TenantBroker.applyAs(row.getTenantId(), ignored -> dispatch(row.getId()));
                if ("SUCCEEDED".equals(result.getStatus())) completed++;
            } catch (RuntimeException ex) {
                log.error("WMS→OMS销售退货冲减调度异常，eventId={}，tenantId={}", row.getId(), row.getTenantId(), ex);
            }
        }
        return completed;
    }

    public int processReconciliationDue(int limit) {
        List<WmsOmsSalesReturnEventOutboxEntity> due = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                        .eq(WmsOmsSalesReturnEventOutboxEntity::getStatus, "SUCCEEDED")
                        .le(WmsOmsSalesReturnEventOutboxEntity::getNextReconcileTime, LocalDateTime.now())
                        .orderByAsc(WmsOmsSalesReturnEventOutboxEntity::getNextReconcileTime)).getRecords();
        int checked = 0;
        for (WmsOmsSalesReturnEventOutboxEntity row : due) {
            try {
                if (row.getTenantId() == null) reconcile(row.getId());
                else TenantBroker.applyAs(row.getTenantId(), ignored -> reconcile(row.getId()));
                checked++;
            } catch (RuntimeException ex) {
                log.error("WMS→OMS销售退货对账异常，eventId={}，tenantId={}", row.getId(), row.getTenantId(), ex);
            }
        }
        return checked;
    }

    private WmsOmsSalesReturnEventOutboxEntity dispatch(Long id) {
        WmsOmsSalesReturnEventOutboxEntity row = mapper.selectById(id);
        require(row != null, "OMS退货冲减事件不存在");
        requireFactory(row.getFactoryCode());
        if ("SUCCEEDED".equals(row.getStatus())) return row;
        try {
            OmsSalesReturnEventCommand.Command command = command(row);
            require(Objects.equals(row.getRequestFingerprint(), hash(facts(command))), "WMS退货Outbox冻结事实已损坏");
            R<OmsSalesReturnEventCommand.Receipt> response = omsService.receiveSalesReturnEvent(command);
            OmsSalesReturnEventCommand.Receipt receipt = response == null ? null : response.getData();
            require(response != null && response.isOk() && receipt != null && "SUCCEEDED".equals(receipt.getStatus())
                            && Objects.equals(receipt.getRequestId(), row.getRequestId())
                            && Objects.equals(receipt.getOrderDetailId(), row.getOrderDetailId())
                            && Objects.equals(receipt.getRequestFingerprint(), row.getRequestFingerprint())
                            && receipt.getOmsDeliveredNum() != null,
                    "OMS未返回匹配的退货永久回执");
            return tx().execute(status -> {
                WmsOmsSalesReturnEventOutboxEntity locked = lock(id);
                if (!"SUCCEEDED".equals(locked.getStatus())) {
                    locked.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null)
                            .setOmsDeliveredNum(receipt.getOmsDeliveredNum())
                            .setOmsReceiptFingerprint(receipt.getRequestFingerprint())
                            .setCompletedTime(receipt.getCompletedTime());
                    require(mapper.updateById(locked) == 1, "WMS退货OMS回执保存失败");
                }
                return locked;
            });
        } catch (Exception ex) {
            return tx().execute(status -> {
                WmsOmsSalesReturnEventOutboxEntity locked = lock(id);
                if (!"SUCCEEDED".equals(locked.getStatus())) {
                    int attempts = (locked.getAttemptCount() == null ? 0 : locked.getAttemptCount()) + 1;
                    String safe = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
                    locked.setStatus(attempts >= 5 ? "NEEDS_MANUAL" : "FAILED").setAttemptCount(attempts)
                            .setNextAttemptTime(attempts >= 5 ? null : LocalDateTime.now()
                                    .plusSeconds(Math.min(1800, 60L << Math.min(attempts, 5))))
                            .setLastError(safe.substring(0, Math.min(1000, safe.length())));
                    require(mapper.updateById(locked) == 1, "WMS退货OMS失败原因保存失败");
                }
                return locked;
            });
        }
    }

    private WmsOmsSalesReturnEventOutboxEntity lock(Long id) {
        WmsOmsSalesReturnEventOutboxEntity row = mapper.selectOne(new LambdaQueryWrapper<WmsOmsSalesReturnEventOutboxEntity>()
                .eq(WmsOmsSalesReturnEventOutboxEntity::getId, id).last("FOR UPDATE"));
        require(row != null, "OMS退货冲减事件不存在");
        return row;
    }

    private OmsSalesReturnEventCommand.Command command(WmsOmsSalesReturnEventOutboxEntity row) {
        return new OmsSalesReturnEventCommand.Command().setRequestId(row.getRequestId())
                .setWmsInBillId(row.getWmsInBillId()).setWmsInBillDetailId(row.getWmsInBillDetailId())
                .setWmsInBillNo(row.getWmsInBillNo()).setWmsReturnId(row.getWmsReturnId())
                .setResolutionCaseId(row.getResolutionCaseId())
                .setWmsReturnDetailId(row.getWmsReturnDetailId()).setWmsPlanId(row.getWmsPlanId())
                .setWmsPlanCode(row.getWmsPlanCode()).setOrderCode(row.getOrderCode())
                .setOrderDetailId(row.getOrderDetailId()).setFactoryCode(row.getFactoryCode())
                .setProductCode(row.getProductCode()).setReturnedNum(row.getReturnedNum())
                .setReturnedTime(row.getReturnedTime());
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
            throw new CheckedException("WMS退货事实指纹生成失败");
        }
    }

    private boolean fitsLedgerQuantity(BigDecimal quantity) {
        BigDecimal normalized = quantity == null ? null : quantity.stripTrailingZeros();
        return normalized != null && normalized.scale() <= 6 && normalized.precision() - normalized.scale() <= 18;
    }

    private void requireFactory(String factory) {
        String selected = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && (StrUtil.isBlank(selected) || Objects.equals(selected, factory)),
                "当前工厂无权续跑该OMS退货事件");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
