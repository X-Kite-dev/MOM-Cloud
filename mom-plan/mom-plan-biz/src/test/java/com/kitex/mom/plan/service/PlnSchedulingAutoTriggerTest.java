package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.impl.PlnSchedulingJobServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingAutoTriggerTest {

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

    @Mock
    private PlnSchedulingTaskMapper plnSchedulingTaskMapper;

    @InjectMocks
    private PlnSchedulingJobServiceImpl plnSchedulingJobService;

    @Test
    void should_create_task_when_mrp_completed_event_received() {
        when(plnSchedulingEngineService.run(any(PlnSchedulingRunDTO.class))).thenReturn(Boolean.TRUE);

        LocalDate scheduleDate = LocalDate.now();
        Boolean result = plnSchedulingJobService.onMrpCompleted("DAY", scheduleDate);

        assertTrue(result);
        ArgumentCaptor<PlnSchedulingRunDTO> dtoCaptor = ArgumentCaptor.forClass(PlnSchedulingRunDTO.class);
        verify(plnSchedulingEngineService).run(dtoCaptor.capture());
        PlnSchedulingRunDTO captured = dtoCaptor.getValue();
        assertTrue("simulate".equals(captured.getMode()));
        assertTrue("AUTO".equals(captured.getTriggerType()));
        assertTrue("DAY".equals(captured.getCycleType()));
        assertTrue(scheduleDate.equals(captured.getScheduleDate()));
    }

    @Test
    void should_retry_failed_tasks() {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setId(1L);
        task.setTaskNo("TASK-1");
        task.setCycleType("DAY");
        task.setScheduleDate(LocalDate.now());
        task.setTaskStatus("FAILED");

        when(plnSchedulingTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(plnSchedulingEngineService.run(any(PlnSchedulingRunDTO.class))).thenReturn(Boolean.TRUE);

        Boolean result = plnSchedulingJobService.retryFailedTasks();

        assertTrue(result);
        verify(plnSchedulingEngineService).run(any(PlnSchedulingRunDTO.class));
        verify(plnSchedulingTaskMapper).updateById(any(PlnSchedulingTaskEntity.class));
    }

    @Test
    void should_return_false_when_retry_failed_task_run_returns_false() {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setId(2L);
        task.setTaskNo("TASK-2");
        task.setCycleType("DAY");
        task.setScheduleDate(LocalDate.now());
        task.setTaskStatus("FAILED");

        when(plnSchedulingTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(plnSchedulingEngineService.run(any(PlnSchedulingRunDTO.class))).thenReturn(Boolean.FALSE);

        Boolean result = plnSchedulingJobService.retryFailedTasks();

        assertFalse(result);
    }

    @Test
    void should_not_retry_when_reach_retry_limit() {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setId(3L);
        task.setTaskNo("TASK-3");
        task.setCycleType("DAY");
        task.setScheduleDate(LocalDate.now());
        task.setTaskStatus("FAILED");
        task.setRemark("RETRY_COUNT=3，重试失败");

        when(plnSchedulingTaskMapper.selectList(any())).thenReturn(List.of(task));

        Boolean result = plnSchedulingJobService.retryFailedTasks();

        assertFalse(result);
    }
}
