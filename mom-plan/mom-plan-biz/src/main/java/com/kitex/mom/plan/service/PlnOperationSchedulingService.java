package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnOperationScheduleEvaluationDTO;
import com.kitex.mom.plan.dto.PlnSchedulingBatchLedgerDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface PlnOperationSchedulingService {
    List<PlnIssueEntity> optimizeGlobalSequence(List<PlnIssueEntity> issues, String strategy, Integer window, LocalDate start, String solverCode);

    Map<Long, PlnOperationScheduleEvaluationDTO> scheduleTask(Long taskId, List<PlnIssueEntity> issues);

    List<PlnIssueEntity> optimizeGlobalSequence(List<PlnIssueEntity> issues, String sequenceStrategy,
                                                Integer crossPriorityWindowDays, LocalDate rangeStart);

    Map<Long, PlnOperationScheduleEvaluationDTO> scheduleTaskBatches(Long taskId,
                                                                     PlnSchedulingBatchLedgerDTO ledger);

    PlnOperationScheduleEvaluationDTO rescheduleIssue(Long taskId, PlnIssueEntity issue, LocalDate startDate);

    PlnOperationScheduleEvaluationDTO previewLocalReplan(Long proposalTaskId, Long originalTaskId,
                                                         PlnIssueEntity issue, LocalDateTime startTime);

    /**
     * 按候选目标预演；目标只改变可行资源间的选择顺序，不放松任何硬约束。
     */
    PlnOperationScheduleEvaluationDTO previewLocalReplan(Long proposalTaskId, Long originalTaskId,
                                                         PlnIssueEntity issue, LocalDateTime startTime,
                                                         String candidateStrategy);

    /**
     * 紧急插单与受影响正式计划按调用顺序共同预演。excludedFormalTasks记录各既有计划的正式任务，
     * 求解时仅释放该范围的旧占用，其他正式计划仍作为不可突破占用。
     */
    Map<Long, PlnOperationScheduleEvaluationDTO> previewInsertionReplan(
            Long proposalTaskId, List<PlnIssueEntity> orderedIssues,
            Map<Long, Long> excludedFormalTasks, Map<Long, LocalDateTime> requestedStarts);

    /**
     * 同一插单影响范围按不同业务目标生成候选；只改变可行资源选择顺序，不放松硬约束。
     */
    Map<Long, PlnOperationScheduleEvaluationDTO> previewInsertionReplan(
            Long proposalTaskId, List<PlnIssueEntity> orderedIssues,
            Map<Long, Long> excludedFormalTasks, Map<Long, LocalDateTime> requestedStarts,
            String candidateStrategy);

    void validateLocalReplan(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> operations,
                             Long originalTaskId, Long issueId);

    /**
     * 对插单及全部受影响计划作为一个原子排程范围进行实时资源复核。
     */
    void validateInsertionReplan(Map<Long, PlnIssueEntity> issues,
                                 Map<Long, List<PlnSchedulingOperationEntity>> operations,
                                 Map<Long, Long> excludedFormalTasks);

    void validateBeforeLock(Long taskId, List<PlnSchedulingTaskDetailEntity> details);

    /**
     * 专业调整后的候选必须重新落在当前班次与工作日历上，不能仅以时间区间不重叠作为可行依据。
     */
    void validateDerivedCalendar(Long taskId);

    void lockIssueOperations(Long taskId, Long issueId, LocalDateTime lockTime);

    List<PlnSchedulingOperationEntity> listTaskOperations(Long taskId, Long issueId, String resourceCode);

    List<PlnSchedulingOperationEntity> listBatchOperations(Long taskId, Long batchId);
}
