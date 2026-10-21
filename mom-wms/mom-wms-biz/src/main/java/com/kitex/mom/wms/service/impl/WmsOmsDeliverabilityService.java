package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.enums.WarehouseTypeEnum;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsOmsDeliverabilitySnapshot;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.util.WmsStockAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 从WMS当前实物形成订单行质量净可交量；这是按承诺日模拟分配的只读证据，不创建库存预留。
 */
@Service
@RequiredArgsConstructor
public class WmsOmsDeliverabilityService {
    private static final Set<String> ACTIVE_STATES = Set.of(
            DeliverStateEnum.NOT_START.getCode(), DeliverStateEnum.CREATE.getCode(), DeliverStateEnum.ING.getCode());
    private final WmsDeliverGoodsPlanBaseService plans;
    private final WmsLiveStockService stocks;
    private final RemoteWarehouseService warehouses;
    private final WmsMaterialReservationService materialReservations;
    private final WmsSalesStockReservationService salesReservations;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsOmsDeliverabilitySnapshot snapshot(String orderCode, Long detailsId) {
        require(StrUtil.isNotBlank(orderCode) && orderCode.length() <= 100 && detailsId != null,
                "OMS质量净可交量查询缺少订单或交付行");
        List<WmsDeliverGoodsPlanBaseEntity> targets = plans.list(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, orderCode)
                        .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, detailsId)
                        .orderByAsc(WmsDeliverGoodsPlanBaseEntity::getId));
        WmsOmsDeliverabilitySnapshot result = new WmsOmsDeliverabilitySnapshot()
                .setOrderDetailId(detailsId).setOrderCode(orderCode).setObservedAt(LocalDateTime.now())
                .setAllocationPolicy("FORMAL_SALES_RESERVATION_FIRST; PROMISED_DATE_THEN_PLAN_ID_FOR_FREE_POOL");
        if (targets.isEmpty()) return result.setStatus("NOT_STARTED")
                .setMessage("尚未形成WMS来源发货计划，不能把全厂同产品库存归属于本订单行");
        if (targets.size() != 1) return result.setStatus("INCONSISTENT")
                .setMessage("同一OMS交付行存在多条WMS来源发货计划，须先治理来源唯一性");
        WmsDeliverGoodsPlanBaseEntity target = targets.get(0);
        requireFactory(target.getFactoryCode());
        result.setFactoryCode(target.getFactoryCode()).setProductCode(target.getProductCode());
        if (StrUtil.isBlank(target.getProductCode()) || target.getDeliverNum() == null
                || target.getDeliveredNum() == null || target.getDeliveredNum().signum() < 0
                || target.getDeliveredNum().compareTo(target.getDeliverNum()) > 0) {
            return result.setStatus("INCONSISTENT").setMessage("WMS来源发货计划产品或数量事实不完整");
        }
        List<WmsLiveStockEntity> productStocks = stocks.getLiveStockByProductCodeList(
                Set.of(target.getProductCode()), null);
        require(productStocks.size() <= 5000, "同产品WMS库存明细超过5000行，拒绝截断质量净可交证据");
        R<List<MdmWarehouseEntity>> warehouseResponse = warehouses.getEnabledProductWarehouses();
        require(warehouseResponse != null && warehouseResponse.isOk() && warehouseResponse.getData() != null,
                "MDM未返回当前工厂启用成品仓，不能判定销售可发库存");
        Set<String> eligibleWarehouses = new HashSet<>();
        for (MdmWarehouseEntity warehouse : warehouseResponse.getData()) {
            require(warehouse != null && Objects.equals(warehouse.getFactoryCode(), target.getFactoryCode())
                            && Objects.equals(warehouse.getWarehouseType(), WarehouseTypeEnum.PRODUCT.getCode())
                            && Boolean.TRUE.equals(warehouse.getStatus()) && StrUtil.isNotBlank(warehouse.getWarehouseCode()),
                    "MDM成品仓返回了跨工厂、停用或非法仓库");
            require(eligibleWarehouses.add(warehouse.getWarehouseCode()), "MDM成品仓编码重复，无法形成唯一可发范围");
        }
        require(!eligibleWarehouses.isEmpty(), "当前工厂没有启用的成品仓，不能计算销售可发库存");
        BigDecimal physical = BigDecimal.ZERO, qualified = BigDecimal.ZERO, qualityHeld = BigDecimal.ZERO;
        BigDecimal otherWarehouse = BigDecimal.ZERO, locked = BigDecimal.ZERO, expired = BigDecimal.ZERO;
        BigDecimal productionReserved = BigDecimal.ZERO, salesReserved = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        Set<Long> stockIds = productStocks.stream().map(WmsLiveStockEntity::getId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> productionByStock = materialReservations.activeReservedNums(stockIds);
        Map<Long, BigDecimal> salesByStock = salesReservations.activeReservedNums(stockIds);
        Set<String> units = new TreeSet<>();
        boolean missingUnit = false;
        for (WmsLiveStockEntity stock : productStocks) {
            BigDecimal quantity = quantity(stock.getMatNum());
            require(quantity.signum() >= 0, "WMS存在负数实时库存，无法计算质量净可交量");
            if (quantity.signum() == 0) continue;
            physical = physical.add(quantity);
            BigDecimal rowProduction = productionByStock.getOrDefault(stock.getId(), BigDecimal.ZERO);
            BigDecimal rowSales = salesByStock.getOrDefault(stock.getId(), BigDecimal.ZERO);
            require(rowProduction.signum() >= 0 && rowSales.signum() >= 0
                            && rowProduction.add(rowSales).compareTo(quantity) <= 0,
                    "WMS库存行生产与销售活动预留超过实物数量，拒绝输出可交量");
            productionReserved = productionReserved.add(rowProduction);
            salesReserved = salesReserved.add(rowSales);
            if (!Objects.equals(stock.getFactoryCode(), target.getFactoryCode()))
                throw new CheckedException("WMS同租户产品库存混入其他工厂，拒绝跨工厂计算可交量");
            if (!eligibleWarehouses.contains(stock.getWarehouseCode())) {
                otherWarehouse = otherWarehouse.add(quantity);
                continue;
            }
            if (StrUtil.isBlank(stock.getMatUnit())) missingUnit = true;
            else units.add(stock.getMatUnit().trim());
            if (!Objects.equals(stock.getStatus(), 0)) {
                locked = locked.add(quantity);
                continue;
            }
            if (stock.getMatValidEndDate() != null && stock.getMatValidEndDate().isBefore(LocalDate.now())) {
                expired = expired.add(quantity);
                continue;
            }
            if (!WmsStockAvailability.isUsable(stock)) {
                qualityHeld = qualityHeld.add(quantity);
                continue;
            }
            qualified = qualified.add(quantity);
            BigDecimal rowAvailable = quantity.subtract(rowProduction).subtract(rowSales).max(BigDecimal.ZERO);
            available = available.add(rowAvailable);
        }
        result.setPhysicalQuantity(physical).setQualifiedQuantity(qualified).setQualityHeldQuantity(qualityHeld)
                .setOtherWarehouseQuantity(otherWarehouse)
                .setLockedQuantity(locked).setExpiredQuantity(expired)
                .setProductionReservedQuantity(productionReserved).setSalesReservedQuantity(salesReserved)
                .setReservedQuantity(productionReserved.add(salesReserved))
                .setQualifiedAvailableQuantity(available).setStockLineCount(productStocks.size());
        BigDecimal eligiblePhysical = physical.subtract(otherWarehouse);
        if (eligiblePhysical.signum() > 0 && (missingUnit || units.size() != 1)) return result.setStatus("INCONSISTENT")
                .setMessage("WMS正库存单位缺失或不唯一，不能与订单数量安全换算");
        if (!units.isEmpty()) result.setUnitCode(units.iterator().next());
        BigDecimal targetRemaining = remaining(target);
        if (!ACTIVE_STATES.contains(target.getDeliverState())) {
            if (!Set.of(DeliverStateEnum.COMPLETE.getCode(), DeliverStateEnum.CLOSE.getCode())
                    .contains(target.getDeliverState())) {
                return result.setStatus("INCONSISTENT").setMessage("WMS来源发货计划状态非法，无法判定库存竞争顺序");
            }
            if (targetRemaining.signum() > 0) {
                return result.setOrderRemainingQuantity(targetRemaining).setStatus("INCONSISTENT")
                        .setMessage("WMS来源发货计划已结束但仍有未交数量，须先治理交付状态");
            }
            return result.setPriorCommittedQuantity(BigDecimal.ZERO).setOrderRemainingQuantity(BigDecimal.ZERO)
                    .setNetDeliverableQuantity(BigDecimal.ZERO).setShortageQuantity(BigDecimal.ZERO)
                    .setCompetingPlanCount(0).setStatus("AVAILABLE")
                    .setMessage("订单行已交付完成，无需参与当前库存竞争；库存分解仅作完结时点查询证据");
        }
        List<WmsDeliverGoodsPlanBaseEntity> active = plans.list(
                new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getFactoryCode, target.getFactoryCode())
                        .eq(WmsDeliverGoodsPlanBaseEntity::getProductCode, target.getProductCode())
                        .in(WmsDeliverGoodsPlanBaseEntity::getDeliverState, ACTIVE_STATES)
                        .orderByAsc(WmsDeliverGoodsPlanBaseEntity::getPlanDeliverTime)
                        .orderByAsc(WmsDeliverGoodsPlanBaseEntity::getId));
        require(active.size() <= 5000, "同产品活动发货计划超过5000条，拒绝截断订单竞争证据");
        Comparator<WmsDeliverGoodsPlanBaseEntity> priority = Comparator
                .comparing(WmsDeliverGoodsPlanBaseEntity::getPlanDeliverTime,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(WmsDeliverGoodsPlanBaseEntity::getId);
        active.sort(priority);
        Set<Long> activePlanIds = active.stream().map(WmsDeliverGoodsPlanBaseEntity::getId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> healthySalesByPlan = salesReservations.healthyActiveReservedByPlanIds(activePlanIds);
        BigDecimal ownReserved = healthySalesByPlan.getOrDefault(target.getId(), BigDecimal.ZERO)
                .min(targetRemaining);
        BigDecimal prior = BigDecimal.ZERO;
        boolean found = false;
        for (WmsDeliverGoodsPlanBaseEntity plan : active) {
            BigDecimal remaining = remaining(plan);
            if (Objects.equals(plan.getId(), target.getId())) {
                found = true;
                break;
            }
            prior = prior.add(remaining.subtract(healthySalesByPlan.getOrDefault(plan.getId(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO));
        }
        if (!found && targetRemaining.signum() > 0)
            return result.setStatus("INCONSISTENT").setMessage("目标发货计划未进入活动计划竞争集合");
        BigDecimal targetUnreserved = targetRemaining.subtract(ownReserved).max(BigDecimal.ZERO);
        BigDecimal net = ownReserved.add(available.subtract(prior).max(BigDecimal.ZERO).min(targetUnreserved));
        result.setCurrentOrderReservedQuantity(ownReserved);
        result.setPriorCommittedQuantity(prior).setOrderRemainingQuantity(targetRemaining)
                .setNetDeliverableQuantity(net).setShortageQuantity(targetRemaining.subtract(net))
                .setCompetingPlanCount(Math.max(0, active.size() - 1)).setStatus("AVAILABLE")
                .setMessage("先计入本单健康正式销售预留，再以扣除生产/销售预留后的自由合格池按承诺日、计划ID模拟分配");
        return result;
    }

    private BigDecimal remaining(WmsDeliverGoodsPlanBaseEntity plan) {
        require(plan.getDeliverNum() != null && plan.getDeliveredNum() != null
                        && plan.getDeliverNum().signum() >= 0 && plan.getDeliveredNum().signum() >= 0
                        && plan.getDeliveredNum().compareTo(plan.getDeliverNum()) <= 0,
                "活动发货计划存在非法数量，无法计算订单竞争");
        return plan.getDeliverNum().subtract(plan.getDeliveredNum());
    }

    private BigDecimal quantity(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private void requireFactory(String factory) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(current) && Objects.equals(current, factory), "当前工厂无权读取该交付可用量");
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
