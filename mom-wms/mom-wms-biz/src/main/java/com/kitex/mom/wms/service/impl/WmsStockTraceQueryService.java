package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsStockTraceDTO;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.entity.WmsMaterialReservationDetailEntity;
import com.kitex.mom.wms.entity.WmsPickingAreaEntity;
import com.kitex.mom.wms.entity.WmsPickingEventEntity;
import com.kitex.mom.wms.entity.WmsSalesStockReservationDetailEntity;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsMaterialMoveSiteMapper;
import com.kitex.mom.wms.mapper.WmsMaterialReservationDetailMapper;
import com.kitex.mom.wms.mapper.WmsPickingAreaMapper;
import com.kitex.mom.wms.mapper.WmsPickingEventMapper;
import com.kitex.mom.wms.mapper.WmsSalesStockReservationDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按库存行、批次或条码返回WMS库存证据与精确关联的作业当前状态。
 */
@Service
@RequiredArgsConstructor
public class WmsStockTraceQueryService {
    private static final String TRACE_VIEW_PERMISSION = "wms_stockTrace_view";
    private static final String GLOBAL_STOCK_VIEW_PERMISSION = "wms_wmsLiveStock_view";
    private static final String STOCK_DETAIL_VIEW_PERMISSION = "wms_wmsLiveStock_view_stock_detail";

    private final WmsLiveStockMapper stockMapper;
    private final WmsInoutFlowMapper flowMapper;
    private final WmsMaterialMoveSiteMapper moveMapper;
    private final WmsPickingAreaMapper pickingMapper;
    private final WmsPickingEventMapper pickingEventMapper;
    private final WmsMaterialReservationDetailMapper reservationMapper;
    private final WmsSalesStockReservationDetailMapper salesReservationMapper;
    private final PermissionService permissions;
    private final RemoteWarehouseService warehouses;

