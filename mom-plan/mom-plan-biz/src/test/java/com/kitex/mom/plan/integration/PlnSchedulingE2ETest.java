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

/**
 * 历史类名保留；仅验证作业端口契约，不代表实库或现场端到端验收。
 */
@ExtendWith(MockitoExtension.class)
class PlnSchedulingE2ETest {

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

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
    void legacy_retry_without_frozen_input_stays_disabled() {
        assertFalse(plnSchedulingJobService.retryFailedTasks());
        verifyZeroInteractions(plnSchedulingEngineService);
    }
}
