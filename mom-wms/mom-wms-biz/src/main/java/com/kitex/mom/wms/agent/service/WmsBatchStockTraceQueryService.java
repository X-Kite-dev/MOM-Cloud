package com.kitex.mom.wms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.agent.dto.WmsBatchStockTraceDTO;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 指定物料批次在WMS当前实物库存中的可复核位置和质量状态。
 */
@Service
@RequiredArgsConstructor
public class WmsBatchStockTraceQueryService {

    public static final String TOOL_CODE = "WMS_BATCH_STOCK_TRACE_QUERY@1";
    private static final List<String> USABLE_QUALITY = List.of("QUALIFIED", "CONCESSION");

    private final WmsAgentFactoryAccessService factoryAccess;
    private final WmsLiveStockMapper stockMapper;

    public WmsBatchStockTraceDTO.Evidence query(WmsBatchStockTraceDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getMaterialCode())
                || !StringUtils.hasText(query.getBatchNo())) {
            throw new CheckedException("批次库存追溯必须提供完整物料编码和批次号");
        }
        WmsAgentFactoryAccessService.Scope scope = factoryAccess.requireRead(query.getFactoryCode());
        int limit = query.getDetailLimit() == null ? 50 : Math.max(1, Math.min(query.getDetailLimit(), 100));
        String materialCode = query.getMaterialCode().trim();
        String batchNo = query.getBatchNo().trim();
        QueryWrapper<WmsLiveStockEntity> base = base(scope.factoryCode(), materialCode, batchNo);
        long total = stockMapper.selectCount(base);
        long locked = stockMapper.selectCount(base(scope.factoryCode(), materialCode, batchNo).eq("status", 1));
        long restricted = stockMapper.selectCount(base(scope.factoryCode(), materialCode, batchNo)
                .and(q -> q.isNull("quality_status").or().notIn("quality_status", USABLE_QUALITY)));
        List<Map<String, Object>> unitRows = stockMapper.selectMaps(base(scope.factoryCode(), materialCode, batchNo)
                .select("COALESCE(NULLIF(mat_unit,''),'UNKNOWN') AS unit",
                        "SUM(mat_num) AS physical_quantity",
                        "SUM(CASE WHEN quality_hold_no IS NULL AND (status=0 OR (status=1 AND (quality_status='PENDING' OR is_quality_test='1'))) THEN mat_num ELSE 0 END) AS recall_freezable_quantity",
                        "SUM(CASE WHEN status=1 THEN mat_num ELSE 0 END) AS locked_quantity",
                        "SUM(CASE WHEN quality_status IS NULL OR quality_status NOT IN ('QUALIFIED','CONCESSION') THEN mat_num ELSE 0 END) AS quality_restricted_quantity")
                .groupBy("COALESCE(NULLIF(mat_unit,''),'UNKNOWN')")
                .orderByAsc("COALESCE(NULLIF(mat_unit,''),'UNKNOWN')"));
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(base(scope.factoryCode(), materialCode, batchNo)
                .orderByAsc("warehouse_code").orderByAsc("site_code").orderByAsc("id")
                .last("LIMIT " + limit));
        List<String> gaps = new ArrayList<>();
        if (total > stocks.size())
            gaps.add("正库存行共" + total + "条，本次返回" + stocks.size() + "条位置明细；单位汇总和异常行数仍为全部命中范围");
        if (unitRows.stream().anyMatch(row -> "UNKNOWN".equals(text(row.get("unit"))))) {
            gaps.add("存在缺少库存单位的批次库存，UNKNOWN组不得与其他单位合并");
        }
        if (stocks.stream().anyMatch(item -> !StringUtils.hasText(item.getWarehouseCode())
                || !StringUtils.hasText(item.getSiteCode())))
            gaps.add("部分库存缺少仓库或库位，召回执行前必须先治理位置");
        List<WmsBatchStockTraceDTO.UnitQuantity> units = unitRows.stream()
                .map(row -> new WmsBatchStockTraceDTO.UnitQuantity().setUnit(text(row.get("unit")))
                        .setPhysicalQuantity(decimal(row.get("physical_quantity")))
                        .setRecallFreezableQuantity(decimal(row.get("recall_freezable_quantity")))
                        .setLockedQuantity(decimal(row.get("locked_quantity")))
                        .setQualityRestrictedQuantity(decimal(row.get("quality_restricted_quantity"))))
                .toList();
        List<WmsBatchStockTraceDTO.StockPosition> positions = stocks.stream().map(this::map).toList();
        List<String> refs = stocks.stream().map(item -> "WMS:wms_live_stock:" + item.getId()).toList();
        return new WmsBatchStockTraceDTO.Evidence().setToolCode(TOOL_CODE)
                .setMetricDefinition("只读取观察时点mat_num>0的实时库存，按物料编码和批次精确匹配；物理量按库存单位分组，recallFreezableQuantity严格按WMS HOLD可选范围计算，锁定和质量限制分别披露，不代表在途或历史出库量。")
                .setFactoryCode(scope.factoryCode()).setMaterialCode(materialCode).setBatchNo(batchNo)
                .setObservedAt(LocalDateTime.now()).setPositiveStockRowCount(total)
                .setReturnedStockRowCount(stocks.size()).setLockedRowCount(locked)
                .setQualityRestrictedRowCount(restricted).setUnitQuantities(units).setStockPositions(positions)
                .setDataGaps(gaps).setEvidenceRefs(refs).setTruncated(total > stocks.size());
    }

    private QueryWrapper<WmsLiveStockEntity> base(String factory, String materialCode, String batchNo) {
        return new QueryWrapper<WmsLiveStockEntity>().eq("factory_code", factory).eq("mat_code", materialCode)
                .eq("mat_batch", batchNo).gt("mat_num", 0);
    }

    private WmsBatchStockTraceDTO.StockPosition map(WmsLiveStockEntity item) {
        return new WmsBatchStockTraceDTO.StockPosition().setStockId(item.getId()).setMaterialName(item.getMatName())
                .setSupplierCode(item.getSupplierCode()).setSupplierName(item.getSupplierName())
                .setWarehouseCode(item.getWarehouseCode()).setWarehouseName(item.getWarehouseName())
                .setLocationCode(item.getSiteCode()).setLocationName(item.getSiteName())
                .setContainerCode(item.getContainerCode()).setPhysicalQuantity(BigDecimal.valueOf(item.getMatNum()))
                .setUnit(item.getMatUnit()).setLockStatus(item.getStatus()).setQualityStatus(item.getQualityStatus())
                .setQualityResult(item.getQualityResult()).setQualityHoldNo(item.getQualityHoldNo())
                .setValidEndDate(item.getMatValidEndDate()).setInboundTime(item.getInTime());
    }

    private String text(Object value) {
        return Objects.toString(value, "");
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
