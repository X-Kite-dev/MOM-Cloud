package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.plan.entity.PlnSchedulingConflictEntity;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlnSchedulingRuleController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlnSchedulingConflictQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlnSchedulingRuleService plnSchedulingRuleService;

    @Test
    void should_query_conflict_page() throws Exception {
        PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
        conflict.setConflictType("MATERIAL_SHORTAGE");
        conflict.setConflictLevel("HIGH");

        Page<PlnSchedulingConflictEntity> page = new Page<>(1, 10);
        page.setRecords(Collections.singletonList(conflict));
        when(plnSchedulingRuleService.pageConflict(any(), any())).thenReturn(page);

        mockMvc.perform(get("/plnScheduling/conflicts/page")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].conflictType").value("MATERIAL_SHORTAGE"));
    }
}
