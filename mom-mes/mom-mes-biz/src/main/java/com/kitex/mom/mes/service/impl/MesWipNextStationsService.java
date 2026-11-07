package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesWipNextStationsDTO;
import com.kitex.mom.mes.dto.MesWipNextStationsDTO.*;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesWipNextStationsMapper;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 下一站只读参考：不改库存/物理库位，不确认余量、开工或AGV到站。 */
@Service
@RequiredArgsConstructor
public class MesWipNextStationsService {
    private final MesActualFactoryAccessService access;
    private final MesWipNextStationsMapper mapper;
    private final MesEngineeringPackageService engineering;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View query(Long stockId, Long afterId) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(
                stockId != null && stockId > 0 && (afterId == null || afterId > 0),
                "须提供精确正整数库存ID和游标");
        var stock = mapper.stock(tenant, factory, stockId);
        require(
                stock != null
                        && stock.getNum() != null
                        && stock.getNum().signum() > 0
                        && stock.getSourceReportId() != null,
                "当前实际工厂没有有效正量WIP来源");
        return routing(
                new MesWipTransferOriginVerifier.Origin(
                        stock.getId(),
                        stock.getSourceReportId(),
                        stock.getProduceTaskCode(),
                        stock.getBillNo(),
                        stock.getMatCode(),
                        null),
                afterId,
                factory);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TransferView queryTransfer(Long transferId, Long afterId) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(
                transferId != null && transferId > 0 && (afterId == null || afterId > 0),
                "须提供精确转运单ID和游标");
        var doc = mapper.transfer(tenant, factory, transferId);
        require(doc != null, "当前实际工厂没有此转运单");
        var originals = mapper.dispatch(tenant, factory, transferId);
        var origin = MesWipTransferOriginVerifier.verify(doc, originals);
        var routing = routing(origin, afterId, factory);
        var dispatch = originals.get(0);
        return new TransferView(
                MesWipNextStationsDTO.TRANSFER_SCHEMA,
                routing.tenantId(),
                factory,
                routing.actorId(),
                transferId.toString(),
                doc.getRevision().toString(),
                doc.getStatus(),
                doc.getQuantity().stripTrailingZeros().toPlainString(),
                doc.getRemainingQuantity().stripTrailingZeros().toPlainString(),
                doc.getMatUnit(),
                doc.getFromLocationCode(),
                doc.getToLocationCode(),
                origin.stockId().toString(),
                origin.reportId().toString(),
                dispatch.getId().toString(),
                dispatch.getRequestId(),
                dispatch.getFingerprint(),
                origin.boundTarget(),
                routing);
    }

    private View routing(
            MesWipTransferOriginVerifier.Origin reference, Long afterId, String factory) {
        Long tenant = TenantContextHolder.getTenantId(), stockId = reference.stockId();
        var report = mapper.report(tenant, factory, reference.reportId());

        require(
                report != null
                        && "EFFECTIVE".equals(report.getStatus())
                        && text(report.getTaskCode())
                        && text(report.getProduceTaskCode())
                        && text(report.getArrangeCode())
                        && text(report.getMatCode())
                        && Objects.equals(reference.matCode(), report.getMatCode())
                        && Objects.equals(reference.operationCode(), report.getProduceTaskCode())
                        && Objects.equals(reference.arrangeCode(), report.getArrangeCode()),
                "库存与有效原报工的产品、工序或派工不一致");
        var order = one(mapper.orders(tenant, factory, report.getTaskCode()));
        require(Objects.equals(order.getMatCode(), report.getMatCode()), "原工单产品与报工不一致");
        var operations = mapper.operations(tenant, factory, order.getCode());
        require(
                operations != null && !operations.isEmpty() && operations.size() <= 1000,
                "原完整工序任务集缺失或超过1000节点，不能截断解释下一站");
        var source =
                one(
                        operations.stream()
                                .filter(o -> report.getProduceTaskCode().equals(o.getCode()))
                                .toList());
        var arrange =
                one(
                        mapper.sourceArrange(
                                tenant, factory, order.getCode(), report.getArrangeCode()));
        require(
                Objects.equals(arrange.getProduceTaskCode(), source.getCode())
                        && Objects.equals(arrange.getRouteCode(), source.getRouteCode())
                        && Objects.equals(arrange.getMatCode(), order.getMatCode())
                        && Objects.equals(source.getMatCode(), order.getMatCode()),
                "原派工与实际来源工序不一致");
        if (text(order.getReworkRequestId())
                || order.getReworkMaterialPlanId() != null
                || "REWORK".equals(order.getResourceType()))
            return view(
                    stockId,
                    afterId,
                    factory,
                    order,
                    source,
                    "REWORK_UNAVAILABLE",
                    List.of(),
                    List.of(),
                    null);
        boolean legacy =
                operations.stream()
                        .allMatch(
                                o ->
                                        o.getRouteExecutionSchemaVersion() == null
                                                && o.getPredecessorNodeCodes() == null
                                                && o.getSuccessorNodeCodes() == null
                                                && o.getNodeTimingRulesJson() == null);
        if (legacy)
            return view(
                    stockId,
                    afterId,
                    factory,
                    order,
                    source,
                    "LEGACY_UNAVAILABLE",
                    List.of(),
                    List.of(),
                    null);
        var graph = MesFrozenExecutionGraph.of(operations);
        graph.current(arrange);
        require(
                operations.stream()
                        .allMatch(
                                o ->
                                        MesFrozenExecutionGraph.NODE_EXECUTION_V1.equals(
                                                        o.getRouteExecutionSchemaVersion())
                                                && Objects.equals(
                                                        o.getProcessRouteId(),
                                                        order.getProcessRouteId())
                                                && Objects.equals(
                                                        o.getProcessRouteCode(),
                                                        order.getProcessRouteCode())
                                                && Objects.equals(
                                                        o.getMatCode(), order.getMatCode())),
                "冻结执行图与原工单路线或产品不一致");
        var approved = engineering.executionRoutes(engineering.readIssuedOriginal(order));
        require(approved != null && approved.size() == operations.size(), "原工程路线与完整实际任务集不一致");
        var unique = new HashSet<String>();
        for (var op : operations) {
            var node =
                    one(
                            approved.stream()
                                    .filter(
                                            n ->
                                                    Objects.equals(
                                                            op.getSourceRouteNodeCode(),
                                                            n.getRouteNodeCode()))
                                    .toList());
            require(
                    unique.add(node.getRouteNodeCode())
                            && Objects.equals(op.getRouteCode(), node.getProcessCode())
                            && node.getPreRouteNodeCodes() != null
                            && node.getNextRouteNodeCodes() != null
                            && Objects.equals(
                                    op.getPredecessorNodeCodes(),
                                    String.join(",", node.getPreRouteNodeCodes()))
                            && Objects.equals(
                                    op.getSuccessorNodeCodes(),
                                    String.join(",", node.getNextRouteNodeCodes())),
                    "实际冻结节点或边与原工程包不一致");
        }
        var next = graph.successors(source);
        if (next.isEmpty())
            return view(
                    stockId,
                    afterId,
                    factory,
                    order,
                    source,
                    "END_OF_ROUTE",
                    List.of(),
                    List.of(),
                    null);
        var rows =
                mapper.stations(
                        tenant,
                        factory,
                        order.getCode(),
                        next.stream().map(MesProduceTaskEntity::getCode).toList(),
                        afterId);
        require(rows != null && rows.size() <= 21, "下一站派工查询超出范围");
        var parents = new HashMap<String, MesProduceTaskEntity>();
        next.forEach(o -> parents.put(o.getCode(), o));
        long previous = afterId == null ? 0 : afterId;
        for (var row : rows) {
            require(row.getId() != null && row.getId() > previous, "下一站派工游标未严格递增");
            previous = row.getId();
            MesFrozenExecutionGraph.assertArrangement(parents.get(row.getProduceTaskCode()), row);
            require(Objects.equals(order.getMatCode(), row.getMatCode()), "下一站派工产品与原工单不一致");
        }
        boolean more = rows.size() > 20;
        var page = rows.stream().limit(20).map(this::station).toList();
        return view(
                stockId,
                afterId,
                factory,
                order,
                source,
                "READY",
                next.stream().map(this::operation).toList(),
                page,
                more ? page.get(page.size() - 1).arrangeId() : null);
    }

    private View view(
            Long stock,
            Long after,
            String factory,
            MesTaskEntity order,
            MesProduceTaskEntity source,
            String state,
            List<Operation> next,
            List<Station> stations,
            String cursor) {
        var actor = SecurityUtils.getUser();
        require(actor != null && actor.getId() != null && actor.getId() > 0, "下一站查询缺少实名人员");
        return new View(
                MesWipNextStationsDTO.SCHEMA,
                TenantContextHolder.getTenantId().toString(),
                factory,
                actor.getId().toString(),
                stock.toString(),
                after == null ? null : after.toString(),
                Instant.now().toString(),
                state,
                order.getCode(),
                operation(source),
                next,
                stations,
                cursor);
    }

    private Operation operation(MesProduceTaskEntity op) {
        return new Operation(
                op.getId().toString(),
                op.getCode(),
                op.getSourceRouteNodeCode(),
                op.getRouteCode(),
                op.getRouteName());
    }

    private Station station(MesTaskArrangeEntity a) {
        return new Station(
                a.getId().toString(),
                a.getCode(),
                a.getProduceTaskCode(),
                a.getSourceRouteNodeCode(),
                a.getRouteName(),
                a.getStatus(),
                a.getWorkshopCode(),
                a.getWorkshopName(),
                a.getActualWorksiteCode(),
                a.getPlannedWorksiteCode(),
                a.getPlannedWorksiteName(),
                a.getWorksiteCodes(),
                a.getPlannedStartTime() == null ? null : a.getPlannedStartTime().toString(),
                a.getPlannedEndTime() == null ? null : a.getPlannedEndTime().toString());
    }

    private static <T> T one(List<T> rows) {
        require(rows != null && rows.size() == 1 && rows.get(0) != null, "实际组织缺少唯一原工单、工序或派工依据");
        return rows.get(0);
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean ok, String msg) {
        if (!ok) throw new CheckedException(msg);
    }
}
