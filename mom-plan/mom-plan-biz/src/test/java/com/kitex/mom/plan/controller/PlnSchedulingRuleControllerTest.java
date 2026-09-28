package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.plan.dto.PlnSchedulingRuleSetCreateDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnResourceLoadService;
import com.kitex.mom.plan.service.PlnSchedulingBatchService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import com.kitex.mom.plan.service.impl.PlnSchedulingExecutionService;
import com.kitex.mom.plan.vo.PlnDemandMonthReadinessVO;
import com.kitex.mom.plan.vo.PlnSchedulingGanttDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingRuleControllerTest {

    @Mock
    private PlnSchedulingRuleService plnSchedulingRuleService;

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

    @Mock
    private PlnDemandMonthService plnDemandMonthService;

    @Mock
    private PlnSchedulingExecutionService plnSchedulingExecutionService;

    @Mock
    private PlnOperationSchedulingService plnOperationSchedulingService;

    @Mock
    private PlnResourceLoadService plnResourceLoadService;

    @Mock
    private PlnSchedulingBatchService plnSchedulingBatchService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        PlnSchedulingRuleController controller = new PlnSchedulingRuleController(
                plnSchedulingRuleService, plnSchedulingEngineService, plnSchedulingExecutionService,
                plnDemandMonthService, plnOperationSchedulingService, plnResourceLoadService,
                plnSchedulingBatchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void should_create_rule_set_and_return_success() throws Exception {
        when(plnSchedulingRuleService.createRule(any(PlnSchedulingRuleSetEntity.class))).thenReturn(Boolean.TRUE);

        PlnSchedulingRuleSetCreateDTO body = new PlnSchedulingRuleSetCreateDTO();
        body.setRuleCode("RULE_DEFAULT");
        body.setRuleName("默认规则");

        mockMvc.perform(post("/plnScheduling/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void should_return_rule_explanation_for_task_detail() throws Exception {
        PlnIssueEntity detail = new PlnIssueEntity();
        detail.setId(1L);
        detail.setIssueNo("ISSUE-1");
        detail.setMatchedRules("[\"DEMAND_DATE_REQUIRED\",\"EARLIER_DEMAND_FIRST\"]");
        detail.setScoreBreakdown("{\"EARLIER_DEMAND_FIRST\":123.0}");
        when(plnSchedulingRuleService.listTaskDetails(100L)).thenReturn(List.of(detail));

        mockMvc.perform(get("/plnScheduling/tasks/100/details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].matchedRules").value("[\"DEMAND_DATE_REQUIRED\",\"EARLIER_DEMAND_FIRST\"]"))
                .andExpect(jsonPath("$.data[0].scoreBreakdown").value("{\"EARLIER_DEMAND_FIRST\":123.0}"));
    }

    @Test
    void should_return_plan_gantt_detail_page() throws Exception {
        PlnSchedulingGanttDetailVO detail = new PlnSchedulingGanttDetailVO();
        detail.setIssueNo("ISSUE-001");
        Page<PlnSchedulingGanttDetailVO> page = new Page<>(1, 10);
        page.setRecords(List.of(detail));
        when(plnSchedulingRuleService.queryGanttDetails(any(), any())).thenReturn(page);

        mockMvc.perform(get("/plnScheduling/gantt/details")
                        .param("taskCode", "TASK-001")
                        .param("routeCode", "ROUTE-A")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].issueNo").value("ISSUE-001"));
    }

    @Test
    void should_return_empty_page_when_task_id_and_task_code_missing() throws Exception {
        Page<PlnSchedulingGanttDetailVO> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(plnSchedulingRuleService.queryGanttDetails(any(), any())).thenReturn(page);

        mockMvc.perform(get("/plnScheduling/gantt/details")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    void should_return_month_demand_readiness() throws Exception {
        PlnDemandMonthReadinessVO vo = new PlnDemandMonthReadinessVO();
        vo.setBalanced(true);
        vo.setDispatched(false);
        when(plnDemandMonthService.readiness(any())).thenReturn(vo);

        mockMvc.perform(get("/plnScheduling/monthDemand/readiness")
                        .param("time", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.balanced").value(true))
                .andExpect(jsonPath("$.data.dispatched").value(false));
    }
}
