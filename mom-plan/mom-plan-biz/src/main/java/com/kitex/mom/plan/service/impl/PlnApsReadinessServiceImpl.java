package com.kitex.mom.plan.service.impl;

import com.kitex.mom.plan.dto.PlnApsResourceDTO;
import com.kitex.mom.plan.entity.PlnApsResourceSnapshotEntity;
import com.kitex.mom.plan.service.PlnApsReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 通过重新采集当前模块证据判断既有快照是否仍可用于求解和发布。
 */
@Service
@RequiredArgsConstructor
public class PlnApsReadinessServiceImpl implements PlnApsReadinessService {

    private final PlnApsResourceSnapshotServiceImpl snapshotService;

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public PlnApsResourceDTO.Snapshot recheck(Long caseId, Long snapshotId) {
        PlnApsResourceSnapshotServiceImpl.Scope scope = snapshotService.currentScope();
        PlnApsResourceSnapshotEntity snapshot = snapshotService.requireSnapshot(caseId, snapshotId, scope);
        PlnApsResourceSnapshotServiceImpl.CurrentEvidence current = snapshotService.currentEvidence(caseId);
        boolean activeReference = Objects.equals(current.caseView().getResourceSnapshotId(), snapshotId.toString())
                && Objects.equals(current.caseView().getResourceFingerprint(), current.material().fingerprint());
        boolean sameEvidence = Objects.equals(snapshotService.detail(caseId, snapshotId).getResourceFingerprint(),
                current.material().fingerprint());
        if (!activeReference || !sameEvidence) {
            String reason = !activeReference
                    ? "案件当前资源快照引用已变化" : "至少一个业务模块的资源事实或来源版本已变化";
            snapshotService.lambdaUpdate()
                    .eq(PlnApsResourceSnapshotEntity::getId, snapshotId)
                    .eq(PlnApsResourceSnapshotEntity::getCaseId, caseId)
                    .eq(PlnApsResourceSnapshotEntity::getTenantId, scope.tenantId())
                    .eq(PlnApsResourceSnapshotEntity::getFactoryCode, scope.factoryCode())
                    .set(PlnApsResourceSnapshotEntity::getStale, true)
                    .set(PlnApsResourceSnapshotEntity::getReadinessStatus, "STALE")
                    .set(PlnApsResourceSnapshotEntity::getComplete, false)
                    .set(PlnApsResourceSnapshotEntity::getStaleReason, reason)
                    .update();
        }
        return snapshotService.detail(caseId, snapshotId);
    }
}
