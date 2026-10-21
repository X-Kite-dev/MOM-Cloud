package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.oms.dto.OmsCustomerExceptionResolutionCommand;
import com.kitex.mom.oms.feign.RemoteOmsOrderService;
import com.kitex.mom.wms.dto.WmsOmsCustomerExceptionResolutionLedger;
import com.kitex.mom.wms.entity.WmsOmsCustomerExceptionResolutionOutboxEntity;
import com.kitex.mom.wms.entity.WmsShipmentExceptionResolutionEntity;
import com.kitex.mom.wms.entity.WmsShipmentReceiptDetailEntity;
import com.kitex.mom.wms.mapper.WmsOmsCustomerExceptionResolutionOutboxMapper;
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
 * 客户异常退款/让步结案可靠投递，原号重试并永久对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsOmsCustomerExceptionResolutionOutboxService {
    private final WmsOmsCustomerExceptionResolutionOutboxMapper mapper;
    private final RemoteOmsOrderService omsService;
    private final PlatformTransactionManager transactionManager;

    public void enqueue(WmsShipmentExceptionResolutionEntity resolution, WmsShipmentReceiptDetailEntity source,
                        BigDecimal refundNum, BigDecimal concessionNum,
                        BigDecimal refundAmount, BigDecimal concessionAmount) {
        require(resolution != null && resolution.getId() != null && resolution.getSettlementTime() != null && source != null && source.getOrderDetailId() != null, "客户异常结案Outbox缺少处置单、结算或订单行来源");
        refundNum = zero(refundNum);
        concessionNum = zero(concessionNum);
        refundAmount = zero(refundAmount);
        concessionAmount = zero(concessionAmount);
        require(refundNum.add(concessionNum).signum() > 0, "客户异常结案事件数量必须为正");
        OmsCustomerExceptionResolutionCommand.Command c = new OmsCustomerExceptionResolutionCommand.Command().setRequestId("WMS-EXCEPTION-SETTLEMENT:" + resolution.getId() + ":" + source.getOrderDetailId()).setResolutionCaseId(resolution.getId()).setCaseNo(resolution.getCaseNo()).setOrderCode(source.getOrderCode()).setOrderDetailId(source.getOrderDetailId()).setProductCode(source.getProductCode()).setRefundNum(refundNum).setConcessionNum(concessionNum).setRefundAmount(refundAmount).setConcessionAmount(concessionAmount).setRefundReference(resolution.getRefundReference()).setConcessionReference(resolution.getConcessionReference()).setEvidence(resolution.getSettlementEvidence()).setSettlementTime(resolution.getSettlementTime()).setFactoryCode(resolution.getFactoryCode());
        String fp = hash(facts(c));
        WmsOmsCustomerExceptionResolutionOutboxEntity old = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getRequestId, c.getRequestId()), false);
        if (old != null) {
            require(Objects.equals(old.getRequestFingerprint(), fp), "同一客户异常结案请求号已有不同事实");
            return;
        }
        WmsOmsCustomerExceptionResolutionOutboxEntity row = new WmsOmsCustomerExceptionResolutionOutboxEntity().setRequestId(c.getRequestId()).setRequestFingerprint(fp).setResolutionCaseId(c.getResolutionCaseId()).setCaseNo(c.getCaseNo()).setOrderCode(c.getOrderCode()).setOrderDetailId(c.getOrderDetailId()).setProductCode(c.getProductCode()).setRefundNum(refundNum).setConcessionNum(concessionNum).setRefundAmount(refundAmount).setConcessionAmount(concessionAmount).setRefundReference(c.getRefundReference()).setConcessionReference(c.getConcessionReference()).setEvidence(c.getEvidence()).setSettlementTime(c.getSettlementTime()).setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now()).setReconcileStatus("PENDING").setNextReconcileTime(LocalDateTime.now()).setFactoryCode(c.getFactoryCode());
        require(mapper.insert(row) == 1, "客户异常结案OMS Outbox保存失败");
    }

    public WmsOmsCustomerExceptionResolutionLedger snapshot(String orderCode, Long detailId, String requestId) {
        WmsOmsCustomerExceptionResolutionOutboxEntity x = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getRequestId, requestId), false);
        require(x != null && Objects.equals(x.getOrderCode(), orderCode) && Objects.equals(x.getOrderDetailId(), detailId), "WMS客户异常结案Outbox缺少指定同源请求");
        requireFactory(x.getFactoryCode());
        return new WmsOmsCustomerExceptionResolutionLedger().setRequestId(x.getRequestId()).setRequestFingerprint(x.getRequestFingerprint()).setResolutionCaseId(x.getResolutionCaseId()).setCaseNo(x.getCaseNo()).setOrderCode(x.getOrderCode()).setOrderDetailId(x.getOrderDetailId()).setProductCode(x.getProductCode()).setRefundNum(x.getRefundNum()).setConcessionNum(x.getConcessionNum()).setRefundAmount(x.getRefundAmount()).setConcessionAmount(x.getConcessionAmount()).setRefundReference(x.getRefundReference()).setConcessionReference(x.getConcessionReference()).setEvidence(x.getEvidence()).setSettlementTime(x.getSettlementTime()).setFactoryCode(x.getFactoryCode());
    }

    public List<WmsOmsCustomerExceptionResolutionOutboxEntity> listByCase(Long caseId) {
        require(caseId != null, "异常处置单ID不能为空");
        String f = FactoryContextHolder.getFactoryCode();
        return mapper.selectList(new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getResolutionCaseId, caseId).eq(StrUtil.isNotBlank(f), WmsOmsCustomerExceptionResolutionOutboxEntity::getFactoryCode, f).orderByAsc(WmsOmsCustomerExceptionResolutionOutboxEntity::getId));
    }

    public WmsOmsCustomerExceptionResolutionOutboxEntity retry(Long id) {
        WmsOmsCustomerExceptionResolutionOutboxEntity x = mapper.selectById(id);
        require(x != null, "客户异常结案事件不存在");
        requireFactory(x.getFactoryCode());
        return dispatch(id);
    }

    public int processDue(int limit) {
        List<WmsOmsCustomerExceptionResolutionOutboxEntity> rows = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))), new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().in(WmsOmsCustomerExceptionResolutionOutboxEntity::getStatus, "PENDING", "FAILED").le(WmsOmsCustomerExceptionResolutionOutboxEntity::getNextAttemptTime, LocalDateTime.now()).orderByAsc(WmsOmsCustomerExceptionResolutionOutboxEntity::getNextAttemptTime)).getRecords();
        int n = 0;
        for (WmsOmsCustomerExceptionResolutionOutboxEntity x : rows)
            try {
                WmsOmsCustomerExceptionResolutionOutboxEntity done = x.getTenantId() == null ? dispatch(x.getId()) : TenantBroker.applyAs(x.getTenantId(), i -> dispatch(x.getId()));
                if ("SUCCEEDED".equals(done.getStatus())) n++;
            } catch (Exception e) {
                log.error("客户异常结案OMS投递失败,eventId={}", x.getId(), e);
            }
        return n;
    }

    public WmsOmsCustomerExceptionResolutionOutboxEntity reconcile(Long id) {
        WmsOmsCustomerExceptionResolutionOutboxEntity row = mapper.selectById(id);
        require(row != null && "SUCCEEDED".equals(row.getStatus()), "仅成功客户异常结案事件可对账");
        requireFactory(row.getFactoryCode());
        String result = "MATCHED", error = null;
        try {
            R<OmsCustomerExceptionResolutionCommand.Receipt> r = omsService.customerExceptionResolutionEventReceipt(row.getRequestId());
            OmsCustomerExceptionResolutionCommand.Receipt p = r == null ? null : r.getData();
            if (r == null || !r.isOk() || p == null || !"SUCCEEDED".equals(p.getStatus()) || !Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint()) || !Objects.equals(p.getRequestFingerprint(), row.getOmsReceiptFingerprint()) || p.getOmsResolvedExceptionNum() == null || p.getOmsSettlementResolvedNum() == null || row.getOmsResolvedExceptionNum() == null || row.getOmsSettlementResolvedNum() == null || p.getOmsResolvedExceptionNum().compareTo(row.getOmsResolvedExceptionNum()) != 0 || p.getOmsSettlementResolvedNum().compareTo(row.getOmsSettlementResolvedNum()) != 0) {
                result = "MISMATCHED";
                error = "OMS客户异常结案永久回执与WMS冻结请求不一致";
            }
        } catch (Exception e) {
            result = "CHECK_FAILED";
            error = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
        }
        String rr = result, ee = error;
        return tx().execute(s -> {
            WmsOmsCustomerExceptionResolutionOutboxEntity x = lock(id);
            x.setReconcileStatus(rr).setReconcileError(ee).setReconciledTime(LocalDateTime.now()).setNextReconcileTime("MATCHED".equals(rr) ? LocalDateTime.now().plusDays(1) : LocalDateTime.now().plusMinutes(30));
            require(mapper.updateById(x) == 1, "客户异常结案对账保存失败");
            return x;
        });
    }

    public int processReconciliationDue(int limit) {
        List<WmsOmsCustomerExceptionResolutionOutboxEntity> rows = mapper.selectPage(new Page<>(1, Math.max(1, Math.min(limit, 100))), new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getStatus, "SUCCEEDED").le(WmsOmsCustomerExceptionResolutionOutboxEntity::getNextReconcileTime, LocalDateTime.now()).orderByAsc(WmsOmsCustomerExceptionResolutionOutboxEntity::getNextReconcileTime)).getRecords();
        int n = 0;
        for (WmsOmsCustomerExceptionResolutionOutboxEntity x : rows)
            try {
                if (x.getTenantId() == null) reconcile(x.getId());
                else TenantBroker.applyAs(x.getTenantId(), i -> reconcile(x.getId()));
                n++;
            } catch (Exception e) {
                log.error("客户异常结案OMS对账失败,eventId={}", x.getId(), e);
            }
        return n;
    }

    private WmsOmsCustomerExceptionResolutionOutboxEntity dispatch(Long id) {
        WmsOmsCustomerExceptionResolutionOutboxEntity row = mapper.selectById(id);
        require(row != null, "客户异常结案事件不存在");
        requireFactory(row.getFactoryCode());
        if ("SUCCEEDED".equals(row.getStatus())) return row;
        try {
            OmsCustomerExceptionResolutionCommand.Command c = command(row);
            require(Objects.equals(row.getRequestFingerprint(), hash(facts(c))), "客户异常结案Outbox冻结事实已损坏");
            R<OmsCustomerExceptionResolutionCommand.Receipt> r = omsService.receiveCustomerExceptionResolutionEvent(c);
            OmsCustomerExceptionResolutionCommand.Receipt p = r == null ? null : r.getData();
            require(r != null && r.isOk() && p != null && "SUCCEEDED".equals(p.getStatus()) && Objects.equals(p.getRequestFingerprint(), row.getRequestFingerprint()) && p.getOmsResolvedExceptionNum() != null && p.getOmsSettlementResolvedNum() != null, "OMS未返回匹配的客户异常结案永久回执");
            return tx().execute(s -> {
                WmsOmsCustomerExceptionResolutionOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    x.setStatus("SUCCEEDED").setLastError(null).setNextAttemptTime(null).setOmsResolvedExceptionNum(p.getOmsResolvedExceptionNum()).setOmsSettlementResolvedNum(p.getOmsSettlementResolvedNum()).setOmsReceiptFingerprint(p.getRequestFingerprint()).setCompletedTime(p.getCompletedTime());
                    require(mapper.updateById(x) == 1, "客户异常结案回执保存失败");
                }
                return x;
            });
        } catch (Exception e) {
            return tx().execute(s -> {
                WmsOmsCustomerExceptionResolutionOutboxEntity x = lock(id);
                if (!"SUCCEEDED".equals(x.getStatus())) {
                    int a = Optional.ofNullable(x.getAttemptCount()).orElse(0) + 1;
                    String m = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
                    x.setStatus(a >= 5 ? "NEEDS_MANUAL" : "FAILED").setAttemptCount(a).setNextAttemptTime(a >= 5 ? null : LocalDateTime.now().plusSeconds(Math.min(1800, 60L << Math.min(a, 5)))).setLastError(m.substring(0, Math.min(1000, m.length())));
                    require(mapper.updateById(x) == 1, "客户异常结案失败原因保存失败");
                }
                return x;
            });
        }
    }

    private OmsCustomerExceptionResolutionCommand.Command command(WmsOmsCustomerExceptionResolutionOutboxEntity x) {
        return new OmsCustomerExceptionResolutionCommand.Command().setRequestId(x.getRequestId()).setResolutionCaseId(x.getResolutionCaseId()).setCaseNo(x.getCaseNo()).setOrderCode(x.getOrderCode()).setOrderDetailId(x.getOrderDetailId()).setProductCode(x.getProductCode()).setRefundNum(x.getRefundNum()).setConcessionNum(x.getConcessionNum()).setRefundAmount(x.getRefundAmount()).setConcessionAmount(x.getConcessionAmount()).setRefundReference(x.getRefundReference()).setConcessionReference(x.getConcessionReference()).setEvidence(x.getEvidence()).setSettlementTime(x.getSettlementTime()).setFactoryCode(x.getFactoryCode());
    }

    private String facts(OmsCustomerExceptionResolutionCommand.Command c) {
        return JSONUtil.toJsonStr(Arrays.asList(c.getResolutionCaseId(), c.getCaseNo(), c.getOrderCode(), c.getOrderDetailId(), c.getProductCode(), decimal(c.getRefundNum()), decimal(c.getConcessionNum()), decimal(zero(c.getRefundAmount())), decimal(zero(c.getConcessionAmount())), c.getRefundReference(), c.getConcessionReference(), c.getEvidence(), c.getSettlementTime(), c.getFactoryCode()));
    }

    private WmsOmsCustomerExceptionResolutionOutboxEntity lock(Long id) {
        WmsOmsCustomerExceptionResolutionOutboxEntity x = mapper.selectOne(new LambdaQueryWrapper<WmsOmsCustomerExceptionResolutionOutboxEntity>().eq(WmsOmsCustomerExceptionResolutionOutboxEntity::getId, id).last("FOR UPDATE"));
        require(x != null, "客户异常结案事件不存在");
        return x;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
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

    private void requireFactory(String f) {
        String s = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(f) && (StrUtil.isBlank(s) || Objects.equals(s, f)), "当前工厂无权操作客户异常结案事件");
    }

    private void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
