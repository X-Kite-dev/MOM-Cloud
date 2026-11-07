package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesWipTransferCommandCodec.require;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.*;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipDestinationChangeDTO.*;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesWipDestinationChangeMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 保留原发出目标，按真实退回签收的新库存追溯重新发出；本类不写业务事实。 */
@Service
@RequiredArgsConstructor
public class MesWipDestinationChangeService {
    private final MesActualFactoryAccessService access;
    private final MesWipDestinationChangeMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View query(Long id, Long afterId) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(id != null && id > 0 && (afterId == null || afterId > 0), "须提供精确转运单ID与游标");
        var actor = SecurityUtils.getUser();
        require(actor != null && actor.getId() != null && actor.getId() > 0, "当前用户身份缺失");
        var t = mapper.transfer(tenant, factory, id);
        scope(t, tenant, factory, id);
        MesWipTransferOriginVerifier.verify(t, mapper.dispatch(tenant, factory, id));
        BigDecimal sum = BigDecimal.ZERO;
        for (var q :
                Arrays.asList(
                        t.getRemainingQuantity(),
                        t.getReceivedQuantity(),
                        t.getReturnedQuantity(),
                        t.getQuarantinedQuantity(),
                        t.getLostQuantity())) sum = sum.add(number(q));
        require(sum.compareTo(number(t.getQuantity())) == 0, "转运数量不守恒，须先核对原单");
        var totals = mapper.totals(tenant, factory, id);
        require(
                totals != null
                        && totals.receiptCount() >= 0
                        && totals.receiptCount() == totals.stockCount()
                        && totals.receiptCount() == totals.quantityCount()
                        && same(number(totals.quantity()), t.getReturnedQuantity())
                        && (totals.receiptCount() == 0 || number(totals.minimum()).signum() > 0),
                "全部退回签收记录与累计退回数量不一致，须先核对原单");
        Long returnSequence = verifyReturnRequest(t, mapper.returnRequest(tenant, factory, id));
        var operations = mapper.receipts(tenant, factory, id, afterId);
        require(operations != null && operations.size() <= 21, "签收查询超出分页范围");
        require(
                afterId != null || operations.size() == Math.min(21L, totals.receiptCount()),
                "退回签收分页与全量记录不一致");
        var page = operations.subList(0, Math.min(20, operations.size()));
        Set<Long> stocks = new LinkedHashSet<>();
        long last = afterId == null ? 0 : afterId;
        BigDecimal pageQuantity = BigDecimal.ZERO;
        for (var op : page) {
            require(op.getId() != null && op.getId() > last, "退回签收游标顺序不一致");
            last = op.getId();
            verifyReceipt(t, op, returnSequence);
            require(stocks.add(op.getResultStockId()), "退回签收重复指向同一库存");
            pageQuantity = pageQuantity.add(op.getQuantity());
        }
        require(
                pageQuantity.compareTo(t.getReturnedQuantity()) <= 0
                        && (afterId != null
                                || t.getReturnedQuantity().signum() == 0
                                || !page.isEmpty()),
                "签收原件与累计退回数量不一致");
        var facts = MesWipDestinationChangeFacts.load(mapper, tenant, factory, List.copyOf(stocks));
        var rows = new ArrayList<Receipt>();
        for (var op : page) {
            var stock = facts.stocks().get(op.getResultStockId());
            if (stock != null) {
                stockIdentity(stock, t, op.getResultStockId());
                require(
                        number(stock.getNum()).compareTo(op.getQuantity()) <= 0,
                        "当前库存超过该次签收数量，须核对实物来源");
            }
            var issues = facts.issues().getOrDefault(op.getResultStockId(), List.of());
            var links = new ArrayList<Issue>();
            for (var issue : issues.subList(0, Math.min(5, issues.size()))) {
                scope(issue, tenant, factory, issue.getId());
                require(
                        issue.getId() > 0
                                && !Objects.equals(issue.getId(), t.getId())
                                && Objects.equals(issue.getSourceStockId(), op.getResultStockId()),
                        "后续转运不属于该签收库存或顺序不一致");
                MesWipTransferOriginVerifier.verify(
                        issue, facts.originals().getOrDefault(issue.getId(), List.of()));
                require(
                        Objects.equals(issue.getSourceReportId(), t.getSourceReportId())
                                && Objects.equals(issue.getMatCode(), t.getMatCode())
                                && Objects.equals(issue.getMatBatch(), t.getMatBatch())
                                && Objects.equals(issue.getMatBarcode(), t.getMatBarcode())
                                && Objects.equals(issue.getMatUnit(), t.getMatUnit()),
                        "后续转运实物身份与退回库存不一致");
                links.add(
                        new Issue(
                                issue.getId().toString(),
                                issue.getToLocationCode(),
                                plain(issue.getQuantity()),
                                issue.getStatus()));
            }
            rows.add(
                    new Receipt(
                            op.getId().toString(),
                            op.getResultStockId().toString(),
                            plain(op.getQuantity()),
                            stock == null ? null : plain(stock.getNum()),
                            op.getLocationCode(),
                            stock == null ? null : stock.getPhysicalLocationCode(),
                            canDispatch(stock, t),
                            List.copyOf(links),
                            issues.size() > 5));
        }
        return new View(
                "MES_WIP_DESTINATION_CHANGE@1",
                tenant.toString(),
                factory,
                actor.getId().toString(),
                id.toString(),
                t.getRevision().toString(),
                t.getStatus(),
                Boolean.TRUE.equals(t.getIndivisible()),
                t.getResumeStatus(),
                t.getFromLocationCode(),
                t.getToLocationCode(),
                t.getMatUnit(),
                plain(t.getQuantity()),
                plain(t.getRemainingQuantity()),
                plain(t.getReceivedQuantity()),
                plain(t.getReturnedQuantity()),
                plain(t.getQuarantinedQuantity()),
                plain(t.getLostQuantity()),
                List.copyOf(rows),
                operations.size() > 20,
                operations.size() > 20 ? Long.toString(last) : null);
    }

    private static Long verifyReturnRequest(
            MesWipTransferEntity t, List<MesWipTransferOperationEntity> requests) {
        try {
            require(requests != null && requests.size() <= 1, "缺少唯一退回申请原件");
            if (requests.isEmpty()) {
                require(
                        t.getReturnUserId() == null
                                && t.getReturnedQuantity().signum() == 0
                                && !"RETURNING".equals(t.getStatus())
                                && !("HOLD".equals(t.getStatus())
                                        && "RETURNING".equals(t.getResumeStatus())),
                        "退回方向与申请原件不一致");
                return null;
            }
            var op = requests.get(0);
            require(
                    op.getId() != null
                            && op.getId() > 0
                            && Objects.equals(op.getTenantId(), t.getTenantId())
                            && Objects.equals(op.getFactoryCode(), t.getFactoryCode())
                            && "0".equals(op.getDelFlag())
                            && Objects.equals(op.getTransferId(), t.getId())
                            && "REQUEST_RETURN".equals(op.getOperationType())
                            && op.getSequenceNo() != null
                            && op.getSequenceNo() > 1
                            && op.getSequenceNo() <= t.getRevision()
                            && Objects.equals(op.getOperatorId(), t.getReturnUserId())
                            && op.getOperatorId() != null
                            && op.getOperatorId() > 0,
                    "退回申请身份或版本不一致");
            var e = object(op.getEvidence());
            var previous = e.getJSONObject("before");
            var command = e.getJSONObject("command");
            require(previous != null && command != null, "退回申请命令未留存");
            var c = JSONUtil.toBean(command, MesWipTransferCommand.class);
            MesWipTransferCommandCodec.normalize(c);
            require(
                    "REQUEST_RETURN".equals(c.getOperationType())
                            && Objects.equals(c.getTransferId(), integer(command, "transferId"))
                            && Objects.equals(c.getTransferId(), t.getId())
                            && c.getSourceStockId() == null
                            && Objects.equals(
                                    c.getExpectedRevision(), integer(command, "expectedRevision"))
                            && c.getExpectedRevision() == op.getSequenceNo() - 1
                            && Objects.equals(c.getRequestId(), op.getRequestId())
                            && Objects.equals(c.getReason(), op.getReason())
                            && Objects.equals(
                                    MesWipTransferCommandCodec.fingerprint(c, op.getOperatorId()),
                                    op.getFingerprint()),
                    "退回申请命令或指纹不一致");
            require(
                    Objects.equals(integer(previous, "id"), t.getId())
                            && Objects.equals(integer(previous, "tenantId"), t.getTenantId())
                            && Objects.equals(previous.getStr("factoryCode"), t.getFactoryCode())
                            && Objects.equals(
                                    integer(previous, "revision"), c.getExpectedRevision())
                            && Objects.equals(
                                    previous.getStr("sourceSnapshot"), t.getSourceSnapshot())
                            && Objects.equals(
                                    previous.getStr("locationSnapshot"), t.getLocationSnapshot())
                            && Objects.equals(
                                    previous.getStr("fromLocationCode"), t.getFromLocationCode())
                            && Objects.equals(
                                    previous.getStr("toLocationCode"), t.getToLocationCode())
                            && Objects.equals(previous.getStr("status"), op.getBeforeStatus())
                            && ("TRANSIT".equals(op.getBeforeStatus())
                                    || "HOLD".equals(op.getBeforeStatus())
                                            && "TRANSIT".equals(previous.getStr("resumeStatus")))
                            && "RETURNING".equals(op.getAfterStatus())
                            && same(op.getQuantity(), BigDecimal.ZERO)
                            && number(op.getBeforeRemaining()).signum() > 0
                            && same(op.getBeforeRemaining(), op.getAfterRemaining())
                            && same(
                                    op.getBeforeRemaining(),
                                    previous.getBigDecimal("remainingQuantity")),
                    "退回申请前原单、方向或数量不一致");
            return op.getSequenceNo();
        } catch (CheckedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CheckedException("退回申请原件损坏，不能继续办理", e);
        }
    }

    private static void verifyReceipt(
            MesWipTransferEntity t, MesWipTransferOperationEntity op, Long returnSequence) {
        try {
            require(
                    Objects.equals(op.getTenantId(), t.getTenantId())
                            && Objects.equals(op.getFactoryCode(), t.getFactoryCode())
                            && "0".equals(op.getDelFlag())
                            && Objects.equals(op.getTransferId(), t.getId())
                            && "RECEIVE_RETURN".equals(op.getOperationType())
                            && op.getSequenceNo() != null
                            && op.getSequenceNo() > 1
                            && returnSequence != null
                            && op.getSequenceNo() > returnSequence
                            && op.getSequenceNo() <= t.getRevision()
                            && op.getOperatorId() != null
                            && op.getOperatorId() > 0,
                    "退回签收身份或版本不一致");
            var evidence = object(op.getEvidence());
            var before = object(evidence.getStr("before"));
            var cmd = before.getJSONObject("command");
            var previous = before.getJSONObject("before");
            require(cmd != null && previous != null, "退回签收命令未留存");
            var c = JSONUtil.toBean(cmd, MesWipTransferCommand.class);
            MesWipTransferCommandCodec.normalize(c);
            require(
                    "RECEIVE_RETURN".equals(c.getOperationType())
                            && Objects.equals(c.getTransferId(), integer(cmd, "transferId"))
                            && Objects.equals(c.getTransferId(), t.getId())
                            && c.getSourceStockId() == null
                            && Objects.equals(
                                    c.getExpectedRevision(), integer(cmd, "expectedRevision"))
                            && c.getExpectedRevision() == op.getSequenceNo() - 1
                            && Objects.equals(c.getRequestId(), op.getRequestId())
                            && Objects.equals(
                                    MesWipTransferCommandCodec.fingerprint(c, op.getOperatorId()),
                                    op.getFingerprint())
                            && Boolean.TRUE.equals(c.getConditionConfirmed())
                            && Objects.equals(c.getReason(), op.getReason())
                            && same(c.getQuantity(), op.getQuantity())
                            && number(op.getQuantity()).signum() > 0,
                    "退回签收命令、数量或指纹不一致");
            require(
                    Objects.equals(integer(previous, "id"), t.getId())
                            && Objects.equals(integer(previous, "tenantId"), t.getTenantId())
                            && Objects.equals(previous.getStr("factoryCode"), t.getFactoryCode())
                            && Objects.equals(
                                    previous.getStr("fromLocationCode"), t.getFromLocationCode())
                            && Objects.equals(
                                    previous.getStr("toLocationCode"), t.getToLocationCode())
                            && Objects.equals(
                                    integer(previous, "revision"), c.getExpectedRevision())
                            && "RETURNING".equals(previous.getStr("status"))
                            && "RETURNING".equals(op.getBeforeStatus())
                            && !Objects.equals(
                                    integer(previous, "returnUserId"), op.getOperatorId())
                            && Objects.equals(
                                    integer(previous, "returnUserId"), t.getReturnUserId())
                            && Objects.equals(
                                    previous.getStr("sourceSnapshot"), t.getSourceSnapshot())
                            && Objects.equals(
                                    previous.getStr("locationSnapshot"), t.getLocationSnapshot()),
                    "退回签收前原单、版本或独立接收身份不一致");
            require(
                    same(previous.getBigDecimal("remainingQuantity"), op.getBeforeRemaining())
                            && number(op.getAfterRemaining()).signum() >= 0
                            && same(
                                    number(op.getBeforeRemaining()).subtract(op.getQuantity()),
                                    op.getAfterRemaining())
                            && (op.getAfterRemaining().signum() == 0 ? "CLOSED" : "RETURNING")
                                    .equals(op.getAfterStatus()),
                    "退回签收前后数量不守恒");
            var loc = evidence.getJSONObject("location");
            var result = evidence.getJSONObject("result");
            var warehouse =
                    JSONUtil.parseArray(t.getLocationSnapshot())
                            .getJSONObject(0)
                            .getStr("warehouseCode");
            require(
                    loc != null
                            && result != null
                            && Objects.equals(integer(loc, "tenantId"), t.getTenantId())
                            && Objects.equals(loc.getStr("factoryCode"), t.getFactoryCode())
                            && Objects.equals(loc.getStr("locationCode"), t.getFromLocationCode())
                            && Objects.equals(loc.getStr("warehouseCode"), warehouse)
                            && Boolean.TRUE.equals(loc.get("status"))
                            && Integer.valueOf(3).equals(loc.getInt("locationLevel"))
                            && Objects.equals(op.getLocationCode(), t.getFromLocationCode())
                            && Objects.equals(c.getScannedLocationCode(), t.getFromLocationCode())
                            && Objects.equals(
                                    StrUtil.blankToDefault(c.getObservedBarcode(), ""),
                                    StrUtil.blankToDefault(t.getMatBarcode(), "")),
                    "退回签收实物或扫描库位与原处不一致");
            require(
                    op.getResultStockId() != null
                            && Objects.equals(integer(result, "id"), op.getResultStockId())
                            && Objects.equals(integer(result, "tenantId"), t.getTenantId())
                            && Objects.equals(
                                    integer(result, "sourceReportId"), t.getSourceReportId()),
                    "退回签收库存主键不一致");
            var saved = JSONUtil.toBean(result, MesWarehouseLiveStockEntity.class);
            stockIdentity(saved, t, op.getResultStockId());
            require(
                    same(saved.getNum(), op.getQuantity())
                            && warehouseMatches(saved, t)
                            && Objects.equals(
                                    saved.getPhysicalLocationCode(), t.getFromLocationCode()),
                    "退回签收库存数量或实际位置不一致");
        } catch (CheckedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CheckedException("退回签收原件损坏，不能继续办理", e);
        }
    }

    private static void scope(MesWipTransferEntity t, Long tenant, String factory, Long id) {
        require(
                t != null
                        && Objects.equals(t.getId(), id)
                        && Objects.equals(t.getTenantId(), tenant)
                        && Objects.equals(t.getFactoryCode(), factory)
                        && "0".equals(t.getDelFlag()),
                "当前实际工厂没有此转运单");
    }

    private static void stockIdentity(
            MesWarehouseLiveStockEntity s, MesWipTransferEntity t, Long id) {
        require(
                Objects.equals(s.getId(), id)
                        && Objects.equals(s.getTenantId(), t.getTenantId())
                        && Objects.equals(s.getFactoryCode(), t.getFactoryCode())
                        && "0".equals(s.getDelFlag())
                        && "WIP".equals(s.getMatDetailType())
                        && Objects.equals(s.getSourceReportId(), t.getSourceReportId())
                        && Objects.equals(s.getBillNo(), t.getArrangeCode())
                        && Objects.equals(s.getProduceTaskCode(), t.getProduceTaskCode())
                        && Objects.equals(s.getMatCode(), t.getMatCode())
                        && Objects.equals(s.getMatBatch(), t.getMatBatch())
                        && Objects.equals(s.getMatBarcode(), t.getMatBarcode())
                        && Objects.equals(s.getMatUnit(), t.getMatUnit()),
                "签收库存与原报工实物身份不一致");
    }

    private static boolean canDispatch(MesWarehouseLiveStockEntity s, MesWipTransferEntity t) {
        return s != null
                && s.getNum().signum() > 0
                && "0".equals(s.getStatus())
                && Set.of("RELEASED", "NOT_REQUIRED")
                        .contains(Objects.toString(s.getInspectionStatus(), ""))
                && (s.getJudgeStatus() == null || s.getJudgeStatus().isBlank())
                && s.getQualityCaseId() == null
                && (s.getMatValidEndDate() == null
                        || !s.getMatValidEndDate().isBefore(LocalDate.now()))
                && warehouseMatches(s, t)
                && Objects.equals(s.getPhysicalLocationCode(), t.getFromLocationCode());
    }

    private static boolean warehouseMatches(
            MesWarehouseLiveStockEntity stock, MesWipTransferEntity t) {
        return StrUtil.isBlank(stock.getWarehouseCode())
                || Objects.equals(
                        stock.getWarehouseCode(),
                        JSONUtil.parseArray(t.getLocationSnapshot())
                                .getJSONObject(0)
                                .getStr("warehouseCode"));
    }

    private static JSONObject object(String raw) {
        require(
                raw != null && raw.length() <= 262144 && JSONUtil.isTypeJSONObject(raw),
                "签收原文缺失或超出范围");
        return JSONUtil.parseObj(raw);
    }

    private static Long integer(JSONObject o, String key) {
        String s = Objects.toString(o.get(key), "");
        require(s.matches("[1-9][0-9]{0,18}"), "签收主键或版本格式不完整");
        return Long.valueOf(s);
    }

    private static BigDecimal number(BigDecimal n) {
        require(
                n != null && n.signum() >= 0 && n.scale() <= 8 && n.precision() - n.scale() <= 16,
                "实物数量缺失或超出24/8精度");
        return n;
    }

    private static String plain(BigDecimal n) {
        return number(n).stripTrailingZeros().toPlainString();
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
}
