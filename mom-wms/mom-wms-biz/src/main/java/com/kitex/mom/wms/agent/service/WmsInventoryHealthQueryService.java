package com.kitex.mom.wms.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import com.kitex.mom.wms.agent.mapper.WmsAgentInventoryHealthMapper;
import com.kitex.mom.wms.agent.model.WmsInventoryHealthRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 计算实时库存、两类活动预留、质量限制、效期和库龄的库存健康证据。
 * 数量始终按库存单位分组，禁止跨单位汇总为一个虚假总量。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryHealthQueryService {

    public static final String TOOL_CODE = "WMS_INVENTORY_HEALTH_QUERY";

    private final WmsAgentFactoryAccessService factoryAccessService;
    private final WmsAgentInventoryHealthMapper inventoryHealthMapper;

    public WmsInventoryHealthDTO.Evidence query(WmsInventoryHealthDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) {
            throw new CheckedException("WMS库存健康查询必须指定工厂");
        }
        int nearExpiryDays = query.getNearExpiryDays() == null ? 30 : query.getNearExpiryDays();
        int slowMovingDays = query.getSlowMovingDays() == null ? 90 : query.getSlowMovingDays();
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        if (nearExpiryDays < 1 || nearExpiryDays > 3650
                || slowMovingDays < 1 || slowMovingDays > 3650 || limit < 1 || limit > 50) {
            throw new CheckedException("WMS库存健康查询阈值超出允许范围");
        }

        WmsAgentFactoryAccessService.Scope scope = factoryAccessService.requireRead(query.getFactoryCode());
        LocalDateTime observedAt = LocalDateTime.now();
        LocalDate today = observedAt.toLocalDate();
        LocalDate nearExpiryEnd = today.plusDays(nearExpiryDays);
        LocalDateTime slowBefore = observedAt.minusDays(slowMovingDays);

        WmsInventoryHealthRow.Summary summaryRow = inventoryHealthMapper.selectSummary(
                scope.tenantId(), scope.factoryCode(), today, nearExpiryEnd, slowBefore);
        List<WmsInventoryHealthDTO.UnitSummary> unitSummaries = inventoryHealthMapper.selectUnitSummaries(
                        scope.tenantId(), scope.factoryCode(), today, nearExpiryEnd, slowBefore).stream()
                .map(this::mapUnit).toList();
        List<WmsInventoryHealthRow.ExceptionStock> collected = inventoryHealthMapper.selectExceptions(
                scope.tenantId(), scope.factoryCode(), today, nearExpiryEnd, slowBefore, limit + 1);
        boolean truncated = collected.size() > limit;
        List<WmsInventoryHealthDTO.ExceptionStock> exceptions = collected.stream()
                .limit(limit).map(this::mapException).toList();

        return new WmsInventoryHealthDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("实物量按实时库存行统计；可用量为通过锁定/效期/质量校验后的实物量，逐行扣除活动生产预留与销售发货预留并截断为非负；所有数量按库存单位分别汇总")
                .setFactoryCode(scope.factoryCode()).setObservedDate(today).setObservedAt(observedAt)
                .setNearExpiryDays(nearExpiryDays).setSlowMovingDays(slowMovingDays)
                .setSummary(mapSummary(summaryRow)).setUnitSummaries(unitSummaries)
                .setExceptionStocks(exceptions).setTruncated(truncated)
                .setDataGaps(List.of(
                        "库存健康可用量尚未扣除物料安全库存，因此不能直接作为跨仓可调拨量；正式调拨仍由WMS调拨服务重新校验安全库存",
                        "没有统一单位换算关系时禁止跨单位合计，MISSING_UNIT数量必须先补齐主数据",
                        "本场景读取实时库存，不把拣货区、调拨在途和未完成入库单数量并入实物库存",
                        "呆滞判断使用库存标记或当前阈值下的入库库龄，不替代企业物料分类和周转策略"))
                .setEvidenceRefs(List.of(
                        "mom_wms.wms_live_stock(tenant_id,factory_code,warehouse_code,site_code,mat_code,mat_batch,mat_unit,mat_num)",
                        "mom_wms.wms_material_reservation[_detail](active production reservation)",
                        "mom_wms.wms_sales_stock_reservation[_detail](active shipment reservation)",
                        "mom_wms.wms_live_stock(status,quality_status,quality_result,mat_valid_end_date,in_time)"));
    }

    private WmsInventoryHealthDTO.Summary mapSummary(WmsInventoryHealthRow.Summary row) {
        if (row == null) {
            return new WmsInventoryHealthDTO.Summary();
        }
        return new WmsInventoryHealthDTO.Summary()
                .setStockRowCount(row.getStockRowCount()).setMaterialCount(row.getMaterialCount())
                .setWarehouseCount(row.getWarehouseCount()).setLockedRowCount(row.getLockedRowCount())
                .setQualityRestrictedRowCount(row.getQualityRestrictedRowCount())
                .setExpiredRowCount(row.getExpiredRowCount()).setNearExpiryRowCount(row.getNearExpiryRowCount())
                .setSlowMovingRowCount(row.getSlowMovingRowCount()).setNegativeRowCount(row.getNegativeRowCount())
                .setOverReservedRowCount(row.getOverReservedRowCount())
                .setMissingUnitRowCount(row.getMissingUnitRowCount())
                .setMissingLocationRowCount(row.getMissingLocationRowCount());
    }

    private WmsInventoryHealthDTO.UnitSummary mapUnit(WmsInventoryHealthRow.Unit row) {
        return new WmsInventoryHealthDTO.UnitSummary()
                .setUnitCode(row.getUnitCode()).setStockRowCount(row.getStockRowCount())
                .setPhysicalNum(row.getPhysicalNum()).setUsablePhysicalNum(row.getUsablePhysicalNum())
                .setProductionReservedNum(row.getProductionReservedNum())
                .setSalesReservedNum(row.getSalesReservedNum()).setAvailableNum(row.getAvailableNum())
                .setLockedNum(row.getLockedNum()).setQualityRestrictedNum(row.getQualityRestrictedNum())
                .setExpiredNum(row.getExpiredNum()).setNearExpiryNum(row.getNearExpiryNum())
                .setSlowMovingNum(row.getSlowMovingNum());
    }

    private WmsInventoryHealthDTO.ExceptionStock mapException(WmsInventoryHealthRow.ExceptionStock row) {
        List<String> exceptionTypes = StrUtil.isBlank(row.getExceptionCodes()) ? List.of()
                : Arrays.stream(row.getExceptionCodes().split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
        return new WmsInventoryHealthDTO.ExceptionStock()
                .setStockId(row.getStockId()).setWarehouseCode(row.getWarehouseCode())
                .setWarehouseName(row.getWarehouseName()).setSiteCode(row.getSiteCode())
                .setSiteName(row.getSiteName()).setMatCode(row.getMatCode()).setMatName(row.getMatName())
                .setMatBatch(row.getMatBatch()).setMatUnit(row.getMatUnit()).setPhysicalNum(row.getPhysicalNum())
                .setProductionReservedNum(row.getProductionReservedNum())
                .setSalesReservedNum(row.getSalesReservedNum()).setAvailableNum(row.getAvailableNum())
                .setQualityStatus(row.getQualityStatus()).setQualityResult(row.getQualityResult())
                .setLockStatus(row.getLockStatus()).setInTime(row.getInTime())
                .setValidEndDate(row.getValidEndDate()).setExceptionTypes(exceptionTypes);
    }
}
