package com.kitex.mom.wms.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteAddDTO;
import com.kitex.mom.wms.dto.WmsMaterialMoveSiteQueryDTO;
import com.kitex.mom.wms.dto.app.WmsMaterialMoveSiteAppDTO;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.service.WmsMaterialMoveSiteService;
import com.kitex.mom.wms.service.impl.WmsMaterialMoveSiteAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsMaterialMoveSiteControllerTest {
    @BeforeEach
    void setScope() {
        TenantContextHolder.setTenantId(1L);
        FactoryContextHolder.setFactoryCode("F1");
    }

    @AfterEach
    void clearScope() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void completedMovementHistoryCannotBeDeleted() {
        var service = mock(WmsMaterialMoveSiteService.class);
        var controller = new WmsMaterialMoveSiteController(service, mock(WmsMaterialMoveSiteAccess.class));

        assertThrows(CheckedException.class, () -> controller.removeById(new Long[] { 1L }));

        verify(service, never()).removeBatchByIds(anyCollection());
    }

    @Test
    void moveCommandsCheckTheirOwnWarehouseBeforeCallingService() {
        var service = mock(WmsMaterialMoveSiteService.class);
        var access = mock(WmsMaterialMoveSiteAccess.class);
        var controller = new WmsMaterialMoveSiteController(service, access);
        var web = new WmsMaterialMoveSiteAddDTO();
        web.setWarehouseCode("W1");
        var app = new WmsMaterialMoveSiteAppDTO();
        app.setWarehouseCode("W2");

        controller.save(web);
        controller.appSave(app);

        verify(access).requireMove("W1");
        verify(access).requireMove("W2");
        verify(service).materialMoveSiteSave(web);
        verify(service).appMaterialMoveSiteSave(app);

        doThrow(new CheckedException("无权限")).when(access).requireMove("W3");
        var denied = new WmsMaterialMoveSiteAddDTO();
        denied.setWarehouseCode("W3");
        assertThrows(CheckedException.class, () -> controller.save(denied));
        verify(service, never()).materialMoveSiteSave(denied);
    }

    @Test
    void readAndExportAuthorizeRecordOrSelectedWarehouse() {
        var service = mock(WmsMaterialMoveSiteService.class);
        var access = mock(WmsMaterialMoveSiteAccess.class);
        var controller = new WmsMaterialMoveSiteController(service, access);
        var row = new WmsMaterialMoveSiteEntity();
        when(service.getById(7L)).thenReturn(row);
        var query = new WmsMaterialMoveSiteQueryDTO();
        query.setWarehouseCode("W1");
        Long[] ids = { 7L };

        controller.getById(7L);
        controller.export(query, ids);

        verify(access).requireViewRow(row);
        verify(access).requireExport("W1");
        verify(service).export(query, ids);

        doThrow(new CheckedException("无权限")).when(access).requireExport("W2");
        var denied = new WmsMaterialMoveSiteQueryDTO();
        denied.setWarehouseCode("W2");
        assertThrows(CheckedException.class, () -> controller.export(denied, ids));
        verify(service, never()).export(denied, ids);
    }

    @Test
    void pageRequiresWarehouseViewBeforeQueryingHistory() {
        var service = mock(WmsMaterialMoveSiteService.class);
        var access = mock(WmsMaterialMoveSiteAccess.class);
        var controller = new WmsMaterialMoveSiteController(service, access);
        var query = new WmsMaterialMoveSiteQueryDTO();
        query.setWarehouseCode("W1");

        controller.getWmsMaterialMoveSitePage(new SearchPage(), query);

        verify(access).requireView("W1");
        verify(service).page(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        doThrow(new CheckedException("无权限")).when(access).requireView("W2");
        var denied = new WmsMaterialMoveSiteQueryDTO();
        denied.setWarehouseCode("W2");
        assertThrows(CheckedException.class, () -> controller.getWmsMaterialMoveSitePage(new SearchPage(), denied));
    }
}
