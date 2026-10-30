package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEventDeliveryCodec.require;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.entity.QmsInspectionEventOutboxEntity;
import com.kitex.mom.qms.mapper.QmsInspectionEventOutboxMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 发送事务只锁永久队列首行和最早未确认件；不跨网络持业务父锁。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionEventWorker {
    private final QmsInspectionEventOutboxMapper outbox;
    private final RemoteMesTaskService mes;

    @Value(
            "${mom.qms-inspection-event.delivery-secret:${MOM_QMS_INSPECTION_EVENT_DELIVERY_SECRET:}}")
    private String deliverySecret;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(Long taskId) {
        scope();
        require(taskId != null && taskId > 0, "普通检验发送缺少任务身份");
        require(Objects.equals(1, outbox.schemaVersion()), "普通检验发件结构或守卫未就绪");
        var anchor =
                outbox.anchor(
                        taskId,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        if (anchor == null) return false;
        var row =
                outbox.head(
                        taskId,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        Date now = new Date();
        if (row == null
                || !Set.of("PENDING", "RETRY_WAIT").contains(Objects.toString(row.getStatus(), ""))
                || row.getNextAttemptTime() == null
                || row.getNextAttemptTime().after(now)) return false;
        var original = outbox.original(row.getId(), row.getTenantId(), row.getFactoryCode());
        try {
            QmsInspectionEventDeliveryCodec.verify(row, original);
            require(deliverySecret != null && deliverySecret.length() >= 32, "普通检验投递专用写密钥未配置");
        } catch (RuntimeException ex) {
            failure(row, false, "发件原件或专用写密钥不可核验，请核账后重试");
            return false;
        }
        var command =
                new MesInspectionEventDeliveryDTO()
                        .setEventId(row.getId())
                        .setTenantId(row.getTenantId())
                        .setFactoryCode(row.getFactoryCode())
                        .setPayload(original.getPayload())
                        .setFingerprint(row.getFingerprint())
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        command.setSignature(command.sign(deliverySecret));
        R<MesInspectionEventReceiptDTO> response;
        try {
            response = mes.deliverInspectionEvent(command);
        } catch (Exception ex) {
            failure(row, true, "MES访问异常，收件结果未知，保留原件重试");
            return false;
        }
        var receipt = response == null ? null : response.getData();
        if (response == null
                || response.getCode() != 0
                || receipt == null
                || !Objects.equals(receipt.getEventId(), row.getId())
                || !Objects.equals(receipt.getTaskId(), row.getTaskId())
                || !Objects.equals(receipt.getSequenceNo(), row.getSequenceNo())
                || !Objects.equals(receipt.getFingerprint(), row.getFingerprint())
                || !"RECEIVED".equals(receipt.getStatus())
                || !Boolean.FALSE.equals(receipt.getApplied())
                || receipt.getReceivedAt() == null
                || receipt.getReceivedAt().getTime() <= 0
                || receipt.getReceivedAt().getTime() > System.currentTimeMillis() + 15000) {
            failure(row, true, "MES收件回执未通过身份核验，保留原件重试");
            return false;
        }
        progress(row, "RECEIVED", null, null, new Date(), receipt.getReceivedAt());
        return true;
    }

    private void failure(QmsInspectionEventOutboxEntity row, boolean retryable, String message) {
        int count = Math.addExact(row.getAttemptCount(), 1);
        boolean retry = retryable && count < 8;
        long delay = Math.min(3600, 30L * (1L << Math.min(count - 1, 7)));
        progress(
                row,
                retry ? "RETRY_WAIT" : "ATTENTION",
                message,
                retry ? new Date(System.currentTimeMillis() + delay * 1000) : null,
                null,
                null);
    }

    private void progress(
            QmsInspectionEventOutboxEntity row,
            String status,
            String error,
            Date next,
            Date confirmed,
            Date received) {
        require(
                outbox.update(
                                null,
                                new LambdaUpdateWrapper<QmsInspectionEventOutboxEntity>()
                                        .eq(QmsInspectionEventOutboxEntity::getId, row.getId())
                                        .eq(
                                                QmsInspectionEventOutboxEntity::getTenantId,
                                                row.getTenantId())
                                        .eq(
                                                QmsInspectionEventOutboxEntity::getFactoryCode,
                                                row.getFactoryCode())
                                        .set(QmsInspectionEventOutboxEntity::getStatus, status)
                                        .set(
                                                QmsInspectionEventOutboxEntity::getAttemptCount,
                                                Math.addExact(row.getAttemptCount(), 1))
                                        .set(QmsInspectionEventOutboxEntity::getLastError, error)
                                        .set(
                                                QmsInspectionEventOutboxEntity::getNextAttemptTime,
                                                next)
                                        .set(
                                                QmsInspectionEventOutboxEntity::getConfirmedAt,
                                                confirmed)
                                        .set(
                                                QmsInspectionEventOutboxEntity::getMesReceivedAt,
                                                received))
                        == 1,
                "普通检验发件进度保存失败，请按同一原件恢复");
    }

    static void scope() {
        require(
                TenantContextHolder.getTenantId() != null
                        && TenantContextHolder.getTenantId() > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && FactoryContextHolder.getFactoryCode() != null
                        && !FactoryContextHolder.getFactoryCode().isBlank(),
                "普通检验发件缺少实际租户或工厂，禁止skip");
    }
}
