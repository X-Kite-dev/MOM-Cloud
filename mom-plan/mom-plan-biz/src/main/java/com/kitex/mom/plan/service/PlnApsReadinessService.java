package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnApsResourceDTO;

/**
 * 智能 APS 数据预检和快照失效复核。
 */
public interface PlnApsReadinessService {

    PlnApsResourceDTO.Snapshot recheck(Long caseId, Long snapshotId);
}
