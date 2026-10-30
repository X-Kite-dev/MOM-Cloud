package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsWarehouseAgingServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private JdbcTemplate jdbc;
    private WmsWarehouseAgingService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:stock-age-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE wms_live_stock (tenant_id BIGINT, factory_code VARCHAR(64), "
                + "warehouse_code VARCHAR(64), mat_unit VARCHAR(64), mat_num DECIMAL(18, 3), "
                + "in_time TIMESTAMP, del_flag VARCHAR(8))");
        TenantContextHolder.setTenantId(8L);
        FactoryContextHolder.setFactoryCode("F1");
        service = new WmsWarehouseAgingService(jdbc, new WmsLiveStockReadAccess(permissions));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void groupsPositiveStockByUnitAndAgeWithoutMixingOrganizations() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        var now = LocalDateTime.now();
        insert(8, "F1", "W1", "EA", "2", now.minusDays(5), "0");
        insert(8, "F1", "W1", "EA", "3", now.minusDays(45), "0");
        insert(8, "F1", "W1", "EA", "4", now.minusDays(120), "0");
        insert(8, "F1", "W1", "EA", "5", now.minusDays(240), "0");
        insert(8, "F1", "W1", "EA", "6", null, "0");
        insert(8, "F1", "W1", "EA", "7", now.plusDays(2), "0");
        insert(8, "F1", "W1", " ", "8", now.minusDays(5), "0");
        insert(8, "F1", "W2", "EA", "100", now.minusDays(5), "0");
        insert(9, "F1", "W1", "EA", "100", now.minusDays(5), "0");
        insert(8, "F2", "W1", "EA", "100", now.minusDays(5), "0");
        insert(8, "F1", "W1", "EA", "100", now.minusDays(5), "1");
        insert(8, "F1", "W1", "EA", "0", now.minusDays(5), "0");

        var result = service.query("W1");

        assertEquals("W1", result.warehouseCode());
        assertEquals(7, result.buckets().size());
        assertEquals("2.000", bucket(result, "EA", "D0_30").physicalNum());
        assertEquals("3.000", bucket(result, "EA", "D31_90").physicalNum());
        assertEquals("4.000", bucket(result, "EA", "D91_180").physicalNum());
        assertEquals("5.000", bucket(result, "EA", "OVER_180").physicalNum());
        assertEquals("6.000", bucket(result, "EA", "UNKNOWN").physicalNum());
        assertEquals("7.000", bucket(result, "EA", "FUTURE").physicalNum());
        assertEquals("8.000", bucket(result, "__MISSING_UNIT__", "D0_30").physicalNum());
        assertEquals(1, bucket(result, "EA", "D0_30").stockRows());
    }

    @Test
    void exactThirtyDayBoundaryBelongsToYoungestBucket() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        // A two-second margin avoids clock precision races while testing the boundary partition.
        insert(8, "F1", "W1", "EA", "1", LocalDateTime.now().minusDays(30).plusSeconds(2), "0");
        insert(8, "F1", "W1", "EA", "1", LocalDateTime.now().minusDays(30).minusSeconds(2), "0");

        var result = service.query("W1");

        assertEquals(1, bucket(result, "EA", "D0_30").stockRows());
        assertEquals(1, bucket(result, "EA", "D31_90").stockRows());
    }

    @Test
    void deniedOrMissingScopeNeverReadsStock() {
        assertThrows(CheckedException.class, () -> service.query("W1"));
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);
        assertThrows(CheckedException.class, () -> service.query("*"));
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.query("W1"));
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(8L);
        FactoryContextHolder.setFactorySkip();
        assertThrows(CheckedException.class, () -> service.query("W1"));
        FactoryContextHolder.clear();
        FactoryContextHolder.setFactoryCode("F1");
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.query("W1"));
    }

    @Test
    void emptyWarehouseReturnsNoBuckets() {
        when(permissions.hasPermission("wms_wmsLiveStock_view_stock_detail_W1")).thenReturn(true);

        assertEquals(0, service.query("W1").buckets().size());
    }

    private WmsWarehouseAgingService.Bucket bucket(WmsWarehouseAgingService.Aging result, String unit, String age) {
        return result.buckets().stream()
                .filter(row -> unit.equals(row.unitCode()) && age.equals(row.ageBucket()))
                .findFirst().orElseThrow();
    }

    private void insert(long tenant, String factory, String warehouse, String unit, String quantity,
                        LocalDateTime inTime, String deleted) {
        jdbc.update("INSERT INTO wms_live_stock VALUES (?,?,?,?,?,?,?)",
                tenant, factory, warehouse, unit, quantity, inTime, deleted);
    }
}
