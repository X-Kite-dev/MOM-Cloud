package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnSchedulingRunDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.service.fixture.PlanningEngineFixture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlnSchedulingEngineServiceTest extends PlanningEngineFixture {

    @Test
    void should_run_schedule_in_simulate_mode_without_writing_issue() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "0")));
        assertTrue(plnSchedulingEngineService.run(run("simulate")));
        assertEquals("SIMULATED", tasks.values().iterator().next().getTaskStatus());
        assertEquals(1, proofs.size());
        assertEquals(1, snapshots.size());
        assertEquals("GREEDY_WINDOW", proofs.get(0).getSolverCode());
        assertEquals(500L, snapshots.get(0).getEngineeringReleaseId());
        verify(plnIssueMapper, never()).insert(any(PlnIssueEntity.class));
        verify(plnIssueMapper, never()).updateById(any(PlnIssueEntity.class));
        verifyZeroInteractions(remoteTaskService);
    }

    @Test
    void should_use_selector_rule_code_when_creating_official_task() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "0")));
        var rule = activeRule();
        rule.setRuleCode("RULE-SELECTED");
        rule.setRuleVersion(9);
        when(ruleSetSelector.selectActiveRule()).thenReturn(rule);
        assertTrue(plnSchedulingEngineService.run(run("official")));
        var task = tasks.values().iterator().next();
        assertEquals("RULE-SELECTED", task.getRuleCode());
        assertEquals(9, task.getRuleVersion());
        assertEquals("WAIT_CONFIRM", task.getTaskStatus());
        assertEquals(1, proofs.size());
        verify(plnIssueMapper, never()).updateById(any(PlnIssueEntity.class));
    }

    @Test
    void should_filter_by_target_ids_when_provided() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(101L, "0")));
        var command = run("simulate");
        command.setTargetIds(List.of(101L));
        assertTrue(plnSchedulingEngineService.run(command));
        assertEquals(1, snapshots.size());
        assertEquals(101L, snapshots.get(0).getIssueId());
        verify(plnIssueMapper).selectList(argThat((LambdaQueryWrapper<PlnIssueEntity> wrapper) -> {
            wrapper.getCustomSqlSegment();
            return wrapper.getParamNameValuePairs().values().stream().anyMatch(value -> Long.valueOf(101).equals(value));
        }));
    }

    @Test
    void should_throw_when_mode_is_invalid() {
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(run("invalid")));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void should_throw_when_mode_is_null() {
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(run(null)));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void should_throw_when_runDTO_is_null() {
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(null));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void should_throw_when_official_task_insert_failed() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "0")));
        when(plnSchedulingTaskMapper.insert(any(PlnSchedulingTaskEntity.class))).thenReturn(0);
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(run("official")));
        assertTrue(proofs.isEmpty());
        assertTrue(snapshots.isEmpty());
        verify(plnSchedulingTaskMapper).insert(any(PlnSchedulingTaskEntity.class));
        verify(plnIssueMapper, never()).updateById(any(PlnIssueEntity.class));
    }

    @Test
    void should_throw_when_no_active_rule() {
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue(1L, "0")));
        when(ruleSetSelector.selectActiveRule()).thenReturn(null);
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(run("official")));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void should_set_issue_time_to_scheduled_date_when_confirming_official_lock() {
        var date = LocalDate.now().plusDays(2);
        var task = new PlnSchedulingTaskEntity();
        task.setId(1L);
        task.setTenantId(1L);
        task.setFactoryCode("F1");
        task.setMode("official");
        task.setTaskStatus("WAIT_CONFIRM");
        task.setRuleCode("RULE-ACTIVE");
        tasks.put(1L, task);
        var issue = issue(101L, "0");
        issue.setLockStatus(false);
        issue.setIssueTime((java.time.LocalDateTime) null);
        when(plnIssueMapper.selectBatchIds(List.of(101L))).thenReturn(List.of(issue));
        when(plnIssueMapper.selectById(101L)).thenReturn(issue);
        var detail = new PlnSchedulingTaskDetailEntity();
        detail.setTaskId(1L);
        detail.setIssueId(101L);
        detail.setIssueNo(issue.getIssueNo());
        detail.setProductCode("P1");
        detail.setDemandTime(date.plusDays(1));
        detail.setScheduledDate(date);
        detail.setProcessRouteCode("R1");
        detail.setOperationScheduleStatus("SCHEDULED");
        detail.setOperationHardBlocked(false);
        when(plnSchedulingTaskDetailMapper.selectList(any())).thenReturn(List.of(detail));
        assertTrue(plnSchedulingEngineService.confirmOfficialTaskLock(1L));
        var writtenIssue = ArgumentCaptor.forClass(PlnIssueEntity.class);
        verify(plnIssueMapper).updateById(writtenIssue.capture());
        assertEquals(101L, writtenIssue.getValue().getId());
        assertEquals(date.atStartOfDay(), writtenIssue.getValue().getIssueTime());
        assertEquals(date, writtenIssue.getValue().getScheduledDate());
        assertTrue(writtenIssue.getValue().getLockStatus());
        assertEquals(1L, writtenIssue.getValue().getLockTaskId());
        assertNotNull(writtenIssue.getValue().getLockTime());
        var writtenTask = ArgumentCaptor.forClass(PlnSchedulingTaskEntity.class);
        verify(plnSchedulingTaskMapper).updateById(writtenTask.capture());
        assertEquals(1L, writtenTask.getValue().getId());
        assertEquals("SUCCESS", writtenTask.getValue().getTaskStatus());
    }

    @Test
    void missing_sequence_proof_cannot_be_saved_as_completed_result() {
        var issue = issue(1L, "0");
        when(plnIssueMapper.selectList(any())).thenReturn(List.of(issue));
        doReturn(List.of(issue)).when(operationSchedulingService).optimizeGlobalSequence(anyList(), anyString(), anyInt(), any(), nullable(String.class));
        assertThrows(CheckedException.class, () -> plnSchedulingEngineService.run(run("official")));
        assertTrue(proofs.isEmpty());
        assertTrue(snapshots.isEmpty());
        verifyZeroInteractions(remoteTaskService);
    }

    private PlnSchedulingRunDTO run(String mode) {
        var command = new PlnSchedulingRunDTO();
        command.setMode(mode);
        command.setTriggerType("MANUAL");
        command.setCycleType("DAY");
        command.setScheduleDate(LocalDate.now());
        return command;
    }
}
