package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsReworkResultCodec.require;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesReworkInspectionDeliveryDTO;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.entity.QmsReworkResultOutboxEntity;
import com.kitex.mom.qms.mapper.QmsReworkOrderMapper;
import com.kitex.mom.qms.mapper.QmsReworkResultOutboxMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 每次发送独立事务；ACK未知或本地回执失败仍从原件恢复。 */
@Service
@RequiredArgsConstructor
public class QmsReworkResultWorker {
    private final QmsReworkResultOutboxMapper outbox;
    private final QmsReworkOrderMapper orders;
    private final RemoteMesTaskService mes;

    @Value("${mom.qms-rework-result.delivery-secret:${MOM_QMS_REWORK_RESULT_DELIVERY_SECRET:}}")
    private String deliverySecret;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(Long id) {
        scope();
        require(Objects.equals(outbox.schemaVersion(), 1), "复检发送队列结构未就绪");
        var observed = outbox.selectOne(scoped().eq(QmsReworkResultOutboxEntity::getId, id));
        if (observed == null || "DELIVERED".equals(observed.getStatus())) return false;
        var order =
                outbox.lockOrder(
                        observed.getOrderId(),
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        require(order != null, "复检协调单已不存在，禁止投递");
        // 与生产写入同序：先协调单，再队列；未确认旧件不被后续结果越过。
        var row =
                outbox.selectOne(
                        scoped().eq(QmsReworkResultOutboxEntity::getOrderId, order.getId())
                                .ne(QmsReworkResultOutboxEntity::getStatus, "DELIVERED")
                                .orderByAsc(QmsReworkResultOutboxEntity::getSequenceNo)
                                .last("LIMIT 1 FOR UPDATE"));
        if (row == null
                || !Objects.equals(row.getId(), id)
                || !java.util.Set.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextAttemptTime() == null
                || row.getNextAttemptTime().isAfter(LocalDateTime.now())) return false;
        MesReworkInspectionResultDTO result;
        try {
            result = QmsReworkResultCodec.decode(row, order);
            require(deliverySecret != null && deliverySecret.length() >= 32, "复检投递专用写密钥未配置或不足32字符");
        } catch (IllegalStateException e) {
            failure(row, order, false, e.getMessage());
            return false;
        }
        var delivery =
                new MesReworkInspectionDeliveryDTO()
                        .setEventId(row.getId())
                        .setTenantId(row.getTenantId())
                        .setFactoryCode(row.getFactoryCode())
                        .setOrderId(row.getOrderId())
                        .setSequenceNo(row.getSequenceNo())
                        .setPayload(row.getPayload())
                        .setFingerprint(row.getFingerprint())
                        .setIssuedAt(java.time.Instant.now().getEpochSecond())
                        .setNonce(java.util.UUID.randomUUID().toString());
        delivery.setSignature(delivery.sign(deliverySecret));
        R<Void> response;
        try {
            response = mes.deliverReworkInspection(delivery);
        } catch (Exception e) {
            failure(row, order, true, "MES访问异常，执行结果未知，保留原件重试");
            return false;
        }
        if (response == null || response.getCode() != 0) {
            failure(
                    row,
                    order,
                    true,
                    response == null ? "MES无响应，执行结果未知，保留原件重试" : "MES拒绝复检同步，请核对接收服务业务日志");
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        progress(row, "DELIVERED", null, null, now);
        var latest =
                outbox.selectOne(
                        scoped().eq(QmsReworkResultOutboxEntity::getOrderId, order.getId())
                                .orderByDesc(QmsReworkResultOutboxEntity::getSequenceNo)
                                .last("LIMIT 1"));
        boolean more = latest != null && latest.getSequenceNo() > row.getSequenceNo();
        boolean finished = result.getHandleNum().equals(result.getTotalNum());
        receipt(
                order,
                more ? "RESULT_SYNC_PENDING" : finished ? "CLOSED" : "REINSPECTION_PENDING",
                more
                        ? "SYNC_PENDING"
                        : finished
                                ? (result.getUsableNum() == 0 ? "REJECTED" : "COMPLETED")
                                : "PARTIAL",
                null,
                now);
        return true;
    }

    private void failure(
            QmsReworkResultOutboxEntity row,
            QmsReworkOrderEntity order,
            boolean retryable,
            String message) {
        int count = row.getAttemptCount() + 1;
        boolean retry = retryable && count < 8;
        progress(
                row,
                retry ? "RETRY_WAIT" : "ATTENTION",
                message,
                retry
                        ? LocalDateTime.now()
                                .plusSeconds(Math.min(3600, 30L * (1L << Math.min(count - 1, 7))))
                        : null,
                null);
        receipt(order, "RESULT_SYNC_FAILED", "SYNC_FAILED", message, order.getResultSyncTime());
    }

    private void progress(
            QmsReworkResultOutboxEntity row,
            String status,
            String error,
            LocalDateTime next,
            LocalDateTime delivered) {
        require(
                outbox.update(
                                null,
                                new LambdaUpdateWrapper<QmsReworkResultOutboxEntity>()
                                        .eq(QmsReworkResultOutboxEntity::getId, row.getId())
                                        .eq(
                                                QmsReworkResultOutboxEntity::getTenantId,
                                                row.getTenantId())
                                        .eq(
                                                QmsReworkResultOutboxEntity::getFactoryCode,
                                                row.getFactoryCode())
                                        .set(QmsReworkResultOutboxEntity::getStatus, status)
                                        .set(
                                                QmsReworkResultOutboxEntity::getAttemptCount,
                                                row.getAttemptCount() + 1)
                                        .set(QmsReworkResultOutboxEntity::getLastError, error)
                                        .set(QmsReworkResultOutboxEntity::getNextAttemptTime, next)
                                        .set(
                                                QmsReworkResultOutboxEntity::getDeliveredAt,
                                                delivered))
                        == 1,
                "复检发送进度保存失败");
    }

    private void receipt(
            QmsReworkOrderEntity order,
            String status,
            String inspection,
            String error,
            LocalDateTime time) {
        var value =
                new QmsReworkOrderEntity()
                        .setStatus(status)
                        .setReinspectionStatus(inspection)
                        .setResultSyncCount(
                                Objects.requireNonNullElse(order.getResultSyncCount(), 0) + 1)
                        .setResultSyncTime(time);
        require(
                orders.update(
                                value,
                                new LambdaUpdateWrapper<QmsReworkOrderEntity>()
                                        .eq(QmsReworkOrderEntity::getId, order.getId())
                                        .eq(QmsReworkOrderEntity::getTenantId, order.getTenantId())
                                        .eq(
                                                QmsReworkOrderEntity::getFactoryCode,
                                                order.getFactoryCode())
                                        .set(QmsReworkOrderEntity::getLastError, error))
                        == 1,
                "返工复检同步回执保存失败，请按原协调单重试");
    }

    static void scope() {
        require(
                TenantContextHolder.getTenantId() != null
                        && TenantContextHolder.getTenantId() > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && FactoryContextHolder.getFactoryCode() != null
                        && !FactoryContextHolder.getFactoryCode().isBlank(),
                "复检发送缺少实际租户或工厂");
    }

    static LambdaQueryWrapper<QmsReworkResultOutboxEntity> scoped() {
        return new LambdaQueryWrapper<QmsReworkResultOutboxEntity>()
                .eq(QmsReworkResultOutboxEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(
                        QmsReworkResultOutboxEntity::getFactoryCode,
                        FactoryContextHolder.getFactoryCode());
    }
}
