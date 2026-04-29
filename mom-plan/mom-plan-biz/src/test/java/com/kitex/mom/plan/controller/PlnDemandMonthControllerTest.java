package com.kitex.mom.plan.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthBalanceDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthDispatchDTO;
import com.kitex.mom.plan.service.PlnDemandMonthService;
import com.kitex.mom.plan.vo.PlnDemandMonthDeviationVO;
import com.kitex.mom.plan.vo.PlnDemandMonthVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlnDemandMonthControllerTest {

    @Mock
    private PlnDemandMonthService plnDemandMonthService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        PlnDemandMonthController controller = new PlnDemandMonthController(plnDemandMonthService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void should_filter_month_demand_by_source_type_and_version() throws Exception {
        PlnDemandMonthVO vo = new PlnDemandMonthVO();
        vo.setProductCode("MAT-001");
        when(plnDemandMonthService.getDemand(any())).thenReturn(List.of(vo));

        mockMvc.perform(get("/plnDemandMonth/demand")
                        .param("time", "2026-04-01")
                        .param("sourceType", "FORECAST")
                        .param("versionNo", "V1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].productCode").value("MAT-001"));

        ArgumentCaptor<MonthPlanQueryDTO> captor = ArgumentCaptor.forClass(MonthPlanQueryDTO.class);
        verify(plnDemandMonthService).getDemand(captor.capture());
        MonthPlanQueryDTO dto = captor.getValue();
        assertEquals("FORECAST", dto.getSourceType());
        assertEquals("V1", dto.getVersionNo());
        assertEquals("2026-04-01", String.valueOf(dto.getTime()));
    }

    @Test
    void should_return_only_effective_confirmed_records() throws Exception {
        PlnDemandMonthVO vo = new PlnDemandMonthVO();
        vo.setProductCode("MAT-002");
        when(plnDemandMonthService.getDemand(any())).thenReturn(List.of(vo));

        mockMvc.perform(get("/plnDemandMonth/demand")
                        .param("time", "2026-04-01")
                        .param("sourceType", "CONFIRMED")
                        .param("effectiveFlag", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].productCode").value("MAT-002"));

        ArgumentCaptor<MonthPlanQueryDTO> captor = ArgumentCaptor.forClass(MonthPlanQueryDTO.class);
        verify(plnDemandMonthService).getDemand(captor.capture());
        MonthPlanQueryDTO dto = captor.getValue();
        assertEquals("CONFIRMED", dto.getSourceType());
        assertTrue(Boolean.TRUE.equals(dto.getEffectiveFlag()));
    }

    @Test
    void should_call_balance_service() throws Exception {
        when(plnDemandMonthService.balance(any(PlnDemandMonthBalanceDTO.class))).thenReturn(Boolean.TRUE);

        mockMvc.perform(post("/plnDemandMonth/balance")
                        .contentType("application/json")
                        .content("{\"time\":\"2026-04-01\",\"versionNo\":\"V1\",\"effectiveFlag\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(plnDemandMonthService).balance(any(PlnDemandMonthBalanceDTO.class));
    }

    @Test
    void should_call_dispatch_service() throws Exception {
        when(plnDemandMonthService.dispatchToIssue(any(PlnDemandMonthDispatchDTO.class))).thenReturn(Boolean.TRUE);

        mockMvc.perform(post("/plnDemandMonth/dispatchToIssue")
                        .contentType("application/json")
                        .content("{\"time\":\"2026-04-01\",\"versionNo\":\"V1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(plnDemandMonthService).dispatchToIssue(any(PlnDemandMonthDispatchDTO.class));
    }

    @Test
    void should_return_deviation_with_forecast_confirmed_executed() throws Exception {
        PlnDemandMonthDeviationVO vo = new PlnDemandMonthDeviationVO();
        vo.setProductCode("MAT-003");
        vo.setForecastNum(new BigDecimal("100"));
        vo.setConfirmedNum(new BigDecimal("120"));
        vo.setExecutedNum(new BigDecimal("110"));
        when(plnDemandMonthService.deviation(any())).thenReturn(List.of(vo));

        mockMvc.perform(get("/plnDemandMonth/deviation")
                        .param("time", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].productCode").value("MAT-003"));
    }
}
