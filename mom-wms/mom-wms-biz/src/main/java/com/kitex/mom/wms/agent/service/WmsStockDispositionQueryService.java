package com.kitex.mom.wms.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.agent.dto.WmsStockDispositionDTO;
import com.kitex.mom.wms.agent.mapper.WmsAgentInventoryHealthMapper;
import com.kitex.mom.wms.agent.model.WmsInventoryHealthRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * 单条实时库存的权威只读取证。所有数量、质量和效期判断复用工厂库存健康的同一SQL口径。
 */
@Service
@RequiredArgsConstructor
public class WmsStockDispositionQueryService {

    public static final String TOOL_CODE = "WMS_STOCK_DISPOSITION_QUERY";

    private final WmsAgentFactoryAccessService factoryAccess;
    private final WmsAgentInventoryHealthMapper mapper;

    public WmsStockDispositionDTO.Evidence query(WmsStockDispositionDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) {
            throw new CheckedException("精确库存调查必须指定当前工厂");
        }
        long stockId = parseStockId(query.getStockId());
        int nearExpiryDays = query.getNearExpiryDays() == null ? 30 : query.getNearExpiryDays();
        int slowMovingDays = query.getSlowMovingDays() == null ? 90 : query.getSlowMovingDays();
        if (nearExpiryDays < 1 || nearExpiryDays > 3650 || slowMovingDays < 1 || slowMovingDays > 3650) {
            throw new CheckedException("精确库存调查阈值超出允许范围");
        }
        WmsAgentFactoryAccessService.Scope scope = factoryAccess.requireRead(query.getFactoryCode());
        LocalDateTime observedAt = LocalDateTime.now();
        WmsInventoryHealthRow.ExceptionStock row = mapper.selectStockById(scope.tenantId(),
                scope.factoryCode(), null, stockId, observedAt.toLocalDate(),
                observedAt.toLocalDate().plusDays(nearExpiryDays), observedAt.minusDays(slowMovingDays));
        if (row == null || row.getStockId() == null || row.getStockId() != stockId) {
            throw new CheckedException("当前租户/工厂不存在这条活动实时库存；不得按物料或批次猜测另一条库存");
        }
        List<String> exceptions = StrUtil.isBlank(row.getExceptionCodes()) ? List.of()
                : Arrays.stream(row.getExceptionCodes().split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
        List<String> dataGaps = new ArrayList<>();
        if (StrUtil.isBlank(row.getWarehouseCode())) dataGaps.add("库存行缺少仓库编码");
        if (StrUtil.isBlank(row.getSiteCode())) dataGaps.add("库存行缺少库位编码");
        if (StrUtil.isBlank(row.getMatCode())) dataGaps.add("库存行缺少物料编码");
        if (StrUtil.isBlank(row.getMatUnit())) dataGaps.add("库存行缺少数量单位");
        if (row.getLockStatus() == null) dataGaps.add("库存行原始锁定状态为空");
        if (StrUtil.isBlank(row.getQualityStatus()) && StrUtil.isBlank(row.getQualityResult()))
            dataGaps.add("库存行缺少独立质量状态与检验结论，不能据此判断已正式放行");
        WmsStockDispositionDTO.Stock stock = new WmsStockDispositionDTO.Stock()
                .setWarehouseCode(row.getWarehouseCode()).setWarehouseName(row.getWarehouseName())
                .setSiteCode(row.getSiteCode()).setSiteName(row.getSiteName())
                .setContainerCode(row.getContainerCode())
                .setMaterialCode(row.getMatCode()).setMaterialName(row.getMatName())
                .setMaterialBatch(row.getMatBatch()).setMaterialBarcode(row.getMatBarcode())
                .setUnitCode(row.getMatUnit()).setSupplierCode(row.getSupplierCode())
                .setSupplierName(row.getSupplierName()).setPhysicalNum(row.getPhysicalNum())
                .setProductionReservedNum(row.getProductionReservedNum())
                .setSalesReservedNum(row.getSalesReservedNum()).setAvailableNum(row.getAvailableNum())
                .setQualityStatus(row.getQualityStatus()).setQualityResult(row.getQualityResult())
                .setQualityHoldNo(row.getQualityHoldNo()).setQualitySourceType(row.getQualitySourceType())
                .setQualitySourceId(row.getQualitySourceId()).setLockStatus(row.getLockStatus())
                .setLocked(row.getLocked()).setQualityUsable(row.getQualityOk())
                .setExpired(row.getExpired()).setNearExpiry(row.getNearExpiry())
                .setSlowMoving(row.getSlowMoving())
                .setInTime(row.getInTime()).setValidEndDate(row.getValidEndDate())
                .setStockUpdatedAt(row.getStockUpdatedAt());
        WmsStockDispositionDTO.Summary summary = new WmsStockDispositionDTO.Summary()
                .setStockRowCount(1L).setExceptionCount((long) exceptions.size())
                .setPhysicalNum(nonNull(row.getPhysicalNum()))
                .setProductionReservedNum(nonNull(row.getProductionReservedNum()))
                .setSalesReservedNum(nonNull(row.getSalesReservedNum()))
                .setAvailableNum(nonNull(row.getAvailableNum()));
        return new WmsStockDispositionDTO.Evidence()
                .setToolCode(TOOL_CODE).setFactoryCode(scope.factoryCode())
                .setStockId(Long.toString(stockId)).setObservedAt(observedAt)
                .setFactFingerprint(fingerprint(scope, row))
                .setMetricDefinition("单条实时库存按库存单位统计；可用量先校验锁定、质量和效期，再扣活动生产与销售预留，截断为非负")
                .setStock(stock).setSummary(summary).setExceptionTypes(exceptions)
                .setDataGaps(dataGaps)
                .setLimitations(List.of(
                        "本结果只描述当前库存行，不授权质量放行、调拨、退料或报废",
                        "可用量尚未扣安全库存；正式调拨须由WMS在提交时重新校验",
                        "原库存行消失不能单独证明风险解除，须核对正式业务单据及去向"))
                .setEvidenceRefs(List.of(
                        "WMS:wms_live_stock:" + stockId,
                        "WMS:wms_material_reservation_detail:live_stock_id=" + stockId,
                        "WMS:wms_sales_stock_reservation_detail:live_stock_id=" + stockId));
    }

    private long parseStockId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new CheckedException("精确库存主键必须是正整数文本，不能使用物料或批次代替");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new CheckedException("精确库存主键超出数据库bigint范围");
        }
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 长度前缀避免字段边界歧义，观察时间不入摘要；同一事实重采得到同一指纹。
     */
    private String fingerprint(WmsAgentFactoryAccessService.Scope scope,
                               WmsInventoryHealthRow.ExceptionStock row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : Arrays.asList(scope.tenantId(), scope.factoryCode(), row.getStockId(),
                    row.getWarehouseCode(), row.getSiteCode(), row.getContainerCode(),
                    row.getMatCode(), row.getMatBatch(),
                    row.getMatBarcode(), row.getMatUnit(), row.getPhysicalNum(),
                    row.getProductionReservedNum(), row.getSalesReservedNum(), row.getAvailableNum(),
                    row.getQualityStatus(), row.getQualityResult(), row.getQualityHoldNo(),
                    row.getQualitySourceType(), row.getQualitySourceId(), row.getLockStatus(),
                    row.getInTime(), row.getValidEndDate(), row.getStockUpdatedAt(), row.getExceptionCodes())) {
                digest.update((byte) (value == null ? 0 : 1));
                if (value != null) {
                    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("库存事实指纹计算失败", failure);
        }
    }
}
