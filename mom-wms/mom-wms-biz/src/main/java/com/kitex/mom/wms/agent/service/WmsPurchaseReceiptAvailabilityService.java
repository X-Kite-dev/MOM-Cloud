package com.kitex.mom.wms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.agent.dto.WmsPurchaseReceiptAvailabilityDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用采购收料来源主键核对WMS正式入库，不用当前库存行是否仍存在替代历史入库证明。
 */
@Service
@RequiredArgsConstructor
public class WmsPurchaseReceiptAvailabilityService {

    public static final String TOOL_CODE = "WMS_PURCHASE_RECEIPT_AVAILABILITY_QUERY@1";

    private final WmsAgentFactoryAccessService factoryAccess;
    private final WmsInNoticeMapper noticeMapper;
    private final WmsInNoticeDetailMapper noticeDetailMapper;
    private final WmsInBillMapper inBillMapper;
    private final WmsInBillDetailMapper inBillDetailMapper;
    private final WmsLiveStockMapper stockMapper;
    private final ObjectMapper objectMapper;

    public WmsPurchaseReceiptAvailabilityDTO.Evidence query(WmsPurchaseReceiptAvailabilityDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getReceiptId())) {
            throw new CheckedException("WMS来料可用取证必须指定采购收料单主键");
        }
        WmsAgentFactoryAccessService.Scope scope = factoryAccess.requireRead(query.getFactoryCode());
        Long receiptId = positiveId(query.getReceiptId());
        int limit = query.getDetailLimit() == null ? 100 : Math.max(1, Math.min(query.getDetailLimit(), 100));

        LambdaQueryWrapper<WmsInNoticeDetailEntity> noticeScope = new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                .eq(WmsInNoticeDetailEntity::getTenantId, scope.tenantId())
                .eq(WmsInNoticeDetailEntity::getFactoryCode, scope.factoryCode())
                .eq(WmsInNoticeDetailEntity::getSourceReceiptId, receiptId);
        long noticeCount = noticeDetailMapper.selectCount(noticeScope);
        List<WmsInNoticeDetailEntity> noticeDetails = noticeDetailMapper.selectList(
                new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                        .eq(WmsInNoticeDetailEntity::getTenantId, scope.tenantId())
                        .eq(WmsInNoticeDetailEntity::getFactoryCode, scope.factoryCode())
                        .eq(WmsInNoticeDetailEntity::getSourceReceiptId, receiptId)
                        .orderByAsc(WmsInNoticeDetailEntity::getId).last("LIMIT " + limit));
        Map<Long, WmsInNoticeEntity> notices = notices(noticeDetails, scope);

        LambdaQueryWrapper<WmsInBillDetailEntity> inboundScope = new LambdaQueryWrapper<WmsInBillDetailEntity>()
                .eq(WmsInBillDetailEntity::getTenantId, scope.tenantId())
                .eq(WmsInBillDetailEntity::getFactoryCode, scope.factoryCode())
                .eq(WmsInBillDetailEntity::getSourceReceiptId, receiptId);
        long inboundCount = inBillDetailMapper.selectCount(inboundScope);
        List<WmsInBillDetailEntity> inboundDetails = inBillDetailMapper.selectList(
                new LambdaQueryWrapper<WmsInBillDetailEntity>()
                        .eq(WmsInBillDetailEntity::getTenantId, scope.tenantId())
                        .eq(WmsInBillDetailEntity::getFactoryCode, scope.factoryCode())
                        .eq(WmsInBillDetailEntity::getSourceReceiptId, receiptId)
                        .orderByAsc(WmsInBillDetailEntity::getId).last("LIMIT " + limit));
        Map<Long, WmsInBillEntity> bills = bills(inboundDetails, scope);
        long approvedInbound = inboundDetails.stream().filter(item -> {
            WmsInBillEntity bill = bills.get(item.getBillId());
            return bill != null && "2".equals(bill.getState());
        }).count();

        LambdaQueryWrapper<WmsLiveStockEntity> stockScope = new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getTenantId, scope.tenantId())
                .eq(WmsLiveStockEntity::getFactoryCode, scope.factoryCode())
                .eq(WmsLiveStockEntity::getSourceReceiptId, receiptId)
                .gt(WmsLiveStockEntity::getMatNum, 0);
        long stockCount = stockMapper.selectCount(stockScope);
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<WmsLiveStockEntity>()
                        .eq(WmsLiveStockEntity::getTenantId, scope.tenantId())
                        .eq(WmsLiveStockEntity::getFactoryCode, scope.factoryCode())
                        .eq(WmsLiveStockEntity::getSourceReceiptId, receiptId)
                        .gt(WmsLiveStockEntity::getMatNum, 0)
                        .orderByAsc(WmsLiveStockEntity::getId).last("LIMIT " + limit));

        List<String> gaps = gaps(receiptId, noticeCount, inboundCount, noticeDetails, inboundDetails, bills);
        boolean truncated = noticeCount > noticeDetails.size() || inboundCount > inboundDetails.size()
                || stockCount > stocks.size();
        if (truncated) gaps.add("WMS来源明细超过单次返回上限，当前证据不是完整数量对账");
        Set<String> refs = new LinkedHashSet<>();
        noticeDetails.forEach(item -> {
            refs.add("WMS:wms_in_notice_detail:" + item.getId());
            refs.add("WMS:wms_in_notice:" + item.getNoticeId());
        });
        inboundDetails.forEach(item -> {
            refs.add("WMS:wms_in_bill_detail:" + item.getId());
            refs.add("WMS:wms_in_bill:" + item.getBillId());
        });
        stocks.forEach(item -> refs.add("WMS:wms_live_stock:" + item.getId()));

        WmsPurchaseReceiptAvailabilityDTO.Evidence evidence = new WmsPurchaseReceiptAvailabilityDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("按source_receipt_id精确读取采购入库通知、入库单明细和当前正库存；只有state=2的WMS入库单作为正式入库回执，当前库存可能因后续领用减少，不能反推历史未入库。")
                .setFactoryCode(scope.factoryCode()).setReceiptId(String.valueOf(receiptId))
                .setObservedAt(LocalDateTime.now()).setNoticeLineCount(noticeCount)
                .setInboundLineCount(inboundCount).setApprovedInboundLineCount(approvedInbound)
                .setCurrentStockRowCount(stockCount)
                .setNoticeLines(noticeDetails.stream().map(item -> notice(item, notices.get(item.getNoticeId()))).toList())
                .setInboundLines(inboundDetails.stream().map(item -> inbound(item, bills.get(item.getBillId()))).toList())
                .setCurrentStocks(stocks.stream().map(this::stock).toList())
                .setDataGaps(gaps)
                .setLimitations(List.of("当前库存为观察时点余额，后续领用或移动会减少原来源库存行",
                        "是否满足某张生产订单齐套仍由PLAN/MRP按需求和预留重新计算"))
                .setEvidenceRefs(List.copyOf(refs)).setTruncated(truncated);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private Map<Long, WmsInNoticeEntity> notices(List<WmsInNoticeDetailEntity> details,
                                                 WmsAgentFactoryAccessService.Scope scope) {
        List<Long> ids = details.stream().map(WmsInNoticeDetailEntity::getNoticeId).filter(Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return noticeMapper.selectList(new LambdaQueryWrapper<WmsInNoticeEntity>()
                        .eq(WmsInNoticeEntity::getTenantId, scope.tenantId())
                        .eq(WmsInNoticeEntity::getFactoryCode, scope.factoryCode())
                        .in(WmsInNoticeEntity::getId, ids))
                .stream().collect(Collectors.toMap(WmsInNoticeEntity::getId, Function.identity()));
    }

    private Map<Long, WmsInBillEntity> bills(List<WmsInBillDetailEntity> details,
                                             WmsAgentFactoryAccessService.Scope scope) {
        List<Long> ids = details.stream().map(WmsInBillDetailEntity::getBillId).filter(Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return inBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                        .eq(WmsInBillEntity::getTenantId, scope.tenantId())
                        .eq(WmsInBillEntity::getFactoryCode, scope.factoryCode())
                        .in(WmsInBillEntity::getId, ids))
                .stream().collect(Collectors.toMap(WmsInBillEntity::getId, Function.identity()));
    }

    private List<String> gaps(Long receiptId, long noticeCount, long inboundCount,
                              List<WmsInNoticeDetailEntity> noticeDetails,
                              List<WmsInBillDetailEntity> inboundDetails,
                              Map<Long, WmsInBillEntity> bills) {
        List<String> gaps = new ArrayList<>();
        if (noticeCount == 0) gaps.add("采购收料单" + receiptId + "尚无WMS采购入库通知来源");
        if (inboundCount == 0) gaps.add("采购收料单" + receiptId + "尚无WMS正式入库明细");
        for (WmsInNoticeDetailEntity item : noticeDetails) {
            if (item.getSourceReceiptDetailId() == null || item.getSourceOrderDetailId() == null)
                gaps.add("入库通知明细" + item.getId() + "缺少收料批次或采购订单行来源");
        }
        for (WmsInBillDetailEntity item : inboundDetails) {
            WmsInBillEntity bill = bills.get(item.getBillId());
            if (bill == null) gaps.add("入库明细" + item.getId() + "缺少当前工厂入库主单");
            else if (!"2".equals(bill.getState()))
                gaps.add("入库单" + bill.getBillNo() + "尚未审核通过");
            if (item.getSourceReceiptDetailId() == null || item.getSourceOrderDetailId() == null)
                gaps.add("入库明细" + item.getId() + "缺少收料批次或采购订单行来源");
        }
        return gaps;
    }

    private WmsPurchaseReceiptAvailabilityDTO.NoticeLine notice(WmsInNoticeDetailEntity item,
                                                                WmsInNoticeEntity header) {
        return new WmsPurchaseReceiptAvailabilityDTO.NoticeLine()
                .setNoticeId(text(item.getNoticeId())).setNoticeDetailId(text(item.getId()))
                .setNoticeState(header == null ? null : header.getState())
                .setReceiptBatchId(text(item.getSourceReceiptDetailId()))
                .setPurchaseOrderDetailId(text(item.getSourceOrderDetailId()))
                .setMaterialCode(item.getMatCode()).setMaterialBatch(item.getMatBatch())
                .setMaterialUnit(item.getMatUnit()).setNoticeQuantity(decimal(item.getNoticeInNum()))
                .setInboundQuantity(decimal(item.getInNum())).setPendingQuantity(decimal(item.getNotInNum()))
                .setQualityTaskId(text(item.getQualityTaskId())).setQualityResult(item.getQualityResult());
    }

    private WmsPurchaseReceiptAvailabilityDTO.InboundLine inbound(WmsInBillDetailEntity item,
                                                                  WmsInBillEntity header) {
        return new WmsPurchaseReceiptAvailabilityDTO.InboundLine()
                .setInboundBillId(text(item.getBillId())).setInboundBillNo(header == null ? null : header.getBillNo())
                .setInboundState(header == null ? null : header.getState()).setInboundDetailId(text(item.getId()))
                .setReceiptBatchId(text(item.getSourceReceiptDetailId()))
                .setPurchaseOrderDetailId(text(item.getSourceOrderDetailId()))
                .setMaterialCode(item.getMatCode()).setMaterialBatch(item.getMatBatch())
                .setMaterialUnit(item.getMatUnit()).setInboundQuantity(decimal(item.getMatNum()))
                .setWarehouseCode(item.getWarehouseCode()).setLocationCode(item.getSiteCode())
                .setQualityTaskId(text(item.getQualityTaskId())).setQualityResult(item.getQualityResult())
                .setInboundTime(item.getInTime());
    }

    private WmsPurchaseReceiptAvailabilityDTO.StockLine stock(WmsLiveStockEntity item) {
        return new WmsPurchaseReceiptAvailabilityDTO.StockLine()
                .setStockId(text(item.getId())).setReceiptBatchId(text(item.getSourceReceiptDetailId()))
                .setPurchaseOrderDetailId(text(item.getSourceOrderDetailId()))
                .setMaterialCode(item.getMatCode()).setMaterialBatch(item.getMatBatch())
                .setMaterialUnit(item.getMatUnit()).setCurrentQuantity(decimal(item.getMatNum()))
                .setWarehouseCode(item.getWarehouseCode()).setLocationCode(item.getSiteCode())
                .setLockStatus(item.getStatus()).setQualityStatus(item.getQualityStatus())
                .setQualityResult(item.getQualityResult());
    }

    private String fingerprint(WmsPurchaseReceiptAvailabilityDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(), evidence.getReceiptId(),
                    evidence.getNoticeLines(), evidence.getInboundLines(), evidence.getCurrentStocks(),
                    evidence.getDataGaps(), evidence.getTruncated()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("WMS采购入库事实无法生成完整指纹");
        }
    }

    private Long positiveId(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new CheckedException("采购收料单主键无效");
        }
    }

    private String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }
}
