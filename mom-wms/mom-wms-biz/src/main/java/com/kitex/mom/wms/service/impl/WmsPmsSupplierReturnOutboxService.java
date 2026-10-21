package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.pms.api.feign.RemotePmsSupplierReturnService;
import com.kitex.mom.pms.dto.PmsSupplierReturnEvent;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * WMS采购退供完成事实可靠投递到PMS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsPmsSupplierReturnOutboxService {
    private static final Set<String> FINISHED_BILL_STATES = Set.of(
            InOutBillStateEnum.PASSED.getCode(), InOutBillStateEnum.FINISHED.getCode());

    private final WmsPmsSupplierReturnOutboxMapper outboxMapper;
    private final WmsOutNoticeMapper noticeMapper;
    private final WmsOutNoticeDetailMapper noticeDetailMapper;
    private final WmsOutBillMapper billMapper;
    private final WmsOutBillDetailMapper billDetailMapper;
    private final RemotePmsSupplierReturnService remotePmsSupplierReturnService;

    /**
     * 与库存扣减处于同一WMS事务；仅入箱，不在实物事务中远程调用PMS。
     */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueIfCompleted(WmsOutBillEntity finalBill) {
        require(finalBill != null && finalBill.getNoticeId() != null, "采购退供出库缺少来源通知");
        WmsOutNoticeEntity notice = noticeMapper.selectById(finalBill.getNoticeId());
        require(notice != null && WmsSupplierReturnService.DOCUMENT_TABLE.equals(notice.getDocumentTable()),
                "采购退供出库通知来源不正确");
        if (!InOutNoticeStateEnum.FINISHED.getCode().equals(notice.getState())) return;
        String eventId = "PMS-SUPPLIER-RETURN:" + notice.getId();
        WmsPmsSupplierReturnOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(WmsPmsSupplierReturnOutboxEntity::getEventId, eventId), false);
        PmsSupplierReturnEvent event = event(notice, finalBill, eventId);
        event.setFingerprint(event.calculateFingerprint());
        if (existing != null) {
            require(Objects.equals(existing.getFingerprint(), event.getFingerprint()),
                    "同一采购退供WMS事件的冻结事实不一致");
            return;
        }
        WmsPmsSupplierReturnOutboxEntity outbox = new WmsPmsSupplierReturnOutboxEntity()
                .setEventId(eventId).setReturnId(notice.getDocumentId()).setNoticeId(notice.getId())
                .setOutBillId(finalBill.getId()).setFingerprint(event.getFingerprint())
                .setPayload(JSONUtil.toJsonStr(event)).setStatus("PENDING").setAttemptCount(0)
                .setNextAttemptTime(LocalDateTime.now()).setFactoryCode(notice.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "采购退供PMS回写Outbox保存失败");
    }

    public int processDue(int limit) {
        List<WmsPmsSupplierReturnOutboxEntity> rows = outboxMapper.selectPage(
                new Page<>(1, Math.max(1, Math.min(limit, 100))),
                new LambdaQueryWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .in(WmsPmsSupplierReturnOutboxEntity::getStatus,
                                "PENDING", "RETRY_WAIT", "PROCESSING")
                        .le(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime)
                        .orderByAsc(WmsPmsSupplierReturnOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (WmsPmsSupplierReturnOutboxEntity row : rows) {
            try {
                boolean ok = row.getTenantId() == null ? dispatch(row.getId())
                        : TenantBroker.applyAs(row.getTenantId(), ignored -> dispatch(row.getId()));
                if (ok) delivered++;
            } catch (Exception exception) {
                log.error("采购退供回写PMS失败，eventId={}", row.getEventId(), exception);
            }
        }
        return delivered;
    }

    /**
     * 运维查询退供回写事件，页面不允许改写载荷。
     */
    public Page<WmsPmsSupplierReturnOutboxEntity> page(SearchPage searchPage, String status, String eventId) {
        return outboxMapper.selectPage(searchPage.toMpPage(),
                new LambdaQueryWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(StrUtil.isNotBlank(status), WmsPmsSupplierReturnOutboxEntity::getStatus, status)
                        .like(StrUtil.isNotBlank(eventId), WmsPmsSupplierReturnOutboxEntity::getEventId, eventId)
                        .orderByDesc(WmsPmsSupplierReturnOutboxEntity::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsPmsSupplierReturnOutboxEntity retry(Long id, String reason) {
        require(StrUtil.isNotBlank(reason) && reason.trim().length() <= 500, "人工续跑必须填写1至500字原因");
        WmsPmsSupplierReturnOutboxEntity row = lock(id);
        require(Set.of("RETRY_WAIT", "DEAD_LETTER").contains(row.getStatus()),
                "只有退避或死信退供事件允许人工续跑");
        row.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastError("人工续跑：" + reason.trim());
        require(outboxMapper.updateById(row) == 1, "采购退供回写人工续跑保存失败");
        return row;
    }

    private boolean dispatch(Long id) {
        WmsPmsSupplierReturnOutboxEntity claimed = claim(id);
        if (claimed == null) return false;
        try {
            PmsSupplierReturnEvent event = JSONUtil.toBean(claimed.getPayload(), PmsSupplierReturnEvent.class);
            require(Objects.equals(claimed.getFingerprint(), event.calculateFingerprint()), "采购退供Outbox载荷指纹损坏");
            R<PmsSupplierReturnEvent.Receipt> response = remotePmsSupplierReturnService.receiveCompleted(event);
            PmsSupplierReturnEvent.Receipt receipt = response == null ? null : response.getData();
            require(response != null && response.isOk() && receipt != null
                            && Objects.equals(receipt.getEventId(), claimed.getEventId())
                            && Objects.equals(receipt.getReturnId(), claimed.getReturnId())
                            && Objects.equals(receipt.getFingerprint(), claimed.getFingerprint())
                            && "PROCESSED".equals(receipt.getStatus()),
                    "PMS未返回匹配的采购退供永久回执");
            return markDelivered(id);
        } catch (Exception exception) {
            markFailed(id, exception);
            return false;
        }
    }

    protected WmsPmsSupplierReturnOutboxEntity claim(Long id) {
        LocalDateTime now = LocalDateTime.now();
        int affected = outboxMapper.update(null,
                new LambdaUpdateWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(WmsPmsSupplierReturnOutboxEntity::getId, id)
                        .in(WmsPmsSupplierReturnOutboxEntity::getStatus,
                                "PENDING", "RETRY_WAIT", "PROCESSING")
                        .le(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime, now)
                        .set(WmsPmsSupplierReturnOutboxEntity::getStatus, "PROCESSING")
                        // 复用nextAttemptTime作为处理租约；进程崩溃后五分钟可安全重投永久事件。
                        .set(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime, now.plusMinutes(5)));
        if (affected == 0) return null;
        return outboxMapper.selectById(id);
    }

    protected boolean markDelivered(Long id) {
        int affected = outboxMapper.update(null,
                new LambdaUpdateWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(WmsPmsSupplierReturnOutboxEntity::getId, id)
                        .eq(WmsPmsSupplierReturnOutboxEntity::getStatus, "PROCESSING")
                        .set(WmsPmsSupplierReturnOutboxEntity::getStatus, "DELIVERED")
                        .set(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime, null)
                        .set(WmsPmsSupplierReturnOutboxEntity::getLastError, null)
                        .set(WmsPmsSupplierReturnOutboxEntity::getDeliveredTime, LocalDateTime.now()));
        require(affected == 1, "采购退供回写成功状态保存失败");
        return true;
    }

    protected void markFailed(Long id, Exception exception) {
        WmsPmsSupplierReturnOutboxEntity row = outboxMapper.selectById(id);
        require(row != null && "PROCESSING".equals(row.getStatus()), "采购退供回写失败事件状态不正确");
        int attempt = Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1;
        String message = StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName());
        int affected = outboxMapper.update(null,
                new LambdaUpdateWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(WmsPmsSupplierReturnOutboxEntity::getId, id)
                        .eq(WmsPmsSupplierReturnOutboxEntity::getStatus, "PROCESSING")
                        .set(WmsPmsSupplierReturnOutboxEntity::getAttemptCount, attempt)
                        .set(WmsPmsSupplierReturnOutboxEntity::getStatus,
                                attempt >= 8 ? "DEAD_LETTER" : "RETRY_WAIT")
                        .set(WmsPmsSupplierReturnOutboxEntity::getNextAttemptTime, attempt >= 8 ? null
                                : LocalDateTime.now().plusSeconds(Math.min(3600, 30L << Math.min(attempt, 6))))
                        .set(WmsPmsSupplierReturnOutboxEntity::getLastError,
                                message.substring(0, Math.min(1000, message.length()))));
        require(affected == 1, "采购退供回写失败状态保存失败");
    }

    private PmsSupplierReturnEvent event(WmsOutNoticeEntity notice, WmsOutBillEntity finalBill, String eventId) {
        List<WmsOutNoticeDetailEntity> noticeDetails = noticeDetailMapper.selectList(
                new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                        .eq(WmsOutNoticeDetailEntity::getNoticeId, notice.getId()));
        Map<Long, WmsOutNoticeDetailEntity> byId = noticeDetails.stream().collect(
                Collectors.toMap(WmsOutNoticeDetailEntity::getId, Function.identity()));
        List<Long> billIds = billMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                        .select(WmsOutBillEntity::getId).eq(WmsOutBillEntity::getNoticeId, notice.getId())
                        .in(WmsOutBillEntity::getState, FINISHED_BILL_STATES))
                .stream().map(WmsOutBillEntity::getId).toList();
        require(!billIds.isEmpty(), "采购退供通知完成但没有终态出库单");
        List<WmsOutBillDetailEntity> billDetails = billDetailMapper.selectList(
                new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                        .in(WmsOutBillDetailEntity::getBillId, billIds));
        Map<Long, BigDecimal> actual = billDetails.stream().collect(Collectors.groupingBy(
                WmsOutBillDetailEntity::getNoticeDetailId, LinkedHashMap::new,
                Collectors.mapping(item -> decimal(item.getMatNum()),
                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
        List<PmsSupplierReturnEvent.Line> lines = new ArrayList<>();
        for (WmsOutNoticeDetailEntity detail : noticeDetails) {
            BigDecimal quantity = actual.getOrDefault(detail.getId(), BigDecimal.ZERO);
            require(quantity.compareTo(decimal(detail.getNoticeOutNum())) == 0,
                    "采购退供通知已完成但明细实际出库数量不守恒");
            Map<?, ?> expansion = JSONUtil.toBean(StrUtil.blankToDefault(detail.getExpansion(), "{}"), Map.class);
            lines.add(new PmsSupplierReturnEvent.Line().setReturnDetailId(detail.getDocumentId())
                    .setOrderDetailId(longValue(expansion.get("orderDetailId")))
                    .setPlanNo(Objects.toString(expansion.get("planNo"), null))
                    .setLiveStockId(detail.getSourceStockId()).setMaterialCode(detail.getMatCode())
                    .setBatchNo(resolveBatch(detail, billDetails)).setActualReturnQuantity(quantity));
        }
        Map<?, ?> header = JSONUtil.toBean(StrUtil.blankToDefault(notice.getExpansion(), "{}"), Map.class);
        return new PmsSupplierReturnEvent().setEventId(eventId).setReturnId(notice.getDocumentId())
                .setReturnNo(Objects.toString(header.get("returnNo"), null)).setWmsNoticeId(notice.getId())
                .setWmsOutBillId(finalBill.getId()).setWmsOutBillNo(finalBill.getBillNo())
                .setFactoryCode(notice.getFactoryCode()).setCompletedTime(notice.getCompleteTime())
                .setLines(lines);
    }

    private String resolveBatch(WmsOutNoticeDetailEntity detail, List<WmsOutBillDetailEntity> billDetails) {
        return billDetails.stream().filter(row -> Objects.equals(row.getNoticeDetailId(), detail.getId()))
                .map(WmsOutBillDetailEntity::getMatBatch).filter(Objects::nonNull).distinct()
                .reduce((left, right) -> {
                    throw new CheckedException("一条退供库存行不能跨多个批次出库");
                }).orElse(null);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new CheckedException("退供通知订单行来源损坏");
        }
    }

    private WmsPmsSupplierReturnOutboxEntity lock(Long id) {
        WmsPmsSupplierReturnOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<WmsPmsSupplierReturnOutboxEntity>()
                        .eq(WmsPmsSupplierReturnOutboxEntity::getId, id).last("FOR UPDATE"), false);
        require(row != null, "采购退供回写事件不存在");
        return row;
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
