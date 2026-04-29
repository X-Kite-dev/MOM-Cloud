package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingConflictMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.conflict.ConflictEngine;
import com.kitex.mom.plan.service.impl.PlnSchedulingEngineServiceImpl;
import com.kitex.mom.plan.service.selector.RuleSetSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlnSchedulingEngineServiceTest {

    @Mock
    private PlnSchedulingTaskMapper plnSchedulingTaskMapper;

    @Mock
    private PlnIssueMapper plnIssueMapper;

    @Mock
    private PlnSchedulingConflictMapper plnSchedulingConflictMapper;

    @Mock
    private PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;

    @Mock
    private PlnUpdateLogService plnUpdateLogService;

    @Mock
    private RemoteMesTaskService remoteTaskService;

    @Mock
    private PlanMatMrpService planMatMrpService;

    @Mock
    private RuleSetSelector ruleSetSelector;

    @Mock
    private ConflictEngine conflictEngine;

    @InjectMocks
    private PlnSchedulingEngineServiceImpl plnSchedulingEngineService;

    @Test
    void should_run_schedule_in_simulate_mode_without_writing_issue() {
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "ISSUE-1")));

        PlnSchedulingRunDTO runDTO = buildRunDTO("simulate");
        Boolean result = plnSchedulingEngineService.run(runDTO);

        assertTrue(result);
        verify(plnSchedulingTaskMapper).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
    }

    @Test
    void should_use_selector_rule_code_when_creating_official_task() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "ISSUE-1")));
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);
        when(plnSchedulingTaskDetailMapper.insert(any(PlnSchedulingTaskDetailEntity.class))).thenReturn(1);

        PlnSchedulingRuleSetEntity selectedRule = new PlnSchedulingRuleSetEntity();
        selectedRule.setRuleCode("RULE-SELECTED");
        selectedRule.setRuleVersion(9);
        when(ruleSetSelector.selectActiveRule()).thenReturn(selectedRule);

        PlnSchedulingRunDTO runDTO = buildRunDTO("official");
        Boolean result = plnSchedulingEngineService.run(runDTO);

        assertTrue(result);
        ArgumentCaptor<PlnSchedulingTaskEntity> taskCaptor = ArgumentCaptor.forClass(PlnSchedulingTaskEntity.class);
        verify(plnSchedulingTaskMapper).insert(taskCaptor.capture());
        assertEquals("RULE-SELECTED", taskCaptor.getValue().getRuleCode());
    }

    @Test
    void should_filter_by_target_ids_when_provided() {
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(1);
        when(plnSchedulingTaskDetailMapper.insert(any(PlnSchedulingTaskDetailEntity.class))).thenReturn(1);
        when(plnIssueMapper.selectList(argThat((LambdaQueryWrapper<PlnIssueEntity> wrapper) -> wrapper != null && wrapper.getSqlSegment().contains("id"))))
                .thenReturn(List.of(issue(101L, "ISSUE-101")));

        PlnSchedulingRunDTO runDTO = buildRunDTO("simulate");
        runDTO.setTargetIds(List.of(101L));

        Boolean result = plnSchedulingEngineService.run(runDTO);

        assertTrue(result);
        ArgumentCaptor<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity> detailCaptor =
                ArgumentCaptor.forClass(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity.class);
        verify(plnSchedulingTaskDetailMapper).insert(detailCaptor.capture());
        assertEquals(101L, detailCaptor.getValue().getIssueId());
    }

    @Test
    void should_throw_when_mode_is_invalid() {
        PlnSchedulingRunDTO runDTO = buildRunDTO("invalid");

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(runDTO));
        verify(plnSchedulingTaskMapper, never()).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
    }

    @Test
    void should_throw_when_mode_is_null() {
        PlnSchedulingRunDTO runDTO = buildRunDTO(null);

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(runDTO));
        verify(plnSchedulingTaskMapper, never()).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
    }

    @Test
    void should_throw_when_runDTO_is_null() {
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(null));
        verify(plnSchedulingTaskMapper, never()).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
    }

    @Test
    void should_throw_when_official_task_insert_failed() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "ISSUE-1")));
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(0);

        PlnSchedulingRunDTO runDTO = buildRunDTO("official");
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(runDTO));

        verify(plnSchedulingTaskMapper).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
    }

    @Test
    void should_throw_when_no_active_rule() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "ISSUE-1")));
        when(ruleSetSelector.selectActiveRule()).thenReturn(null);

        PlnSchedulingRunDTO runDTO = buildRunDTO("official");

        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(runDTO));
    }

    @Test
    void should_set_issue_time_to_scheduled_date_when_confirming_official_lock() {
        LocalDate scheduledDate = LocalDate.now().plusDays(2);

        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setId(1L);
        task.setMode("official");
        task.setTaskStatus("WAIT_CONFIRM");
        task.setRuleCode("RULE-1");
        when(plnSchedulingTaskMapper.selectById(1L)).thenReturn(task);

        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        detail.setTaskId(1L);
        detail.setIssueId(101L);
        detail.setIssueNo("ISSUE-101");
        detail.setProductCode("P-101");
        detail.setDemandTime(scheduledDate.plusDays(1));
        detail.setScheduledDate(scheduledDate);
        when(plnSchedulingTaskDetailMapper.selectList(any())).thenReturn(List.of(detail));

        when(conflictEngine.detect(1L, List.of(detail))).thenReturn(Collections.emptyList());

        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setId(101L);
        issue.setIssueNo("ISSUE-101");
        issue.setProductCode("P-101");
        issue.setStatus("0");
        issue.setIssueTime((LocalDateTime) null);
        issue.setLockStatus(Boolean.FALSE);
        when(plnIssueMapper.selectById(101L)).thenReturn(issue);
        when(plnIssueMapper.selectCount(any())).thenReturn(0L);

        Boolean result = plnSchedulingEngineService.confirmOfficialTaskLock(1L);

        assertTrue(result);
        ArgumentCaptor<PlnIssueEntity> issueCaptor = ArgumentCaptor.forClass(PlnIssueEntity.class);
        verify(plnIssueMapper).updateById(issueCaptor.capture());
        assertEquals(scheduledDate.atStartOfDay(), issueCaptor.getValue().getIssueTime());
        assertEquals(scheduledDate, issueCaptor.getValue().getScheduledDate());
        assertTrue(issueCaptor.getValue().getLockStatus());
        assertFalse("WAIT_CONFIRM".equals(task.getTaskStatus()));
    }

    private PlnSchedulingRunDTO buildRunDTO(String mode) {
        PlnSchedulingRunDTO runDTO = new PlnSchedulingRunDTO();
        runDTO.setMode(mode);
        runDTO.setTriggerType("MANUAL");
        runDTO.setCycleType("DAY");
        runDTO.setScheduleDate(LocalDate.now());
        return runDTO;
    }

    private PlnIssueEntity issue(Long id, String issueNo) {
        PlnIssueEntity entity = new PlnIssueEntity();
        entity.setId(id);
        entity.setIssueNo(issueNo);
        entity.setStatus("0");
        entity.setDemandTime(LocalDate.now());
        entity.setIssueTime(LocalDate.now().atStartOfDay());
        return entity;
    }
}
