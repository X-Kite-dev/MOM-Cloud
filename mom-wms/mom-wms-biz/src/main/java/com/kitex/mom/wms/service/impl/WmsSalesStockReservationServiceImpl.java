package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.enums.WarehouseTypeEnum;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsSalesStockReservationCommand;
import com.kitex.mom.wms.dto.WmsSalesStockReservationSnapshot;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.service.WmsReservationMovementPolicyService;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WmsSalesStockReservationServiceImpl implements WmsSalesStockReservationService {
    public static final String PREFIX = WmsSalesStockReservationService.PREFIX;
    private static final String ACTIVE = "ACTIVE";
    private static final String CLOSED = "CLOSED";
    private static final Set<String> ACTIVE_PLAN_STATES = Set.of(
            DeliverStateEnum.NOT_START.getCode(), DeliverStateEnum.CREATE.getCode(), DeliverStateEnum.ING.getCode());
    private static final String POLICY = "PRODUCT_WAREHOUSE; QUALITY_USABLE; FEFO_THEN_FIFO; ROW_LOCKED";

    private final WmsSalesStockReservationMapper reservationMapper;
    private final WmsSalesStockReservationDetailMapper detailMapper;
    private final WmsSalesStockReservationOperationMapper operationMapper;
    private final WmsDeliverGoodsPlanBaseMapper planMapper;
    private final WmsDeliverGoodsDetailsMapper deliverDetailsMapper;
    private final WmsDeliverGoodsBaseMapper deliverBaseMapper;
    private final WmsLiveStockMapper stockMapper;
    private final WmsMaterialReservationDetailMapper materialDetailMapper;
    private final RemoteWarehouseService warehouseService;
    private final WmsReservationMovementPolicyService reservationMovementPolicyService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesStockReservationSnapshot reserve(WmsSalesStockReservationCommand command) {
        requireScopedOrganization();
        validateCommand(command, true);
        WmsDeliverGoodsPlanBaseEntity plan = lockPlan(command.getDeliverPlanId());
        require(ACTIVE_PLAN_STATES.contains(plan.getDeliverState()), "仅活动发货计划允许预留销售库存");
        requireNoOpenDelivery(plan);
        WmsSalesStockReservationOperationEntity replay = replay(command.getRequestId(), plan);
        if (replay != null) {
            require("RESERVE".equals(replay.getOperationType()), "请求号已被其他销售预留操作使用");
            require(Objects.equals(replay.getReason(), command.getReason().trim())
                            && (command.getQuantity() == null || replay.getQuantity().compareTo(command.getQuantity()) == 0),
                    "同请求号的销售预留载荷不一致");
            return snapshot(plan.getId());
        }
        reservationMovementPolicyService.ensureSalesReservable();
        WmsSalesStockReservationEntity reservation = lockReservation(plan.getId());
        String before = reservation == null ? null : JSONUtil.toJsonStr(snapshotOf(reservation));
        BigDecimal remainingOrder = quantity(plan.getDeliverNum()).subtract(quantity(plan.getDeliveredNum()));
        require(remainingOrder.signum() > 0, "发货计划已无剩余可交数量");
        BigDecimal activeBefore = reservation == null ? BigDecimal.ZERO : active(reservation);
        BigDecimal uncovered = remainingOrder.subtract(activeBefore).max(BigDecimal.ZERO);
        BigDecimal requested = command.getQuantity() == null ? uncovered : command.getQuantity();
        require(requested.signum() > 0 && requested.compareTo(uncovered) <= 0,
                "预留数量必须大于0且不得超过计划未覆盖数量" + uncovered.stripTrailingZeros().toPlainString());

        Set<String> eligibleWarehouses = eligibleWarehouses(plan.getFactoryCode());
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                        .eq(WmsLiveStockEntity::getFactoryCode, plan.getFactoryCode())
                        .eq(WmsLiveStockEntity::getMatCode, plan.getProductCode())
                        .in(WmsLiveStockEntity::getWarehouseCode, eligibleWarehouses)
                        .eq(WmsLiveStockEntity::getStatus, 0)
                        .gt(WmsLiveStockEntity::getMatNum, 0)
                        .and(q -> q.isNull(WmsLiveStockEntity::getMatValidEndDate)
                                .or().ge(WmsLiveStockEntity::getMatValidEndDate, LocalDate.now()))
                        .orderByAsc(WmsLiveStockEntity::getMatValidEndDate)
                        .orderByAsc(WmsLiveStockEntity::getInTime)
                        .orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE")).stream()
                .filter(WmsStockAvailability::isUsable).toList();
        require(stocks.size() <= 5000, "销售预留候选库存超过5000行，拒绝截断分配");
        Set<String> units = stocks.stream().filter(s -> quantity(s.getMatNum()).signum() > 0)
                .map(WmsLiveStockEntity::getMatUnit).filter(StrUtil::isNotBlank)
                .map(String::trim).collect(Collectors.toCollection(TreeSet::new));
        require(stocks.stream().noneMatch(s -> quantity(s.getMatNum()).signum() > 0 && StrUtil.isBlank(s.getMatUnit()))
                && units.size() == 1, "合格成品库存单位缺失或不唯一，禁止正式预留");
        Map<Long, BigDecimal> production = productionReserved(stockIds(stocks));
        Map<Long, BigDecimal> sales = activeReservedNums(stockIds(stocks));
        BigDecimal usable = stocks.stream().map(stock -> quantity(stock.getMatNum())
                        .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO))
                        .subtract(sales.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(usable.compareTo(requested) >= 0, "扣除生产和销售预留后仅可正式预留"
                + usable.stripTrailingZeros().toPlainString());

        boolean createdHeader = reservation == null;
        if (createdHeader) {
            reservation = new WmsSalesStockReservationEntity();
            reservation.setReservationNo(PREFIX + plan.getId());
            reservation.setDeliverPlanId(plan.getId());
            reservation.setDeliverPlanCode(plan.getDeliverPlanCode());
            reservation.setOrderCode(plan.getOrderCode());
            reservation.setOrderDetailId(plan.getOrderCodeDetails());
            reservation.setCustomerCode(plan.getCustomerCode());
            reservation.setProductCode(plan.getProductCode());
            reservation.setUnitCode(units.iterator().next());
            reservation.setTargetNum(remainingOrder);
            reservation.setReservedNum(requested);
            reservation.setConsumedNum(BigDecimal.ZERO);
            reservation.setReleasedNum(BigDecimal.ZERO);
            reservation.setStatus(ACTIVE);
            reservation.setAllocationPolicy(POLICY);
            reservation.setFactoryCode(plan.getFactoryCode());
            reservation.setLastRequestId(command.getRequestId());
            reservation.setLastOperationType("RESERVE");
            require(reservationMapper.insert(reservation) == 1, "销售库存预留主单保存失败");
        } else {
            validateSource(reservation, plan);
            reservation.setTargetNum(remainingOrder);
            reservation.setStatus(ACTIVE);
            reservation.setLastRequestId(command.getRequestId());
            reservation.setLastOperationType("RESERVE");
        }
        BigDecimal toAllocate = requested;
        for (WmsLiveStockEntity stock : stocks) {
            BigDecimal rowAvailable = quantity(stock.getMatNum())
                    .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO))
                    .subtract(sales.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            BigDecimal take = rowAvailable.min(toAllocate);
            if (take.signum() <= 0) {
                continue;
            }
            WmsSerialStockGuard.requireWhole(stock, take);
            insertDetail(reservation, plan, stock, take);
            toAllocate = toAllocate.subtract(take);
            if (toAllocate.signum() == 0) {
                break;
            }
        }
        require(toAllocate.signum() == 0, "销售库存预留并发分配未完整，请重试");
        if (!createdHeader) {
            reservation.setReservedNum(quantity(reservation.getReservedNum()).add(requested));
        }
        require(reservationMapper.updateById(reservation) == 1, "销售库存预留主单数量更新失败");
        audit(reservation, plan, command, "RESERVE", before, JSONUtil.toJsonStr(snapshotOf(reservation)), requested);
        return snapshotOf(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesStockReservationSnapshot release(WmsSalesStockReservationCommand command) {
        requireScopedOrganization();
        validateCommand(command, true);
        WmsDeliverGoodsPlanBaseEntity plan = lockPlan(command.getDeliverPlanId());
        requireNoOpenDelivery(plan);
        WmsSalesStockReservationOperationEntity replay = replay(command.getRequestId(), plan);
        if (replay != null) {
            require("RELEASE".equals(replay.getOperationType()), "请求号已被其他销售预留操作使用");
            require(Objects.equals(replay.getReason(), command.getReason().trim())
                            && (command.getQuantity() == null || replay.getQuantity().compareTo(command.getQuantity()) == 0),
                    "同请求号的销售预留释放载荷不一致");
            return snapshot(plan.getId());
        }
        WmsSalesStockReservationEntity reservation = lockReservation(plan.getId());
        require(reservation != null && active(reservation).signum() > 0, "该发货计划没有可释放的活动销售预留");
        validateSource(reservation, plan);
        String before = JSONUtil.toJsonStr(snapshotOf(reservation));
        BigDecimal requested = command.getQuantity() == null ? active(reservation) : command.getQuantity();
        require(requested.signum() > 0 && requested.compareTo(active(reservation)) <= 0,
                "释放数量必须大于0且不得超过活动预留");
        List<WmsSalesStockReservationDetailEntity> details = activeDetails(reservation.getId(), true);
        BigDecimal left = requested;
        ListIterator<WmsSalesStockReservationDetailEntity> iterator = details.listIterator(details.size());
        while (iterator.hasPrevious() && left.signum() > 0) {
            WmsSalesStockReservationDetailEntity detail = iterator.previous();
            BigDecimal take = detailActive(detail).min(left);
            if (take.signum() <= 0) {
                continue;
            }
            WmsLiveStockEntity stock = stockMapper.selectById(detail.getLiveStockId());
            require((stock != null && !Boolean.TRUE.equals(stock.getSerialManaged()))
                            || take.compareTo(detailActive(detail)) == 0,
                    "单件或缺失库存的预留必须整条释放");
            detail.setReleasedNum(quantity(detail.getReleasedNum()).add(take));
            if (detailActive(detail).signum() == 0) {
                detail.setStatus(CLOSED);
            }
            require(detailMapper.updateById(detail) == 1, "销售预留明细释放失败");
            left = left.subtract(take);
        }
        require(left.signum() == 0, "销售预留明细与主单数量不守恒，拒绝释放");
        reservation.setReleasedNum(quantity(reservation.getReleasedNum()).add(requested));
        refreshStatus(reservation);
        reservation.setLastRequestId(command.getRequestId());
        reservation.setLastOperationType("RELEASE");
        require(reservationMapper.updateById(reservation) == 1, "销售库存预留释放状态更新失败");
        audit(reservation, plan, command, "RELEASE", before, JSONUtil.toJsonStr(snapshotOf(reservation)), requested);
        return snapshotOf(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsSalesStockReservationSnapshot reallocateDrifted(WmsSalesStockReservationCommand command) {
        requireScopedOrganization();
        validateCommand(command, true);
        require(command.getQuantity() == null, "受控换批固定处理全部漂移量，不接受客户端指定数量");
        WmsDeliverGoodsPlanBaseEntity plan = lockPlan(command.getDeliverPlanId());
        require(ACTIVE_PLAN_STATES.contains(plan.getDeliverState()), "仅活动发货计划允许受控换批");
        requireNoOpenDelivery(plan);
        WmsSalesStockReservationOperationEntity replay = replay(command.getRequestId(), plan);
        if (replay != null) {
            require("REALLOCATE".equals(replay.getOperationType()), "请求号已被其他销售预留操作使用");
            require(Objects.equals(replay.getReason(), command.getReason().trim()),
                    "同请求号的销售预留换批载荷不一致");
            return snapshot(plan.getId());
        }
        WmsSalesStockReservationEntity reservation = lockReservation(plan.getId());
        require(reservation != null && active(reservation).signum() > 0,
                "该发货计划没有可换批的活动销售预留");
        validateSource(reservation, plan);
        String before = JSONUtil.toJsonStr(snapshotOf(reservation));
        Set<String> eligibleWarehouses = eligibleWarehouses(plan.getFactoryCode());
        List<WmsSalesStockReservationDetailEntity> productReservations = detailMapper.selectList(
                new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                        .eq(WmsSalesStockReservationDetailEntity::getFactoryCode, plan.getFactoryCode())
                        .eq(WmsSalesStockReservationDetailEntity::getProductCode, plan.getProductCode())
                        .eq(WmsSalesStockReservationDetailEntity::getStatus, ACTIVE)
                        .orderByAsc(WmsSalesStockReservationDetailEntity::getId).last("FOR UPDATE"));
        require(productReservations.size() <= 5000,
                "同产品活动销售预留明细超过5000行，拒绝截断换批证据");
        List<WmsSalesStockReservationDetailEntity> details = productReservations.stream()
                .filter(detail -> Objects.equals(detail.getReservationId(), reservation.getId())).toList();
        require(!details.isEmpty(), "销售预留主账存在活动余额但没有活动冻结明细");
        List<WmsLiveStockEntity> stocks = stockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getFactoryCode, plan.getFactoryCode())
                .eq(WmsLiveStockEntity::getMatCode, plan.getProductCode())
                .orderByAsc(WmsLiveStockEntity::getMatValidEndDate)
                .orderByAsc(WmsLiveStockEntity::getInTime)
                .orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
        require(stocks.size() <= 5000, "销售预留换批候选库存超过5000行，拒绝截断分配");
        Map<Long, WmsLiveStockEntity> stockById = stocks.stream().collect(Collectors.toMap(
                WmsLiveStockEntity::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
        Set<Long> stockIds = stockIds(stocks);
        Map<Long, BigDecimal> production = productionReserved(stockIds);
        Map<Long, BigDecimal> physicalRemaining = new HashMap<>();
        for (WmsLiveStockEntity stock : stocks) {
            BigDecimal physical = quantity(stock.getMatNum());
            require(physical.signum() >= 0, "WMS存在负数实时库存，禁止受控换批");
            physicalRemaining.put(stock.getId(), physical
                    .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO));
        }
        Map<Long, BigDecimal> ownHealthyByDetail = new HashMap<>();
        for (WmsSalesStockReservationDetailEntity detail : productReservations) {
            WmsLiveStockEntity stock = stockById.get(detail.getLiveStockId());
            BigDecimal rowPhysical = stock == null ? BigDecimal.ZERO
                    : physicalRemaining.getOrDefault(stock.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            boolean healthy = stock != null && eligibleWarehouses.contains(stock.getWarehouseCode())
                    && WmsStockAvailability.isUsable(stock) && matches(detail, stock);
            BigDecimal healthyQuantity = healthy ? detailActive(detail).min(rowPhysical) : BigDecimal.ZERO;
            if (stock != null) {
                physicalRemaining.put(stock.getId(), rowPhysical.subtract(healthyQuantity));
            }
            if (Objects.equals(detail.getReservationId(), reservation.getId())) {
                ownHealthyByDetail.put(detail.getId(), healthyQuantity);
            }
        }
        Map<WmsSalesStockReservationDetailEntity, BigDecimal> driftByDetail = new LinkedHashMap<>();
        BigDecimal drift = BigDecimal.ZERO;
        for (WmsSalesStockReservationDetailEntity detail : details) {
            BigDecimal healthyQuantity = ownHealthyByDetail.getOrDefault(detail.getId(), BigDecimal.ZERO);
            BigDecimal detailDrift = detailActive(detail).subtract(healthyQuantity);
            if (detailDrift.signum() > 0) {
                driftByDetail.put(detail, detailDrift);
                drift = drift.add(detailDrift);
            }
        }
        require(drift.signum() > 0, "当前销售预留没有质量、位置、效期或实物数量漂移，无需换批");

        for (Map.Entry<WmsSalesStockReservationDetailEntity, BigDecimal> entry : driftByDetail.entrySet()) {
            WmsSalesStockReservationDetailEntity detail = entry.getKey();
            detail.setReleasedNum(quantity(detail.getReleasedNum()).add(entry.getValue()));
            if (detailActive(detail).signum() == 0) {
                detail.setStatus(CLOSED);
            }
            require(detailMapper.updateById(detail) == 1, "漂移销售预留明细释放失败");
        }

        Map<Long, BigDecimal> sales = activeReservedNums(stockIds);
        List<WmsLiveStockEntity> candidates = stocks.stream()
                .filter(stock -> quantity(stock.getMatNum()).signum() > 0)
                .filter(stock -> eligibleWarehouses.contains(stock.getWarehouseCode()))
                .filter(WmsStockAvailability::isUsable)
                .filter(stock -> Objects.equals(reservation.getUnitCode(), stock.getMatUnit()))
                .toList();
        BigDecimal replacementAvailable = candidates.stream().map(stock -> quantity(stock.getMatNum())
                        .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO))
                        .subtract(sales.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(replacementAvailable.compareTo(drift) >= 0,
                "释放漂移明细后仅有" + replacementAvailable.stripTrailingZeros().toPlainString()
                        + "可替换合格库存，换批整笔回滚");
        BigDecimal left = drift;
        for (WmsLiveStockEntity stock : candidates) {
            BigDecimal available = quantity(stock.getMatNum())
                    .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO))
                    .subtract(sales.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            BigDecimal take = available.min(left);
            if (take.signum() <= 0) {
                continue;
            }
            WmsSerialStockGuard.requireWhole(stock, take);
            insertDetail(reservation, plan, stock, take);
            left = left.subtract(take);
            if (left.signum() == 0) {
                break;
            }
        }
        require(left.signum() == 0, "销售预留换批并发分配未完整，整笔回滚");
        reservation.setReservedNum(quantity(reservation.getReservedNum()).add(drift));
        reservation.setReleasedNum(quantity(reservation.getReleasedNum()).add(drift));
        reservation.setStatus(ACTIVE);
        reservation.setLastRequestId(command.getRequestId());
        reservation.setLastOperationType("REALLOCATE");
        require(reservationMapper.updateById(reservation) == 1, "销售预留换批主账更新失败");
        audit(reservation, plan, command, "REALLOCATE", before,
                JSONUtil.toJsonStr(snapshotOf(reservation)), drift);
        return snapshot(plan.getId());
    }

    @Override
    public WmsSalesStockReservationSnapshot snapshot(Long deliverPlanId) {
        requireScopedOrganization();
        require(deliverPlanId != null, "发货计划ID不能为空");
        WmsDeliverGoodsPlanBaseEntity plan = planMapper.selectById(deliverPlanId);
        require(plan != null, "发货计划不存在");
        requireFactory(plan.getFactoryCode());
        WmsSalesStockReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                        .eq(WmsSalesStockReservationEntity::getDeliverPlanId, deliverPlanId),
                false);
        if (reservation == null) {
            return new WmsSalesStockReservationSnapshot().setDeliverPlanId(plan.getId())
                    .setDeliverPlanCode(plan.getDeliverPlanCode()).setOrderCode(plan.getOrderCode())
                    .setOrderDetailId(plan.getOrderCodeDetails()).setProductCode(plan.getProductCode())
                    .setTargetQuantity(quantity(plan.getDeliverNum()).subtract(quantity(plan.getDeliveredNum())))
                    .setReservedQuantity(BigDecimal.ZERO).setConsumedQuantity(BigDecimal.ZERO)
                    .setReleasedQuantity(BigDecimal.ZERO).setActiveQuantity(BigDecimal.ZERO)
                    .setHealthyActiveQuantity(BigDecimal.ZERO).setDriftQuantity(BigDecimal.ZERO)
                    .setHealthStatus("NONE").setStatus("NONE").setDetails(List.of());
        }
        WmsSalesStockReservationSnapshot snapshot = snapshotOf(reservation);
        BigDecimal healthy = healthyActiveReservedByPlanIds(Set.of(plan.getId()))
                .getOrDefault(plan.getId(), BigDecimal.ZERO).min(snapshot.getActiveQuantity());
        BigDecimal drift = snapshot.getActiveQuantity().subtract(healthy).max(BigDecimal.ZERO);
        return snapshot.setHealthyActiveQuantity(healthy).setDriftQuantity(drift)
                .setHealthStatus(drift.signum() > 0 ? "DRIFTED" : "HEALTHY");
    }

    @Override
    public Map<Long, WmsSalesStockReservationSnapshot> snapshotsByPlanIds(Set<Long> deliverPlanIds) {
        requireScopedOrganization();
        if (CollUtil.isEmpty(deliverPlanIds)) {
            return Map.of();
        }
        Map<Long, WmsSalesStockReservationEntity> headers = reservationsByPlanIds(deliverPlanIds);
        if (headers.isEmpty()) {
            return Map.of();
        }
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory) && headers.values().stream()
                .allMatch(row -> Objects.equals(factory, row.getFactoryCode())), "销售预留批量快照混入跨工厂主账");
        require(headers.size() <= 1000, "单次销售预留健康扫描超过1000单，请按工厂治理数据范围");
        Map<Long, BigDecimal> healthyByPlan = healthyActiveReservedByPlanIds(headers.keySet());
        Set<Long> reservationIds = headers.values().stream().map(WmsSalesStockReservationEntity::getId)
                .collect(Collectors.toSet());
        Map<Long, List<WmsSalesStockReservationDetailEntity>> detailsByReservation = detailMapper.selectList(
                        new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                                .in(WmsSalesStockReservationDetailEntity::getReservationId, reservationIds)
                                .orderByAsc(WmsSalesStockReservationDetailEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(WmsSalesStockReservationDetailEntity::getReservationId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, WmsSalesStockReservationSnapshot> result = new LinkedHashMap<>();
        headers.values().stream().sorted(Comparator.comparing(WmsSalesStockReservationEntity::getId))
                .forEach(row -> {
                    WmsSalesStockReservationSnapshot snapshot = snapshotOf(row,
                            detailsByReservation.getOrDefault(row.getId(), List.of()));
                    BigDecimal healthy = healthyByPlan.getOrDefault(row.getDeliverPlanId(), BigDecimal.ZERO)
                            .min(snapshot.getActiveQuantity());
                    BigDecimal drift = snapshot.getActiveQuantity().subtract(healthy).max(BigDecimal.ZERO);
                    result.put(row.getDeliverPlanId(), snapshot.setHealthyActiveQuantity(healthy)
                            .setDriftQuantity(drift).setHealthStatus(drift.signum() > 0 ? "DRIFTED" : "HEALTHY"));
                });
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsLiveStockEntity> availableReservedStocks(String reservationNo, String productCode,
                                                             String warehouseCode) {
        requireScopedOrganization();
        if (!StrUtil.startWith(reservationNo, PREFIX) || StrUtil.isBlank(productCode)) {
            return List.of();
        }
        WmsSalesStockReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                        .eq(WmsSalesStockReservationEntity::getReservationNo, reservationNo)
                        .last("FOR UPDATE"), false);
        require(reservation != null && ACTIVE.equals(reservation.getStatus())
                        && active(reservation).signum() > 0,
                "销售预留[" + reservationNo + "]不存在或已失效");
        requireFactory(reservation.getFactoryCode());
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && Objects.equals(tenantId, reservation.getTenantId()),
                "当前租户无权查看该销售预留");
        require(Objects.equals(productCode, reservation.getProductCode()), "销售预留产品与出库通知不一致");

        Map<Long, BigDecimal> physicalRemaining = new HashMap<>();
        Map<Long, WmsLiveStockEntity> availableByStock = new LinkedHashMap<>();
        for (WmsSalesStockReservationDetailEntity detail : activeDetails(reservation.getId(), false)) {
            boolean warehouseMatches = StrUtil.isBlank(warehouseCode)
                    || Objects.equals(warehouseCode, detail.getWarehouseCode());
            if (!Objects.equals(productCode, detail.getProductCode())
                    || !Objects.equals(reservation.getFactoryCode(), detail.getFactoryCode())
                    || !warehouseMatches
                    || detailActive(detail).signum() <= 0) {
                continue;
            }
            WmsLiveStockEntity stock = stockMapper.selectById(detail.getLiveStockId());
            if (stock == null || !Objects.equals(tenantId, stock.getTenantId())
                    || !WmsStockAvailability.isUsable(stock) || !matches(detail, stock)) {
                continue;
            }
            BigDecimal physical = physicalRemaining.computeIfAbsent(stock.getId(),
                    ignored -> quantity(stock.getMatNum()));
            BigDecimal usable = detailActive(detail).min(physical);
            if (usable.signum() <= 0) {
                continue;
            }
            physicalRemaining.put(stock.getId(), physical.subtract(usable));
            WmsLiveStockEntity candidate = availableByStock.computeIfAbsent(stock.getId(), ignored -> {
                WmsLiveStockEntity copy = new WmsLiveStockEntity();
                BeanUtils.copyProperties(stock, copy);
                copy.setMatNum(0d);
                return copy;
            });
            candidate.setMatNum(quantity(candidate.getMatNum()).add(usable).doubleValue());
        }
        return new ArrayList<>(availableByStock.values());
    }

    @Override
    public Map<Long, BigDecimal> activeReservedNums(Set<Long> liveStockIds) {
        requireScopedOrganization();
        if (CollUtil.isEmpty(liveStockIds)) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> ids = liveStockIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> batch = ids.subList(start, Math.min(start + 500, ids.size()));
            detailMapper.selectList(new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                            .in(WmsSalesStockReservationDetailEntity::getLiveStockId, batch)
                            .eq(WmsSalesStockReservationDetailEntity::getStatus, ACTIVE))
                    .forEach(detail -> result.merge(detail.getLiveStockId(), detailActive(detail), BigDecimal::add));
        }
        return result;
    }

    @Override
    public Map<Long, BigDecimal> activeReservedByPlanIds(Set<Long> deliverPlanIds) {
        requireScopedOrganization();
        if (CollUtil.isEmpty(deliverPlanIds)) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> ids = deliverPlanIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> batch = ids.subList(start, Math.min(start + 500, ids.size()));
            reservationMapper.selectList(new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                            .in(WmsSalesStockReservationEntity::getDeliverPlanId, batch))
                    .forEach(row -> result.put(row.getDeliverPlanId(), active(row)));
        }
        return result;
    }

    @Override
    public Map<Long, BigDecimal> healthyActiveReservedByPlanIds(Set<Long> deliverPlanIds) {
        requireScopedOrganization();
        Map<Long, WmsSalesStockReservationEntity> headers = reservationsByPlanIds(deliverPlanIds);
        if (headers.isEmpty()) {
            return Map.of();
        }
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "当前工厂不能为空");
        require(headers.values().stream().allMatch(row -> Objects.equals(factory, row.getFactoryCode())),
                "销售预留健康量查询混入跨工厂主账");
        Set<Long> reservationIds = headers.values().stream()
                .map(WmsSalesStockReservationEntity::getId).collect(Collectors.toSet());
        if (reservationIds.isEmpty()) {
            return Map.of();
        }
        Set<String> products = headers.values().stream().map(WmsSalesStockReservationEntity::getProductCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        require(!products.isEmpty(), "销售预留主账产品不能为空");
        List<WmsSalesStockReservationDetailEntity> productDetails = detailMapper.selectList(
                new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                        .eq(WmsSalesStockReservationDetailEntity::getFactoryCode, factory)
                        .in(WmsSalesStockReservationDetailEntity::getProductCode, products)
                        .eq(WmsSalesStockReservationDetailEntity::getStatus, ACTIVE)
                        .orderByAsc(WmsSalesStockReservationDetailEntity::getId));
        require(productDetails.size() <= 5000, "同产品活动销售预留明细超过5000行，拒绝截断健康量证据");
        if (productDetails.isEmpty()) {
            return Map.of();
        }
        Set<String> warehouses = eligibleWarehouses(factory);
        Set<Long> stockIds = productDetails.stream().map(WmsSalesStockReservationDetailEntity::getLiveStockId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, WmsLiveStockEntity> stocks = stockIds.isEmpty() ? Map.of()
                : stockMapper.selectBatchIds(stockIds)
                .stream().peek(stock -> require(Objects.equals(factory, stock.getFactoryCode()),
                        "销售预留健康量查询混入跨工厂库存"))
                .collect(Collectors.toMap(WmsLiveStockEntity::getId, item -> item));
        Map<Long, Long> planByReservation = headers.values().stream().collect(Collectors.toMap(
                WmsSalesStockReservationEntity::getId, WmsSalesStockReservationEntity::getDeliverPlanId));
        Map<Long, BigDecimal> production = productionReserved(stockIds);
        Map<Long, BigDecimal> physicalRemaining = stocks.values().stream().collect(Collectors.toMap(
                WmsLiveStockEntity::getId, stock -> quantity(stock.getMatNum())
                        .subtract(production.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO)));
        Map<Long, BigDecimal> result = new HashMap<>();
        for (WmsSalesStockReservationDetailEntity detail : productDetails) {
            WmsLiveStockEntity stock = stocks.get(detail.getLiveStockId());
            if (stock == null || !warehouses.contains(stock.getWarehouseCode())
                    || !WmsStockAvailability.isUsable(stock) || !matches(detail, stock)) {
                continue;
            }
            BigDecimal rowPhysical = physicalRemaining.getOrDefault(stock.getId(), BigDecimal.ZERO)
                    .max(BigDecimal.ZERO);
            BigDecimal healthy = detailActive(detail).min(rowPhysical);
            physicalRemaining.put(stock.getId(), rowPhysical.subtract(healthy));
            Long planId = planByReservation.get(detail.getReservationId());
            if (planId != null) {
                result.merge(planId, healthy, BigDecimal::add);
            }
        }
        return result;
    }

    @Override
    public Map<Long, WmsSalesStockReservationEntity> reservationsByPlanIds(Set<Long> deliverPlanIds) {
        requireScopedOrganization();
        if (CollUtil.isEmpty(deliverPlanIds)) {
            return Map.of();
        }
        Map<Long, WmsSalesStockReservationEntity> result = new HashMap<>();
        List<Long> ids = deliverPlanIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> batch = ids.subList(start, Math.min(start + 500, ids.size()));
            reservationMapper.selectList(new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                            .in(WmsSalesStockReservationEntity::getDeliverPlanId, batch))
                    .forEach(row -> result.put(row.getDeliverPlanId(), row));
        }
        return result;
    }

    @Override
    public String activeReservationNo(Long deliverPlanId) {
        requireScopedOrganization();
        WmsSalesStockReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                        .eq(WmsSalesStockReservationEntity::getDeliverPlanId, deliverPlanId),
                false);
        return reservation != null && active(reservation).signum() > 0 ? reservation.getReservationNo() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsLiveStockEntity> consume(WmsOutBillDetailEntity billDetail) {
        requireScopedOrganization();
        require(billDetail != null && StrUtil.startWith(billDetail.getReservationNo(), PREFIX)
                && billDetail.getId() != null && billDetail.getBillId() != null, "销售预留出库缺少稳定来源");
        require(billDetail.getMatNum() != null && Double.isFinite(billDetail.getMatNum())
                && billDetail.getMatNum() > 0, "销售预留消费数量必须为大于0的有限数值");
        BigDecimal requested = quantity(billDetail.getMatNum());
        WmsSalesStockReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                        .eq(WmsSalesStockReservationEntity::getReservationNo, billDetail.getReservationNo())
                        .last("FOR UPDATE"),
                false);
        require(reservation != null && active(reservation).compareTo(requested) >= 0,
                "销售预留不存在或活动数量不足");
        requireFactory(reservation.getFactoryCode());
        require(Objects.equals(reservation.getProductCode(), billDetail.getMatCode())
                        && Objects.equals(reservation.getFactoryCode(), billDetail.getFactoryCode()),
                "出库明细与销售预留产品或工厂不一致");
        String requestId = "SALES-OUT-" + billDetail.getBillId() + "-" + billDetail.getId();
        WmsSalesStockReservationOperationEntity replay = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationOperationEntity>()
                        .eq(WmsSalesStockReservationOperationEntity::getRequestId, requestId),
                false);
        require(replay == null, "同一销售预留出库明细已消费，禁止重复扣库");
        String before = JSONUtil.toJsonStr(snapshotOf(reservation));
        List<WmsSalesStockReservationDetailEntity> details = activeDetails(reservation.getId(), true).stream()
                .filter(detail -> matches(detail, billDetail)).toList();
        BigDecimal left = requested;
        List<WmsLiveStockEntity> reduced = new ArrayList<>();
        for (WmsSalesStockReservationDetailEntity detail : details) {
            if (left.signum() == 0) {
                break;
            }
            WmsLiveStockEntity stock = stockMapper.selectOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
                    .eq(WmsLiveStockEntity::getId, detail.getLiveStockId()).last("FOR UPDATE"), false);
            require(stock != null && WmsStockAvailability.isUsable(stock) && matches(detail, stock),
                    "销售预留库存行已丢失、漂移或质量不可用，须先受控释放并重新预留");
            BigDecimal take = detailActive(detail).min(left);
            require(quantity(stock.getMatNum()).compareTo(take) >= 0, "销售预留库存实物不足，拒绝透支出库");
            WmsSerialStockGuard.requireWhole(stock, take);
            BigDecimal balance = quantity(stock.getMatNum()).subtract(take);
            WmsLiveStockEntity snapshot = new WmsLiveStockEntity();
            BeanUtils.copyProperties(stock, snapshot);
            snapshot.setMatNum(take.doubleValue());
            reduced.add(snapshot);
            if (balance.signum() == 0) {
                require(stockMapper.deleteById(stock.getId()) == 1, "销售预留源库存删除未确认，消费整体回滚");
            } else {
                stock.setMatNum(balance.doubleValue());
                require(stockMapper.updateById(stock) == 1, "销售预留库存扣减失败");
            }
            detail.setConsumedNum(quantity(detail.getConsumedNum()).add(take));
            if (detailActive(detail).signum() == 0) {
                detail.setStatus(CLOSED);
            }
            require(detailMapper.updateById(detail) == 1, "销售预留消费明细更新失败");
            left = left.subtract(take);
        }
        require(left.signum() == 0, "所选批次/库位不属于当前销售预留或预留量不足");
        reservation.setConsumedNum(quantity(reservation.getConsumedNum()).add(requested));
        refreshStatus(reservation);
        reservation.setLastRequestId(requestId);
        reservation.setLastOperationType("CONSUME");
        require(reservationMapper.updateById(reservation) == 1, "销售预留消费状态更新失败");
        WmsSalesStockReservationCommand operation = new WmsSalesStockReservationCommand();
        operation.setDeliverPlanId(reservation.getDeliverPlanId());
        operation.setRequestId(requestId);
        operation.setQuantity(requested);
        operation.setReason("销售出库单明细" + billDetail.getBillId() + "/" + billDetail.getId());
        WmsDeliverGoodsPlanBaseEntity plan = planMapper.selectById(reservation.getDeliverPlanId());
        require(plan != null, "销售预留来源发货计划不存在");
        audit(reservation, plan, operation, "CONSUME", before,
                JSONUtil.toJsonStr(snapshotOf(reservation)), requested);
        return reduced;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreAfterShipmentCorrection(Long deliverPlanId, List<WmsLiveStockEntity> restoredStocks,
                                               String requestId, String reason) {
        requireScopedOrganization();
        require(deliverPlanId != null && CollUtil.isNotEmpty(restoredStocks)
                        && StrUtil.isNotBlank(requestId) && requestId.length() <= 64
                        && StrUtil.isNotBlank(reason) && reason.length() <= 500,
                "销售红冲重新预留缺少计划、库存、请求号或原因");
        WmsDeliverGoodsPlanBaseEntity plan = lockPlan(deliverPlanId);
        WmsSalesStockReservationEntity reservation = lockReservation(deliverPlanId);
        require(reservation != null, "销售红冲原计划没有正式库存预留，禁止恢复为无主库存");
        validateSource(reservation, plan);
        WmsSalesStockReservationOperationEntity replay = replay(requestId, plan);
        if (replay != null) {
            require("CORRECTION_RESTORE".equals(replay.getOperationType()), "销售红冲重新预留请求号已被其他操作占用");
            return;
        }
        BigDecimal total = restoredStocks.stream().map(stock -> quantity(stock.getMatNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        require(total.signum() > 0 && restoredStocks.stream().allMatch(stock -> stock.getId() != null
                        && Objects.equals(stock.getFactoryCode(), plan.getFactoryCode())
                        && Objects.equals(stock.getMatCode(), plan.getProductCode())
                        && Objects.equals(stock.getMatUnit(), reservation.getUnitCode())),
                "红冲恢复库存与原计划的工厂、产品或单位不一致");
        String before = JSONUtil.toJsonStr(snapshotOf(reservation));
        for (WmsLiveStockEntity stock : restoredStocks) {
            insertDetail(reservation, plan, stock, quantity(stock.getMatNum()));
        }
        reservation.setReservedNum(quantity(reservation.getReservedNum()).add(total));
        refreshStatus(reservation);
        reservation.setLastRequestId(requestId);
        reservation.setLastOperationType("CORRECTION_RESTORE");
        require(reservationMapper.updateById(reservation) == 1, "销售红冲重新预留主账保存失败");
        WmsSalesStockReservationCommand command = new WmsSalesStockReservationCommand();
        command.setDeliverPlanId(deliverPlanId);
        command.setRequestId(requestId);
        command.setQuantity(total);
        command.setReason(reason.trim());
        audit(reservation, plan, command, "CORRECTION_RESTORE", before,
                JSONUtil.toJsonStr(snapshotOf(reservation)), total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateOutboundReservation(List<WmsOutBillDetailEntity> details) {
        requireScopedOrganization();
        Map<String, List<WmsOutBillDetailEntity>> grouped = Optional.ofNullable(details).orElse(List.of()).stream()
                .filter(detail -> detail != null && StrUtil.startWith(detail.getReservationNo(), PREFIX))
                .collect(Collectors.groupingBy(WmsOutBillDetailEntity::getReservationNo));
        for (Map.Entry<String, List<WmsOutBillDetailEntity>> entry : grouped.entrySet()) {
            WmsSalesStockReservationEntity reservation = reservationMapper.selectOne(
                    new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                            .eq(WmsSalesStockReservationEntity::getReservationNo, entry.getKey()),
                    false);
            require(entry.getValue().stream().allMatch(item -> item.getMatNum() != null
                            && Double.isFinite(item.getMatNum()) && item.getMatNum() > 0),
                    "销售预留出库明细数量必须为大于0的有限数值");
            BigDecimal total = entry.getValue().stream().map(item -> quantity(item.getMatNum()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            require(reservation != null && total.signum() > 0 && active(reservation).compareTo(total) >= 0
                            && entry.getValue().stream()
                            .allMatch(item -> Objects.equals(reservation.getProductCode(), item.getMatCode())
                                    && Objects.equals(reservation.getFactoryCode(), item.getFactoryCode())
                                    && (StrUtil.isBlank(item.getMatUnit())
                                    || Objects.equals(reservation.getUnitCode(), item.getMatUnit()))),
                    "销售出库引用的正式预留不存在、产品/工厂/单位不符或活动数量不足");
            List<WmsLiveStockEntity> stocks = availableReservedStocks(entry.getKey(),
                    reservation.getProductCode(), null);
            Map<Long, BigDecimal> availableByStock = stocks.stream().collect(Collectors.toMap(
                    WmsLiveStockEntity::getId, stock -> quantity(stock.getMatNum())));
            for (WmsOutBillDetailEntity out : entry.getValue()) {
                BigDecimal required = quantity(out.getMatNum());
                for (WmsLiveStockEntity stock : stocks) {
                    if (required.signum() == 0) {
                        break;
                    }
                    if (!matchesOutboundStock(out, stock)) {
                        continue;
                    }
                    BigDecimal available = availableByStock.getOrDefault(stock.getId(), BigDecimal.ZERO);
                    BigDecimal take = available.min(required);
                    availableByStock.put(stock.getId(), available.subtract(take));
                    required = required.subtract(take);
                }
                require(required.signum() == 0,
                        "销售预留所选库存行、批次或剩余实物量已变化，请重新生成出库批次");
            }
        }
    }

    private boolean matchesOutboundStock(WmsOutBillDetailEntity out, WmsLiveStockEntity stock) {
        return (out.getSourceStockId() == null || Objects.equals(out.getSourceStockId(), stock.getId()))
                && Objects.equals(out.getMatCode(), stock.getMatCode())
                && Objects.equals(out.getWarehouseCode(), stock.getWarehouseCode())
                && optionalMatch(stock.getMatBatch(), out.getMatBatch())
                && optionalMatch(stock.getSiteCode(), out.getSiteCode())
                && optionalMatch(stock.getContainerCode(), out.getContainerCode())
                && optionalMatch(stock.getSupplierCode(), out.getSupplierCode());
    }

    private void requireNoOpenDelivery(WmsDeliverGoodsPlanBaseEntity plan) {
        List<String> deliverCodes = deliverDetailsMapper.selectList(
                        new LambdaQueryWrapper<WmsDeliverGoodsDetailsEntity>()
                                .eq(WmsDeliverGoodsDetailsEntity::getDeliverPlanCode, plan.getDeliverPlanCode()))
                .stream().map(WmsDeliverGoodsDetailsEntity::getDeliverCode).filter(StrUtil::isNotBlank)
                .distinct().toList();
        if (deliverCodes.isEmpty()) {
            return;
        }
        Long open = deliverBaseMapper.selectCount(new LambdaQueryWrapper<WmsDeliverGoodsBaseEntity>()
                .in(WmsDeliverGoodsBaseEntity::getDeliverCode, deliverCodes)
                .in(WmsDeliverGoodsBaseEntity::getDeliverState,
                        DeliverStateEnum.NOT_START.getCode(), DeliverStateEnum.CREATE.getCode(),
                        DeliverStateEnum.ING.getCode()));
        require(open == null || open == 0, "发货计划存在未结束发货单，须先完成或关闭仓库执行后再调整正式预留");
    }

    private Set<String> eligibleWarehouses(String factoryCode) {
        R<List<MdmWarehouseEntity>> response = warehouseService.getEnabledProductWarehouses();
        require(response != null && response.isOk() && response.getData() != null, "MDM成品仓范围不可用");
        Set<String> result = new HashSet<>();
        for (MdmWarehouseEntity warehouse : response.getData()) {
            require(warehouse != null && Objects.equals(factoryCode, warehouse.getFactoryCode())
                            && Objects.equals(WarehouseTypeEnum.PRODUCT.getCode(), warehouse.getWarehouseType())
                            && Boolean.TRUE.equals(warehouse.getStatus()) && StrUtil.isNotBlank(warehouse.getWarehouseCode()),
                    "MDM成品仓返回跨工厂、停用或非法数据");
            require(result.add(warehouse.getWarehouseCode()), "MDM成品仓编码重复");
        }
        require(!result.isEmpty(), "当前工厂未配置启用成品仓");
        return result;
    }

    private Map<Long, BigDecimal> productionReserved(Set<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> ids = new ArrayList<>(stockIds);
        for (int start = 0; start < ids.size(); start += 500) {
            List<Long> batch = ids.subList(start, Math.min(start + 500, ids.size()));
            materialDetailMapper.selectList(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                            .in(WmsMaterialReservationDetailEntity::getLiveStockId, batch)
                            .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE))
                    .forEach(detail -> result.merge(detail.getLiveStockId(), materialActive(detail), BigDecimal::add));
        }
        return result;
    }

    private void insertDetail(WmsSalesStockReservationEntity reservation,
                              WmsDeliverGoodsPlanBaseEntity plan,
                              WmsLiveStockEntity stock,
                              BigDecimal quantity) {
        WmsSalesStockReservationDetailEntity detail = new WmsSalesStockReservationDetailEntity();
        detail.setReservationId(reservation.getId());
        detail.setReservationNo(reservation.getReservationNo());
        detail.setLiveStockId(stock.getId());
        detail.setProductCode(plan.getProductCode());
        detail.setUnitCode(stock.getMatUnit());
        detail.setReservedNum(quantity);
        detail.setConsumedNum(BigDecimal.ZERO);
        detail.setReleasedNum(BigDecimal.ZERO);
        detail.setWarehouseCode(stock.getWarehouseCode());
        detail.setWarehouseName(stock.getWarehouseName());
        detail.setSiteCode(stock.getSiteCode());
        detail.setSiteName(stock.getSiteName());
        detail.setContainerCode(stock.getContainerCode());
        detail.setMatBatch(stock.getMatBatch());
        detail.setSupplierCode(stock.getSupplierCode());
        detail.setQualityStatus(stock.getQualityStatus());
        detail.setQualityResult(stock.getQualityResult());
        detail.setValidEndDate(stock.getMatValidEndDate());
        detail.setStockInTime(stock.getInTime());
        detail.setStatus(ACTIVE);
        detail.setFactoryCode(plan.getFactoryCode());
        require(detailMapper.insert(detail) == 1, "销售库存预留明细保存失败");
    }

    private List<WmsSalesStockReservationDetailEntity> activeDetails(Long reservationId, boolean lock) {
        LambdaQueryWrapper<WmsSalesStockReservationDetailEntity> query = new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                .eq(WmsSalesStockReservationDetailEntity::getReservationId, reservationId)
                .eq(WmsSalesStockReservationDetailEntity::getStatus, ACTIVE)
                .orderByAsc(WmsSalesStockReservationDetailEntity::getValidEndDate)
                .orderByAsc(WmsSalesStockReservationDetailEntity::getStockInTime)
                .orderByAsc(WmsSalesStockReservationDetailEntity::getId);
        if (lock) {
            query.last("FOR UPDATE");
        }
        return detailMapper.selectList(query);
    }

    private WmsDeliverGoodsPlanBaseEntity lockPlan(Long id) {
        WmsDeliverGoodsPlanBaseEntity plan = planMapper
                .selectOne(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getId, id).last("FOR UPDATE"), false);
        require(plan != null, "发货计划不存在");
        requireFactory(plan.getFactoryCode());
        require(plan.getDeliverNum() != null && plan.getDeliveredNum() != null
                && plan.getDeliverNum().signum() >= 0 && plan.getDeliveredNum().signum() >= 0
                && plan.getDeliveredNum().compareTo(plan.getDeliverNum()) <= 0
                && StrUtil.isNotBlank(plan.getProductCode()), "发货计划产品或数量事实非法");
        return plan;
    }

    private WmsSalesStockReservationEntity lockReservation(Long planId) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<WmsSalesStockReservationEntity>()
                .eq(WmsSalesStockReservationEntity::getDeliverPlanId, planId).last("FOR UPDATE"), false);
    }

    private WmsSalesStockReservationOperationEntity replay(String requestId, WmsDeliverGoodsPlanBaseEntity plan) {
        WmsSalesStockReservationOperationEntity operation = operationMapper.selectOne(
                new LambdaQueryWrapper<WmsSalesStockReservationOperationEntity>()
                        .eq(WmsSalesStockReservationOperationEntity::getRequestId, requestId),
                false);
        if (operation != null) {
            require(Objects.equals(operation.getDeliverPlanId(), plan.getId()),
                    "请求号已属于其他发货计划");
        }
        return operation;
    }

    private void audit(WmsSalesStockReservationEntity reservation, WmsDeliverGoodsPlanBaseEntity plan,
                       WmsSalesStockReservationCommand command, String type, String before, String after,
                       BigDecimal quantity) {
        MomUser user = null;
        try {
            user = SecurityUtils.getUser();
        } catch (Exception ignored) {
        }
        WmsSalesStockReservationOperationEntity operation = new WmsSalesStockReservationOperationEntity();
        operation.setReservationId(reservation.getId());
        operation.setDeliverPlanId(plan.getId());
        operation.setRequestId(command.getRequestId());
        operation.setOperationType(type);
        operation.setBeforeStatus(before == null ? null : statusFrom(before));
        operation.setAfterStatus(reservation.getStatus());
        operation.setQuantity(quantity);
        operation.setReason(command.getReason().trim());
        operation.setBeforeSnapshot(before);
        operation.setAfterSnapshot(after);
        operation.setOperatorCode(user == null ? "SYSTEM" : StrUtil.blankToDefault(user.getUsername(), "SYSTEM"));
        operation.setOperatorName(user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername()));
        operation.setOperationTime(LocalDateTime.now());
        operation.setFactoryCode(plan.getFactoryCode());
        require(operationMapper.insert(operation) == 1, "销售库存预留不可变操作履历保存失败");
    }

    private String statusFrom(String json) {
        try {
            return JSONUtil.parseObj(json).getStr("status");
        } catch (Exception ignored) {
            return null;
        }
    }

    private WmsSalesStockReservationSnapshot snapshotOf(WmsSalesStockReservationEntity row) {
        List<WmsSalesStockReservationDetailEntity> details = row.getId() == null ? List.of()
                : detailMapper.selectList(new LambdaQueryWrapper<WmsSalesStockReservationDetailEntity>()
                .eq(WmsSalesStockReservationDetailEntity::getReservationId, row.getId())
                .orderByAsc(WmsSalesStockReservationDetailEntity::getId));
        return snapshotOf(row, details);
    }

    private WmsSalesStockReservationSnapshot snapshotOf(WmsSalesStockReservationEntity row,
                                                        List<WmsSalesStockReservationDetailEntity> details) {
        return new WmsSalesStockReservationSnapshot().setReservationId(row.getId())
                .setReservationNo(row.getReservationNo()).setDeliverPlanId(row.getDeliverPlanId())
                .setDeliverPlanCode(row.getDeliverPlanCode()).setOrderCode(row.getOrderCode())
                .setOrderDetailId(row.getOrderDetailId()).setProductCode(row.getProductCode())
                .setUnitCode(row.getUnitCode()).setTargetQuantity(row.getTargetNum())
                .setReservedQuantity(quantity(row.getReservedNum())).setConsumedQuantity(quantity(row.getConsumedNum()))
                .setReleasedQuantity(quantity(row.getReleasedNum())).setActiveQuantity(active(row))
                .setStatus(row.getStatus()).setAllocationPolicy(row.getAllocationPolicy()).setDetails(details);
    }

    private void refreshStatus(WmsSalesStockReservationEntity row) {
        if (active(row).signum() > 0) {
            row.setStatus(ACTIVE);
        } else if (quantity(row.getConsumedNum()).signum() > 0) {
            row.setStatus("CONSUMED");
        } else {
            row.setStatus("RELEASED");
        }
    }

    private boolean matches(WmsSalesStockReservationDetailEntity reserved, WmsOutBillDetailEntity out) {
        return (out.getSourceStockId() == null
                || Objects.equals(reserved.getLiveStockId(), out.getSourceStockId()))
                && Objects.equals(reserved.getWarehouseCode(), out.getWarehouseCode())
                && optionalMatch(reserved.getMatBatch(), out.getMatBatch())
                && optionalMatch(reserved.getSiteCode(), out.getSiteCode())
                && optionalMatch(reserved.getContainerCode(), out.getContainerCode())
                && optionalMatch(reserved.getSupplierCode(), out.getSupplierCode());
    }

    private boolean matches(WmsSalesStockReservationDetailEntity reserved, WmsLiveStockEntity stock) {
        return Objects.equals(reserved.getLiveStockId(), stock.getId())
                && Objects.equals(reserved.getProductCode(), stock.getMatCode())
                && Objects.equals(reserved.getFactoryCode(), stock.getFactoryCode())
                && Objects.equals(reserved.getWarehouseCode(), stock.getWarehouseCode())
                && Objects.equals(reserved.getMatBatch(), stock.getMatBatch())
                && Objects.equals(reserved.getSiteCode(), stock.getSiteCode())
                && Objects.equals(reserved.getContainerCode(), stock.getContainerCode())
                && Objects.equals(reserved.getSupplierCode(), stock.getSupplierCode())
                && Objects.equals(reserved.getUnitCode(), stock.getMatUnit())
                && Objects.equals(reserved.getQualityStatus(), stock.getQualityStatus())
                && Objects.equals(reserved.getQualityResult(), stock.getQualityResult())
                && Objects.equals(reserved.getValidEndDate(), stock.getMatValidEndDate())
                && Objects.equals(reserved.getStockInTime(), stock.getInTime());
    }

    private boolean optionalMatch(String reserved, String selected) {
        return StrUtil.isBlank(selected)
                || Objects.equals(StrUtil.nullToEmpty(reserved), StrUtil.nullToEmpty(selected));
    }

    private void validateSource(WmsSalesStockReservationEntity row, WmsDeliverGoodsPlanBaseEntity plan) {
        require(Objects.equals(row.getFactoryCode(), plan.getFactoryCode())
                        && Objects.equals(row.getDeliverPlanCode(), plan.getDeliverPlanCode())
                        && Objects.equals(row.getOrderCode(), plan.getOrderCode())
                        && Objects.equals(row.getOrderDetailId(), plan.getOrderCodeDetails())
                        && Objects.equals(row.getProductCode(), plan.getProductCode()),
                "销售预留与发货计划来源事实漂移");
    }

    private void validateCommand(WmsSalesStockReservationCommand command, boolean reasonRequired) {
        require(command != null && command.getDeliverPlanId() != null && StrUtil.isNotBlank(command.getRequestId())
                && command.getRequestId().length() <= 64, "销售库存预留命令缺少计划ID或稳定请求号");
        require(!reasonRequired || StrUtil.isNotBlank(command.getReason()) && command.getReason().length() <= 500,
                "销售库存预留操作原因不能为空且最多500字");
        require(command.getQuantity() == null || command.getQuantity().signum() > 0,
                "销售库存预留操作数量必须大于0");
    }

    private Set<Long> stockIds(List<WmsLiveStockEntity> stocks) {
        return stocks.stream().map(WmsLiveStockEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private BigDecimal active(WmsSalesStockReservationEntity row) {
        return quantity(row.getReservedNum()).subtract(quantity(row.getConsumedNum()))
                .subtract(quantity(row.getReleasedNum())).max(BigDecimal.ZERO);
    }

    private BigDecimal detailActive(WmsSalesStockReservationDetailEntity row) {
        return quantity(row.getReservedNum()).subtract(quantity(row.getConsumedNum()))
                .subtract(quantity(row.getReleasedNum())).max(BigDecimal.ZERO);
    }

    private BigDecimal materialActive(WmsMaterialReservationDetailEntity row) {
        return quantity(row.getReservedNum()).subtract(quantity(row.getConsumedNum()))
                .subtract(quantity(row.getReleasedNum())).max(BigDecimal.ZERO);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal quantity(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private void requireFactory(String factory) {
        requireScopedOrganization();
        require(StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                && Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "当前工厂无权操作该销售预留");
    }

    private void requireScopedOrganization() {
        require(TenantContextHolder.getTenantId() != null
                        && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                        && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "销售预留必须具有明确的租户和工厂上下文");
    }

    private void require(boolean value, String message) {
        if (!value) {
            throw new CheckedException(message);
        }
    }
}