    public WmsStockTraceDTO.Evidence query(WmsStockTraceDTO.Query query) {
        if (query == null) {
            throw new CheckedException("追溯查询条件不能为空");
        }
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("追溯查询必须具有租户和工厂上下文");
        }
        boolean byStock = query.getStockId() != null;
        boolean byBarcode = StrUtil.isNotBlank(query.getBarcode());
        boolean byBatch = StrUtil.isNotBlank(query.getBatchNo());
        boolean hasMaterial = StrUtil.isNotBlank(query.getMaterialCode());
        if (byStock && (byBarcode || byBatch || hasMaterial)) {
            throw new CheckedException("库存行ID不能与条码、物料编码或批次同时查询");
        }
        if (byBarcode && byBatch) {
            throw new CheckedException("条码和批次只能选择一种查询方式");
        }
        if ((byStock && query.getStockId() <= 0) || (!byStock && !byBarcode
                && !(byBatch && hasMaterial))) {
            throw new CheckedException("请提供库存行ID、条码，或物料编码加批次");
        }
        if (!permissions.hasPermission(TRACE_VIEW_PERMISSION)) {
            throw new CheckedException("无库存追溯查看权限");
        }
        WarehouseScope warehouseScope = authorizedWarehouses(tenant, factory);
        int limit = query.getLimit() == null ? 100 : Math.max(1, Math.min(query.getLimit(), 200));
        Long sourceDetailId = null;
        if (byStock) {
            WmsLiveStockEntity stock = stockMapper.selectById(query.getStockId());
            if (stock != null) {
                if (!Objects.equals(tenant, stock.getTenantId())
                        || !Objects.equals(factory, stock.getFactoryCode())) {
                    throw new CheckedException("库存行超出当前组织范围");
                }
                if (!warehouseScope.allowed().contains(stock.getWarehouseCode())) {
                    throw new CheckedException("无该库存行所在仓库的追溯查看权限");
                }
                sourceDetailId = stock.getSourceInBillDetailId();
            } else {
                List<WmsInoutFlowEntity> origin = flowMapper.selectList(flowScope(tenant, factory,
                                warehouseScope.allowed())
                        .eq(WmsInoutFlowEntity::getSourceStockId, query.getStockId())
                        .orderByAsc(WmsInoutFlowEntity::getId).last("LIMIT 1"));
                if (!origin.isEmpty()) {
                    sourceDetailId = origin.get(0).getSourceInBillDetailId();
                }
                if (sourceDetailId == null) {
                    List<WmsMaterialMoveSiteEntity> originMove = moveMapper.selectList(moveScope(tenant, factory,
                                    warehouseScope.allowed())
                            .and(scope -> scope.eq(WmsMaterialMoveSiteEntity::getSourceStockId, query.getStockId())
                                    .or().eq(WmsMaterialMoveSiteEntity::getTargetStockId, query.getStockId()))
                            .orderByAsc(WmsMaterialMoveSiteEntity::getId).last("LIMIT 1"));
                    if (!originMove.isEmpty()) {
                        sourceDetailId = originMove.get(0).getSourceInBillDetailId();
                    }
                }
            }
            if (sourceDetailId == null) {
                List<WmsPickingAreaEntity> originPicking = pickingMapper.selectList(
                        pickingScope(tenant, factory, warehouseScope.allowed())
                                .eq(WmsPickingAreaEntity::getSourceStockId, query.getStockId())
                                .isNotNull(WmsPickingAreaEntity::getSourceInBillDetailId)
                                .orderByAsc(WmsPickingAreaEntity::getId).last("LIMIT 1"));
                if (!originPicking.isEmpty()) {
                    sourceDetailId = originPicking.get(0).getSourceInBillDetailId();
                }
            }
            if (sourceDetailId == null) {
                List<WmsPickingEventEntity> originEvent = pickingEventMapper.selectList(
                        pickingEventScope(tenant, factory, warehouseScope.allowed())
                                .eq(WmsPickingEventEntity::getSourceStockId, query.getStockId())
                                .isNotNull(WmsPickingEventEntity::getSourceInBillDetailId)
                                .orderByAsc(WmsPickingEventEntity::getId).last("LIMIT 1"));
                if (!originEvent.isEmpty()) {
                    sourceDetailId = originEvent.get(0).getSourceInBillDetailId();
                }
            }
        }
        LambdaQueryWrapper<WmsLiveStockEntity> stockQuery = stockScope(tenant, factory, warehouseScope.allowed());
        LambdaQueryWrapper<WmsInoutFlowEntity> flowQuery = flowScope(tenant, factory, warehouseScope.allowed());
        LambdaQueryWrapper<WmsMaterialMoveSiteEntity> moveQuery = moveScope(tenant, factory,
                warehouseScope.allowed());
        LambdaQueryWrapper<WmsPickingAreaEntity> pickingQuery = pickingScope(tenant, factory,
                warehouseScope.allowed());
        LambdaQueryWrapper<WmsPickingEventEntity> pickingEventQuery = pickingEventScope(tenant, factory,
                warehouseScope.allowed());
        if (byStock) {
            if (sourceDetailId != null) {
                stockQuery.eq(WmsLiveStockEntity::getSourceInBillDetailId, sourceDetailId);
                flowQuery.eq(WmsInoutFlowEntity::getSourceInBillDetailId, sourceDetailId);
                moveQuery.eq(WmsMaterialMoveSiteEntity::getSourceInBillDetailId, sourceDetailId);
            } else {
                stockQuery.eq(WmsLiveStockEntity::getId, query.getStockId());
                flowQuery.eq(WmsInoutFlowEntity::getSourceStockId, query.getStockId());
                moveQuery.and(scope -> scope.eq(WmsMaterialMoveSiteEntity::getSourceStockId, query.getStockId())
                        .or().eq(WmsMaterialMoveSiteEntity::getTargetStockId, query.getStockId()));
            }
            // 当前拣货仍按实物库存行匹配，不能将同一次入库拆出的其他库存行当作目标行。
            pickingQuery.eq(WmsPickingAreaEntity::getSourceStockId, query.getStockId());
            pickingEventQuery.eq(WmsPickingEventEntity::getSourceStockId, query.getStockId());
        } else if (byBarcode) {
            String barcode = query.getBarcode().trim();
            stockQuery.eq(WmsLiveStockEntity::getMatBarcode, barcode);
            flowQuery.eq(WmsInoutFlowEntity::getMatBarcode, barcode);
            moveQuery.eq(WmsMaterialMoveSiteEntity::getMatBarcode, barcode);
            pickingQuery.eq(WmsPickingAreaEntity::getMatBarcode, barcode);
            pickingEventQuery.eq(WmsPickingEventEntity::getMatBarcode, barcode);
            if (StrUtil.isNotBlank(query.getMaterialCode())) {
                stockQuery.eq(WmsLiveStockEntity::getMatCode, query.getMaterialCode().trim());
                flowQuery.eq(WmsInoutFlowEntity::getMatCode, query.getMaterialCode().trim());
                moveQuery.eq(WmsMaterialMoveSiteEntity::getMatCode, query.getMaterialCode().trim());
                pickingQuery.eq(WmsPickingAreaEntity::getMatCode, query.getMaterialCode().trim());
                pickingEventQuery.eq(WmsPickingEventEntity::getMatCode, query.getMaterialCode().trim());
            }
        } else {
            stockQuery.eq(WmsLiveStockEntity::getMatCode, query.getMaterialCode().trim())
                    .eq(WmsLiveStockEntity::getMatBatch, query.getBatchNo().trim());
            flowQuery.eq(WmsInoutFlowEntity::getMatCode, query.getMaterialCode().trim())
                    .eq(WmsInoutFlowEntity::getMatBatch, query.getBatchNo().trim());
            moveQuery.eq(WmsMaterialMoveSiteEntity::getMatCode, query.getMaterialCode().trim())
                    .eq(WmsMaterialMoveSiteEntity::getMatBatch, query.getBatchNo().trim());
            pickingQuery.eq(WmsPickingAreaEntity::getMatCode, query.getMaterialCode().trim())
                    .eq(WmsPickingAreaEntity::getMatBatch, query.getBatchNo().trim());
            pickingEventQuery.eq(WmsPickingEventEntity::getMatCode, query.getMaterialCode().trim())
                    .eq(WmsPickingEventEntity::getMatBatch, query.getBatchNo().trim());
        }
        if (StrUtil.isNotBlank(query.getStockOwnerType())) {
            stockQuery.eq(WmsLiveStockEntity::getStockOwnerType, query.getStockOwnerType().trim());
            flowQuery.eq(WmsInoutFlowEntity::getStockOwnerType, query.getStockOwnerType().trim());
            moveQuery.eq(WmsMaterialMoveSiteEntity::getStockOwnerType, query.getStockOwnerType().trim());
            pickingQuery.eq(WmsPickingAreaEntity::getStockOwnerType, query.getStockOwnerType().trim());
            pickingEventQuery.eq(WmsPickingEventEntity::getStockOwnerType, query.getStockOwnerType().trim());
        }
        if (StrUtil.isNotBlank(query.getStockOwnerCode())) {
            stockQuery.eq(WmsLiveStockEntity::getStockOwnerCode, query.getStockOwnerCode().trim());
            flowQuery.eq(WmsInoutFlowEntity::getStockOwnerCode, query.getStockOwnerCode().trim());
            moveQuery.eq(WmsMaterialMoveSiteEntity::getStockOwnerCode, query.getStockOwnerCode().trim());
            pickingQuery.eq(WmsPickingAreaEntity::getStockOwnerCode, query.getStockOwnerCode().trim());
            pickingEventQuery.eq(WmsPickingEventEntity::getStockOwnerCode, query.getStockOwnerCode().trim());
        }
        if (StrUtil.isNotBlank(query.getSourceBusinessNo())) {
            stockQuery.eq(WmsLiveStockEntity::getSourceBusinessNo, query.getSourceBusinessNo().trim());
            flowQuery.eq(WmsInoutFlowEntity::getSourceBusinessNo, query.getSourceBusinessNo().trim());
            moveQuery.eq(WmsMaterialMoveSiteEntity::getSourceBusinessNo, query.getSourceBusinessNo().trim());
            pickingQuery.eq(WmsPickingAreaEntity::getSourceBusinessNo, query.getSourceBusinessNo().trim());
            pickingEventQuery.eq(WmsPickingEventEntity::getSourceBusinessNo, query.getSourceBusinessNo().trim());
        }
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(stockQuery
                .orderByAsc(WmsLiveStockEntity::getId).last("LIMIT " + (limit + 1)));
        List<WmsInoutFlowEntity> flows = flowMapper.selectList(flowQuery
                .orderByAsc(WmsInoutFlowEntity::getId).last("LIMIT " + (limit + 1)));
        List<WmsMaterialMoveSiteEntity> moves = moveMapper.selectList(moveQuery
                .orderByAsc(WmsMaterialMoveSiteEntity::getId).last("LIMIT " + (limit + 1)));
        List<WmsPickingAreaEntity> pickings = pickingMapper.selectList(pickingQuery
                .orderByAsc(WmsPickingAreaEntity::getId).last("LIMIT " + (limit + 1)));
        List<WmsPickingEventEntity> pickingEvents = pickingEventMapper.selectList(pickingEventQuery
                .orderByAsc(WmsPickingEventEntity::getId).last("LIMIT " + (limit + 1)));
        boolean stocksTruncated = stocks.size() > limit;
        boolean flowsTruncated = flows.size() > limit;
        boolean movesTruncated = moves.size() > limit;
        boolean pickingsTruncated = pickings.size() > limit;
        boolean pickingEventsTruncated = pickingEvents.size() > limit;
        Set<Long> linkedStockIds = new LinkedHashSet<>();
        stocks.stream().limit(limit).map(WmsLiveStockEntity::getId)
                .filter(Objects::nonNull).forEach(linkedStockIds::add);
        flows.stream().limit(limit).map(WmsInoutFlowEntity::getSourceStockId)
                .filter(Objects::nonNull).forEach(linkedStockIds::add);
        moves.stream().limit(limit).forEach(move -> {
            if (move.getSourceStockId() != null) {
                linkedStockIds.add(move.getSourceStockId());
            }
            if (move.getTargetStockId() != null) {
                linkedStockIds.add(move.getTargetStockId());
            }
        });
        pickings.stream().limit(limit).map(WmsPickingAreaEntity::getSourceStockId)
                .filter(Objects::nonNull).forEach(linkedStockIds::add);
        pickingEvents.stream().limit(limit).map(WmsPickingEventEntity::getSourceStockId)
                .filter(Objects::nonNull).forEach(linkedStockIds::add);
        List<WmsMaterialReservationDetailEntity> reservations = linkedStockIds.isEmpty() ? List.of()
                : reservationMapper.selectList(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getTenantId, tenant)
                        .eq(WmsMaterialReservationDetailEntity::getFactoryCode, factory)
                        .in(WmsMaterialReservationDetailEntity::getWarehouseCode, warehouseScope.allowed())
                        .in(WmsMaterialReservationDetailEntity::getLiveStockId, linkedStockIds)
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId).last("LIMIT " + (limit + 1)));
        List<WmsSalesStockReservationDetailEntity> salesReservations = linkedStockIds.isEmpty() ? List.of()
                : salesReservationMapper.selectList(new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                        .eq(WmsSalesStockReservationDetailEntity::getTenantId, tenant)
                        .eq(WmsSalesStockReservationDetailEntity::getFactoryCode, factory)
                        .in(WmsSalesStockReservationDetailEntity::getWarehouseCode, warehouseScope.allowed())
                        .in(WmsSalesStockReservationDetailEntity::getLiveStockId, linkedStockIds)
                        .orderByAsc(WmsSalesStockReservationDetailEntity::getId).last("LIMIT " + (limit + 1)));
        boolean productionReservationsTruncated = reservations.size() > limit;
        boolean salesReservationsTruncated = salesReservations.size() > limit;
        List<String> gaps = new ArrayList<>();
        Set<String> gapCodes = new LinkedHashSet<>();
        if (warehouseScope.partial()) {
            gaps.add("仅展示授权仓库的证据；跨仓追溯链路可能不完整");
            gapCodes.add("WAREHOUSE_SCOPE_PARTIAL");
        }
        boolean noEvidence = stocks.isEmpty() && flows.isEmpty() && moves.isEmpty() && pickings.isEmpty()
                && pickingEvents.isEmpty();
        if (noEvidence) {
            gaps.add("未找到匹配的WMS库存或作业证据");
            gapCodes.add("NO_WMS_EVIDENCE");
        }
        if (!noEvidence && byStock && sourceDetailId == null) {
            gaps.add("原库存未记录首次入库明细，仅能按库存行ID查直接关联记录");
            gapCodes.add("MISSING_ORIGIN_DETAIL");
        }
        if (stocksTruncated || flowsTruncated || movesTruncated || pickingsTruncated || pickingEventsTruncated
                || productionReservationsTruncated
                || salesReservationsTruncated) {
            gaps.add("证据超过单次返回上限，请缩小到库存行或条码查询");
            gapCodes.add("EVIDENCE_TRUNCATED");
        }
        if (!noEvidence && flows.isEmpty()) {
            gaps.add(pickings.isEmpty()
                    ? "当前范围没有匹配的WMS出入库流水；不能据此推断MES投料或成品去向"
                    : "当前存在待出库拣货；正式出库流水尚未形成，不能推断MES投料或成品去向");
            gapCodes.add("MISSING_INOUT_FLOW");
        }
        if (moves.stream().anyMatch(move -> move.getSourceStockId() == null)) {
            gaps.add("部分历史移库记录缺少来源库存行ID，"
                    + "仅可按条码或批次查看，不能确认精确入库来源");
            gapCodes.add("MISSING_MOVE_SOURCE_STOCK");
        }
        if (flows.stream().anyMatch(flow -> flow.getSourceInBillDetailId() == null)) {
            gaps.add("部分出入库流水缺少首次入库明细ID，不能证明属于同一实物来源");
            gapCodes.add("MISSING_FLOW_SOURCE_DETAIL");
        }
        if (flows.stream().anyMatch(flow -> "out".equalsIgnoreCase(flow.getOperateType())
                && flow.getSourceStockId() == null)) {
            gaps.add("部分出库流水缺少来源库存行ID，不能证明精确出库实物");
            gapCodes.add("MISSING_OUTBOUND_STOCK_ID");
        }
        boolean missingPickingHistory = !pickingEventsTruncated && flows.stream()
                .filter(flow -> "out".equalsIgnoreCase(flow.getOperateType())
                        && ("OUTBOUND".equals(flow.getStockProofType())
                        || "SALES_SHIPMENT".equals(flow.getStockProofType())))
                .filter(flow -> !byStock || Objects.equals(flow.getSourceStockId(), query.getStockId()))
                .anyMatch(flow -> pickingEvents.stream().noneMatch(event ->
                        Objects.equals(event.getBillId(), flow.getBillId())
                        && Objects.equals(event.getBillDetailId(), flow.getBillDetailId())
                        && Objects.equals(event.getSourceStockId(), flow.getSourceStockId())));
        if (missingPickingHistory) {
            gaps.add("现有出库流水没有逐次拣配事件；历史作业步骤无法补证");
            gapCodes.add("MISSING_PICKING_EVENT_HISTORY");
        }
        boolean hasInbound = flows.stream().anyMatch(flow -> "in".equalsIgnoreCase(flow.getOperateType()));
        if (!flows.isEmpty() && !hasInbound) {
            gaps.add("当前范围缺少入库流水，不能证明实物的入库来源");
            gapCodes.add("MISSING_INBOUND_FLOW");
        }
        String wmsStatus = noEvidence ? "NO_EVIDENCE"
                : byStock && sourceDetailId != null && hasInbound && gapCodes.isEmpty()
                ? "LINKED_WMS" : "PARTIAL";
        return new WmsStockTraceDTO.Evidence().setFactoryCode(factory)
                .setSourceInBillDetailId(sourceDetailId)
                .setCurrentStocks(stocks.stream().limit(limit).toList())
                .setFlows(flows.stream().limit(limit).toList())
                .setMoves(moves.stream().limit(limit).toList())
                .setPickingEvents(pickingEvents.stream().limit(limit).toList())
                .setActivePickings(pickings.stream().limit(limit).map(this::pickingSnapshot).toList())
                .setProductionReservationDetails(reservations.stream().limit(limit)
                        .map(this::reservationSnapshot).toList())
                .setSalesReservationDetails(salesReservations.stream().limit(limit)
                        .map(this::reservationSnapshot).toList())
                .setStocksTruncated(stocksTruncated).setFlowsTruncated(flowsTruncated)
                .setMovesTruncated(movesTruncated)
                .setPickingEventsTruncated(pickingEventsTruncated)
                .setActivePickingsTruncated(pickingsTruncated)
                .setProductionReservationsTruncated(productionReservationsTruncated)
                .setSalesReservationsTruncated(salesReservationsTruncated)
                .setDataGaps(gaps)
                .setEvidenceQuality(new WmsStockTraceDTO.EvidenceQuality(wmsStatus, "NOT_VERIFIED",
                        List.copyOf(gapCodes)));
    }

    private WmsStockTraceDTO.PickingSnapshot pickingSnapshot(WmsPickingAreaEntity row) {
        return new WmsStockTraceDTO.PickingSnapshot()
                .setId(row.getId())
                .setBillNo(row.getBillNo())
                .setBillDetailId(row.getBillDetailId())
                .setSourceStockId(row.getSourceStockId())
                .setSourceInBillDetailId(row.getSourceInBillDetailId())
                .setMaterialCode(row.getMatCode())
                .setMatBatch(row.getMatBatch())
                .setMatBarcode(row.getMatBarcode())
                .setWarehouseCode(row.getWarehouseCode())
                .setSiteCode(row.getSiteCode())
                .setMatNum(row.getMatNum())
                .setStockOwnerType(row.getStockOwnerType())
                .setStockOwnerCode(row.getStockOwnerCode())
                .setSourceBusinessNo(row.getSourceBusinessNo())
                .setSerialManaged(row.getSerialManaged());
    }

    private WmsStockTraceDTO.ReservationSnapshot reservationSnapshot(WmsMaterialReservationDetailEntity detail) {
        return new WmsStockTraceDTO.ReservationSnapshot()
                .setId(detail.getId())
                .setLiveStockId(detail.getLiveStockId())
                .setReservationNo(detail.getReservationNo())
                .setMaterialCode(detail.getActualMatCode())
                .setMatBatch(detail.getMatBatch())
                .setWarehouseCode(detail.getWarehouseCode())
                .setSiteCode(detail.getSiteCode())
                .setReservedNum(detail.getReservedNum())
                .setConsumedNum(detail.getConsumedNum())
                .setReturnedNum(detail.getReturnedNum())
                .setReleasedNum(detail.getReleasedNum())
                .setStatus(detail.getStatus());
    }

    private WmsStockTraceDTO.ReservationSnapshot reservationSnapshot(WmsSalesStockReservationDetailEntity detail) {
        return new WmsStockTraceDTO.ReservationSnapshot()
                .setId(detail.getId())
                .setLiveStockId(detail.getLiveStockId())
                .setReservationNo(detail.getReservationNo())
                .setMaterialCode(detail.getProductCode())
                .setMatBatch(detail.getMatBatch())
                .setWarehouseCode(detail.getWarehouseCode())
                .setSiteCode(detail.getSiteCode())
                .setReservedNum(detail.getReservedNum())
                .setConsumedNum(detail.getConsumedNum())
                .setReleasedNum(detail.getReleasedNum())
                .setStatus(detail.getStatus());
    }

    private WarehouseScope authorizedWarehouses(Long tenant, String factory) {
        R<List<MdmWarehouseEntity>> response;
        try {
            response = warehouses.getMenuConfigurationWarehouses();
        } catch (RuntimeException exception) {
            throw new CheckedException("读取仓库授权范围失败");
        }
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("读取仓库授权范围失败");
        }
        Set<String> all = new LinkedHashSet<>();
        for (MdmWarehouseEntity warehouse : response.getData()) {
            if (warehouse == null || StrUtil.isBlank(warehouse.getWarehouseCode())
                    || warehouse.getWarehouseCode().contains("*") || warehouse.getStatus() == null
                    || !Objects.equals(tenant, warehouse.getTenantId())
                    || !Objects.equals(factory, warehouse.getFactoryCode())
                    || !all.add(warehouse.getWarehouseCode())) {
                throw new CheckedException("仓库授权范围不完整或组织范围不一致");
            }
        }
        boolean globalView = permissions.hasPermission(GLOBAL_STOCK_VIEW_PERMISSION)
                || permissions.hasPermission(STOCK_DETAIL_VIEW_PERMISSION);
        Set<String> allowed = new LinkedHashSet<>();
        for (String warehouseCode : all) {
            if (globalView || permissions.hasPermission(STOCK_DETAIL_VIEW_PERMISSION + "_" + warehouseCode)) {
                allowed.add(warehouseCode);
            }
        }
        if (allowed.isEmpty()) {
            throw new CheckedException("无授权仓库的库存追溯查看权限");
        }
        return new WarehouseScope(allowed, allowed.size() < all.size());
    }

    private LambdaQueryWrapper<WmsLiveStockEntity> stockScope(Long tenant, String factory, Set<String> warehouses) {
        return new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getTenantId, tenant)
                .eq(WmsLiveStockEntity::getFactoryCode, factory)
                .in(WmsLiveStockEntity::getWarehouseCode, warehouses);
    }

    private LambdaQueryWrapper<WmsInoutFlowEntity> flowScope(Long tenant, String factory, Set<String> warehouses) {
        return new LambdaQueryWrapper<WmsInoutFlowEntity>()
                .eq(WmsInoutFlowEntity::getTenantId, tenant)
                .eq(WmsInoutFlowEntity::getFactoryCode, factory)
                .in(WmsInoutFlowEntity::getWarehouseCode, warehouses);
    }

    private LambdaQueryWrapper<WmsMaterialMoveSiteEntity> moveScope(Long tenant, String factory,
            Set<String> warehouses) {
        return new LambdaQueryWrapper<WmsMaterialMoveSiteEntity>()
                .eq(WmsMaterialMoveSiteEntity::getTenantId, tenant)
                .eq(WmsMaterialMoveSiteEntity::getFactoryCode, factory)
                .in(WmsMaterialMoveSiteEntity::getWarehouseCode, warehouses);
    }

    private LambdaQueryWrapper<WmsPickingAreaEntity> pickingScope(Long tenant, String factory,
            Set<String> warehouses) {
        return new LambdaQueryWrapper<WmsPickingAreaEntity>()
                .eq(WmsPickingAreaEntity::getTenantId, tenant)
                .eq(WmsPickingAreaEntity::getFactoryCode, factory)
                .in(WmsPickingAreaEntity::getWarehouseCode, warehouses);
    }

    private LambdaQueryWrapper<WmsPickingEventEntity> pickingEventScope(Long tenant, String factory,
            Set<String> warehouses) {
        return new LambdaQueryWrapper<WmsPickingEventEntity>()
                .eq(WmsPickingEventEntity::getTenantId, tenant)
                .eq(WmsPickingEventEntity::getFactoryCode, factory)
                .in(WmsPickingEventEntity::getWarehouseCode, warehouses);
    }

    private record WarehouseScope(Set<String> allowed, boolean partial) {
    }
}
