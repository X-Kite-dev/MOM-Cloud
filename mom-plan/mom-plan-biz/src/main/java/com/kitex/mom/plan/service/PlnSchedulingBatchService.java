package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnSchedulingBatchConfigureDTO;
import com.kitex.mom.plan.dto.PlnSchedulingBatchLedgerDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;

import java.time.LocalDateTime;
import java.util.List;

public interface PlnSchedulingBatchService {
    void initializeSingleBatches(Long taskId, List<PlnIssueEntity> issues);

    boolean configure(PlnSchedulingBatchConfigureDTO configureDTO);

    PlnSchedulingBatchLedgerDTO ledger(Long taskId);

    void validateBeforeLock(Long taskId);

    void lockIssueBatches(Long taskId, Long issueId, LocalDateTime lockTime);
}
