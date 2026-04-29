package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingTaskQueryTest {

    @Mock
    private PlnSchedulingRuleService plnSchedulingRuleService;

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

    @Mock
    private PlnDemandMonthService plnDemandMonthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PlnSchedulingRuleController controller = new PlnSchedulingRuleController(plnSchedulingRuleService, plnSchedulingEngineService, plnDemandMonthService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void should_query_task_page() throws Exception {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setTaskNo("TASK-001");
        task.setTaskStatus("PENDING");

        Page<PlnSchedulingTaskEntity> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(task));
        when(plnSchedulingRuleService.pageTask(any(), any())).thenReturn(page);

        mockMvc.perform(get("/plnScheduling/tasks/page")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].taskNo").value("TASK-001"));
    }
}
