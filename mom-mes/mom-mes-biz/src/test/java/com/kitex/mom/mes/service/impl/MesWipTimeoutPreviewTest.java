package com.kitex.mom.mes.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class MesWipTimeoutPreviewTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Clock clock =
            Clock.fixed(Instant.parse("2026-10-31T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    MesActualFactoryAccessService access;
    MesWarehouseLiveStockMapper stocks;
    MesTaskArrangeMapper arrangements;
    MesWipTimeoutConfigMapper configs;
    MesWarehouseLiveStockEntity stock;
    List<MesWipTimeoutConfigEntity> rules;
    Map<String, Object> input;

    @BeforeEach
    void setup() {
        TenantContextHolder.setTenantId(1L);
        var u = mock(MomUser.class);
        when(u.getId()).thenReturn(7L);
        when(u.getTenantId()).thenReturn(1L);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(u, "N/A", List.of()));
        var config = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var t :
                List.of(
                        MesWarehouseLiveStockEntity.class,
                        MesTaskArrangeEntity.class,
                        MesWipTimeoutConfigEntity.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(config, "preview"), t);
        access = mock(MesActualFactoryAccessService.class);
        when(access.require("read")).thenReturn("F1");
        stocks = mock(MesWarehouseLiveStockMapper.class);
        arrangements = mock(MesTaskArrangeMapper.class);
        configs = mock(MesWipTimeoutConfigMapper.class);
        stock = new MesWarehouseLiveStockEntity();
        stock.setId(9007199254740993L);
        stock.setTenantId(1L);
        stock.setFactoryCode("F1");
        stock.setMatDetailType("WIP");
        stock.setMatBarcode("BC1");
        stock.setMatCode("P1");
        stock.setWarehouseCode("L1");
        stock.setRouteCode("R1");
        stock.setInTime(LocalDateTime.of(2026, 10, 31, 11, 58));
        stock.setNum(new BigDecimal("9007199254740993.125"));
        when(stocks.selectList(any())).thenReturn(List.of(stock));
        when(arrangements.selectList(any())).thenReturn(List.of());
        rules = new ArrayList<>();
        when(configs.selectList(any())).thenAnswer(c -> rules);
        input = new HashMap<>();
        input.put("stockId", "9007199254740993");
        input.put("name", "草稿");
        input.put("lineCode", "L1");
        input.put("routeCode", "R1");
        input.put("productCode", "P1");
        input.put("timeoutSec", "60");
        input.put("level2Sec", "120");
        input.put("level3Sec", "180");
        input.put("enabled", true);
    }

    @AfterEach
    void clean() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    Object service() throws Exception {
        var t = Class.forName("com.kitex.mom.mes.service.impl.MesWipTimeoutPreviewService");
        var c =
                Arrays.stream(t.getDeclaredConstructors())
                        .filter(v -> v.getParameterCount() == 5)
                        .findFirst()
                        .orElseThrow();
        c.setAccessible(true);
        return c.newInstance(access, stocks, arrangements, configs, clock);
    }

    JsonNode query() throws Exception {
        var q = Class.forName("com.kitex.mom.mes.dto.MesWipTimeoutPreviewDTO$Query");
        var s = service();
        try {
            return json.valueToTree(
                    s.getClass().getMethod("preview", q).invoke(s, json.convertValue(input, q)));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    JsonNode sources() throws Exception {
        var s = service();
        try {
            return json.valueToTree(
                    s.getClass().getMethod("sources", String.class).invoke(s, "BC1"));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    MesWipTimeoutConfigEntity rule(String code) {
        var r = new MesWipTimeoutConfigEntity();
        r.setId(3L);
        r.setTenantId(1L);
        r.setFactoryCode("F1");
        r.setCode(code);
        r.setEnabled(true);
        r.setTimeoutSec(500);
        return r;
    }

    @Test
    void draftUsesActualElapsedTimeExactSourceAndNoWrites() throws Exception {
        var d = query();
        assertEquals("SELECTED", d.path("draftStatus").asText());
        assertEquals("2", d.path("alertLevel").asText());
        assertEquals("120", d.path("elapsedSeconds").asText());
        assertEquals("9007199254740993.125", d.at("/source/quantity").asText());
        assertEquals("9007199254740993", d.at("/source/stockId").asText());
        verify(stocks, never()).insert(any(MesWarehouseLiveStockEntity.class));
        verify(configs, never()).insert(any(MesWipTimeoutConfigEntity.class));
    }

    @Test
    void disabledDraftIsNotMadeEnabledByPreview() throws Exception {
        input.put("enabled", false);
        var d = query();
        assertEquals("DISABLED", d.path("draftStatus").asText());
        assertTrue(d.path("selectedRule").isNull());
        assertFalse(d.path("wouldAlert").asBoolean());
    }

    @Test
    void moreSpecificSavedRuleShadowsCurrentDraft() throws Exception {
        input.remove("productCode");
        input.remove("routeCode");
        var r = rule("PRODUCT");
        r.setProductCode("P1");
        rules.add(r);
        var d = query();
        assertEquals("SHADOWED", d.path("draftStatus").asText());
        assertEquals("PRODUCT", d.at("/selectedRule/code").asText());
        assertFalse(d.path("wouldAlert").asBoolean());
    }

    @Test
    void sameScopeCompetitionIsExplicitAmbiguity() throws Exception {
        var r = rule("EXISTING");
        r.setLineCode("L1");
        r.setRouteCode("R1");
        r.setProductCode("P1");
        rules.add(r);
        var d = query();
        assertEquals("AMBIGUOUS", d.path("draftStatus").asText());
        assertTrue(d.path("selectedRule").isNull());
        assertFalse(d.path("wouldAlert").asBoolean());
    }

    @Test
    void scopeMismatchIsNotAnAlert() throws Exception {
        input.put("productCode", "P2");
        var d = query();
        assertEquals("OUT_OF_SCOPE", d.path("draftStatus").asText());
        assertFalse(d.path("wouldAlert").asBoolean());
    }

    @Test
    void missingAndFutureTimeNeverBecomeZeroAge() throws Exception {
        stock.setInTime(null);
        assertEquals("MISSING", query().path("sourceTimeStatus").asText());
        assertTrue(query().path("elapsedSeconds").isNull());
        stock.setInTime(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertEquals("FUTURE", query().path("sourceTimeStatus").asText());
        assertFalse(query().path("wouldAlert").asBoolean());
    }

    @Test
    void foreignFactorySourceRejected() {
        stock.setFactoryCode("F2");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void foreignTenantSourceRejected() {
        stock.setTenantId(2L);
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void nonWipOrNonpositiveSourceRejected() {
        stock.setMatDetailType("RAW");
        assertThrows(RuntimeException.class, this::query);
        stock.setMatDetailType("WIP");
        stock.setNum(BigDecimal.ZERO);
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void stockIdCannotBeSubstituted() {
        input.put("stockId", "2");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void editingMustFindExactOwnSavedRule() {
        input.put("draftId", "3");
        assertThrows(RuntimeException.class, this::query);
        var r = rule("EXISTING");
        r.setFactoryCode("F2");
        rules.add(r);
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void editingReplacesOwnRuleWithoutSelfAmbiguity() throws Exception {
        input.put("draftId", "3");
        var r = rule("EXISTING");
        r.setLineCode("L1");
        r.setRouteCode("R1");
        r.setProductCode("P1");
        rules.add(r);
        var d = query();
        assertEquals("SELECTED", d.path("draftStatus").asText());
        assertEquals(1, d.path("matches").size());
    }

    @Test
    void malformedThresholdAndExtraFieldsAreRejected() {
        input.put("timeoutSec", "60.5");
        assertThrows(RuntimeException.class, this::query);
        input.put("timeoutSec", "60");
        input.put("factoryCode", "F2");
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void truncatedRuleSetCannotClaimUniqueWinner() {
        for (int i = 0; i < 1001; i++) {
            var r = rule("R" + i);
            rules.add(r);
        }
        assertThrows(RuntimeException.class, this::query);
    }

    @Test
    void barcodeChoicesKeepPreciseIdsAndAreExplicitlyBounded() throws Exception {
        var d = sources();
        assertEquals("9007199254740993", d.at("/rows/0/stockId").asText());
        var list = new ArrayList<MesWarehouseLiveStockEntity>();
        for (int i = 0; i < 21; i++) {
            var row = new MesWarehouseLiveStockEntity();
            org.springframework.beans.BeanUtils.copyProperties(stock, row);
            row.setId(100L + i);
            list.add(row);
        }
        when(stocks.selectList(any())).thenReturn(list);
        assertEquals(20, sources().path("rows").size());
        assertTrue(sources().path("truncated").asBoolean());
    }
}
