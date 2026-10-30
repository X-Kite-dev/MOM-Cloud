package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsWarehouseThroughputServiceTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private JdbcTemplate jdbc;
    private WmsWarehouseThroughputService service;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:throughput-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String table : new String[]{"wms_in_bill", "wms_out_bill"}) {
            jdbc.execute("CREATE TABLE " + table + " (tenant_id BIGINT, factory_code VARCHAR(64), "
                    + "warehouse_code VARCHAR(64), state VARCHAR(8), review_time TIMESTAMP, "
                    + "create_time TIMESTAMP, del_flag VARCHAR(8))");
        }
        TenantContextHolder.setTenantId(8L);
        FactoryContextHolder.setFactoryCode("F1");
        service = new WmsWarehouseThroughputService(jdbc, permissions);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void reviewedBillLeadTimeUsesOnlyAuthorizedWarehouseAndValidTimestamps() {
        when(permissions.hasPermission("wms_bill_view_in_W1")).thenReturn(true);
        LocalDateTime reviewed = LocalDateTime.now().minusDays(1);
        insert("wms_in_bill", 8, "F1", "W1", "2", reviewed.minusMinutes(30), reviewed);
        insert("wms_in_bill", 8, "F1", "W1", "2", null, reviewed);
        insert("wms_in_bill", 8, "F1", "W2", "2", reviewed.minusMinutes(90), reviewed);
        insert("wms_in_bill", 9, "F1", "W1", "2", reviewed.minusMinutes(90), reviewed);
        insert("wms_in_bill", 8, "F1", "W1", "1", reviewed.minusMinutes(90), reviewed);
        insert("wms_in_bill", 8, "F1", "W1", "2", reviewed.minusDays(10), reviewed.minusDays(9));

        var result = service.query("W1", 7);

        assertEquals(2L, result.inbound().reviewedBills());
        assertEquals(1L, result.inbound().timedBills());
        assertEquals(1L, result.inbound().missingTimeBills());
        assertTrue(result.inbound().averageReviewMinutes().compareTo(new java.math.BigDecimal("30")) == 0);
        assertNull(result.outbound());
        assertEquals("W1", result.warehouseCode());
    }

    @Test
    void noBillPermissionReturnsUnobservedInsteadOfZero() {
        var result = service.query("W1", 7);

        assertNull(result.inbound());
        assertNull(result.outbound());
    }

    @Test
    void missingOrBypassedScopeAndInvalidWindowFailClosed() {
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.query("W1", 7));
        TenantContextHolder.setTenantId(8L);
        assertThrows(CheckedException.class, () -> service.query("*", 7));
        assertThrows(CheckedException.class, () -> service.query("W1", 0));
        TenantContextHolder.setTenantSkip();
        assertThrows(CheckedException.class, () -> service.query("W1", 7));
    }

    private void insert(String table, long tenant, String factory, String warehouse, String state,
                        LocalDateTime created, LocalDateTime reviewed) {
        jdbc.update("INSERT INTO " + table + " VALUES (?,?,?,?,?,?,?)",
                tenant, factory, warehouse, state, reviewed, created, "0");
    }
}
