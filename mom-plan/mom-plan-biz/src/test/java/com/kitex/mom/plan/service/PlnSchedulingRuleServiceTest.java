package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSchedulingGanttDetailQueryDTO;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingConflictMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.impl.PlnSchedulingRuleServiceImpl;
import com.kitex.mom.plan.vo.PlnSchedulingGanttDetailVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingRuleServiceTest {

    @Mock
    private PlnSchedulingTaskMapper plnSchedulingTaskMapper;

    @Mock
    private PlnSchedulingConflictMapper plnSchedulingConflictMapper;

    @Mock
    private PlnIssueMapper plnIssueMapper;

    @Mock
    private PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;

    @InjectMocks
    private PlnSchedulingRuleServiceImpl plnSchedulingRuleService;

    @Test
    void should_throw_checked_exception_when_rule_content_is_invalid_json() {
        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        ruleSet.setRuleCode("RULE_001");
        ruleSet.setRuleName("测试规则");
        ruleSet.setRuleContent("{invalid json}");

        assertThrows(CheckedException.class, () -> plnSchedulingRuleService.createRule(ruleSet));
    }

    @Test
    void should_query_gantt_details_by_task_id() {
        SearchPage page = new SearchPage();
        page.setCurrent(1L);
        page.setSize(10L);

        PlnSchedulingGanttDetailQueryDTO query = new PlnSchedulingGanttDetailQueryDTO();
        query.setTaskId(1001L);

        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        detail.setIssueId(2001L);
        detail.setIssueNo("ISSUE-2001");
        detail.setDemandTime(LocalDate.of(2026, 4, 11));
        detail.setTaskId(1001L);

        Page<PlnSchedulingTaskDetailEntity> detailPage = new Page<>(1, 10, 1);
        detailPage.setRecords(List.of(detail));
        when(plnSchedulingTaskDetailMapper.selectPage(any(), any())).thenReturn(detailPage);

        Page<PlnSchedulingGanttDetailVO> result = plnSchedulingRuleService.queryGanttDetails(page, query);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(2001L, result.getRecords().get(0).getIssueId());
        assertEquals("ISSUE-2001", result.getRecords().get(0).getIssueNo());
    }

    @Test
    void should_return_empty_page_when_query_param_missing() {
        SearchPage page = new SearchPage();
        page.setCurrent(1L);
        page.setSize(10L);

        PlnSchedulingGanttDetailQueryDTO query = new PlnSchedulingGanttDetailQueryDTO();

        Page<PlnSchedulingGanttDetailVO> result = plnSchedulingRuleService.queryGanttDetails(page, query);

        assertEquals(0L, result.getTotal());
        assertEquals(0, result.getRecords().size());
    }

    @Test
    void should_return_default_empty_page_when_page_is_null() {
        PlnSchedulingGanttDetailQueryDTO query = new PlnSchedulingGanttDetailQueryDTO();

        Page<PlnSchedulingGanttDetailVO> result = assertDoesNotThrow(() -> plnSchedulingRuleService.queryGanttDetails(null, query));

        assertEquals(1L, result.getCurrent());
        assertEquals(10L, result.getSize());
        assertEquals(0L, result.getTotal());
        assertEquals(0, result.getRecords().size());
    }

    @Test
    void should_return_empty_page_when_only_task_code_is_provided() {
        SearchPage page = new SearchPage();
        page.setCurrent(2L);
        page.setSize(5L);

        PlnSchedulingGanttDetailQueryDTO query = new PlnSchedulingGanttDetailQueryDTO();
        query.setTaskCode("TASK-ONLY");

        Page<PlnSchedulingGanttDetailVO> result = plnSchedulingRuleService.queryGanttDetails(page, query);

        assertEquals(2L, result.getCurrent());
        assertEquals(5L, result.getSize());
        assertEquals(0L, result.getTotal());
        assertEquals(0, result.getRecords().size());
    }
}
