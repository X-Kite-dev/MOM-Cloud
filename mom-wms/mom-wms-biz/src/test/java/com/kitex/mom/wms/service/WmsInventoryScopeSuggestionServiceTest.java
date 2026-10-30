package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WmsInventoryScopeSuggestionServiceTest {
    private final WmsLiveStockMapper stocks = mock(WmsLiveStockMapper.class);
    private final RemoteWarehouseLocationService locations = mock(RemoteWarehouseLocationService.class);
    private final WmsInventoryWarehousePermission permission = mock(WmsInventoryWarehousePermission.class);
    private final WmsWarehouseBusinessGuard guard = mock(WmsWarehouseBusinessGuard.class);
    private WmsInventoryScopeSuggestionService service;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(8L);
        FactoryContextHolder.setFactoryCode("F1");
        var site = new MdmWarehouseLocationEntity();
        site.setTenantId(8L);
        site.setFactoryCode("F1");
        site.setWarehouseCode("W1");
        site.setLocationCode("S1");
        site.setLocationName("库位一");
        site.setLocationLevel(3);
        site.setStatus(true);
        when(locations.getByCode("S1")).thenReturn(R.ok(site));
        when(guard.check("W1", "other_inventory", true)).thenReturn(true);
        when(stocks.selectList(any())).thenReturn(List.of());
        service = new WmsInventoryScopeSuggestionService(stocks, locations, permission, guard);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
    }

    @Test
    void sameMaterialWithCompleteBatchesProducesSeparateDraftRows() {
        when(stocks.selectList(any())).thenReturn(List.of(
                stock(1, "M1", "B1", "PCS", 2, "OWNED"),
                stock(2, "M1", "B1", "PCS", 3, "OWNED"),
                stock(3, "M1", "B2", "PCS", 4, "CUSTOMER_SUPPLIED")));

        var result = service.suggest("W1", "S1");

        assertEquals(3, result.stockRowCount());
        assertEquals(2, result.rows().size());
        assertEquals("B1", result.rows().get(0).matBatch());
        assertEquals(5, result.rows().get(0).currentNumber().intValue());
        assertFalse(result.rows().get(0).externalOwnerPresent());
        assertEquals("B2", result.rows().get(1).matBatch());
        assertTrue(result.rows().get(1).externalOwnerPresent());
        verify(permission).require("add", "W1");
    }

    @Test
    void blankBatchCollapsesToOneAllBatchScopeWithoutOverlap() {
        when(stocks.selectList(any())).thenReturn(List.of(
                stock(1, "M1", null, "PCS", 2, "OWNED"),
                stock(2, "M1", "B1", "PCS", 3, "OWNED")));

        var result = service.suggest("W1", "S1");

        assertEquals(1, result.rows().size());
        assertEquals(null, result.rows().get(0).matBatch());
        assertEquals(5, result.rows().get(0).currentNumber().intValue());
    }

    @Test
    void mixedUnitsOrUnexpectedOrganizationFailsInsteadOfProducingWrongPlan() {
        when(stocks.selectList(any())).thenReturn(List.of(
                stock(1, "M1", "B1", "PCS", 2, "OWNED"),
                stock(2, "M1", "B2", "KG", 3, "OWNED")));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1"));

        var foreign = stock(3, "M1", "B3", "PCS", 1, "OWNED");
        foreign.setTenantId(9L);
        when(stocks.selectList(any())).thenReturn(List.of(foreign));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1"));
    }

    @Test
    void invalidContextAndForeignLocationFailClosed() {
        TenantContextHolder.clear();
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1"));
        TenantContextHolder.setTenantId(8L);
        var foreign = new MdmWarehouseLocationEntity();
        foreign.setTenantId(8L);
        foreign.setFactoryCode("F1");
        foreign.setWarehouseCode("W2");
        foreign.setLocationCode("S1");
        foreign.setLocationLevel(3);
        foreign.setStatus(true);
        when(locations.getByCode("S1")).thenReturn(R.ok(foreign));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1"));
    }

    @Test
    void emptyLocationReturnsEmptySuggestion() {
        var result = service.suggest("W1", "S1");

        assertTrue(result.rows().isEmpty());
        assertEquals(0, result.stockRowCount());
    }

    @Test
    void oversizedLocationFailsInsteadOfReturningAnIncompleteCountScope() {
        when(stocks.selectList(any())).thenReturn(LongStream.rangeClosed(1, 1001)
                .mapToObj(id -> stock(id, "M1", "B1", "PCS", 1, "OWNED")).toList());

        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1"));
    }

    @Test
    void nearExpiryRecommendsTheWholeBatchQuantityWhenOnlyOneStockRowMatches() {
        var soon = stock(1, "M1", "B1", "PCS", 2, "OWNED");
        soon.setMatValidEndDate(LocalDate.now().minusDays(1));
        var later = stock(2, "M1", "B1", "PCS", 3, "OWNED");
        later.setMatValidEndDate(LocalDate.now().plusDays(100));
        var unknown = stock(3, "M2", "B2", "PCS", 4, "OWNED");
        when(stocks.selectList(any())).thenReturn(List.of(soon, later, unknown));

        var result = service.suggest("W1", "S1", "NEAR_EXPIRY", 30);

        assertEquals("NEAR_EXPIRY", result.ruleCode());
        assertEquals(30, result.thresholdDays());
        assertEquals(3, result.stockRowCount());
        assertEquals(1, result.rows().size());
        assertEquals("B1", result.rows().get(0).matBatch());
        assertEquals(5, result.rows().get(0).currentNumber().intValue());
    }

    @Test
    void oldStockAndExternalOwnerRulesSelectWholeScopes() {
        var old = stock(1, "M1", "B1", "PCS", 2, "OWNED");
        old.setInTime(LocalDateTime.now().minusDays(200));
        var recent = stock(2, "M1", "B1", "PCS", 3, "CUSTOMER_SUPPLIED");
        recent.setInTime(LocalDateTime.now().minusDays(1));
        var unknown = stock(3, "M2", "B2", "PCS", 4, "OWNED");
        when(stocks.selectList(any())).thenReturn(List.of(old, recent, unknown));

        var aged = service.suggest("W1", "S1", "LONG_STAY", 90);
        var external = service.suggest("W1", "S1", "EXTERNAL_OWNER", null);

        assertEquals(1, aged.rows().size());
        assertEquals(5, aged.rows().get(0).currentNumber().intValue());
        assertEquals(1, external.rows().size());
        assertEquals(5, external.rows().get(0).currentNumber().intValue());
        assertTrue(external.rows().get(0).externalOwnerPresent());
    }

    @Test
    void blankBatchRuleMatchProducesOneCompleteAllBatchScope() {
        var blank = stock(1, "M1", null, "PCS", 2, "OWNED");
        var near = stock(2, "M1", "B1", "PCS", 3, "OWNED");
        near.setMatValidEndDate(LocalDate.now().minusDays(1));
        when(stocks.selectList(any())).thenReturn(List.of(blank, near));

        var result = service.suggest("W1", "S1", "NEAR_EXPIRY", 30);

        assertEquals(1, result.rows().size());
        assertEquals(null, result.rows().get(0).matBatch());
        assertEquals(5, result.rows().get(0).currentNumber().intValue());
    }

    @Test
    void unknownRuleAndInvalidThresholdAreRejected() {
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1", "ANYTHING", null));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1", "NEAR_EXPIRY", null));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1", "LONG_STAY", 0));
        assertThrows(CheckedException.class, () -> service.suggest("W1", "S1", "LONG_STAY", 3651));
    }

    private WmsLiveStockEntity stock(long id, String material, String batch, String unit,
                                     double quantity, String owner) {
        var row = new WmsLiveStockEntity().setFactoryCode("F1").setWarehouseCode("W1")
                .setSiteCode("S1").setMatCode(material).setMatBatch(batch).setMatUnit(unit)
                .setMatNum(quantity).setStockOwnerType(owner);
        row.setId(id);
        row.setTenantId(8L);
        return row;
    }
}
