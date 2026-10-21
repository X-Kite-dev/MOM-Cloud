package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mes.api.feign.RemoteMesFinishedGoodsReceiptReverseService;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptCorrectionEntity;
import com.kitex.mom.wms.entity.WmsFinishedGoodsReceiptReverseOutboxEntity;
import com.kitex.mom.wms.mapper.WmsFinishedGoodsReceiptCorrectionMapper;
import com.kitex.mom.wms.mapper.WmsFinishedGoodsReceiptReverseOutboxMapper;
import com.kitex.mom.wms.service.WmsFinishedGoodsReceiptReverseOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * WMS成品实物红冲到MES的租约式可靠投递实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WmsFinishedGoodsReceiptReverseOutboxServiceImpl
        extends ServiceImpl<WmsFinishedGoodsReceiptReverseOutboxMapper, WmsFinishedGoodsReceiptReverseOutboxEntity>
        implements WmsFinishedGoodsReceiptReverseOutboxService {

    private final WmsFinishedGoodsReceiptCorrectionMapper correctionMapper;
    private final RemoteMesFinishedGoodsReceiptReverseService mesService;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WmsFinishedGoodsReceiptCorrectionEntity correction,
                        MesFinishedGoodsReceiptReverseEvent event) {
        require(correction != null && correction.getId() != null && event != null
                        && Objects.equals(event.getFingerprint(), event.calculateFingerprint()),
                "成品实物红冲待投递事实不完整");
        WmsFinishedGoodsReceiptReverseOutboxEntity existing = getOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptReverseOutboxEntity>()
                        .eq(WmsFinishedGoodsReceiptReverseOutboxEntity::getEventId, event.getEventId()), false);
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint()),
                    "同一成品实物红冲事件已有不同冻结事实");
            return;
        }
        WmsFinishedGoodsReceiptReverseOutboxEntity row = new WmsFinishedGoodsReceiptReverseOutboxEntity()
                .setEventId(event.getEventId()).setCorrectionId(correction.getId())
                .setNoticeId(correction.getNoticeId())
                .setSourceMesInboundRequestId(correction.getSourceMesInboundRequestId())
                .setFingerprint(event.getFingerprint()).setPayloadJson(JSONUtil.toJsonStr(event))
                .setStatus("PENDING").setAttemptCount(0).setCycleAttempts(0)
                .setNextAttemptTime(LocalDateTime.now()).setFactoryCode(correction.getFactoryCode());
        require(save(row), "成品实物红冲待投递事实保存失败，WMS事务回滚");
        correction.setMesReceiptStatus("PENDING");
        require(correctionMapper.updateById(correction) == 1, "红冲申请MES回执状态保存失败");
    }

    @Override
    public int processDue(int limit) {
        int delivered = 0;
        for (WmsFinishedGoodsReceiptReverseOutboxEntity candidate : baseMapper.dueAcrossTenants(
                Math.min(100, Math.max(1, limit)))) {
            String previous = FactoryContextHolder.getFactoryCode();
            try {
                FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                WmsFinishedGoodsReceiptReverseOutboxEntity result = TenantBroker.applyAs(
                        candidate.getTenantId(), ignored -> dispatch(candidate.getId()));
                if (result != null && "DELIVERED".equals(result.getStatus())) delivered++;
            } catch (RuntimeException exception) {
                log.warn("WMS成品实物红冲事件调度失败，outboxId={}，原因={}",
                        candidate.getId(), safeError(exception));
            } finally {
                FactoryContextHolder.setFactoryCode(previous);
            }
        }
        return delivered;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int resetCorrection(Long correctionId) {
        List<WmsFinishedGoodsReceiptReverseOutboxEntity> rows = list(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptReverseOutboxEntity>()
                        .eq(WmsFinishedGoodsReceiptReverseOutboxEntity::getCorrectionId, correctionId)
                        .ne(WmsFinishedGoodsReceiptReverseOutboxEntity::getStatus, "DELIVERED")
                        .orderByAsc(WmsFinishedGoodsReceiptReverseOutboxEntity::getId));
        LocalDateTime now = LocalDateTime.now();
        for (WmsFinishedGoodsReceiptReverseOutboxEntity row : rows) {
            require(!"SENDING".equals(row.getStatus()) || row.getLeaseUntil() == null
                    || !row.getLeaseUntil().isAfter(now), "红冲事件正在投递，请等待当前租约结束");
            row.setStatus("PENDING").setCycleAttempts(0).setNextAttemptTime(now)
                    .setLeaseToken(null).setLeaseUntil(null).setLastError(null);
            require(updateById(row), "红冲事件重试状态保存失败");
        }
        if (!rows.isEmpty()) updateCorrectionReceipt(correctionId, "PENDING", null);
        return rows.size();
    }

    private WmsFinishedGoodsReceiptReverseOutboxEntity dispatch(Long id) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "禁止在WMS红冲源事务内远程投递MES事件");
        WmsFinishedGoodsReceiptReverseOutboxEntity claimed = tx().execute(status -> {
            WmsFinishedGoodsReceiptReverseOutboxEntity row = lock(id);
            LocalDateTime now = LocalDateTime.now();
            if ("DELIVERED".equals(row.getStatus()) || "DEAD".equals(row.getStatus())) return null;
            if ("SENDING".equals(row.getStatus()) && row.getLeaseUntil() != null
                    && row.getLeaseUntil().isAfter(now)) return null;
            if (!"SENDING".equals(row.getStatus()) && row.getNextAttemptTime() != null
                    && row.getNextAttemptTime().isAfter(now)) return null;
            row.setStatus("SENDING").setLeaseToken(UUID.randomUUID().toString())
                    .setLeaseUntil(now.plusMinutes(2)).setNextAttemptTime(null)
                    .setAttemptCount(row.getAttemptCount() + 1).setCycleAttempts(row.getCycleAttempts() + 1);
            require(updateById(row), "红冲事件投递租约保存失败");
            updateCorrectionReceipt(row.getCorrectionId(), "SENDING", null);
            return row;
        });
        if (claimed == null) return getById(id);
        try {
            MesFinishedGoodsReceiptReverseEvent event = JSONUtil.toBean(
                    claimed.getPayloadJson(), MesFinishedGoodsReceiptReverseEvent.class);
            require(event != null && Objects.equals(event.getFingerprint(), claimed.getFingerprint())
                            && Objects.equals(event.calculateFingerprint(), claimed.getFingerprint()),
                    "冻结的成品实物红冲载荷已损坏");
            R<MesFinishedGoodsReceiptReverseEvent.Receipt> lookup = mesService.receipt(claimed.getEventId());
            require(lookup != null && lookup.isOk(), "MES成品红冲回执查询失败");
            MesFinishedGoodsReceiptReverseEvent.Receipt receipt = lookup.getData();
            if (receipt == null) {
                R<MesFinishedGoodsReceiptReverseEvent.Receipt> response = mesService.receive(event);
                require(response != null && response.isOk(), "MES未确认成品实物红冲事件");
                receipt = response.getData();
            }
            require(receipt != null && receipt.matches(event) && receipt.getAcceptedTime() != null,
                    "MES成品实物红冲回执与冻结事件不一致");
            MesFinishedGoodsReceiptReverseEvent.Receipt accepted = receipt;
            return tx().execute(status -> {
                WmsFinishedGoodsReceiptReverseOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken())) return row;
                String receiptJson = JSONUtil.toJsonStr(accepted);
                row.setStatus("DELIVERED").setReceiptJson(receiptJson).setDeliveredTime(LocalDateTime.now())
                        .setLeaseToken(null).setLeaseUntil(null).setNextAttemptTime(null).setLastError(null);
                require(updateById(row), "MES红冲永久回执保存失败");
                updateCorrectionReceipt(row.getCorrectionId(), "DELIVERED", receiptJson);
                return row;
            });
        } catch (Exception exception) {
            return tx().execute(status -> {
                WmsFinishedGoodsReceiptReverseOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken())) return row;
                boolean dead = row.getCycleAttempts() >= 5;
                row.setStatus(dead ? "DEAD" : "RETRY").setLastError(safeError(exception))
                        .setLeaseToken(null).setLeaseUntil(null)
                        .setNextAttemptTime(dead ? null : LocalDateTime.now().plusSeconds(
                                Math.min(1800, 30L << Math.min(row.getCycleAttempts(), 6))));
                require(updateById(row), "红冲事件失败状态保存失败");
                updateCorrectionReceipt(row.getCorrectionId(), dead ? "DEAD" : "PENDING", null);
                return row;
            });
        }
    }

    private void updateCorrectionReceipt(Long correctionId, String status, String receiptJson) {
        WmsFinishedGoodsReceiptCorrectionEntity correction = correctionMapper.selectById(correctionId);
        require(correction != null, "成品实物红冲申请不存在");
        correction.setMesReceiptStatus(status);
        if (receiptJson != null) correction.setMesReceiptJson(receiptJson);
        require(correctionMapper.updateById(correction) == 1, "红冲申请MES回执状态更新失败");
    }

    private WmsFinishedGoodsReceiptReverseOutboxEntity lock(Long id) {
        WmsFinishedGoodsReceiptReverseOutboxEntity row = getOne(
                new LambdaQueryWrapper<WmsFinishedGoodsReceiptReverseOutboxEntity>()
                        .eq(WmsFinishedGoodsReceiptReverseOutboxEntity::getId, id).last("FOR UPDATE"), false);
        require(row != null, "成品实物红冲投递事件不存在");
        return row;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String safeError(Exception exception) {
        String message = StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName());
        return message.substring(0, Math.min(1000, message.length()));
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
