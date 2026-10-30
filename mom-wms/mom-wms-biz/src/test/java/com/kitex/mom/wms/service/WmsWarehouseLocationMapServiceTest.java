package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.mapper.WmsWarehouseLocationMapMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsWarehouseLocationMapServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final WmsWarehouseLocationMapMapper maps = mock(WmsWarehouseLocationMapMapper.class);
    private final WmsWarehouseMenuMapper menus = mock(WmsWarehouseMenuMapper.class);
    private final RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
    private final RemoteWarehouseService warehouses = mock(RemoteWarehouseService.class);
    private JdbcTemplate jdbc;
    private WmsWarehouseLocationMapService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(11L);
        FactoryContextHolder.setFactoryCode("F1");
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        when(locations.getLayoutLocations("W1")).thenReturn(R.ok(List.of(site("A-01", true), site("A-02", true))));
        MdmWarehouseEntity warehouse = new MdmWarehouseEntity().setWarehouseCode("W1")
                .setFactoryCode("F1").setStatus(true);
        warehouse.setTenantId(11L);
        when(warehouses.getMenuConfigurationWarehouses()).thenReturn(R.ok(List.of(warehouse)));
        var source = new DriverManagerDataSource("jdbc:h2:mem:location-map-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE wms_live_stock (tenant_id BIGINT, factory_code VARCHAR(100), "
                + "warehouse_code VARCHAR(100), site_code VARCHAR(100), mat_code VARCHAR(100), "
                + "mat_num DECIMAL(18, 3), in_time TIMESTAMP, del_flag VARCHAR(8))");
        service = new WmsWarehouseLocationMapService(maps, menus, locations, warehouses,
                new WmsLiveStockReadAccess(permissions), permissions, jdbc, new ObjectMapper());
        MomUser user = mock(MomUser.class);
        when(user.getUsername()).thenReturn("operator");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void loadReportsPhysicalLocationOccupancyAndUnplacedStockWithoutSummingUnits() {
        when(maps.find(11L, "F1", "W1")).thenReturn(new WmsWarehouseLocationMapMapper.Row(
                "[{\"locationCode\":\"A-01\",\"x\":20,\"y\":30,\"width\":120,\"height\":80}]", 2));
        insert("A-01", "MAT-1", "2", 10);
        insert("A-01", "MAT-2", "3", 25);
        jdbc.update("INSERT INTO wms_live_stock VALUES (11,'F1','W1','A-01','MAT-3',1,NULL,'0')");
        insert("A-02", "MAT-3", "1", 1);
        insert("UNKNOWN", "MAT-4", "1", 2);
        jdbc.update("INSERT INTO wms_live_stock VALUES (11,'F1','W2','A-01','X',10,CURRENT_TIMESTAMP,'0')");
        jdbc.update("INSERT INTO wms_live_stock VALUES (12,'F1','W1','A-01','X',10,CURRENT_TIMESTAMP,'0')");
        jdbc.update("INSERT INTO wms_live_stock VALUES (11,'F2','W1','A-01','X',10,CURRENT_TIMESTAMP,'0')");
        jdbc.update("INSERT INTO wms_live_stock VALUES (11,'F1','W1','A-01','X',10,CURRENT_TIMESTAMP,'1')");
        jdbc.update("INSERT INTO wms_live_stock VALUES (11,'F1','W1','A-01','X',0,CURRENT_TIMESTAMP,'0')");

        var result = service.load("W1");

        assertEquals(2, result.version());
        assertEquals(2, result.locations().size());
        assertEquals(3, result.locations().get(0).stockRows());
        assertEquals(3, result.locations().get(0).materialCount());
        assertEquals(1, result.locations().get(0).unknownAgeRows());
        assertEquals("A-02", result.locations().get(1).locationCode());
        assertEquals(2, result.unplacedStockRows());
        assertEquals(1, result.unknownLocationStockRows());
        assertEquals(1, result.occupiedLocations());
    }

    @Test
    void savingRequiresCurrentVersionRealEnabledSiteAndConfigurationPermission() {
        var placement = new WmsWarehouseLocationMapService.Placement("A-01", 20, 30, 120, 80);
        var command = new WmsWarehouseLocationMapService.SaveCommand("W1", 0, List.of(placement));
        assertThrows(CheckedException.class, () -> service.save(command));
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(maps.upsert(11L, "F1", "W1", "[{\"locationCode\":\"A-01\",\"x\":20,\"y\":30,\"width\":120,\"height\":80}]",
                0, "operator")).thenReturn(1);

        assertEquals(1, service.save(command).version());
        verify(menus).lockConfiguration("WMS_WAREHOUSE_MENU:11:F1");
        assertThrows(CheckedException.class, () -> service.save(new WmsWarehouseLocationMapService.SaveCommand(
                "W1", 0, List.of(placement, placement))));
        assertThrows(CheckedException.class, () -> service.save(new WmsWarehouseLocationMapService.SaveCommand(
                "W1", 0, List.of(new WmsWarehouseLocationMapService.Placement("UNKNOWN", 20, 30, 120, 80)))));
        assertThrows(CheckedException.class, () -> service.save(new WmsWarehouseLocationMapService.SaveCommand(
                "W1", 0, List.of(new WmsWarehouseLocationMapService.Placement("A-01", -1, 30, 120, 80)))));
    }

    @Test
    void staleVersionAndForeignLocationResponseCannotOverwriteConfiguration() {
        when(permissions.hasPermission("wms_wmsWarehouseMenu_add")).thenReturn(true);
        when(maps.find(11L, "F1", "W1")).thenReturn(new WmsWarehouseLocationMapMapper.Row("[]", 3));
        var command = new WmsWarehouseLocationMapService.SaveCommand("W1", 2, List.of());
        assertThrows(CheckedException.class, () -> service.save(command));
        verify(maps, never()).upsert(11L, "F1", "W1", "[]", 2, "operator");

        var foreign = site("A-01", true);
        foreign.setWarehouseCode("W2");
        when(locations.getLayoutLocations("W1")).thenReturn(R.ok(List.of(foreign)));
        assertThrows(CheckedException.class, () -> service.load("W1"));
    }

    @Test
    void deniedScopeNeverQueriesMasterDataOrStock() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(false);
        assertThrows(CheckedException.class, () -> service.load("W1"));
        verify(locations, never()).getLayoutLocations(anyString());
    }

    private MdmWarehouseLocationEntity site(String code, boolean enabled) {
        var site = new MdmWarehouseLocationEntity();
        site.setTenantId(11L);
        site.setFactoryCode("F1");
        site.setWarehouseCode("W1");
        site.setLocationCode(code);
        site.setLocationName(code);
        site.setLocationLevel(3);
        site.setStatus(enabled);
        return site;
    }

    private void insert(String site, String material, String quantity, int ageDays) {
        jdbc.update("INSERT INTO wms_live_stock VALUES (?,?,?,?,?,?,?,?)", 11L, "F1", "W1", site,
                material, quantity, LocalDateTime.now().minusDays(ageDays), "0");
    }
}
