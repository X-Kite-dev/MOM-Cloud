package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.service.PlnIssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlnIssueController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlnIssueCompatibilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlnIssueService plnIssueService;

    @Test
    void old_module_endpoint_should_delegate_and_keep_response_shape() throws Exception {
        when(plnIssueService.module(any(PlnIssueModuleDTO.class))).thenReturn(Boolean.TRUE);

        PlnIssueModuleDTO dto = new PlnIssueModuleDTO();
        dto.setIds(new Long[]{1L, 2L});
        dto.setWorkshopCode("WS-01");
        dto.setWorkshopName("一车间");

        mockMvc.perform(post("/plnIssue/module")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(plnIssueService).module(any(PlnIssueModuleDTO.class));
    }

    @Test
    void mes_plan_page_should_accept_date_only_issue_time_query_param() throws Exception {
        when(plnIssueService.getMesPlanPage(any(Page.class), any(PlnIssueEntity.class))).thenReturn(new Page<>());

        mockMvc.perform(get("/plnIssue/mesPlan/page")
                        .param("current", "1")
                        .param("size", "10")
                        .param("issueTime", "2026-04-13"))
                .andExpect(status().isOk());
    }
}
