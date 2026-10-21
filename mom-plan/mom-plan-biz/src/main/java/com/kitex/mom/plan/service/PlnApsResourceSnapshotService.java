package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.plan.dto.PlnApsResourceDTO;
import com.kitex.mom.plan.entity.PlnApsResourceSnapshotEntity;

/** 智能 APS 跨模块不可变资源快照服务。 */
public interface PlnApsResourceSnapshotService extends IService<PlnApsResourceSnapshotEntity> {

    PlnApsResourceDTO.Snapshot capture(Long caseId, String requestId, String expectedCaseFingerprint);

    PlnApsResourceDTO.Snapshot detail(Long caseId, Long snapshotId);
}
