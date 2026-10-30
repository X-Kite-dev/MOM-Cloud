package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesExecutionTemplateMatcherTest {
    private final LocalDateTime at = LocalDateTime.of(2026, 10, 23, 10, 0);
    private final MesTaskEntity task = new MesTaskEntity().setWorkshopCode("WS1").setProcessRouteCode("PR1");
    private final MesTaskArrangeEntity arrange = new MesTaskArrangeEntity().setMatCode("M1").setRouteCode("R1")
            .setWorksiteCodes(" SITE1 , SITE2 ").setSourceRouteNodeCode("RN-1")
            .setRouteExecutionSchemaVersion("NODE_EXECUTION_V1");

    @Test
    void onlyPublishedWithinHalfOpenEffectiveWindowIsEligible() {
        var t = template(1);
        t.setEffectiveFrom(at).setEffectiveTo(at.plusSeconds(1));

        assertTrue(eligible(t));

        t.setEffectiveTo(at);

        assertFalse(eligible(t));

        t.setEffectiveTo(null).setEffectiveFrom(at.plusSeconds(1));

        assertFalse(eligible(t));

        t.setEffectiveFrom(null).setStatus("DRAFT");

        assertFalse(eligible(t));

        t.setStatus("RETIRED");

        assertFalse(eligible(t));
    }

    @Test
    void unrestrictedAndAllSixScopesUseExactProductionMatching() {
        var t = template(1);

        assertTrue(eligible(t));

        t.setMaterialCode("M1").setRouteCode("R1").setProcessRouteCode("PR1").setRouteNodeCode("RN-1")
                .setWorkshopCode("WS1").setWorksiteCode("SITE2");

        assertTrue(eligible(t));
        assertEquals(6, MesExecutionTemplateMatcher.specificity(t));

        t.setWorksiteCode("SITE");

        assertFalse(eligible(t));

        t.setWorksiteCode("SITE2").setRouteNodeCode("RN-2");

        assertFalse(eligible(t));
    }

    @Test
    void nativeTemplateMustNotMatchLegacyNodeEvenWhenNodeTextCoincides() {
        var t = template(1).setRouteNodeCode("RN-1");
        arrange.setRouteExecutionSchemaVersion(null);

        assertTrue(
                MesExecutionTemplateMatcher.reasons(t, task, arrange, at).stream().anyMatch(r -> r.contains("原生节点")));
    }

    @Test
    void allMismatchesAreExplainedWithoutChangingSourceTemplate() {
        var t = template(1).setMaterialCode("OTHER").setRouteCode("OTHER").setWorkshopCode("OTHER").setStatus("DRAFT");

        assertEquals(4, MesExecutionTemplateMatcher.reasons(t, task, arrange, at).size());
        assertEquals("DRAFT", t.getStatus());
    }

    @Test
    void rankKeepsPrioritySpecificityVersionAndExactLongIdOrder() {
        var broad = template(1).setPriority(10);
        var specific = template(2).setMaterialCode("M1");

        assertSame(broad, List.of(broad, specific).stream().max(MesExecutionTemplateMatcher.order()).orElseThrow());

        broad.setPriority(0);

        assertSame(specific, List.of(broad, specific).stream().max(MesExecutionTemplateMatcher.order()).orElseThrow());

        var newer = template(3).setMaterialCode("M1").setVersionNo(2);

        assertTrue(MesExecutionTemplateMatcher.order().compare(newer, specific) > 0);

        newer.setVersionNo(1);
        newer.setId(9007199254740993L);
        specific.setId(9007199254740992L);

        assertTrue(MesExecutionTemplateMatcher.order().compare(newer, specific) > 0);
    }

    private boolean eligible(MesExecutionTemplateEntity t) {
        return MesExecutionTemplateMatcher.reasons(t, task, arrange, at).isEmpty();
    }

    private MesExecutionTemplateEntity template(long id) {
        var t = new MesExecutionTemplateEntity().setStatus("PUBLISHED").setVersionNo(1).setPriority(0);
        t.setId(id);
        return t;
    }
}
