package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.plan.entity.PlnSchedulingConflictEntity;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.service.impl.PlnSchedulingExecutionService;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Collections;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingConflictQueryTest {

    private MockMvc mockMvc;

    @Mock
    private PlnSchedulingEngineService engine;

    @Mock
    private PlnSchedulingExecutionService execution;

    @Mock
    private PlnDemandMonthService demand;

    @Mock
    private PlnOperationSchedulingService operations;

    @Mock
    private PlnResourceLoadService loads;

    @Mock
    private PlnSchedulingBatchService batches;

    @InjectMocks
    private PlnSchedulingRuleController controller;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Mock
    private PlnSchedulingRuleService plnSchedulingRuleService;

    @Test
    void should_query_conflict_page() throws Exception {
        PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
        conflict.setConflictType("MATERIAL_SHORTAGE");
        conflict.setConflictLevel("HIGH");
        Page<PlnSchedulingConflictEntity> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(conflict));
        when(plnSchedulingRuleService.pageConflict(any(), any())).thenReturn(page);
        mockMvc.perform(get("/plnScheduling/conflicts/page").param("current", "1").param("size", "10")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.records[0].conflictType").value("MATERIAL_SHORTAGE"));
    }
}
