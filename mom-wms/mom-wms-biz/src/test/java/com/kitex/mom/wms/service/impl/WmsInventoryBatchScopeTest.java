package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryBatchScopeTest {
    private final WmsInsideInventoryPlanMapper plans = mock(WmsInsideInventoryPlanMapper.class);
    private final WmsInsideInventoryPlanDetailMapper details = mock(WmsInsideInventoryPlanDetailMapper.class);
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final WmsWarehouseBusinessGuard warehouseGuard = mock(WmsWarehouseBusinessGuard.class);
    private final RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
    private final RemoteMaterialService materials = mock(RemoteMaterialService.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);

    @BeforeAll
    static void initializeMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "inventory-batch-scope");
        TableInfoHelper.initTableInfo(assistant, WmsLiveStockEntity.class);
    }

    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        when(warehouseGuard.check("W1", "other_inventory", true)).thenReturn(true);
        when(locations.getByCode("S1")).thenReturn(R.ok(location("W1", 1L, "F1", true, 3)));
        when(materials.getListByCodes(any())).thenReturn(R.ok(List.of(material("M1", 1L, "F1"))));
        when(warehouses.getByCode("W1")).thenReturn(R.ok(warehouse()));
        when(plans.insert(any(WmsInsideInventoryPlanEntity.class))).thenReturn(1);
        when(plans.lockCountScope(any())).thenReturn(1);
        when(details.insert(any(WmsInsideInventoryPlanDetailEntity.class))).thenReturn(1);
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void distinctBatchesOfOneMaterialAndLocationCanBeCountedSeparately() {
        when(stocks.selectList(any())).thenReturn(List.of(stock("B1", 5d)), List.of(stock("B2", 7d)));
        var plan = plan(row("B1"), row("B2"));

        assertTrue(service().saveDeep(plan));

        assertEquals(5d, plan.getWmsInsideInventoryPlanDetailList().get(0).getCurrentNumber());
        assertEquals(7d, plan.getWmsInsideInventoryPlanDetailList().get(1).getCurrentNumber());
        assertEquals("B1", plan.getWmsInsideInventoryPlanDetailList().get(0).getMatBatch());
        assertEquals("B2", plan.getWmsInsideInventoryPlanDetailList().get(1).getMatBatch());
        @SuppressWarnings("unchecked")
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(stocks, times(2)).selectList(query.capture());
        assertTrue(query.getAllValues().stream().allMatch(scope -> scope.getSqlSegment().contains("mat_batch")));
        assertTrue(query.getAllValues().get(0).getParamNameValuePairs().containsValue("B1"));
        assertTrue(query.getAllValues().get(1).getParamNameValuePairs().containsValue("B2"));
    }

    @Test
    void onePlanKeepsTheSameMaterialBatchSeparateAcrossPhysicalLocations() {
        var secondLocation = location("W1", 1L, "F1", true, 3);
        secondLocation.setLocationCode("S2");
        secondLocation.setLocationName("库位二");
        when(locations.getByCode("S2")).thenReturn(R.ok(secondLocation));
        when(stocks.selectList(any())).thenReturn(List.of(stock("B1", 5d)), List.of(stock("B1", 7d)));
        var secondRow = row("B1");
        secondRow.setSiteCode("S2");
        var plan = plan(row("B1"), secondRow);

        assertTrue(service().saveDeep(plan));

        assertEquals(5d, plan.getWmsInsideInventoryPlanDetailList().get(0).getCurrentNumber());
        assertEquals(7d, plan.getWmsInsideInventoryPlanDetailList().get(1).getCurrentNumber());
        assertEquals("库位二", secondRow.getSiteName());
        @SuppressWarnings("unchecked")
        var queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(stocks, times(2)).selectList(queries.capture());
        assertTrue(queries.getAllValues().stream().allMatch(query -> query.getSqlSegment().contains("site_code")));
        assertTrue(queries.getAllValues().get(0).getParamNameValuePairs().containsValue("S1"));
        assertTrue(queries.getAllValues().get(1).getParamNameValuePairs().containsValue("S2"));
    }

    @Test
    void allBatchesAndSpecificBatchCannotOverlapInOnePlan() {
        when(stocks.selectList(any())).thenReturn(List.of());

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row(null), row("B1"))));
        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row("B1"), row(null))));
    }

    @Test
    void sameBatchCannotBeCountedTwiceInOnePlan() {
        when(stocks.selectList(any())).thenReturn(List.of());

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row("B1"), row("B1"))));
    }

    @Test
    void unfinishedPlanBlocksTheSameWarehouseLocationMaterialAndBatch() {
        when(plans.findUnfinishedDetails(1L, "F1", "W1")).thenReturn(List.of(activeRow("B1")));

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row("B1"))));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void unfinishedAllBatchScopeOverlapsAnySpecificBatchInEitherDirection() {
        when(plans.findUnfinishedDetails(1L, "F1", "W1")).thenReturn(List.of(activeRow(null)));
        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row("B1"))));

        when(plans.findUnfinishedDetails(1L, "F1", "W1")).thenReturn(List.of(activeRow("B1")));
        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row(null))));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void distinctBatchCanUseTheSameWarehouseLocationAndMaterial() {
        when(plans.findUnfinishedDetails(1L, "F1", "W1")).thenReturn(List.of(activeRow("B1")));
        when(stocks.selectList(any())).thenReturn(List.of());

        assertTrue(service().saveDeep(plan(row("B2"))));
        verify(plans).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void countCreationFailsClosedIfTheScopedReadReturnsAnotherTenant() {
        var foreign = activeRow("B1");
        foreign.setTenantId(2L);
        when(plans.findUnfinishedDetails(1L, "F1", "W1")).thenReturn(List.of(foreign));

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row("B2"))));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void countPlanNumberIsRequiredAndBoundedBeforeAnyInsert() {
        var missing = plan(row(null));
        missing.setPlanNo(" ");
        assertThrows(CheckedException.class, () -> service().saveDeep(missing));

        var tooLong = plan(row(null));
        tooLong.setPlanNo("P".repeat(33));
        assertThrows(CheckedException.class, () -> service().saveDeep(tooLong));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void sameFactoryPlanNumberIsLockedAndRejectedEvenAcrossWarehouses() {
        when(plans.countActivePlanNo(1L, "F1", "PD-1")).thenReturn(1);

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row(null))));

        var order = inOrder(plans);
        order.verify(plans).lockCountScope("WMS_INVENTORY_PLAN_NO|1|F1|PD-1");
        order.verify(plans).countActivePlanNo(1L, "F1", "PD-1");
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void anotherOrganizationCannotBlockTheCurrentPlanNumber() {
        when(stocks.selectList(any())).thenReturn(List.of());

        assertTrue(service().saveDeep(plan(row(null))));

        verify(plans).countActivePlanNo(1L, "F1", "PD-1");
        verify(plans).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void invalidOrCrossWarehouseLocationCannotCreateInventoryPlan() {
        var invalidLocations = List.of(
                location("W2", 1L, "F1", true, 3),
                location("W1", 2L, "F1", true, 3),
                location("W1", 1L, "F2", true, 3),
                location("W1", 1L, "F1", false, 3),
                location("W1", 1L, "F1", true, 2));
        for (var invalid : invalidLocations) {
            when(locations.getByCode("S1")).thenReturn(R.ok(invalid));
            assertThrows(CheckedException.class, () -> service().saveDeep(plan(row(null))));
        }
    }

    @Test
    void enabledEmptyLocationCanBeCountedAsZero() {
        when(stocks.selectList(any())).thenReturn(List.of());
        var plan = plan(row(null));

        assertTrue(service().saveDeep(plan));

        assertEquals(0d, plan.getWmsInsideInventoryPlanDetailList().get(0).getCurrentNumber());
    }

    @Test
    void countFreezeModeDefaultsToSnapshotAndPreservesExplicitFreeze() {
        when(stocks.selectList(any())).thenReturn(List.of());
        var ordinary = plan(row(null));
        assertTrue(service().saveDeep(ordinary));
        assertEquals("SNAPSHOT", ReflectionTestUtils.getField(ordinary, "stockFreezeMode"));

        var frozen = plan(row(null));
        ReflectionTestUtils.setField(frozen, "stockFreezeMode", "FREEZE_COUNT");
        when(plans.countFreezeReady()).thenReturn(1);
        assertTrue(service().saveDeep(frozen));
        assertEquals("FREEZE_COUNT", ReflectionTestUtils.getField(frozen, "stockFreezeMode"));
    }

    @Test
    void countFreezeCannotBeSelectedWithoutTheDatabaseGuard() {
        var frozen = plan(row(null));
        ReflectionTestUtils.setField(frozen, "stockFreezeMode", "FREEZE_COUNT");

        assertThrows(CheckedException.class, () -> service().saveDeep(frozen));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void unsupportedCountFreezeModeFailsBeforePlanInsert() {
        var plan = plan(row(null));
        ReflectionTestUtils.setField(plan, "stockFreezeMode", "SKIP_STOCK_GUARD");

        assertThrows(CheckedException.class, () -> service().saveDeep(plan));
        verify(plans, never()).insert(any(WmsInsideInventoryPlanEntity.class));
    }

    @Test
    void planUsesCurrentMasterDataInsteadOfSubmittedNamesAndUnit() {
        when(stocks.selectList(any())).thenReturn(List.of());
        var row = row(null);
        row.setMatName("伪造物料名");
        row.setMatUnit("BOX");
        row.setSiteName("伪造库位名");
        var plan = plan(row);
        plan.setWarehouseName("伪造仓库名");

        assertTrue(service().saveDeep(plan));

        assertEquals("一号仓", plan.getWarehouseName());
        assertEquals("物料一", row.getMatName());
        assertEquals("PCS", row.getMatUnit());
        assertEquals("库位一", row.getSiteName());
    }

    @Test
    void materialOutsideCurrentTenantCannotCreateInventoryPlan() {
        when(materials.getListByCodes(any())).thenReturn(R.ok(List.of(material("M1", 2L, "F1"))));

        assertThrows(CheckedException.class, () -> service().saveDeep(plan(row(null))));
    }

    private WmsInsideInventoryPlanServiceImpl service() {
        var service = new WmsInsideInventoryPlanServiceImpl(details, stocks, locations, materials, warehouses,
                new WmsInventoryPlanAccess(plans, details), warehouseGuard, null);
        ReflectionTestUtils.setField(service, "baseMapper", plans);
        return service;
    }

    private WmsInsideInventoryPlanEntity plan(WmsInsideInventoryPlanDetailEntity... rows) {
        var plan = new WmsInsideInventoryPlanEntity();
        plan.setPlanNo("PD-1");
        plan.setWarehouseCode("W1");
        plan.setWmsInsideInventoryPlanDetailList(List.of(rows));
        return plan;
    }

    private WmsInsideInventoryPlanDetailEntity row(String batch) {
        var row = new WmsInsideInventoryPlanDetailEntity();
        row.setMatCode("M1");
        row.setMatUnit("PCS");
        row.setSiteCode("S1");
        row.setMatBatch(batch);
        return row;
    }

    private WmsInsideInventoryPlanDetailEntity activeRow(String batch) {
        var prior = row(batch);
        prior.setPlanId(55L);
        prior.setTenantId(1L);
        prior.setFactoryCode("F1");
        prior.setWarehouseCode("W1");
        return prior;
    }

    private WmsLiveStockEntity stock(String batch, double quantity) {
        return new WmsLiveStockEntity().setMatBatch(batch).setMatUnit("PCS").setMatNum(quantity);
    }

    private MdmWarehouseLocationEntity location(String warehouse, Long tenant, String factory,
                                                boolean enabled, int level) {
        var location = new MdmWarehouseLocationEntity();
        location.setLocationCode("S1");
        location.setLocationName("库位一");
        location.setWarehouseName("一号仓");
        location.setWarehouseCode(warehouse);
        location.setTenantId(tenant);
        location.setFactoryCode(factory);
        location.setStatus(enabled);
        location.setLocationLevel(level);
        return location;
    }

    private MdmMaterialEntity material(String code, Long tenant, String factory) {
        var material = new MdmMaterialEntity();
        material.setMaterialCode(code);
        material.setMaterialName("物料一");
        material.setBasicUnit("PCS");
        material.setTenantId(tenant);
        material.setFactoryCode(factory);
        return material;
    }

    private MdmWarehouseEntity warehouse() {
        var warehouse = new MdmWarehouseEntity();
        warehouse.setWarehouseCode("W1");
        warehouse.setWarehouseName("一号仓");
        warehouse.setTenantId(1L);
        warehouse.setFactoryCode("F1");
        warehouse.setStatus(true);
        return warehouse;
    }
}
