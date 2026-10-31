package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.kitex.mom.mes.entity.*;

import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

class MesWipTimeoutRuleEvaluatorTest {
    MesWarehouseLiveStockEntity stock;
    MesTaskArrangeEntity arrange;

    @BeforeEach
    void setup() {
        stock = new MesWarehouseLiveStockEntity();
        stock.setId(1L);
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        stock.setBillNo("A1");
        stock.setRouteCode("R1");
        stock.setWarehouseCode("L1");
        stock.setMatCode("P1");
        arrange = new MesTaskArrangeEntity();
        arrange.setTenantId(1L);
        arrange.setFactoryCode("F1");
        arrange.setCode("A1");
        arrange.setRouteCode("R2");
        arrange.setRouteWorkCenter("L2");
    }

    MesWipTimeoutConfigEntity rule(String code, String l, String r, String p) {
        var v = new MesWipTimeoutConfigEntity();
        v.setTenantId(1L);
        v.setFactoryCode("F1");
        v.setCode(code);
        v.setLineCode(l);
        v.setRouteCode(r);
        v.setProductCode(p);
        v.setEnabled(true);
        v.setTimeoutSec(60);
        v.setLevel2Sec(120);
        v.setLevel3Sec(180);
        return v;
    }

    Object call(String method, Class<?>[] types, Object... args) throws Exception {
        var type = Class.forName("com.kitex.mom.mes.service.impl.MesWipTimeoutRuleEvaluator");
        try {
            return type.getMethod(method, types).invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    Object select(List<MesWipTimeoutConfigEntity> rules) throws Exception {
        return call(
                "select",
                new Class[] {
                    List.class, MesWarehouseLiveStockEntity.class, MesTaskArrangeEntity.class
                },
                rules,
                stock,
                arrange);
    }

    Object prop(Object o, String name) throws Exception {
        return o.getClass().getMethod(name).invoke(o);
    }

    @Test
    void productThenProcessThenLinePrecedenceIsSameAsProductionScan() throws Exception {
        var global = rule("G", null, null, null);
        var line = rule("L", "L1", null, null);
        var route = rule("R", null, "R1", null);
        var product = rule("P", null, null, "P1");
        var s = select(List.of(global, line, route, product));
        assertSame(product, prop(s, "selected"));
        assertEquals(4, ((List<?>) prop(s, "matches")).size());
    }

    @Test
    void equallySpecificRulesRemainAmbiguous() throws Exception {
        var s = select(List.of(rule("A", null, "R1", null), rule("B", null, "R1", null)));
        assertNull(prop(s, "selected"));
        assertEquals(2, ((List<?>) prop(s, "preferred")).size());
    }

    @Test
    void foreignFactoryRuleCannotWin() throws Exception {
        var foreign = rule("X", "L1", "R1", "P1");
        foreign.setFactoryCode("F2");
        var own = rule("O", null, null, null);
        assertSame(own, prop(select(List.of(foreign, own)), "selected"));
    }

    @Test
    void foreignTenantRuleCannotWin() throws Exception {
        var foreign = rule("X", "L1", "R1", "P1");
        foreign.setTenantId(2L);
        assertNull(prop(select(List.of(foreign)), "selected"));
    }

    @Test
    void disabledRuleCannotWin() throws Exception {
        var r = rule("X", null, null, null);
        r.setEnabled(false);
        assertNull(prop(select(List.of(r)), "selected"));
    }

    @Test
    void blankScopeIsWildcardButCodesAreExact() throws Exception {
        assertNotNull(prop(select(List.of(rule("X", " ", null, null))), "selected"));
        assertNull(prop(select(List.of(rule("X", "L10", null, null))), "selected"));
    }

    @Test
    void actualSourceFieldsWinAndExplicitFallbackMatchesScan() throws Exception {
        stock.setRouteCode(null);
        stock.setWarehouseCode(null);
        assertNotNull(prop(select(List.of(rule("X", "L2", "R2", null))), "selected"));
        stock.setSiteCode("R3");
        assertNull(prop(select(List.of(rule("X", null, "R2", null))), "selected"));
    }

    @Test
    void crossFactoryDispatchNeverSuppliesFallback() {
        arrange.setFactoryCode("F2");
        assertThrows(RuntimeException.class, () -> select(List.of(rule("X", null, null, null))));
    }

    @Test
    void wrongDispatchCodeNeverSuppliesFallback() {
        arrange.setCode("OTHER");
        assertThrows(RuntimeException.class, () -> select(List.of(rule("X", null, null, null))));
    }

    @Test
    void unknownSourceOwnerCannotProduceDecision() {
        stock.setTenantId(null);
        assertThrows(RuntimeException.class, () -> select(List.of(rule("X", null, null, null))));
    }

    @Test
    void thresholdBoundaryUsesExistingGreaterOrEqualContract() throws Exception {
        var r = rule("X", null, null, null);
        for (long s : new long[] {-1, 0, 59, 60, 119, 120, 179, 180, Long.MAX_VALUE})
            assertEquals(
                    s < 60 ? null : s < 120 ? 1 : s < 180 ? 2 : 3,
                    call("level", new Class[] {MesWipTimeoutConfigEntity.class, long.class}, r, s));
    }

    @Test
    void malformedThresholdCannotSilentlyYieldAnAlert() {
        var r = rule("X", null, null, null);
        r.setLevel2Sec(50);
        assertThrows(
                RuntimeException.class,
                () ->
                        call(
                                "level",
                                new Class[] {MesWipTimeoutConfigEntity.class, long.class},
                                r,
                                200L));
    }
}
