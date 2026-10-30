package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.sentinel.handle.GlobalBizExceptionHandler;
import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.service.PlnIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实MVC路由、绑定及公共异常响应；服务替身，不包含权限过滤或远程服务。
 */
@ExtendWith(MockitoExtension.class)
class PlnIssueCompatibilityTest {

    @Mock
    private PlnIssueService service;

    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PlnIssueController(service))
                .setControllerAdvice(new GlobalBizExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    void old_module_endpoint_should_delegate_and_keep_response_shape() throws Exception {
        when(service.module(any(PlnIssueModuleDTO.class))).thenReturn(true);
        var command = new PlnIssueModuleDTO();
        command.setIds(new Long[] { 1L, 2L });
        command.setWorkshopCode("WS-01");
        command.setWorkshopName("一车间");
        mvc.perform(post("/plnIssue/module")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        var sent = ArgumentCaptor.forClass(PlnIssueModuleDTO.class);
        verify(service).module(sent.capture());
        assertArrayEquals(new Long[] { 1L, 2L }, sent.getValue().getIds());
        assertEquals("WS-01", sent.getValue().getWorkshopCode());
    }

    @ParameterizedTest
    @ValueSource(strings = { "2026-04-13", "2026-04-13T09:30:00", "2026-04-13 09:30:00" })
    void mes_plan_page_accepts_supported_dates_and_preserves_product_filters(String date) throws Exception {
        when(service.getMesPlanPage(any(Page.class), any(PlnIssueEntity.class))).thenReturn(new Page<>());
        mvc.perform(get("/plnIssue/mesPlan/page")
                        .param("current", "1")
                        .param("size", "10")
                        .param("issueTime", date)
                        .param("productCode", "P1")
                        .param("productName", "测试产品")
                        .param("specificationModel", "S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        var query = ArgumentCaptor.forClass(PlnIssueEntity.class);
        verify(service).getMesPlanPage(any(Page.class), query.capture());
        assertEquals(LocalDateTime.of(2026, 4, 13, date.length() == 10 ? 0 : 9, date.length() == 10 ? 0 : 30), query.getValue().getIssueTime());
        assertEquals("P1", query.getValue().getProductCode());
        assertEquals("测试产品", query.getValue().getProductName());
        assertEquals("S1", query.getValue().getSpecificationModel());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "garbage", "2026-02-30", "2026-04-13T25:00:00" })
    void invalid_month_query_does_not_reach_the_service(String date) throws Exception {
        mvc.perform(get("/plnIssue/mesPlan/page").param("issueTime", date))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("issueTime"));
        verifyZeroInteractions(service);
    }

    @Test
    void missing_month_query_does_not_reach_the_service() throws Exception {
        mvc.perform(get("/plnIssue/mesPlan/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("issueTime"));
        verifyZeroInteractions(service);
    }
}
