package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.dto.QmsMesCaseDecisionSnapshot;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileOperationEntity;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileMapper;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileOperationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class QmsMesReceiptReconcileQueue {
    private final QmsMesReceiptReconcileMapper jobs;
    private final QmsMesReceiptReconcileOperationMapper operations;

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueue(QmsBadInfoEntity info, QmsBadInfoDetailEntity detail) {
        if (!com.kitex.mom.mes.dto.MesInventoryQualityOrigin.isMesResource(info.getResourceType()) || detail.getMesDecisionKey() == null)
            return;
        jobs.lockKey("QMS-MES-RECONCILE:" + info.getTenantId() + ":" + info.getFactoryCode() + ":" + detail.getId() + ":" + detail.getMesDecisionKey());
        var existing = jobs.selectOne(new LambdaQueryWrapper<QmsMesReceiptReconcileEntity>().eq(QmsMesReceiptReconcileEntity::getDetailId, detail.getId()).eq(QmsMesReceiptReconcileEntity::getDecisionKey, detail.getMesDecisionKey()));
        String snapshot = QmsMesCaseDecisionSnapshot.of(info, detail);
        if (existing != null) {
            require(existing.getDecisionSnapshot().equals(snapshot), "原MES对账决策证据已变化，不能覆盖");
            return;
        }
        var job = new QmsMesReceiptReconcileEntity().setFactoryCode(info.getFactoryCode()).setBadId(info.getId()).setDetailId(detail.getId()).setCaseId(info.getResourceTaskId())
                .setNcrNo(info.getNcrNo()).setDecisionKey(detail.getMesDecisionKey()).setDecisionSnapshot(snapshot)
                .setStatus("PENDING").setRevision(0L).setCheckCount(0L).setFailureCount(0).setNextCheckTime(LocalDateTime.now());
        job.setTenantId(info.getTenantId());
        require(jobs.insert(job) == 1, "MES回执对账待办保存失败");
        record(job, "CREATE", null, "CREATE:" + job.getId(), hash(snapshot), "跟踪已提交决策；只读取MES永久回执，不执行实物操作", null, "SYSTEM");
    }

    public void record(QmsMesReceiptReconcileEntity job, String type, String before, String request, String fingerprint, String evidence, Long operatorId, String operator) {
        var op = new QmsMesReceiptReconcileOperationEntity().setFactoryCode(job.getFactoryCode()).setReconcileId(job.getId()).setRevision(job.getRevision())
                .setRequestId(request).setFingerprint(fingerprint).setOperationType(type).setBeforeStatus(before).setAfterStatus(job.getStatus()).setEvidence(evidence)
                .setOperatorId(operatorId).setOperatorCode(operator);
        op.setTenantId(job.getTenantId());
        require(operations.insert(op) == 1, "MES回执对账履历保存失败");
    }
}
