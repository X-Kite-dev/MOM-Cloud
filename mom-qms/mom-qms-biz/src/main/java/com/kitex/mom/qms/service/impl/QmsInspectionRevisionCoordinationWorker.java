package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionCoordinationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 只持投递行锁跨网络；从不倒取业务父锁。远端未知按原意图恢复。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionCoordinationWorker {
    private final QmsInspectionRevisionCoordinationMapper mapper;
    private final RemoteMesTaskService mes;

    @Value("${mom.qms-inspection-revision.delivery-secret:}")
    private String deliverySecret;

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public boolean deliver(Long id) {
        QmsInspectionEventWorker.scope();
        require(
                id != null && id > 0 && Integer.valueOf(1).equals(mapper.ready()),
                "修订协调队列或申请号不可核验");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var d = mapper.delivery(id, tenant, factory, true);
        if (d == null
                || !Set.of("PENDING", "RETRY_WAIT").contains(Objects.toString(d.getStatus(), ""))
                || d.getNextAttemptAt() == null
                || d.getNextAttemptAt().after(new Date())) return false;
        String formal = mapper.runPhase(id, tenant, factory);
        if (formal != null && !"PREPARE".equals(formal)) return false;
        var e = mapper.execution(id, tenant, factory);
        var c = mapper.cancel(id, tenant, factory);
        try {
            QmsInspectionRevisionCoordinationCodec.owner(
                    e, mapper.request(id, tenant, factory), mapper.review(id, tenant, factory));
            QmsInspectionRevisionCoordinationCodec.cancel(e, c);
            require(
                    Objects.equals(d.getTaskId(), e.getTaskId())
                            && Objects.equals(d.getOperation(), c == null ? "RESERVE" : "CANCEL")
                            && d.getAttemptCount() != null
                            && d.getAttemptCount() >= 0
                            && d.getAttemptCount() < 8
                            && d.getVersion() != null
                            && d.getVersion() >= 0,
                    "修订投递与意图不一致");
            for (var prior : mapper.receipts(id, tenant, factory))
                QmsInspectionRevisionCoordinationCodec.original(e, c, prior);
            require(deliverySecret != null && deliverySecret.length() >= 32, "专用写密钥未配置");
        } catch (RuntimeException ex) {
            failure(d, false, "执行/取消/回执原件或专用写密钥不可核验，请核账后重试");
            return false;
        }
        var command =
                new MesInspectionRevisionDeliveryDTO()
                        .setOperation(d.getOperation())
                        .setRequestId(id)
                        .setTenantId(tenant)
                        .setFactoryCode(factory)
                        .setIntent(e.getIntent())
                        .setIntentFingerprint(e.getIntentFingerprint())
                        .setOperatorId(c == null ? e.getExecutorId() : c.getCanceledBy())
                        .setReason(c == null ? null : c.getReason())
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        command.setSignature(command.sign(deliverySecret));
        R<MesInspectionRevisionAdmissionDTO> response;
        try {
            response = mes.inspectionRevisionAdmission(command);
        } catch (Exception ex) {
            failure(d, true, "MES响应未知，保留同一意图恢复");
            return false;
        }
        try {
            require(response != null && response.getCode() == 0, "MES未确认该操作");
            QmsInspectionRevisionCoordinationCodec.receipt(
                    e, c, d.getOperation(), response.getData());
            if ("CANCEL".equals(d.getOperation()))
                for (var prior : mapper.receipts(id, tenant, factory))
                    if ("RESERVE".equals(prior.getOperation()))
                        QmsInspectionRevisionCoordinationCodec.continuity(
                                QmsInspectionRevisionCoordinationCodec.original(e, c, prior),
                                response.getData());
        } catch (RuntimeException ex) {
            failure(d, true, "MES回执未通过身份核验，冻结结果未知，请按原件恢复");
            return false;
        }
        Date now = new Date();
        String raw = QmsInspectionRevisionCoordinationCodec.write(response.getData());
        require(
                mapper.insertReceipt(
                                new QmsInspectionRevisionReceiptEntity()
                                        .setId(IdWorker.getId())
                                        .setRequestId(id)
                                        .setTenantId(tenant)
                                        .setFactoryCode(factory)
                                        .setTaskId(e.getTaskId())
                                        .setOperation(d.getOperation())
                                        .setPayload(raw)
                                        .setFingerprint(
                                                QmsInspectionRevisionCoordinationCodec.hash(raw))
                                        .setRecordedAt(now))
                        == 1,
                "MES已确认但本地回执未落库，请按原意图恢复");
        d.setStatus(response.getData().getStatus())
                .setAttemptCount(d.getAttemptCount() + 1)
                .setNextAttemptAt(null)
                .setUpdatedAt(now)
                .setLastError(null);
        require(mapper.progress(d) == 1, "MES回执进度未保存，须原意图恢复");
        return true;
    }

    private void failure(QmsInspectionRevisionDeliveryEntity d, boolean retryable, String message) {
        int n = Math.addExact(d.getAttemptCount(), 1);
        boolean retry = retryable && n < 8;
        long seconds = Math.min(3600, 30L * (1L << Math.min(n - 1, 7)));
        d.setAttemptCount(n)
                .setStatus(retry ? "RETRY_WAIT" : "ATTENTION")
                .setNextAttemptAt(
                        retry ? new Date(System.currentTimeMillis() + seconds * 1000) : null)
                .setUpdatedAt(new Date())
                .setLastError(message);
        require(mapper.progress(d) == 1, "协调失败进度未保存，请原意图恢复");
    }
}
