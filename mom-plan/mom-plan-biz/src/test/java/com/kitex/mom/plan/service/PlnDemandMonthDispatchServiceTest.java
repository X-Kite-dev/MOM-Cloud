package com.kitex.mom.plan.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnDemandMonthDispatchDTO;
import com.kitex.mom.plan.entity.PlnDemandMonthEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.mapper.PlnDemandMonthMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.service.impl.PlnDemandMonthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlnDemandMonthDispatchServiceTest {

    @Mock
    private PlnDemandMonthMapper plnDemandMonthMapper;

    @Mock
    private PlnIssueMapper plnIssueMapper;

    @Mock
    private com.kitex.mom.mdm.feign.RemoteMaterialService remoteMaterialService;

    @Mock
    private com.kitex.mom.mdm.feign.RemoteCustomerService remoteCustomerService;

    @InjectMocks
    private PlnDemandMonthServiceImpl plnDemandMonthService;

    @Test
    void should_throw_when_dispatch_version_is_empty() {
        ReflectionTestUtils.setField(plnDemandMonthService, "baseMapper", plnDemandMonthMapper);
        PlnDemandMonthDispatchDTO dto = new PlnDemandMonthDispatchDTO();
        dto.setTime(LocalDate.of(2026, 4, 1));

        assertThrows(CheckedException.class, () -> plnDemandMonthService.dispatchToIssue(dto));
    }

    @Test
    void should_dispatch_effective_month_demand_to_unissued_pln_issue_idempotently() {
        ReflectionTestUtils.setField(plnDemandMonthService, "baseMapper", plnDemandMonthMapper);

        PlnDemandMonthEntity month = new PlnDemandMonthEntity();
        month.setFactoryCode("F1");
        month.setCustomerCode("C1");
        month.setCustomerName("客户1");
        month.setProductCode("P1");
        month.setProductName("产品1");
        month.setSpecificationModel("S1");
        month.setMaterialAttribute("A1");
        month.setDemandTime(LocalDate.of(2026, 4, 1));
        month.setVersionNo("V1");
        month.setEffectiveFlag(true);
        month.setExecutableNum(new BigDecimal("300"));

        when(plnDemandMonthMapper.selectList(any())).thenReturn(List.of(month));

        AtomicInteger selectOneCounter = new AtomicInteger(0);
        when(plnIssueMapper.selectOne(any())).thenAnswer(invocation -> {
            int current = selectOneCounter.incrementAndGet();
            if (current <= 30) {
                return null;
            }
            PlnIssueEntity existing = new PlnIssueEntity();
            existing.setId((long) (current - 30));
            return existing;
        });
        when(plnIssueMapper.insert(any(PlnIssueEntity.class))).thenReturn(1);
        when(plnIssueMapper.updateById(any(PlnIssueEntity.class))).thenReturn(1);

        PlnDemandMonthDispatchDTO dto = new PlnDemandMonthDispatchDTO();
        dto.setTime(LocalDate.of(2026, 4, 1));
        dto.setVersionNo("V1");

        Boolean first = plnDemandMonthService.dispatchToIssue(dto);
        Boolean second = plnDemandMonthService.dispatchToIssue(dto);

        assertTrue(Boolean.TRUE.equals(first));
        assertTrue(Boolean.TRUE.equals(second));
        verify(plnIssueMapper, times(30)).insert(any(PlnIssueEntity.class));
        verify(plnIssueMapper, times(30)).updateById(any(PlnIssueEntity.class));
    }
}
