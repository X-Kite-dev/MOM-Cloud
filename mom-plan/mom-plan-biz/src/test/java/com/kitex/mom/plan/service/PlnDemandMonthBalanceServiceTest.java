package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.MonthPlanQueryDTO;
import com.kitex.mom.plan.dto.PlnDemandMonthBalanceDTO;
import com.kitex.mom.plan.entity.PlnDemandMonthEntity;
import com.kitex.mom.plan.mapper.PlnDemandMonthMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.service.impl.PlnDemandMonthServiceImpl;
import com.kitex.mom.plan.vo.PlnDemandMonthReadinessVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlnDemandMonthBalanceServiceTest {

    @Mock
    private PlnDemandMonthMapper plnDemandMonthMapper;

    @Mock
    private com.kitex.mom.mdm.feign.RemoteMaterialService remoteMaterialService;

    @Mock
    private com.kitex.mom.mdm.feign.RemoteCustomerService remoteCustomerService;

    @Mock
    private PlnIssueMapper plnIssueMapper;

    @InjectMocks
    private PlnDemandMonthServiceImpl plnDemandMonthService;

    @Test
    void should_set_executable_num_as_max_of_forecast_and_confirmed() {
        ReflectionTestUtils.setField(plnDemandMonthService, "baseMapper", plnDemandMonthMapper);

        PlnDemandMonthEntity forecast = new PlnDemandMonthEntity();
        forecast.setId(1L);
        forecast.setFactoryCode("F1");
        forecast.setCustomerCode("C1");
        forecast.setProductCode("P1");
        forecast.setDemandTime(LocalDate.of(2026, 4, 1));
        forecast.setVersionNo("V1");
        forecast.setSourceType("FORECAST");
        forecast.setTotalDemandNum(new BigDecimal("100"));

        PlnDemandMonthEntity confirmed = new PlnDemandMonthEntity();
        confirmed.setId(2L);
        confirmed.setFactoryCode("F1");
        confirmed.setCustomerCode("C1");
        confirmed.setProductCode("P1");
        confirmed.setDemandTime(LocalDate.of(2026, 4, 1));
        confirmed.setVersionNo("V1");
        confirmed.setSourceType("CONFIRMED");
        confirmed.setTotalDemandNum(new BigDecimal("120"));

        when(plnDemandMonthMapper.selectList(any())).thenReturn(List.of(forecast, confirmed));
        when(plnDemandMonthMapper.updateById(any(PlnDemandMonthEntity.class))).thenReturn(1);

        PlnDemandMonthBalanceDTO dto = new PlnDemandMonthBalanceDTO();
        dto.setTime(LocalDate.of(2026, 4, 1));
        dto.setVersionNo("V1");
        dto.setEffectiveFlag(true);

        Boolean ok = plnDemandMonthService.balance(dto);
        assertTrue(Boolean.TRUE.equals(ok));

        ArgumentCaptor<PlnDemandMonthEntity> captor = ArgumentCaptor.forClass(PlnDemandMonthEntity.class);
        verify(plnDemandMonthMapper, atLeast(2)).updateById(captor.capture());
        List<PlnDemandMonthEntity> updates = captor.getAllValues();
        assertEquals(new BigDecimal("120"), updates.get(0).getExecutableNum());
        assertEquals(Boolean.TRUE, updates.get(0).getEffectiveFlag());
    }

    @Test
    void should_return_not_dispatched_when_no_month_dispatch_issue() {
        ReflectionTestUtils.setField(plnDemandMonthService, "baseMapper", plnDemandMonthMapper);

        PlnDemandMonthEntity forecast = new PlnDemandMonthEntity();
        forecast.setId(1L);
        forecast.setFactoryCode("F1");
        forecast.setCustomerCode("C1");
        forecast.setProductCode("P1");
        forecast.setDemandTime(LocalDate.of(2026, 4, 1));
        forecast.setVersionNo("V1");
        forecast.setSourceType("FORECAST");
        forecast.setExecutableNum(new BigDecimal("100"));

        when(plnDemandMonthMapper.selectList(any())).thenReturn(List.of(forecast));
        when(plnIssueMapper.selectCount(any())).thenReturn(0L);

        MonthPlanQueryDTO queryDTO = new MonthPlanQueryDTO();
        queryDTO.setTime(LocalDate.of(2026, 4, 1));
        queryDTO.setVersionNo("V1");

        PlnDemandMonthReadinessVO vo = plnDemandMonthService.readiness(queryDTO);

        assertTrue(Boolean.TRUE.equals(vo.getBalanced()));
        assertFalse(Boolean.TRUE.equals(vo.getDispatched()));
        verify(plnIssueMapper).selectCount(any());
    }
}
