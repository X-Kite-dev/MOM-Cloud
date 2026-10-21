package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.wms.dto.WmsSupplierReturnCommand;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.OtherOutBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeHandler;
import com.kitex.mom.wms.util.WmsStockAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 采购到货后退供的 WMS 资格核验和出库通知创建。
 *
 * <p>这里只接收 PMS 已复核的冻结命令。每条命令必须指向一条精确实时库存，
 * 且质量、效期、库位、供应商和两类活动预留在批准时均未漂移。</p>
 */
@Service
@RequiredArgsConstructor
public class WmsSupplierReturnService {
    public static final String DOCUMENT_TABLE = "mom_pms.pms_return";

    private final WmsLiveStockMapper stockMapper;
    private final WmsOutNoticeMapper noticeMapper;
    private final WmsMaterialReservationService materialReservationService;
    private final WmsSalesStockReservationService salesReservationService;

    @Transactional(readOnly = true)
    public List<WmsSupplierReturnCommand.StockCandidate> eligibleStocks(
            WmsSupplierReturnCommand.StockQuery query) {
        require(query != null && StrUtil.isNotBlank(query.getOrderNo()) && query.getOrderDetailId() != null
                && StrUtil.isNotBlank(query.getMaterialCode()), "请选择采购订单行和采购物料");
        String factory = currentFactory();
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<WmsLiveStockEntity>()
                        .eq(WmsLiveStockEntity::getFactoryCode, factory)
                        .eq(WmsLiveStockEntity::getSourceOrderNo, query.getOrderNo().trim())
                        .eq(WmsLiveStockEntity::getSourceOrderDetailId, query.getOrderDetailId())
                        .eq(WmsLiveStockEntity::getMatCode, query.getMaterialCode().trim())
                        .eq(StrUtil.isNotBlank(query.getSupplierCode()), WmsLiveStockEntity::getSupplierCode,
                                StrUtil.trim(query.getSupplierCode()))
                        .eq(StrUtil.isNotBlank(query.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode,
                                StrUtil.trim(query.getWarehouseCode()))
                        .eq(StrUtil.isNotBlank(query.getBatchNo()), WmsLiveStockEntity::getMatBatch,
                                StrUtil.trim(query.getBatchNo()))
                        .eq(WmsLiveStockEntity::getStatus, 0)
                        .gt(WmsLiveStockEntity::getMatNum, 0)
                        .orderByAsc(WmsLiveStockEntity::getMatValidEndDate)
                        .orderByAsc(WmsLiveStockEntity::getInTime)
                        .last("LIMIT 500"));
        Set<Long> ids = stocks.stream().map(WmsLiveStockEntity::getId).collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> production = materialReservationService.activeReservedNums(ids);
        Map<Long, BigDecimal> sales = salesReservationService.activeReservedNums(ids);
        return stocks.stream().filter(WmsStockAvailability::isUsable)
                .map(stock -> candidate(stock, production.getOrDefault(stock.getId(), BigDecimal.ZERO),
                        sales.getOrDefault(stock.getId(), BigDecimal.ZERO)))
                .filter(row -> row.getReturnableQuantity().signum() > 0).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsSupplierReturnCommand.NoticeReceipt createNotice(
            WmsSupplierReturnCommand.CreateNotice command) {
        validateCommand(command);
        String factory = currentFactory();
        require(Objects.equals(factory, command.getFactoryCode()), "退供命令工厂与当前请求工厂不一致");

        WmsOutNoticeEntity existing = noticeMapper.selectOne(new LambdaQueryWrapper<WmsOutNoticeEntity>()
                .eq(WmsOutNoticeEntity::getDocumentTable, DOCUMENT_TABLE)
                .eq(WmsOutNoticeEntity::getDocumentId, command.getReturnId()).last("LIMIT 1"), false);
        if (existing != null) {
            Map<?, ?> expansion = JSONUtil.toBean(StrUtil.blankToDefault(existing.getExpansion(), "{}"), Map.class);
            require(Objects.equals(command.getSourceFingerprint(), expansion.get("sourceFingerprint")),
                    "该退供申请已经按另一组冻结事实生成WMS通知");
            return receipt(existing, command.getSourceFingerprint());
        }

        Map<Long, WmsLiveStockEntity> locked = new LinkedHashMap<>();
        Set<String> warehouses = new LinkedHashSet<>();
        for (WmsSupplierReturnCommand.NoticeLine line : command.getLines()) {
            validateLine(line);
            require(locked.put(line.getLiveStockId(), lockAndValidate(line, command.getOrderNo(), factory)) == null,
                    "同一库存行不能在一张退供申请中重复提交");
            warehouses.add(line.getWarehouseCode());
        }
        require(warehouses.size() == 1, "一张退供通知只能由同一仓库执行，请按仓库拆单");

        WmsLiveStockEntity first = locked.values().iterator().next();
        WmsOutNoticeEntity notice = new WmsOutNoticeEntity();
        notice.setWarehouseCode(first.getWarehouseCode());
        notice.setWarehouseName(first.getWarehouseName());
        notice.setBillType(OtherOutBillTypeEnum.OTHER_PMS.getCode());
        notice.setDocumentId(command.getReturnId());
        notice.setDocumentTable(DOCUMENT_TABLE);
        notice.setRemark("采购退供[" + command.getReturnNo() + "]：" + command.getReason());
        notice.setFactoryCode(factory);
        notice.setExpansion(JSONUtil.toJsonStr(Map.of(
                "requestId", command.getRequestId(),
                "returnNo", command.getReturnNo(),
                "orderNo", command.getOrderNo(),
                "sourceFingerprint", command.getSourceFingerprint())));
        List<WmsOutNoticeDetailEntity> details = new ArrayList<>();
        for (WmsSupplierReturnCommand.NoticeLine line : command.getLines()) {
            WmsLiveStockEntity stock = locked.get(line.getLiveStockId());
            WmsOutNoticeDetailEntity detail = new WmsOutNoticeDetailEntity();
            detail.setSourceStockId(stock.getId());
            detail.setMatCode(stock.getMatCode());
            detail.setMatName(stock.getMatName());
            detail.setMatSpec(stock.getMatSpec());
            detail.setMatUnit(stock.getMatUnit());
            detail.setMatGroup(stock.getMatGroup());
            detail.setMatGroupName(stock.getMatGroupName());
            detail.setMatProduceDate(stock.getMatProduceDate());
            detail.setMatValidPeriod(stock.getMatValidPeriod());
            detail.setMatValidEndDate(stock.getMatValidEndDate());
            detail.setNoticeOutNum(line.getReturnQuantity().doubleValue());
            detail.setDocumentId(line.getReturnDetailId());
            detail.setDocumentTable("mom_pms.pms_return_detail");
            detail.setWarehouseCode(stock.getWarehouseCode());
            detail.setWarehouseName(stock.getWarehouseName());
            detail.setFactoryCode(factory);
            detail.setExpansion(JSONUtil.toJsonStr(Map.of(
                    "orderDetailId", line.getOrderDetailId(),
                    "planNo", StrUtil.nullToEmpty(line.getPlanNo()),
                    "stockFingerprint", line.getStockFingerprint())));
            details.add(detail);
        }
        notice.setDetailList(details);
        WmsOutNoticeHandler<WmsOutNoticeEntity, WmsOutNoticeDetailEntity> handler =
                WmsOutNoticeFactory.getHandler(notice.getBillType());
        require(Boolean.TRUE.equals(handler.saveDeep(notice)), "WMS退供出库通知创建失败");
        return receipt(notice, command.getSourceFingerprint());
    }

    private WmsLiveStockEntity lockAndValidate(WmsSupplierReturnCommand.NoticeLine line,
                                               String orderNo, String factory) {
        WmsLiveStockEntity stock = stockMapper.selectOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getId, line.getLiveStockId())
                .eq(WmsLiveStockEntity::getFactoryCode, factory).last("FOR UPDATE"), false);
        require(stock != null && WmsStockAvailability.isUsable(stock), "冻结库存行已不存在或不再满足质量/效期要求");
        BigDecimal production = materialReservationService.activeReservedNum(stock.getId());
        BigDecimal sales = salesReservationService.activeReservedNums(Set.of(stock.getId()))
                .getOrDefault(stock.getId(), BigDecimal.ZERO);
        WmsSupplierReturnCommand.StockCandidate current = candidate(stock, production, sales);
        require(Objects.equals(line.getStockFingerprint(), current.getStockFingerprint()),
                "库存事实已变化，请退回申请并重新选择库存");
        require(Objects.equals(line.getMaterialCode(), stock.getMatCode())
                        && Objects.equals(line.getSupplierCode(), stock.getSupplierCode())
                        && Objects.equals(line.getWarehouseCode(), stock.getWarehouseCode())
                        && Objects.equals(orderNo, stock.getSourceOrderNo())
                        && Objects.equals(line.getOrderDetailId(), stock.getSourceOrderDetailId())
                        && Objects.equals(line.getSourceReceiptId(), stock.getSourceReceiptId())
                        && Objects.equals(line.getSourceReceiptDetailId(), stock.getSourceReceiptDetailId()),
                "退供行与冻结库存的采购订单、收料批次、物料、供应商或仓库不一致");
        require(current.getReturnableQuantity().compareTo(line.getReturnQuantity()) >= 0,
                "冻结库存扣除生产和销售预留后的可退数量不足");
        return stock;
    }

    private WmsSupplierReturnCommand.StockCandidate candidate(WmsLiveStockEntity stock,
                                                              BigDecimal production,
                                                              BigDecimal sales) {
        BigDecimal physical = decimal(stock.getMatNum());
        BigDecimal returnable = physical.subtract(production).subtract(sales).max(BigDecimal.ZERO);
        WmsSupplierReturnCommand.StockCandidate row = new WmsSupplierReturnCommand.StockCandidate()
                .setLiveStockId(stock.getId()).setSourceOrderNo(stock.getSourceOrderNo())
                .setSourceOrderDetailId(stock.getSourceOrderDetailId())
                .setSourceReceiptId(stock.getSourceReceiptId())
                .setSourceReceiptDetailId(stock.getSourceReceiptDetailId())
                .setMaterialCode(stock.getMatCode())
                .setMaterialName(stock.getMatName()).setMaterialSpec(stock.getMatSpec())
                .setMaterialUnit(stock.getMatUnit()).setSupplierCode(stock.getSupplierCode())
                .setSupplierName(stock.getSupplierName()).setWarehouseCode(stock.getWarehouseCode())
                .setWarehouseName(stock.getWarehouseName()).setSiteCode(stock.getSiteCode())
                .setSiteName(stock.getSiteName()).setContainerCode(stock.getContainerCode())
                .setBatchNo(stock.getMatBatch()).setQualityStatus(stock.getQualityStatus())
                .setQualityResult(stock.getQualityResult()).setValidEndDate(stock.getMatValidEndDate())
                .setStockInTime(stock.getInTime()).setPhysicalQuantity(physical)
                .setProductionReservedQuantity(production).setSalesReservedQuantity(sales)
                .setReturnableQuantity(returnable);
        row.setStockFingerprint(hash(JSONUtil.toJsonStr(Arrays.asList(
                stock.getId(), stock.getSourceOrderNo(), stock.getSourceOrderDetailId(),
                stock.getSourceReceiptId(), stock.getSourceReceiptDetailId(), stock.getMatCode(),
                Objects.toString(stock.getSupplierCode(), ""),
                stock.getWarehouseCode(), Objects.toString(stock.getSiteCode(), ""),
                Objects.toString(stock.getContainerCode(), ""),
                Objects.toString(stock.getMatBatch(), ""), Objects.toString(stock.getQualityStatus(), ""),
                Objects.toString(stock.getQualityResult(), ""), Objects.toString(stock.getMatValidEndDate(), ""),
                Objects.toString(stock.getInTime(), ""), number(physical), number(production), number(sales),
                number(returnable)))));
        return row;
    }

    private WmsSupplierReturnCommand.NoticeReceipt receipt(WmsOutNoticeEntity notice, String fingerprint) {
        return new WmsSupplierReturnCommand.NoticeReceipt().setReturnId(notice.getDocumentId())
                .setNoticeId(notice.getId()).setNoticeStatus(notice.getState())
                .setSourceFingerprint(fingerprint).setAcceptedTime(LocalDateTime.now());
    }

    private void validateCommand(WmsSupplierReturnCommand.CreateNotice command) {
        require(command != null && command.getReturnId() != null && validRequest(command.getRequestId())
                        && StrUtil.isNotBlank(command.getReturnNo()) && StrUtil.isNotBlank(command.getOrderNo())
                        && StrUtil.isNotBlank(command.getSupplierCode()) && StrUtil.isNotBlank(command.getReason())
                        && StrUtil.isNotBlank(command.getFactoryCode()) && StrUtil.isNotBlank(command.getSourceFingerprint())
                        && CollUtil.isNotEmpty(command.getLines()),
                "退供通知缺少申请、订单、供应商、工厂、冻结指纹或明细");
    }

    private void validateLine(WmsSupplierReturnCommand.NoticeLine line) {
        require(line != null && line.getReturnDetailId() != null && line.getOrderDetailId() != null
                        && line.getLiveStockId() != null && StrUtil.isNotBlank(line.getMaterialCode())
                        && line.getSourceReceiptId() != null && line.getSourceReceiptDetailId() != null
                        && StrUtil.isNotBlank(line.getSupplierCode()) && StrUtil.isNotBlank(line.getWarehouseCode())
                        && line.getReturnQuantity() != null && line.getReturnQuantity().signum() > 0
                        && StrUtil.isNotBlank(line.getStockFingerprint()),
                "退供明细缺少业务行、库存行、物料、供应商、仓库、数量或库存指纹");
    }

    private String currentFactory() {
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请求头必须提供当前工厂编码");
        return factory;
    }

    private boolean validRequest(String value) {
        return StrUtil.isNotBlank(value) && value.trim().length() <= 100;
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("库存事实指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
