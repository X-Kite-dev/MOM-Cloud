package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsLiveStockDynamicGroupDTO;
import com.kitex.mom.wms.dto.WmsLiveStockQueryDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import com.kitex.mom.wms.vo.WmsLiveStockImportExcelVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsLiveStockReadAccessTest {
    private final WmsLiveStockService stocks = mock(WmsLiveStockService.class);

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "stock-read-access"),
                WmsLiveStockEntity.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void warehouseDetailViewerCannotReadAnotherWarehouseOrAllWarehouses() {
        authenticate("wms_wmsLiveStock_view_stock_detail_W1");
        var query = new WmsLiveStockQueryDTO().setWarehouseCode("W2");

        assertThrows(CheckedException.class,
                () -> controller().getWmsLiveStockPage(new SearchPage(), query));
        query.setWarehouseCode(null);
        assertThrows(CheckedException.class,
                () -> controller().getWmsLiveStockPage(new SearchPage(), query));
        verify(stocks, never()).pageDetail(any(), any());
    }

    @Test
    void pageSpecificPermissionDoesNotOpenAnotherInventoryView() {
        authenticate("wms_wmsLiveStock_view_by_material_W1");
        var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");

        assertThrows(CheckedException.class,
                () -> controller().pageBySupplier(new SearchPage(), query));
        verify(stocks, never()).page(any(), any());
    }

    @Test
    void viewPermissionDoesNotGrantExport() {
        authenticate("wms_wmsLiveStock_view_stock_detail_W1");
        var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");

        assertThrows(CheckedException.class, () -> controller().export(query, new String[] { "11" }));
        verify(stocks, never()).listDetail(any(), any());
    }

    @Test
    void matchingWarehousePermissionCanReadItsOwnPage() {
        authenticate("wms_wmsLiveStock_view_stock_detail_W1");
        var query = new WmsLiveStockQueryDTO().setWarehouseCode("W1");

        assertDoesNotThrow(() -> controller().getWmsLiveStockPage(new SearchPage(), query));
        verify(stocks).pageDetail(any(), any());
    }

    @Test
    void globalViewPermissionCanReadWithoutWarehouseFilter() {
        authenticate("wms_wmsLiveStock_view");

        assertDoesNotThrow(() -> controller().getWmsLiveStockPage(new SearchPage(), new WmsLiveStockQueryDTO()));
        verify(stocks).pageDetail(any(), any());
    }

    @Test
    void wildcardWarehouseIsRejectedEvenForGlobalViewer() {
        authenticate("wms_wmsLiveStock_view");

        assertThrows(CheckedException.class, () -> controller().getWmsLiveStockPage(new SearchPage(),
                new WmsLiveStockQueryDTO().setWarehouseCode("W*")));
        verify(stocks, never()).pageDetail(any(), any());
    }

    @Test
    void detailByIdRejectsForeignFactoryAndWarehouseRows() {
        authenticate("wms_wmsLiveStock_view_stock_detail_W1");
        var foreignWarehouse = row("W2", "F1", 1L);
        when(stocks.getById("11")).thenReturn(foreignWarehouse);

        assertThrows(CheckedException.class, () -> controller().getById("11"));

        var foreignFactory = row("W1", "F2", 1L);
        when(stocks.getById("11")).thenReturn(foreignFactory);
        assertThrows(CheckedException.class, () -> controller().getById("11"));
    }

    @Test
    void stockCountAndDynamicGroupRespectWarehousePermission() {
        authenticate("wms_wmsLiveStock_view_stock_detail_W1");

        assertThrows(CheckedException.class, () -> controller().getLiveStockDetailNum("W2"));
        var group = new WmsLiveStockDynamicGroupDTO();
        assertThrows(CheckedException.class,
                () -> controller().queryByDynamicGroup(new SearchPage(), group));
        verify(stocks, never()).queryByDynamicGroup(any(), any());

        group.setWarehouseCode("W1");
        assertDoesNotThrow(() -> controller().queryByDynamicGroup(new SearchPage(), group));
    }

    @Test
    void scopedImportChecksEveryRowAndUsesCurrentFactory() {
        authenticate("wms_wmsLiveStock_export_stock_detail_W1");
        var first = new WmsLiveStockImportExcelVO();
        first.setWarehouseCode("W1");
        var second = new WmsLiveStockImportExcelVO();
        second.setWarehouseCode("W2");
        var rows = List.of(first, second);
        var binding = mock(BindingResult.class);

        assertThrows(CheckedException.class, () -> controller().importExcel(rows, binding, null));
        verify(stocks, never()).importExcel(any(), any(), any(), any());

        assertThrows(CheckedException.class, () -> controller().importExcel(rows, binding, "W1"));
        verify(stocks, never()).importExcel(any(), any(), any(), any());

        var singleWarehouse = List.of(first);
        assertDoesNotThrow(() -> controller().importExcel(singleWarehouse, binding, "W1"));
        verify(stocks).importExcel(singleWarehouse, binding, "W1", "F1");
    }

    @Test
    void missingOrganizationOrSkipFlagCannotReadInventory() {
        authenticate("wms_wmsLiveStock_view");
        FactoryContextHolder.clear();
        assertThrows(CheckedException.class,
                () -> controller().getWmsLiveStockPage(new SearchPage(), new WmsLiveStockQueryDTO()));
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class,
                () -> controller().getWmsLiveStockPage(new SearchPage(), new WmsLiveStockQueryDTO()));
        verify(stocks, never()).pageDetail(any(), any());
    }

    private WmsMaterialLiveStockController controller() {
        return new WmsMaterialLiveStockController(stocks, new WmsLiveStockReadAccess(new PermissionService()),
                mock(com.kitex.mom.wms.service.impl.WmsOperationStockSummaryAccess.class));
    }

    private WmsLiveStockEntity row(String warehouse, String factory, Long tenant) {
        var stock = new WmsLiveStockEntity();
        stock.setWarehouseCode(warehouse);
        stock.setFactoryCode(factory);
        stock.setTenantId(tenant);
        return stock;
    }

    private void authenticate(String... permissions) {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
        var authorities = Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, authorities));
    }
}
