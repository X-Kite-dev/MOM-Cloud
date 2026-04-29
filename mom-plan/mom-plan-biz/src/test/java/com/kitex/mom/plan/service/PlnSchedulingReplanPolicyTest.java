package com.kitex.mom.plan.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSchedulingReplanDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.impl.PlnSchedulingEngineServiceImpl;
import com.kitex.mom.plan.service.selector.RuleSetSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingReplanPolicyTest {

    @Mock
    private PlnSchedulingTaskMapper plnSchedulingTaskMapper;

    @Mock
    private PlnIssueMapper plnIssueMapper;

    @Mock
    private PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;

    @Mock
    private RuleSetSelector ruleSetSelector;

    @InjectMocks
    private PlnSchedulingEngineServiceImpl plnSchedulingEngineService;

    @Test
    void should_reject_replan_for_completed_status() {
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setId(1L);
        issue.setStatus("3");
        when(plnIssueMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(issue));

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setIssueId(1L);

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_allow_replan_for_released_status() {
        PlnIssueEntity issue = issue(2L, "1");
        when(plnIssueMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(issue));
        when(ruleSetSelector.selectActiveRule()).thenReturn(activeRule());
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setIssueId(2L);

        assertTrue(plnSchedulingEngineService.replan(replanDTO));
        verify(plnSchedulingTaskMapper).insert(any(PlnSchedulingTaskEntity.class));
    }

    @Test
    void should_allow_replan_when_target_ids_are_all_active() {
        PlnIssueEntity issue1 = issue(11L, "1");
        PlnIssueEntity issue2 = issue(12L, "0");
        when(plnIssueMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(issue1, issue2));
        when(ruleSetSelector.selectActiveRule()).thenReturn(activeRule());
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setTargetIds(List.of(11L, 12L));

        assertTrue(plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_reject_replan_when_target_ids_all_null() {
        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setTargetIds(Arrays.asList((Long) null));

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_reject_replan_when_some_targets_not_found() {
        PlnIssueEntity issue = issue(11L, "1");
        when(plnIssueMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(issue));

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setTargetIds(List.of(11L, 12L));

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_reject_replan_when_contains_closed_target() {
        PlnIssueEntity issue1 = issue(11L, "1");
        PlnIssueEntity issue2 = issue(12L, "4");
        when(plnIssueMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(issue1, issue2));

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setTargetIds(List.of(11L, 12L));

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_prefer_target_ids_when_both_issue_id_and_targets_provided() {
        PlnIssueEntity issue11 = issue(11L, "1");
        PlnIssueEntity issue12 = issue(12L, "1");
        when(plnIssueMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(issue11, issue12));
        when(ruleSetSelector.selectActiveRule()).thenReturn(activeRule());
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setIssueId(2L);
        replanDTO.setTargetIds(List.of(11L, 12L));

        assertTrue(plnSchedulingEngineService.replan(replanDTO));
    }

    @Test
    void should_deduplicate_target_ids_before_replan() {
        PlnIssueEntity issue11 = issue(11L, "1");
        when(plnIssueMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(issue11));
        when(ruleSetSelector.selectActiveRule()).thenReturn(activeRule());
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);

        PlnSchedulingReplanDTO replanDTO = new PlnSchedulingReplanDTO();
        replanDTO.setTargetIds(List.of(11L, 11L));

        assertTrue(plnSchedulingEngineService.replan(replanDTO));
    }

    private PlnIssueEntity issue(Long id, String status) {
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setId(id);
        issue.setStatus(status);
        issue.setDemandTime(java.time.LocalDate.now());
        issue.setIssueTime(java.time.LocalDateTime.now());
        return issue;
    }

    private PlnSchedulingRuleSetEntity activeRule() {
        PlnSchedulingRuleSetEntity rule = new PlnSchedulingRuleSetEntity();
        rule.setRuleCode("RULE-ACTIVE");
        rule.setRuleVersion(1);
        return rule;
    }
}
