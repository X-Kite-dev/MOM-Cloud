package com.kitex.mom.plan.service.conflict;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConflictEngineTest {

    private final ConflictEngine engine = new ConflictEngine();

    @Test
    void explicit_operation_resource_overlap_is_a_hard_conflict() {
        var detail = detail(1L, "P1", LocalDate.of(2026, 4, 11));
        detail.setOperationHardBlocked(true);
        detail.setOperationScheduleStatus("RESOURCE_OVERLAP");
        var result = engine.detect(100L, List.of(detail));
        assertEquals(1, result.size());
        assertEquals("OPERATION_RESOURCE_OVERLAP", result.get(0).getConflictType());
        assertTrue(result.get(0).getHardConstraint());
        assertEquals(1L, result.get(0).getIssueId());
    }

    @Test
    void complete_snapshots_without_resource_or_material_conflicts_are_clear() {
        assertTrue(engine.detect(100L, List.of(detail(1L, "P1", LocalDate.of(2026, 4, 11)), detail(2L, "P2", LocalDate.of(2026, 4, 12)))).isEmpty());
    }

    @Test
    void same_product_and_day_do_not_invent_a_machine_overlap() {
        var day = LocalDate.of(2026, 4, 11);
        assertTrue(engine.detect(100L, List.of(detail(1L, "P1", day), detail(2L, "P1", day))).isEmpty());
    }

    @Test
    void same_day_concentration_is_only_a_soft_warning() {
        var day = LocalDate.of(2026, 4, 11);
        var result = engine.detect(100L, List.of(detail(1L, "P1", day), detail(2L, "P1", day), detail(3L, "P1", day), detail(4L, "P1", day)));
        assertEquals(1, result.size());
        assertEquals("SAME_PRODUCT_DAY_CONCENTRATION", result.get(0).getConflictType());
        assertFalse(result.get(0).getHardConstraint());
    }

    @Test
    void null_detail_is_explicitly_rejected_without_dropping_the_source() {
        assertThrows(CheckedException.class, () -> engine.detect(100L, Arrays.asList(null, detail(1L, "P1", LocalDate.of(2026, 4, 11)))));
    }

    private PlnSchedulingTaskDetailEntity detail(Long id, String product, LocalDate date) {
        var detail = new PlnSchedulingTaskDetailEntity();
        detail.setTaskId(100L);
        detail.setIssueId(id);
        detail.setIssueNo("ISSUE-" + id);
        detail.setProductCode(product);
        detail.setScheduledDate(date);
        detail.setProcessRouteCode("R1");
        detail.setOperationScheduleStatus("SCHEDULED");
        detail.setOperationHardBlocked(false);
        detail.setMaterialConstraintStatus("READY");
        detail.setMaterialHardBlocked(false);
        return detail;
    }
}
