package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mes.api.feign.RemoteMesMaterialReturnReceiptService;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsMesMaterialReturnReceiptOutboxEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInBillDetailMapper;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.mapper.WmsMesMaterialReturnReceiptOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * WMS生产退料实收事实到MES的事务Outbox。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WmsMesMaterialReturnReceiptOutboxService {
    private static final String MES_RETURN_TABLE = "mom_mes.mes_warehouse_stock_feed_judge";
    private static final Set<String> TERMINAL_BILL_STATES = Set.of("2", "12");

    private final WmsMesMaterialReturnReceiptOutboxMapper mapper;
    private final WmsInNoticeMapper noticeMapper;
    private final WmsInBillMapper billMapper;
    private final WmsInBillDetailMapper detailMapper;
    private final RemoteMesMaterialReturnReceiptService mesService;
    private final PlatformTransactionManager transactionManager;

    /**
     * 入库记账与待投递事实必须在同一个本地事务提交。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBill(WmsInBillEntity bill) {
        require(bill != null && bill.getId() != null, "生产退料实收缺少入库单");
        WmsInNoticeEntity notice = bill.getNoticeId() == null ? null : noticeMapper.selectById(bill.getNoticeId());
        if (!isMesReturn(notice)) return;
        require(TERMINAL_BILL_STATES.contains(bill.getState()), "只有已记账的生产退料入库单可以回写MES");
        enqueue(buildEvent(notice, bill.getId(), "WMS-MES-MATERIAL-RETURN:" + bill.getId()));
    }

    /**
     * 差异申报或处置只追加状态事件，不伪造实收数量。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueNoticeState(WmsInNoticeEntity notice, String requestId) {
        require(isMesReturn(notice), "生产退料通知缺少MES来源");
        enqueue(buildEvent(notice, null, "WMS-MES-MATERIAL-RETURN-STATE:" + notice.getId()
                + ":" + shortHash(requestId)));
    }

    public int processDue(int limit) {
        int delivered = 0;
        for (WmsMesMaterialReturnReceiptOutboxEntity candidate : mapper.dueAcrossTenants(
                Math.min(100, Math.max(1, limit)))) {
            String previous = FactoryContextHolder.getFactoryCode();
            try {
                FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                WmsMesMaterialReturnReceiptOutboxEntity result = TenantBroker.applyAs(
                        candidate.getTenantId(), ignored -> dispatch(candidate.getId()));
                if (result != null && "DELIVERED".equals(result.getStatus())) delivered++;
            } catch (RuntimeException ex) {
                log.warn("WMS生产退料实收事件调度失败，outboxId={}，原因={}", candidate.getId(), safeError(ex));
            } finally {
                FactoryContextHolder.setFactoryCode(previous);
            }
        }
        return delivered;
    }

    /**
     * 人工续跑只重置当前通知尚未送达的冻结事件，不重建或改写业务事实。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int resetNotice(Long noticeId) {
        List<WmsMesMaterialReturnReceiptOutboxEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<WmsMesMaterialReturnReceiptOutboxEntity>()
                        .eq(WmsMesMaterialReturnReceiptOutboxEntity::getNoticeId, noticeId)
                        .ne(WmsMesMaterialReturnReceiptOutboxEntity::getStatus, "DELIVERED")
                        .orderByAsc(WmsMesMaterialReturnReceiptOutboxEntity::getId));
        LocalDateTime now = LocalDateTime.now();
        for (WmsMesMaterialReturnReceiptOutboxEntity row : rows) {
            require(!"SENDING".equals(row.getStatus()) || row.getLeaseUntil() == null
                    || !row.getLeaseUntil().isAfter(now), "实收事件正在投递，请等待当前租约结束");
            row.setStatus("PENDING").setCycleAttempts(0).setNextAttemptTime(now)
                    .setLeaseToken(null).setLeaseUntil(null).setLastError(null);
            save(row);
        }
        return rows.size();
    }

    private void enqueue(MesMaterialReturnReceiptEvent event) {
        WmsMesMaterialReturnReceiptOutboxEntity existing = mapper.selectOne(
                new LambdaQueryWrapper<WmsMesMaterialReturnReceiptOutboxEntity>()
                        .eq(WmsMesMaterialReturnReceiptOutboxEntity::getEventId, event.getEventId()));
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint()),
                    "同一生产退料实收事件已有不同冻结事实");
            return;
        }
        WmsMesMaterialReturnReceiptOutboxEntity row = new WmsMesMaterialReturnReceiptOutboxEntity()
                .setEventId(event.getEventId()).setNoticeId(event.getWmsNoticeId())
                .setInBillId(event.getWmsInBillId()).setReturnRequestId(event.getReturnRequestId())
                .setFingerprint(event.getFingerprint()).setPayloadJson(JSONUtil.toJsonStr(event))
                .setStatus("PENDING").setAttemptCount(0).setCycleAttempts(0)
                .setNextAttemptTime(LocalDateTime.now()).setFactoryCode(event.getFactoryCode());
        require(mapper.insert(row) == 1, "生产退料实收待投递事实保存失败，入库事务回滚");
    }

    private WmsMesMaterialReturnReceiptOutboxEntity dispatch(Long id) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "禁止在WMS入库事务内远程投递生产退料实收事件");
        WmsMesMaterialReturnReceiptOutboxEntity claimed = tx().execute(status -> {
            WmsMesMaterialReturnReceiptOutboxEntity row = lock(id);
            LocalDateTime now = LocalDateTime.now();
            if ("DELIVERED".equals(row.getStatus()) || "DEAD".equals(row.getStatus())) return null;
            if ("SENDING".equals(row.getStatus()) && row.getLeaseUntil() != null
                    && row.getLeaseUntil().isAfter(now)) return null;
            if (!"SENDING".equals(row.getStatus()) && row.getNextAttemptTime() != null
                    && row.getNextAttemptTime().isAfter(now)) return null;
            row.setStatus("SENDING").setLeaseToken(UUID.randomUUID().toString())
                    .setLeaseUntil(now.plusMinutes(2)).setNextAttemptTime(null)
                    .setAttemptCount(row.getAttemptCount() + 1).setCycleAttempts(row.getCycleAttempts() + 1);
            save(row);
            return row;
        });
        if (claimed == null) return mapper.selectById(id);
        try {
            MesMaterialReturnReceiptEvent event = JSONUtil.toBean(
                    claimed.getPayloadJson(), MesMaterialReturnReceiptEvent.class);
            require(Objects.equals(event.getFingerprint(), claimed.getFingerprint())
                    && Objects.equals(event.calculateFingerprint(), claimed.getFingerprint()), "冻结实收载荷已损坏");
            R<MesMaterialReturnReceiptEvent.Receipt> lookup = mesService.receipt(claimed.getEventId());
            require(lookup != null && lookup.isOk(), "MES生产退料实收回执查询失败");
            MesMaterialReturnReceiptEvent.Receipt receipt = lookup.getData();
            if (receipt == null) {
                R<MesMaterialReturnReceiptEvent.Receipt> response = mesService.receive(event);
                require(response != null && response.isOk(), "MES未确认生产退料实收事件");
                receipt = response.getData();
            }
            require(receipt != null && receipt.matches(event) && receipt.getAcceptedTime() != null,
                    "MES生产退料实收回执与冻结事件不一致");
            MesMaterialReturnReceiptEvent.Receipt accepted = receipt;
            return tx().execute(status -> {
                WmsMesMaterialReturnReceiptOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken())) return row;
                row.setStatus("DELIVERED").setReceiptJson(JSONUtil.toJsonStr(accepted))
                        .setDeliveredTime(LocalDateTime.now()).setLeaseToken(null).setLeaseUntil(null)
                        .setNextAttemptTime(null).setLastError(null);
                save(row);
                return row;
            });
        } catch (Exception ex) {
            return tx().execute(status -> {
                WmsMesMaterialReturnReceiptOutboxEntity row = lock(id);
                if (!Objects.equals(row.getLeaseToken(), claimed.getLeaseToken())) return row;
                boolean dead = row.getCycleAttempts() >= 5;
                row.setStatus(dead ? "DEAD" : "RETRY").setLastError(safeError(ex))
                        .setLeaseToken(null).setLeaseUntil(null)
                        .setNextAttemptTime(dead ? null : LocalDateTime.now().plusSeconds(
                                Math.min(1800, 30L << Math.min(row.getCycleAttempts(), 6))));
                save(row);
                return row;
            });
        }
    }

    private MesMaterialReturnReceiptEvent buildEvent(WmsInNoticeEntity notice, Long billId, String eventId) {
        require(isMesReturn(notice) && notice.getDocumentId() != null && notice.getNoticeInNum() != null,
                "生产退料通知来源或通知量不完整");
        List<WmsInBillEntity> terminalBills = billMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, notice.getId())
                .in(WmsInBillEntity::getState, TERMINAL_BILL_STATES));
        List<Long> ids = terminalBills.stream().map(WmsInBillEntity::getId).toList();
        List<WmsInBillDetailEntity> details = ids.isEmpty() ? List.of()
                : detailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                .in(WmsInBillDetailEntity::getBillId, ids));
        BigDecimal cumulative = details.stream().map(d -> decimal(d.getMatNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = decimal(notice.getNoticeInNum());
        require(cumulative.compareTo(expected) <= 0, "生产退料已记账数量超过批准通知量");
        BigDecimal receiptQuantity = billId == null ? BigDecimal.ZERO : details.stream()
                .filter(d -> Objects.equals(d.getBillId(), billId)).map(d -> decimal(d.getMatNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        WmsInBillEntity bill = billId == null ? null : terminalBills.stream()
                .filter(item -> Objects.equals(item.getId(), billId)).findFirst().orElse(null);
        String receiptStatus = StrUtil.blankToDefault(notice.getReturnReceiptStatus(),
                cumulative.compareTo(expected) == 0 ? "RECEIVED" : (cumulative.signum() > 0 ? "RECEIVING" : "WAITING"));
        boolean finalReceipt = Set.of("RECEIVED", "CLOSED_WITH_VARIANCE").contains(receiptStatus);
        MesMaterialReturnReceiptEvent event = new MesMaterialReturnReceiptEvent().setVersion(1)
                .setEventId(eventId).setReturnRequestId(notice.getDocumentId()).setWmsNoticeId(notice.getId())
                .setWmsInBillId(billId).setWmsInBillNo(bill == null ? null : bill.getBillNo())
                .setFactoryCode(notice.getFactoryCode()).setExpectedQuantity(expected)
                .setReceiptQuantity(receiptQuantity).setCumulativeReceivedQuantity(cumulative)
                .setShortageQuantity(expected.subtract(cumulative))
                .setOverageQuantity(zero(notice.getReturnOverageNum())).setFinalReceipt(finalReceipt)
                .setReceiptStatus(receiptStatus)
                .setReceiptTime(bill != null && bill.getInTime() != null ? bill.getInTime() : LocalDateTime.now());
        event.setFingerprint(event.calculateFingerprint());
        return event;
    }

    private boolean isMesReturn(WmsInNoticeEntity notice) {
        return notice != null && InBillTypeEnum.RETURN.getCode().equals(notice.getBillType())
                && MES_RETURN_TABLE.equals(notice.getDocumentTable());
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private WmsMesMaterialReturnReceiptOutboxEntity lock(Long id) {
        WmsMesMaterialReturnReceiptOutboxEntity row = mapper.selectOne(
                new LambdaQueryWrapper<WmsMesMaterialReturnReceiptOutboxEntity>()
                        .eq(WmsMesMaterialReturnReceiptOutboxEntity::getId, id).last("FOR UPDATE"));
        require(row != null, "生产退料实收投递事件不存在");
        return row;
    }

    private void save(WmsMesMaterialReturnReceiptOutboxEntity row) {
        require(mapper.updateById(row) == 1, "生产退料实收投递状态保存失败");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String safeError(Exception ex) {
        String message = StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName());
        return message.substring(0, Math.min(1000, message.length()));
    }

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
