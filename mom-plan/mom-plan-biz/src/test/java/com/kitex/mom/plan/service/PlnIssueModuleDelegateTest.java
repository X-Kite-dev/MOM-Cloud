package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnIssueModuleDTO;
import com.kitex.mom.plan.mapper.PlnIssueFeedbackMapper;
import com.kitex.mom.plan.service.impl.PlnIssueServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlnIssueModuleDelegateTest {

    @Mock
    private com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService remoteSeqConfigService;

    @Mock
    private PlnUpdateLogService plnUpdateLogService;

    @Mock
    private PlnIssueFeedbackMapper plnIssueFeedbackMapper;

    @Mock
    private PlanMatMrpService planMatMrpService;

    @Mock
    private PlnSchedulingEngineService plnSchedulingEngineService;

    @InjectMocks
    private PlnIssueServiceImpl plnIssueService;

    @Test
    void module_should_delegate_to_scheduling_engine() {
        when(plnSchedulingEngineService.issueToModule(any(PlnIssueModuleDTO.class))).thenReturn(Boolean.TRUE);

        PlnIssueModuleDTO dto = new PlnIssueModuleDTO();
        dto.setIds(new Long[]{1L});
        dto.setWorkshopCode("WS-01");
        dto.setWorkshopName("一车间");

        Boolean result = plnIssueService.module(dto);

        assertTrue(result);
        verify(plnSchedulingEngineService).issueToModule(any(PlnIssueModuleDTO.class));
    }
}
