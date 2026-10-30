package com.kitex.mom.plan.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.service.impl.PlnSchedulingJobServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingAutoTriggerTest {

    @Mock
    private PlnSchedulingEngineService engine;

    @InjectMocks
    private PlnSchedulingJobServiceImpl jobs;

    @Test
    void should_create_simulation_when_mrp_completed_event_received() {
        when(engine.run(any(PlnSchedulingRunDTO.class))).thenReturn(true);
        var date = LocalDate.now();
        assertTrue(jobs.onMrpCompleted("DAY", date));
        var command = ArgumentCaptor.forClass(PlnSchedulingRunDTO.class);
        verify(engine).run(command.capture());
        assertEquals("simulate", command.getValue().getMode());
        assertEquals("AUTO", command.getValue().getTriggerType());
        assertEquals("DAY", command.getValue().getCycleType());
        assertEquals(date, command.getValue().getScheduleDate());
    }

    @Test
    void legacy_bulk_retry_without_frozen_commands_cannot_expand_the_source_range() {
        assertFalse(jobs.retryFailedTasks());
        verifyZeroInteractions(engine);
    }

    @Test
    void legacy_single_retry_is_rejected_and_keeps_original_failure_evidence() {
        var task = new PlnSchedulingTaskEntity();
        task.setId(3L);
        task.setTaskNo("TASK-3");
        task.setTaskStatus("FAILED");
        task.setRemark("RETRY_COUNT=3，原失败原因");
        assertThrows(CheckedException.class, () -> jobs.retryFailedTask(task));
        assertEquals("FAILED", task.getTaskStatus());
        assertEquals("RETRY_COUNT=3，原失败原因", task.getRemark());
        verifyZeroInteractions(engine);
    }
}
