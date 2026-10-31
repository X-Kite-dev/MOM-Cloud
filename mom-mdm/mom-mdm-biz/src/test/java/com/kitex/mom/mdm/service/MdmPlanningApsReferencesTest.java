package com.kitex.mom.mdm.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.feign.RemoteMesFactoryPlanningImpactService;
import com.kitex.mom.mdm.feign.RemotePlanFactoryPlanningImpactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Remote transport is declared; actual inspector validates the returned read-only snapshot.
 */
class MdmPlanningApsReferencesTest {
    RemotePlanFactoryPlanningImpactService plan;
    FactoryPlanningPolicyImpactInspector inspector;
    MdmFactoryPlanningPolicyEntity draft;
    MdmFactoryPlanningApsReferencesDTO body;

    @BeforeEach
    void setup() {
        plan = mock(RemotePlanFactoryPlanningImpactService.class);
        inspector =
                new FactoryPlanningPolicyImpactInspector(
                        plan, mock(RemoteMesFactoryPlanningImpactService.class));
        draft = new MdmFactoryPlanningPolicyEntity();
        draft.setId(10L);
        draft.setTenantId(1L);
        draft.setFactoryCode("F1");
        body =
                new MdmFactoryPlanningApsReferencesDTO()
                        .setTenantId(1L)
                        .setFactoryCode("F1")
                        .setTotal(1L)
                        .setLimit(50)
                        .setTruncated(false)
                        .setObservedAt(LocalDateTime.now())
                        .setRecords(new ArrayList<>(List.of(row("1"))));
        when(plan.apsReferences("F1")).thenAnswer(a -> R.ok(body));
    }

    MdmFactoryPlanningApsReferencesDTO.Reference row(String id) {
        return new MdmFactoryPlanningApsReferencesDTO.Reference()
                .setSourceType("PLAN")
                .setSourceId(id)
                .setSourceDeleted(false)
                .setRelationIncomplete(false);
    }

    @Test
    void bindsSuccessfulSnapshotToTheExactDraftWithoutChangingPublicationState() {
        draft.setStatus("DRAFT");
        var result = inspector.apsReferences(draft);
        assertEquals("10", result.getDraftId());
        assertEquals(1L, result.getTotal());
        assertEquals("1", result.getRecords().get(0).getSourceId());
        assertEquals("DRAFT", draft.getStatus());
    }

    @Test
    void preservesExplicitEmptyAndTruncatedSnapshots() {
        body.setTotal(0L).setRecords(List.of());
        assertEquals(0L, inspector.apsReferences(draft).getTotal());
        body.setTotal(51L)
                .setTruncated(true)
                .setRecords(
                        java.util.stream.LongStream.rangeClosed(1, 50)
                                .mapToObj(n -> row(Long.toString(n)))
                                .toList());
        assertTrue(inspector.apsReferences(draft).getTruncated());
        assertEquals(50, inspector.apsReferences(draft).getRecords().size());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "TENANT",
                    "FACTORY",
                    "NEGATIVE",
                    "NO_TOTAL",
                    "NO_TIME",
                    "NO_ROWS",
                    "NO_LIMIT",
                    "OVERSIZED",
                    "DUPLICATE",
                    "SHORT",
                    "FALSE_TRUNCATED",
                    "BAD_TYPE",
                    "BAD_ID",
                    "NULL_FLAGS",
                    "NULL_TYPE"
            })
    void malformedOrWrongScopeEvidenceIsUnavailable(String mode) {
        switch (mode) {
            case "TENANT" -> body.setTenantId(2L);
            case "FACTORY" -> body.setFactoryCode("F2");
            case "NEGATIVE" -> body.setTotal(-1L);
            case "NO_TOTAL" -> body.setTotal(null);
            case "NO_TIME" -> body.setObservedAt(null);
            case "NO_ROWS" -> body.setRecords(null);
            case "NO_LIMIT" -> body.setLimit(null);
            case "OVERSIZED" -> {
                body.setTotal(51L).setTruncated(true);
                body.setRecords(
                        java.util.stream.LongStream.rangeClosed(1, 51)
                                .mapToObj(n -> row(Long.toString(n)))
                                .toList());
            }
            case "DUPLICATE" -> body.setTotal(2L).setRecords(List.of(row("1"), row("1")));
            case "SHORT" -> body.setTotal(2L);
            case "FALSE_TRUNCATED" -> body.setTruncated(true);
            case "NULL_TYPE" -> body.getRecords().get(0).setSourceType(null);
            case "BAD_TYPE" -> body.getRecords().get(0).setSourceType("UNKNOWN");
            case "BAD_ID" -> body.getRecords().get(0).setSourceId("1e2");
            default -> body.getRecords().get(0).setRelationIncomplete(null);
        }
        assertThrows(CheckedException.class, () -> inspector.apsReferences(draft));
    }

    @Test
    void failureResponseWithDataAndTransportFailureAreNotZero() {
        when(plan.apsReferences("F1")).thenReturn(R.failed(body, "unavailable"));
        assertThrows(CheckedException.class, () -> inspector.apsReferences(draft));
        when(plan.apsReferences("F1")).thenThrow(new IllegalStateException("offline"));
        assertThrows(CheckedException.class, () -> inspector.apsReferences(draft));
    }
}
