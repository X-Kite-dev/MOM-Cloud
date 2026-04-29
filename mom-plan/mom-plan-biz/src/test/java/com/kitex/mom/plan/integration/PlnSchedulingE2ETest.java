package com.kitex.mom.plan.integration;

import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingE2ETest {

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

    @Mock
    private PlnSchedulingTaskMapper plnSchedulingTaskMapper;

    @InjectMocks
    private PlnSchedulingJobServiceImpl plnSchedulingJobService;

    @Test
    void should_trigger_and_retry_for_day_week_month_flow() {
        when(plnSchedulingEngineService.run(any(PlnSchedulingRunDTO.class))).thenReturn(Boolean.TRUE);

        LocalDate scheduleDate = LocalDate.now();
        assertTrue(plnSchedulingJobService.onMrpCompleted("DAY", scheduleDate));
        assertTrue(plnSchedulingJobService.onMrpCompleted("WEEK", scheduleDate));
        assertTrue(plnSchedulingJobService.onMrpCompleted("MONTH", scheduleDate));

        ArgumentCaptor<PlnSchedulingRunDTO> captor = ArgumentCaptor.forClass(PlnSchedulingRunDTO.class);
        verify(plnSchedulingEngineService, times(3)).run(captor.capture());

        List<PlnSchedulingRunDTO> all = captor.getAllValues();
        assertTrue("simulate".equals(all.get(0).getMode()));
        assertTrue("AUTO".equals(all.get(0).getTriggerType()));
        assertTrue("DAY".equals(all.get(0).getCycleType()));

        assertTrue("simulate".equals(all.get(1).getMode()));
        assertTrue("AUTO".equals(all.get(1).getTriggerType()));
        assertTrue("WEEK".equals(all.get(1).getCycleType()));

        assertTrue("simulate".equals(all.get(2).getMode()));
        assertTrue("AUTO".equals(all.get(2).getTriggerType()));
        assertTrue("MONTH".equals(all.get(2).getCycleType()));
    }

    @Test
    void should_return_false_when_retry_contains_failed_reexecution() {
        PlnSchedulingTaskEntity dayTask = new PlnSchedulingTaskEntity();
        dayTask.setId(1L);
        dayTask.setTaskNo("TASK-DAY");
        dayTask.setCycleType("DAY");
        dayTask.setScheduleDate(LocalDate.now());
        dayTask.setTaskStatus("FAILED");

        PlnSchedulingTaskEntity weekTask = new PlnSchedulingTaskEntity();
        weekTask.setId(2L);
        weekTask.setTaskNo("TASK-WEEK");
        weekTask.setCycleType("WEEK");
        weekTask.setScheduleDate(LocalDate.now());
        weekTask.setTaskStatus("FAILED");

        when(plnSchedulingTaskMapper.selectList(any())).thenReturn(List.of(dayTask, weekTask));
        when(plnSchedulingEngineService.run(any(PlnSchedulingRunDTO.class))).thenReturn(Boolean.TRUE, Boolean.FALSE);

        Boolean result = plnSchedulingJobService.retryFailedTasks();

        assertFalse(result);
        verify(plnSchedulingEngineService, times(2)).run(any(PlnSchedulingRunDTO.class));
    }
}
