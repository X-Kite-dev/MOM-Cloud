package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesWipTransferCommandCodec.require;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.dto.MesWipTransferRequestDTO.*;
import com.kitex.mom.mes.mapper.MesWipTransferRequestMapper;
import com.kitex.mom.mes.mapper.MesWipTransferRequestMapper.CancellationRow;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MesWipTransferRequestStore {
    private final MesWipTransferRequestMapper mapper;

    boolean installed() {
        int n = mapper.installed();
        if (n == 0) return false;
        require(n == 1 && Objects.equals(mapper.ready(), 1), "转运原请求保护未完整就绪");
        return true;
    }

    void ready() {
        require(installed(), "转运原请求保护尚未安装，请完成升级");
        requireReadCommitted();
    }

    void assertOpen(MesWipTransferCommand c, String factory) {
        if (!installed()) return;
        requireReadCommitted();
        var rows =
                mapper.cancellations(TenantContextHolder.getTenantId(), factory, c.getRequestId());
        require(rows != null && rows.isEmpty(), "此原请求已终止或存在冲突，禁止继续执行");
    }

    private void requireReadCommitted() {
        require("read committed".equals(mapper.isolation()), "转运服务事务配置不兼容，请联系管理员核对后继续");
    }

    Receipt inspect(Envelope e, boolean enabled) {
        var c = e.command();
        String fp = MesWipTransferCommandCodec.fingerprint(c, Long.valueOf(e.actorId()));
        var ops = mapper.operations(Long.valueOf(e.tenantId()), e.factoryCode(), c.getRequestId());
        var cancels =
                enabled
                        ? mapper.cancellations(
                                Long.valueOf(e.tenantId()), e.factoryCode(), c.getRequestId())
                        : List.<CancellationRow>of();
        require(
                ops != null
                        && ops.size() <= 1
                        && cancels != null
                        && cancels.size() <= 1
                        && (ops.isEmpty() || cancels.isEmpty()),
                "转运原请求存在重复或成功/终止冲突");
        if (!ops.isEmpty()) {
            var o = ops.get(0);
            require(
                    Objects.equals(o.getTenantId(), Long.valueOf(e.tenantId()))
                            && Objects.equals(o.getFactoryCode(), e.factoryCode())
                            && Objects.equals(o.getOperatorId(), Long.valueOf(e.actorId()))
                            && Objects.equals(o.getRequestId(), c.getRequestId())
                            && Objects.equals(o.getOperationType(), c.getOperationType())
                            && Objects.equals(o.getFingerprint(), fp)
                            && "0".equals(o.getDelFlag())
                            && o.getId() != null
                            && o.getId() > 0
                            && o.getTransferId() != null
                            && o.getTransferId() > 0
                            && o.getSequenceNo() != null
                            && o.getSequenceNo() > 0
                            && Objects.equals(o.getReason(), c.getReason()),
                    "原回执与本人原请求正文或实际归属不一致");
            require(
                    o.getQuantity() != null
                            && o.getQuantity()
                                            .compareTo(
                                                    c.getQuantity() == null
                                                            ? BigDecimal.ZERO
                                                            : c.getQuantity())
                                    == 0
                            && o.getBeforeRemaining() != null
                            && o.getAfterRemaining() != null
                            && o.getBeforeRemaining().signum() >= 0
                            && o.getAfterRemaining().signum() >= 0
                            && Set.of("TRANSIT", "RETURNING", "HOLD", "CLOSED")
                                    .contains(Objects.toString(o.getAfterStatus(), "")),
                    "原操作数量或状态不完整");
            if ("DISPATCH".equals(c.getOperationType()))
                require(
                        o.getSequenceNo() == 1
                                && "NONE".equals(o.getBeforeStatus())
                                && "TRANSIT".equals(o.getAfterStatus())
                                && o.getBeforeRemaining().signum() == 0
                                && o.getAfterRemaining().compareTo(c.getQuantity()) == 0
                                && o.getResultStockId() == null
                                && o.getSourceBeforeNum() != null
                                && o.getSourceAfterNum() != null
                                && o.getSourceBeforeNum().compareTo(c.getExpectedSourceQuantity())
                                        == 0
                                && o.getSourceAfterNum().signum() >= 0
                                && o.getSourceBeforeNum()
                                                .subtract(c.getQuantity())
                                                .compareTo(o.getSourceAfterNum())
                                        == 0,
                        "原发出回执的库存与在途数量矛盾");
            if (!"DISPATCH".equals(c.getOperationType()))
                require(
                        Objects.equals(o.getTransferId(), c.getTransferId())
                                && o.getSequenceNo() == c.getExpectedRevision() + 1,
                        "原操作转运单或版本与请求不一致");
            return result(
                    e,
                    "COMMITTED",
                    fp,
                    new Operation(
                            o.getId().toString(),
                            o.getTransferId().toString(),
                            o.getSequenceNo().toString(),
                            number(o.getQuantity()),
                            number(o.getBeforeRemaining()),
                            number(o.getAfterRemaining()),
                            o.getBeforeStatus(),
                            o.getAfterStatus(),
                            o.getResultStockId() == null ? null : o.getResultStockId().toString(),
                            number(o.getSourceBeforeNum()),
                            number(o.getSourceAfterNum()),
                            o.getReason(),
                            o.getCreateTime() == null ? null : o.getCreateTime().toString()),
                    null);
        }
        if (!cancels.isEmpty()) {
            var r = cancels.get(0);
            require(
                    Objects.equals(r.getTenantId(), Long.valueOf(e.tenantId()))
                            && Objects.equals(r.getFactoryCode(), e.factoryCode())
                            && Objects.equals(r.getActorId(), Long.valueOf(e.actorId()))
                            && Objects.equals(r.getRequestId(), c.getRequestId())
                            && Objects.equals(r.getOperationType(), c.getOperationType())
                            && Objects.equals(r.getFingerprint(), fp)
                            && Objects.equals(r.getCommandJson(), MesWipTransferCommandCodec.raw(c))
                            && r.getCanceledAt() != null
                            && r.getCanceledAt() > 0
                            && r.getReason() != null
                            && !r.getReason().isBlank(),
                    "原终止记录与本人原请求正文不一致");
            return result(
                    e,
                    "CANCELED",
                    fp,
                    null,
                    new Cancellation(r.getReason(), r.getCanceledAt().toString()));
        }
        return result(e, "NOT_FOUND", fp, null, null);
    }

    void cancel(Envelope e, String reason) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "原请求终止必须在实际事务中保存");
        var r = new CancellationRow();
        r.setTenantId(Long.valueOf(e.tenantId()));
        r.setFactoryCode(e.factoryCode());
        r.setActorId(Long.valueOf(e.actorId()));
        r.setRequestId(e.command().getRequestId());
        r.setOperationType(e.command().getOperationType());
        r.setCommandJson(MesWipTransferCommandCodec.raw(e.command()));
        r.setFingerprint(MesWipTransferCommandCodec.fingerprint(e.command(), r.getActorId()));
        r.setReason(reason);
        r.setCanceledAt(System.currentTimeMillis());
        require(mapper.insertCancel(r) == 1, "原请求终止保存失败，整笔回滚");
    }

    private Receipt result(Envelope e, String state, String fp, Operation op, Cancellation cancel) {
        return new Receipt(
                e.schemaVersion(),
                e.tenantId(),
                e.factoryCode(),
                e.actorId(),
                e.command().getRequestId(),
                e.command().getOperationType(),
                state,
                fp,
                op,
                cancel,
                "NOT_FOUND".equals(state) ? null : e.command().getDispatchTarget());
    }

    private static String number(BigDecimal n) {
        return n == null ? null : n.stripTrailingZeros().toPlainString();
    }
}
