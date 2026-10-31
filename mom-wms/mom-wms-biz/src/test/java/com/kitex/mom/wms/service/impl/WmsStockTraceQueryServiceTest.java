package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsStockTraceDTO;
import com.kitex.mom.wms.repository.WmsOrganizationTransferRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WmsStockTraceQueryServiceTest {
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final WmsInoutFlowMapper flows = mock(WmsInoutFlowMapper.class);
    private final WmsMaterialMoveSiteMapper moves = mock(WmsMaterialMoveSiteMapper.class);
    private final WmsPickingAreaMapper pickings = mock(WmsPickingAreaMapper.class);
    private final WmsPickingEventMapper pickingEvents = mock(WmsPickingEventMapper.class);
    private final WmsMaterialReservationDetailMapper reservations = mock(WmsMaterialReservationDetailMapper.class);
    private final WmsSalesStockReservationDetailMapper salesReservations =
            mock(WmsSalesStockReservationDetailMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private final WmsOrganizationTransferRepository organizationTransfers =
            mock(WmsOrganizationTransferRepository.class);
    private final WmsStockTraceQueryService service = new WmsStockTraceQueryService(stocks, flows, moves, pickings,
            pickingEvents,
            reservations, salesReservations, permissions, warehouses, organizationTransfers);

    @BeforeAll
    static void initializeTableMetadata() {
        for (var type : List.of(WmsLiveStockEntity.class, WmsInoutFlowEntity.class, WmsPickingAreaEntity.class,
                WmsPickingEventEntity.class,
                WmsMaterialMoveSiteEntity.class, WmsMaterialReservationDetailEntity.class,
                WmsSalesStockReservationDetailEntity.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "stock-trace"), type);
        }
    }

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        when(permissions.hasPermission("wms_stockTrace_view")).thenReturn(true);
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(true);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse("W1"), warehouse("W2"))));
        when(pickingEvents.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void exactStockReturnsItsSharedInboundLineageAndHistory() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1").setSourceInBillDetailId(81L)
                .setStockOwnerType("CUSTOMER_SUPPLIED").setStockOwnerCode("C1");
        stock.setId(7L);
        stock.setTenantId(1L);
        var flow = new WmsInoutFlowEntity();
        flow.setSourceInBillDetailId(81L);
        flow.setSourceBusinessNo("CUSTOMER-ORDER-1");
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of(flow));
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(81L, result.getSourceInBillDetailId());
        assertEquals(List.of(stock), result.getCurrentStocks());
        assertEquals(List.of(flow), result.getFlows());
        assertFalse(result.isFlowsTruncated());
        assertTrue(wrapperContainsValue(stocks, 81L));
        assertTrue(wrapperContainsValue(flows, 81L));
    }

    @Test
    void completedPickingEventRemainsVisibleForConsumedStock() {
        var picked = new WmsPickingEventEntity().setSourceStockId(7L).setSourceInBillDetailId(81L)
                .setMatCode("M1").setMatBatch("B1").setMatBarcode("BC1").setMatNum(2d)
                .setWarehouseCode("W1");
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(pickingEvents.selectList(any())).thenReturn(List.of(picked));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(81L, result.getSourceInBillDetailId());
        assertEquals(List.of(picked), result.getPickingEvents());
        assertTrue(wrapperContainsValue(pickingEvents, 7L));
        assertTrue(result.getEvidenceQuality().gapCodes().contains("MISSING_INOUT_FLOW"));
    }

    @Test
    void pickingProofMustMatchTheSameBillDetailAndSourceStock() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1")
                .setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        var inbound = new WmsInoutFlowEntity();
        inbound.setOperateType("in");
        inbound.setSourceInBillDetailId(81L);
        var outbound = new WmsInoutFlowEntity();
        outbound.setOperateType("out");
        outbound.setStockProofType("OUTBOUND");
        outbound.setSourceStockId(7L);
        outbound.setSourceInBillDetailId(81L);
        outbound.setBillId(20L);
        outbound.setBillDetailId(40L);
        var event = new WmsPickingEventEntity().setBillId(20L).setBillDetailId(40L)
                .setSourceStockId(7L).setSourceInBillDetailId(81L);
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of(inbound, outbound));
        when(moves.selectList(any())).thenReturn(List.of());
        when(pickingEvents.selectList(any())).thenReturn(List.of(event));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals("LINKED_WMS", result.getEvidenceQuality().wmsStatus());
        assertFalse(result.getEvidenceQuality().gapCodes().contains("MISSING_PICKING_EVENT_HISTORY"));

        event.setBillDetailId(41L);
        result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));
        assertTrue(result.getEvidenceQuality().gapCodes().contains("MISSING_PICKING_EVENT_HISTORY"));
    }

    @Test
    void pickingEventQueryUsesAuthorizedWarehouseAndOwnershipFilters() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1")
                .setStockOwnerType("CONSIGNED").setSourceBusinessNo("PO-1"));

        assertTrue(wrapperContainsValue(pickingEvents, 1L));
        assertTrue(wrapperContainsValue(pickingEvents, "F1"));
        assertTrue(wrapperContainsValue(pickingEvents, "W1"));
        assertFalse(wrapperContainsValue(pickingEvents, "W2"));
        assertTrue(wrapperContainsValue(pickingEvents, "SERIAL-1"));
        assertTrue(wrapperContainsValue(pickingEvents, "CONSIGNED"));
        assertTrue(wrapperContainsValue(pickingEvents, "PO-1"));
    }

    @Test
    void linkedWarehouseFlowsStillDoNotClaimMesFeeding() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1")
                .setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        var inbound = new WmsInoutFlowEntity();
        inbound.setOperateType("in");
        inbound.setSourceInBillDetailId(81L);
        inbound.setSourceStockId(7L);
        var outbound = new WmsInoutFlowEntity();
        outbound.setOperateType("out");
        outbound.setSourceInBillDetailId(81L);
        outbound.setSourceStockId(7L);
        outbound.setStockProofType("OUTBOUND");
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of(inbound, outbound));
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals("PARTIAL", result.getEvidenceQuality().wmsStatus());
        assertEquals("NOT_VERIFIED", result.getEvidenceQuality().crossSystemStatus());
        assertFalse(result.getEvidenceQuality().gapCodes().contains("MISSING_ORIGIN_DETAIL"));
        assertTrue(result.getEvidenceQuality().gapCodes().contains("MISSING_PICKING_EVENT_HISTORY"));
    }

    @Test
    void missingOutboundOriginCannotBeReportedAsLinkedWmsEvidence() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1")
                .setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        var inbound = new WmsInoutFlowEntity();
        inbound.setOperateType("in");
        inbound.setSourceInBillDetailId(81L);
        var outbound = new WmsInoutFlowEntity();
        outbound.setOperateType("out");
        outbound.setSourceStockId(7L);
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of(inbound, outbound));
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals("PARTIAL", result.getEvidenceQuality().wmsStatus());
        assertTrue(result.getEvidenceQuality().gapCodes().contains("MISSING_FLOW_SOURCE_DETAIL"));
    }

    @Test
    void outboundWithoutInboundEvidenceReportsExplicitGap() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1")
                .setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        var outbound = new WmsInoutFlowEntity();
        outbound.setOperateType("out");
        outbound.setSourceInBillDetailId(81L);
        outbound.setSourceStockId(7L);
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of(outbound));
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals("PARTIAL", result.getEvidenceQuality().wmsStatus());
        assertTrue(result.getEvidenceQuality().gapCodes().contains("MISSING_INBOUND_FLOW"));
    }

    @Test
    void partialWarehousePermissionIsAnExplicitMachineReadableGap() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertEquals("NO_EVIDENCE", result.getEvidenceQuality().wmsStatus());
        assertTrue(result.getEvidenceQuality().gapCodes().contains("WAREHOUSE_SCOPE_PARTIAL"));
        assertTrue(result.getEvidenceQuality().gapCodes().contains("NO_WMS_EVIDENCE"));
    }

    @Test
    void productionReservationSnapshotUsesOnlyStockIdsFromAuthorizedEvidence() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1");
        stock.setId(7L);
        stock.setTenantId(1L);
        var detail = new WmsMaterialReservationDetailEntity();
        detail.setLiveStockId(7L);
        detail.setReservationNo("RES-1");
        detail.setWarehouseCode("W1");
        detail.setReturnedNum(BigDecimal.ONE);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(reservations.selectList(any())).thenReturn(List.of(detail));

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertEquals(1, result.getProductionReservationDetails().size());
        assertEquals("RES-1", result.getProductionReservationDetails().get(0).getReservationNo());
        assertEquals(7L, result.getProductionReservationDetails().get(0).getLiveStockId());
        assertEquals(BigDecimal.ONE, result.getProductionReservationDetails().get(0).getReturnedNum());
        assertFalse(result.isProductionReservationsTruncated());
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reservations).selectList(captor.capture());
        var wrapper = captor.getValue();
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("F1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("W1"));
        assertFalse(wrapper.getParamNameValuePairs().containsValue("W2"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void productionReservationSnapshotsRespectThePerSourceLimit() {
        var stock = new WmsLiveStockEntity().setWarehouseCode("W1");
        stock.setId(7L);
        var first = new WmsMaterialReservationDetailEntity();
        first.setReservationNo("RES-1");
        var second = new WmsMaterialReservationDetailEntity();
        second.setReservationNo("RES-2");
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(reservations.selectList(any())).thenReturn(List.of(first, second));

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1").setLimit(1));

        assertEquals(1, result.getProductionReservationDetails().size());
        assertTrue(result.isProductionReservationsTruncated());
        assertTrue(result.getDataGaps().stream().anyMatch(gap -> gap.contains("上限")));
    }

    @Test
    void traceWithoutLinkedStockIdDoesNotReadReservations() {
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertTrue(result.getProductionReservationDetails().isEmpty());
        assertTrue(result.getSalesReservationDetails().isEmpty());
        verify(reservations, never()).selectList(any());
        verify(salesReservations, never()).selectList(any());
    }

    @Test
    void consumedStockCanStillFindItsReservationByOutboundFlowStockId() {
        var flow = new WmsInoutFlowEntity();
        flow.setSourceStockId(7L);
        flow.setSourceInBillDetailId(81L);
        var detail = new WmsMaterialReservationDetailEntity();
        detail.setLiveStockId(7L);
        detail.setReservationNo("RES-1");
        when(flows.selectList(any())).thenReturn(List.of(flow));
        when(stocks.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(reservations.selectList(any())).thenReturn(List.of(detail));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(81L, result.getSourceInBillDetailId());
        assertEquals("RES-1", result.getProductionReservationDetails().get(0).getReservationNo());
        verify(reservations).selectList(any());
    }

    @Test
    void fullyPickedStockWithoutLiveRowStillReturnsCurrentPickingState() {
        var picking = picking(7L, 81L, "W1");
        picking.setBillNo("OUT-1");
        picking.setBillDetailId(40L);
        picking.setMatNum(2d);
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(stocks.selectList(any())).thenReturn(List.of());
        when(pickings.selectList(any())).thenReturn(List.of(picking));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(81L, result.getSourceInBillDetailId());
        assertEquals(1, result.getActivePickings().size());
        assertEquals("OUT-1", result.getActivePickings().get(0).getBillNo());
        assertEquals(40L, result.getActivePickings().get(0).getBillDetailId());
        assertEquals(7L, result.getActivePickings().get(0).getSourceStockId());
        assertEquals(2d, result.getActivePickings().get(0).getMatNum());
        assertFalse(result.getDataGaps().stream().anyMatch(gap -> gap.contains("未找到匹配")));
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(pickings, times(2)).selectList(captor.capture());
        var currentQuery = captor.getAllValues().get(1);
        assertTrue(currentQuery.getSqlSegment().contains("source_stock_id"));
        assertTrue(currentQuery.getParamNameValuePairs().containsValue(7L));
        assertFalse(currentQuery.getParamNameValuePairs().containsValue(81L));
    }

    @Test
    void activePickingUsesAuthorizedWarehouseAndTheSameIdentityFilters() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);

        service.query(new WmsStockTraceDTO.Query().setMaterialCode("M1").setBatchNo("B1")
                .setStockOwnerType("CONSIGNED").setStockOwnerCode("SUP1")
                .setSourceBusinessNo("PO-1"));

        assertTrue(wrapperContainsValue(pickings, 1L));
        assertTrue(wrapperContainsValue(pickings, "F1"));
        assertTrue(wrapperContainsValue(pickings, "W1"));
        assertFalse(wrapperContainsValue(pickings, "W2"));
        assertTrue(wrapperContainsValue(pickings, "M1"));
        assertTrue(wrapperContainsValue(pickings, "B1"));
        assertTrue(wrapperContainsValue(pickings, "CONSIGNED"));
        assertTrue(wrapperContainsValue(pickings, "SUP1"));
        assertTrue(wrapperContainsValue(pickings, "PO-1"));
    }

    @Test
    void activePickingHasOwnLimitAndOnlyVisibleRowsLinkReservations() {
        var first = picking(7L, 81L, "W1");
        var second = picking(8L, 82L, "W1");
        when(pickings.selectList(any())).thenReturn(List.of(first, second));

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1").setLimit(1));

        assertEquals(1, result.getActivePickings().size());
        assertTrue(result.isActivePickingsTruncated());
        assertTrue(result.getDataGaps().stream().anyMatch(gap -> gap.contains("上限")));
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reservations).selectList(captor.capture());
        captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(7L));
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue(8L));
    }

    @Test
    void legacyPickingCanAppearByBatchWithoutInventedStockId() {
        var legacy = picking(null, null, "W1");
        when(pickings.selectList(any())).thenReturn(List.of(legacy));

        var result = service.query(new WmsStockTraceDTO.Query().setMaterialCode("M1").setBatchNo("B1"));

        assertEquals(1, result.getActivePickings().size());
        assertNull(result.getActivePickings().get(0).getSourceStockId());
        verify(reservations, never()).selectList(any());
    }

    @Test
    void salesReservationSnapshotUsesTheSameStockAndWarehouseScope() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        var stock = new WmsLiveStockEntity().setWarehouseCode("W1");
        stock.setId(7L);
        var detail = new WmsSalesStockReservationDetailEntity();
        detail.setLiveStockId(7L);
        detail.setReservationNo("SALE-1");
        detail.setProductCode("P1");
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(salesReservations.selectList(any())).thenReturn(List.of(detail));

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertEquals("SALE-1", result.getSalesReservationDetails().get(0).getReservationNo());
        assertEquals("P1", result.getSalesReservationDetails().get(0).getMaterialCode());
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(salesReservations).selectList(captor.capture());
        var wrapper = captor.getValue();
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("F1"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("W1"));
        assertFalse(wrapper.getParamNameValuePairs().containsValue("W2"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void salesReservationSnapshotsHaveTheirOwnLimit() {
        var stock = new WmsLiveStockEntity().setWarehouseCode("W1");
        stock.setId(7L);
        var first = new WmsSalesStockReservationDetailEntity();
        first.setReservationNo("SALE-1");
        var second = new WmsSalesStockReservationDetailEntity();
        second.setReservationNo("SALE-2");
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());
        when(salesReservations.selectList(any())).thenReturn(List.of(first, second));

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1").setLimit(1));

        assertEquals(1, result.getSalesReservationDetails().size());
        assertTrue(result.isSalesReservationsTruncated());
        assertFalse(result.isProductionReservationsTruncated());
    }

    @Test
    void batchTraceIsScopedAndRequiresMaterialCode() {
        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setBatchNo("B1")));
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        service.query(new WmsStockTraceDTO.Query().setMaterialCode("M1").setBatchNo("B1")
                .setStockOwnerType("CUSTOMER_SUPPLIED").setStockOwnerCode("C1")
                .setSourceBusinessNo("ORDER-1"));

        assertTrue(wrapperContainsValue(stocks, "F1"));
        assertTrue(wrapperContainsValue(stocks, "M1"));
        assertTrue(wrapperContainsValue(stocks, "B1"));
        assertTrue(wrapperContainsValue(stocks, "CUSTOMER_SUPPLIED"));
        assertTrue(wrapperContainsValue(stocks, "C1"));
        assertTrue(wrapperContainsValue(flows, "ORDER-1"));
        assertTrue(wrapperContainsValue(flows, 1L));
        assertTrue(wrapperContainsValue(moves, "ORDER-1"));
        assertTrue(wrapperContainsValue(moves, "CUSTOMER_SUPPLIED"));
    }

    @Test
    void stockIdCannotSilentlyOverrideAnotherSearchKey() {
        assertThrows(CheckedException.class, () -> service.query(new WmsStockTraceDTO.Query()
                .setStockId(7L).setBarcode("SERIAL-1")));
        assertThrows(CheckedException.class, () -> service.query(new WmsStockTraceDTO.Query()
                .setStockId(7L).setMaterialCode("M1")));

        verifyZeroInteractions(stocks, flows, moves, reservations, salesReservations);
    }

    @Test
    void barcodeCannotSilentlyOverrideBatchSearch() {
        assertThrows(CheckedException.class, () -> service.query(new WmsStockTraceDTO.Query()
                .setBarcode("SERIAL-1").setMaterialCode("M1").setBatchNo("B1")));

        verifyZeroInteractions(stocks, flows, moves, reservations, salesReservations);
    }

    @Test
    void unknownStockIdReportsNoEvidenceInsteadOfClaimingLostLineage() {
        var result = service.query(new WmsStockTraceDTO.Query().setStockId(999L));

        assertTrue(result.getDataGaps().stream().anyMatch(gap -> gap.contains("未找到匹配")));
        assertFalse(result.getDataGaps().stream().anyMatch(gap -> gap.contains("原库存未记录")));
    }

    @Test
    void stockFromAnotherTenantCannotRevealLineage() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1");
        stock.setTenantId(2L);
        when(stocks.selectById(7L)).thenReturn(stock);

        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setStockId(7L)));
        verifyZeroInteractions(flows);
        verifyZeroInteractions(moves);
        verifyZeroInteractions(reservations);
        verifyZeroInteractions(salesReservations);
    }

    @Test
    void stockTraceIncludesOnlyMovesFromTheSameInboundLineage() {
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1").setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        var move = new WmsMaterialMoveSiteEntity();
        move.setSourceInBillDetailId(81L);
        move.setSourceStockId(7L);
        move.setOldSiteCode("S1");
        move.setNewSiteCode("S2");
        when(stocks.selectById(7L)).thenReturn(stock);
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of(move));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(List.of(move), result.getMoves());
        assertTrue(wrapperContainsValue(moves, 81L));
        assertTrue(wrapperContainsValue(moves, "F1"));
        assertTrue(wrapperContainsValue(moves, 1L));
    }

    @Test
    void removedSourceStockCanRecoverLineageFromMoveRecord() {
        var move = new WmsMaterialMoveSiteEntity();
        move.setSourceStockId(7L);
        move.setTargetStockId(9L);
        move.setSourceInBillDetailId(81L);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of(move));

        var result = service.query(new WmsStockTraceDTO.Query().setStockId(7L));

        assertEquals(81L, result.getSourceInBillDetailId());
        assertEquals(List.of(move), result.getMoves());
        assertTrue(wrapperContainsValue(moves, 7L));
        assertTrue(wrapperContainsValue(moves, 81L));
    }

    @Test
    void traceRequiresItsOwnRolePermissionBeforeReadingWarehouses() {
        when(permissions.hasPermission("wms_stockTrace_view")).thenReturn(false);

        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1")));

        verify(warehouses, never()).getMenuConfigurationWarehouses();
        verifyZeroInteractions(stocks, flows, moves, reservations, salesReservations);
    }

    @Test
    void traceFiltersEachEvidenceSourceToAuthorizedWarehousesAndExplainsPartialLineage() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertTrue(wrapperContainsValue(stocks, "W1"));
        assertFalse(wrapperContainsValue(stocks, "W2"));
        assertTrue(wrapperContainsValue(flows, "W1"));
        assertFalse(wrapperContainsValue(flows, "W2"));
        assertTrue(wrapperContainsValue(moves, "W1"));
        assertFalse(wrapperContainsValue(moves, "W2"));
        assertTrue(result.getDataGaps().stream().anyMatch(gap -> gap.contains("授权仓库")));
    }

    @Test
    void globalStockViewerCanTraceAllCurrentFactoryWarehouses() {
        when(permissions.hasPermission("wms_wmsLiveStock_view")).thenReturn(true);
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(false);
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        var result = service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertTrue(wrapperContainsValue(stocks, "W1"));
        assertTrue(wrapperContainsValue(stocks, "W2"));
        assertFalse(result.getDataGaps().stream().anyMatch(gap -> gap.contains("授权仓库")));
    }

    @Test
    void exactStockFromUnauthorizedWarehouseDoesNotRevealItsLineage() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W2")).thenReturn(false);
        var stock = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W2")
                .setSourceInBillDetailId(81L);
        stock.setId(7L);
        stock.setTenantId(1L);
        when(stocks.selectById(7L)).thenReturn(stock);

        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setStockId(7L)));

        verify(stocks, never()).selectList(any());
        verifyZeroInteractions(flows, moves, reservations, salesReservations);
    }

    @Test
    void traceFailsClosedWhenWarehouseCatalogCannotBeRead() {
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(null);

        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1")));

        verifyZeroInteractions(stocks, flows, moves, reservations, salesReservations);
    }

    @Test
    void stoppedWarehouseHistoryRemainsVisibleWhenItsGrantIsRetained() {
        var stopped = warehouse("W2").setStatus(false);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse("W1"), stopped)));
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(false);
        when(stocks.selectList(any())).thenReturn(List.of());
        when(flows.selectList(any())).thenReturn(List.of());
        when(moves.selectList(any())).thenReturn(List.of());

        service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1"));

        assertTrue(wrapperContainsValue(stocks, "W2"));
        assertFalse(wrapperContainsValue(stocks, "W1"));
    }

    @Test
    void traceFailsClosedWhenCatalogContainsAnotherTenantWarehouse() {
        var foreign = warehouse("W3");
        foreign.setTenantId(2L);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse("W1"), foreign)));

        assertThrows(CheckedException.class,
                () -> service.query(new WmsStockTraceDTO.Query().setBarcode("SERIAL-1")));

        verifyZeroInteractions(stocks, flows, moves, reservations, salesReservations);
    }

    private MdmWarehouseEntity warehouse(String code) {
        var warehouse = new MdmWarehouseEntity();
        warehouse.setWarehouseCode(code);
        warehouse.setFactoryCode("F1");
        warehouse.setTenantId(1L);
        warehouse.setStatus(true);
        return warehouse;
    }

    private WmsPickingAreaEntity picking(Long stockId, Long sourceDetailId, String warehouseCode) {
        var picking = new WmsPickingAreaEntity();
        picking.setId(100L);
        picking.setTenantId(1L);
        picking.setFactoryCode("F1");
        picking.setWarehouseCode(warehouseCode);
        picking.setSiteCode("S1");
        picking.setMatCode("M1");
        picking.setMatBatch("B1");
        picking.setSourceStockId(stockId);
        picking.setSourceInBillDetailId(sourceDetailId);
        return picking;
    }

    private boolean wrapperContainsValue(Object mapper, Object value) {
        if (mapper == stocks) {
            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(stocks, atLeastOnce()).selectList(captor.capture());
            return captor.getAllValues().stream()
                    .anyMatch(wrapper -> {
                        wrapper.getSqlSegment();
                        return wrapper.getParamNameValuePairs().containsValue(value);
                    });
        }
        if (mapper == moves) {
            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(moves, atLeastOnce()).selectList(captor.capture());
            return captor.getAllValues().stream()
                    .anyMatch(wrapper -> {
                        wrapper.getSqlSegment();
                        return wrapper.getParamNameValuePairs().containsValue(value);
                    });
        }
        if (mapper == pickings) {
            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(pickings, atLeastOnce()).selectList(captor.capture());
            return captor.getAllValues().stream()
                    .anyMatch(wrapper -> {
                        wrapper.getSqlSegment();
                        return wrapper.getParamNameValuePairs().containsValue(value);
                    });
        }
        if (mapper == pickingEvents) {
            @SuppressWarnings("unchecked")
            var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(pickingEvents, atLeastOnce()).selectList(captor.capture());
            return captor.getAllValues().stream()
                    .anyMatch(wrapper -> {
                        wrapper.getSqlSegment();
                        return wrapper.getParamNameValuePairs().containsValue(value);
                    });
        }
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(flows, atLeastOnce()).selectList(captor.capture());
        return captor.getAllValues().stream()
                .anyMatch(wrapper -> {
                    wrapper.getSqlSegment();
                    return wrapper.getParamNameValuePairs().containsValue(value);
                });
    }
}
