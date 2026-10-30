package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesExecutionTemplatePreviewQueryDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Only persistence is substituted; result ranking, reasons, scope guards and paging use production service.
 */
class MesExecutionTemplatePreviewTest {
    private final MesActualFactoryAccessService access = mock(MesActualFactoryAccessService.class);
    private String querySql;

    private MesExecutionTemplateServiceImpl service(List<MesExecutionTemplateEntity> rows) {
        TenantContextHolder.setTenantId(1L);
        when(access.require("read")).thenReturn("F1");
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "preview-test"),
                MesExecutionTemplateEntity.class);
        return new MesExecutionTemplateServiceImpl(access, null, null, null, null, null, null) {
            @Override
            public List<MesExecutionTemplateEntity> list(Wrapper<MesExecutionTemplateEntity> wrapper) {
                querySql = wrapper.getSqlSegment();
                return rows;
            }
        };
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void selectionAndReasonsDoNotDependOnDisplayPage() {
        var high = template(2).setPriority(10).setMaterialCode("M1");
        var draft = template(3).setPriority(20).setStatus("DRAFT");
        var s = service(List.of(template(1), high, draft));
        var q = new MesExecutionTemplatePreviewQueryDTO();
        q.setMaterialCode(" M1 ");
        q.setSize(1);
        q.setCurrent(3);
        var result = s.preview(q);

        assertEquals("2", result.getSelected().getTemplateId());
        assertEquals(2, result.getMatchedCount());
        assertEquals(3, result.getTotal());
        assertEquals("1", result.getRecords().get(0).getTemplateId());
        assertTrue(querySql.contains("tenant_id") && querySql.contains("factory_code"));
        assertEquals(" M1 ", q.getMaterialCode());
        assertEquals("M1", result.getScenario().getMaterialCode());
    }

    @Test
    void draftsRetiredExpiredAndOtherScopesNeverWinAndExplainWhy() {
        var draft = template(1).setStatus("DRAFT");
        var retired = template(2).setStatus("RETIRED");
        var future = template(3).setEffectiveFrom(LocalDateTime.now().plusDays(1));
        var expired = template(4).setEffectiveTo(LocalDateTime.now().minusDays(1));
        var scoped = template(5).setMaterialCode("OTHER");
        var result = service(List.of(draft, retired, future, expired, scoped))
                .preview(new MesExecutionTemplatePreviewQueryDTO());

        assertNull(result.getSelected());
        assertEquals(0, result.getMatchedCount());
        assertEquals(5, result.getTotal());
        assertTrue(result.getRecords().stream().allMatch(r -> !r.getReasons().isEmpty()));
        assertTrue(result.getMessage().contains("历史工艺兼容"));
    }

    @Test
    void tieIsExposedAndLongIdsStayExactText() {
        var a = template(9007199254740992L);
        var b = template(9007199254740993L);
        var result = service(List.of(a, b)).preview(new MesExecutionTemplatePreviewQueryDTO());

        assertTrue(result.isRankingTie());
        assertEquals("9007199254740993", result.getSelected().getTemplateId());
    }

    @Test
    void matchedOnlyFiltersRowsWithoutChangingChoiceAndEmptyPageIsHonest() {
        var q = new MesExecutionTemplatePreviewQueryDTO();
        q.setMatchedOnly(true);
        q.setCurrent(Integer.MAX_VALUE);
        q.setSize(100);
        var result = service(List.of(template(1), template(2).setStatus("DRAFT"))).preview(q);

        assertEquals(1, result.getTotal());
        assertEquals("1", result.getSelected().getTemplateId());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void crossTenantOrFactoryResponseMustFailClosed() {
        var wrong = template(1);
        wrong.setTenantId(2L);
        var s = service(List.of(wrong));

        assertThrows(RuntimeException.class, () -> s.preview(new MesExecutionTemplatePreviewQueryDTO()));

        wrong.setTenantId(1L);
        wrong.setFactoryCode("F2");

        assertThrows(RuntimeException.class, () -> s.preview(new MesExecutionTemplatePreviewQueryDTO()));
    }

    @Test
    void nativeNodeInputsMustBeCompleteAndCannotUseDisplaySequence() {
        var s = service(List.of());
        var q = new MesExecutionTemplatePreviewQueryDTO();
        q.setRouteNodeCode("RN-1");

        assertThrows(RuntimeException.class, () -> s.preview(q));

        q.setRouteExecutionSchemaVersion("NODE_EXECUTION_V1");

        assertThrows(RuntimeException.class, () -> s.preview(q));

        q.setRouteCode("R");
        q.setProcessRouteCode("PR");
        q.setRouteNodeCode("1");

        assertThrows(RuntimeException.class, () -> s.preview(q));

        q.setRouteNodeCode("RN-1");

        assertDoesNotThrow(() -> s.preview(q));
    }

    @Test
    void invalidPaginationAndScopeInputsAreRejected() {
        var s = service(List.of());
        var q = new MesExecutionTemplatePreviewQueryDTO();
        q.setCurrent(0);

        assertThrows(RuntimeException.class, () -> s.preview(q));

        q.setCurrent(1);
        q.setSize(101);

        assertThrows(RuntimeException.class, () -> s.preview(q));

        q.setSize(20);
        q.setMaterialCode("x".repeat(101));

        assertThrows(RuntimeException.class, () -> s.preview(q));
    }

    private MesExecutionTemplateEntity template(long id) {
        var t = new MesExecutionTemplateEntity().setFactoryCode("F1").setTemplateCode("T" + id)
                .setStatus("PUBLISHED").setPriority(0).setVersionNo(1).setReportMode("QUANTITY");
        t.setId(id);
        t.setTenantId(1L);
        return t;
    }
}
