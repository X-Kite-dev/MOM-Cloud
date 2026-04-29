package com.kitex.mom.plan.service.conflict;

import com.kitex.mom.plan.entity.PlnSchedulingConflictEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictEngineTest {

    private final ConflictEngine conflictEngine = new ConflictEngine();

    @Test
    void should_generate_machine_time_overlap_with_hard_constraint_true() {
        Long taskId = 1001L;
        LocalDate sameDay = LocalDate.of(2026, 4, 11);

        PlnSchedulingTaskDetailEntity first = detail(taskId, 1L, "ISSUE-1", "P-01", sameDay);
        PlnSchedulingTaskDetailEntity second = detail(taskId, 2L, "ISSUE-2", "P-01", sameDay);

        List<PlnSchedulingConflictEntity> conflicts = conflictEngine.detect(taskId, List.of(first, second));

        assertTrue(conflicts.stream().anyMatch(c -> "MACHINE_TIME_OVERLAP".equals(c.getConflictType())));
        assertTrue(conflicts.stream().allMatch(c -> Boolean.TRUE.equals(c.getHardConstraint())));
    }

    @Test
    void should_return_empty_when_no_conflict() {
        Long taskId = 1002L;

        PlnSchedulingTaskDetailEntity first = detail(taskId, 3L, "ISSUE-3", "P-01", LocalDate.of(2026, 4, 11));
        PlnSchedulingTaskDetailEntity second = detail(taskId, 4L, "ISSUE-4", "P-02", LocalDate.of(2026, 4, 12));

        List<PlnSchedulingConflictEntity> conflicts = conflictEngine.detect(taskId, List.of(first, second));

        assertEquals(0, conflicts.size());
    }

    @Test
    void should_ignore_null_detail_when_detecting_conflicts() {
        Long taskId = 1003L;
        PlnSchedulingTaskDetailEntity detail = detail(taskId, 5L, "ISSUE-5", "P-03", LocalDate.of(2026, 4, 13));

        List<PlnSchedulingConflictEntity> conflicts = conflictEngine.detect(taskId, List.of(null, detail));

        assertTrue(conflicts.isEmpty());
    }

    private PlnSchedulingTaskDetailEntity detail(Long taskId, Long issueId, String issueNo, String productCode, LocalDate scheduledDate) {
        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        detail.setTaskId(taskId);
        detail.setIssueId(issueId);
        detail.setIssueNo(issueNo);
        detail.setProductCode(productCode);
        detail.setScheduledDate(scheduledDate);
        return detail;
    }
}
