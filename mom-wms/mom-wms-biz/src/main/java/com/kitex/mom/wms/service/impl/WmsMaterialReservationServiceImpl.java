package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialAuthorizationService;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mes.api.feign.RemoteWarehousePickingBillService;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.wms.dto.*;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.DocumentEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsReservationMovementPolicyService;
import com.kitex.mom.wms.service.WmsSalesStockReservationService;
import com.kitex.mom.wms.util.WmsStockAvailability;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WmsMaterialReservationServiceImpl implements WmsMaterialReservationService {
    private static final String ACTIVE = "ACTIVE";
    private static final String RELEASED = "RELEASED";
    private static final String CONSUMED = "CONSUMED";
    private static final String DETAIL_ORIGINAL = "ORIGINAL";
    private static final String DETAIL_RETURN_REPLENISH = "RETURN_REPLENISH";
    private static final String DETAIL_COMPLIANCE_REALLOCATION = "COMPLIANCE_REALLOCATION";
    private static final String ADJUSTMENT_COMPLIANCE_REALLOCATION = "COMPLIANCE_REALLOCATION";
    private static final Set<String> NON_REALLOCATABLE_COMPLIANCE_ISSUES = Set.of(
            "COMPLIANCE_SNAPSHOT_MISSING", "COMPLIANCE_SNAPSHOT_BROKEN",
            "CUSTOMER_AUTHORIZATION_DRIFT", "SUPPLIER_CERTIFICATION_INVALID",
            "SUPPLIER_CERTIFICATION_EXPIRED", "AUTHORIZATION_LEDGER_UNAVAILABLE",
            "DEVIATION_PERMIT_INVALID", "DEVIATION_PERMIT_EXPIRED");
    private static final Set<String> REALLOCATABLE_COMPLIANCE_ISSUES = Set.of(
            "SUPPLIER_BATCH_COMPLIANCE_DRIFT", "QUALITY_RESULT_COMPLIANCE_DRIFT");

    private final WmsMaterialReservationMapper reservationMapper;
    private final WmsMaterialReservationDetailMapper detailMapper;
    private final WmsMaterialReservationAdjustmentMapper adjustmentMapper;
    private final WmsLiveStockMapper liveStockMapper;
    private final RemoteMaterialService remoteMaterialService;
    private final RemoteMaterialAuthorizationService remoteMaterialAuthorizationService;
    private final RemoteWarehouseService remoteWarehouseService;
    private final WmsOutBillDetailMapper outBillDetailMapper;
    private final WmsOutBillMapper outBillMapper;
    private final WmsOutNoticeMapper outNoticeMapper;
    private final RemoteWarehousePickingBillService remoteWarehousePickingBillService;
    private final WmsSalesStockReservationService salesStockReservationService;
    private final WmsReservationMovementPolicyService reservationMovementPolicyService;

    private void requireScopedOrganization() {
        if (TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("物料预留必须具有明确的租户和工厂上下文");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsMaterialReservationResultDTO reserve(WmsMaterialReservationCreateDTO command) {
        validateCreate(command);
        List<WmsMaterialReservationAllocationDTO> allocations = normalize(command.getAllocations());
        MdmWarehouseEntity warehouse = validateWarehouse(command);
        command.setWarehouseCode(warehouse.getWarehouseCode());
        command.setWarehouseName(warehouse.getWarehouseName());
        Map<String, Object> frozenRequest = new LinkedHashMap<>();
        frozenRequest.put("warehouseCode", command.getWarehouseCode());
        frozenRequest.put("allocations", allocations);
        String snapshot = JSONUtil.toJsonStr(frozenRequest);
        WmsMaterialReservationEntity existing = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getSourceType, command.getSourceType())
                        .eq(WmsMaterialReservationEntity::getSourceId, command.getSourceId()),
                false);
        if (existing != null) {
            if (ACTIVE.equals(existing.getStatus()) && Objects.equals(snapshot, existing.getAllocationSnapshot())
                    && Objects.equals(command.getFactoryCode(), existing.getFactoryCode())
                    && Objects.equals(command.getCustomerCode(), existing.getCustomerCode())
                    && Objects.equals(command.getRequiredDate(), existing.getRequiredDate())) {
                return result(existing, allocations);
            }
            throw new CheckedException("来源记录已存在状态为[" + existing.getStatus() + "]的物料预留，不能重复或变更预留方案");
        }

        // 已存在预留的幂等重放和后续消耗不受新配置影响；只拦截新建。
        reservationMovementPolicyService.ensureProductionReservable(command.getSourceType());

        WmsMaterialReservationEntity reservation = new WmsMaterialReservationEntity();
        reservation.setReservationNo(reservationNo(command));
        reservation.setRequestId(command.getRequestId());
        reservation.setSourceType(command.getSourceType());
        reservation.setSourceId(command.getSourceId());
        reservation.setSourceNo(command.getSourceNo());
        reservation.setWarehouseCode(command.getWarehouseCode());
        reservation.setWarehouseName(command.getWarehouseName());
        reservation.setCustomerCode(command.getCustomerCode());
        reservation.setRequiredDate(command.getRequiredDate());
        reservation.setStatus(ACTIVE);
        reservation.setAllocationSnapshot(snapshot);
        reservation.setFactoryCode(command.getFactoryCode());
        if (reservationMapper.insert(reservation) != 1) {
            throw new CheckedException("物料预留主单保存失败，整笔预留回滚");
        }

        for (WmsMaterialReservationAllocationDTO allocation : allocations) {
            reserveAllocation(reservation, allocation);
        }
        return result(reservation, allocations);
    }

    private void validateCreate(WmsMaterialReservationCreateDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getSourceType())
                || command.getSourceId() == null || CollUtil.isEmpty(command.getAllocations())) {
            throw new CheckedException("物料预留缺少请求号、来源或分配明细");
        }
        if (StrUtil.isBlank(command.getFactoryCode()) || StrUtil.isBlank(command.getWarehouseCode())) {
            throw new CheckedException("物料预留必须指定工厂和需求领料仓库");
        }
        if (command.getRequestId().length() > 64 || command.getSourceType().length() > 50) {
            throw new CheckedException("物料预留请求号最多64位、来源类型最多50位");
        }
    }

    private MdmWarehouseEntity validateWarehouse(WmsMaterialReservationCreateDTO command) {
        requireScopedOrganization();
        Long tenantId = TenantContextHolder.getTenantId();
        String factoryCode = FactoryContextHolder.getFactoryCode();
        if (!Objects.equals(factoryCode, command.getFactoryCode())) {
            throw new CheckedException("物料预留必须具有一致的租户和工厂上下文");
        }
        R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(command.getWarehouseCode().trim());
        MdmWarehouseEntity warehouse = response == null || !response.isOk() ? null : response.getData();
        if (warehouse == null || !Boolean.TRUE.equals(warehouse.getStatus())) {
            throw new CheckedException("预留仓库[" + command.getWarehouseCode() + "]不存在或未启用");
        }
        if (!Objects.equals(tenantId, warehouse.getTenantId())) {
            throw new CheckedException("预留仓库[" + warehouse.getWarehouseCode() + "]不属于当前租户");
        }
        if (!Objects.equals(factoryCode, warehouse.getFactoryCode())) {
            throw new CheckedException("预留仓库[" + warehouse.getWarehouseCode() + "]不属于工厂["
                    + factoryCode + "]");
        }
        return warehouse;
    }

    private String reservationNo(WmsMaterialReservationCreateDTO command) {
        if ("PLAN_MRP_MAIN".equals(command.getSourceType())) {
            return "MRP-M-" + command.getSourceId();
        }
        if ("PLAN_MRP_SUBSTITUTE".equals(command.getSourceType())) {
            return "MRP-S-" + command.getSourceId();
        }
        return "MRP-" + command.getSourceId();
    }

    private List<WmsMaterialReservationAllocationDTO> normalize(List<WmsMaterialReservationAllocationDTO> source) {
        Map<String, WmsMaterialReservationAllocationDTO> grouped = new TreeMap<>();
        for (WmsMaterialReservationAllocationDTO item : source) {
            if (item == null || StrUtil.isBlank(item.getActualMatCode())
                    || StrUtil.isBlank(item.getRequirementMatCode())
                    || item.getActualNum() == null || item.getActualNum().compareTo(BigDecimal.ZERO) <= 0
                    || item.getRequirementNum() == null || item.getRequirementNum().compareTo(BigDecimal.ZERO) <= 0
                    || item.getSubstituteRatio() == null || item.getSubstituteRatio().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("物料预留分配的需求物料、实际物料、比例和数量必须有效");
            }
            normalizeCompliance(item);
            String key = item.getRequirementMatCode() + "#" + item.getActualMatCode() + "#"
                    + item.getSubstituteRatio().stripTrailingZeros().toPlainString() + "#" + JSONUtil.toJsonStr(Map.of(
                    "customers", item.getApplicableCustomerCodes(), "suppliers",
                    item.getCertifiedSupplierCodes(),
                    "quality", item.getAllowedQualityResults(), "mode", item.getComplianceMode(),
                    "certificate", StrUtil.nullToEmpty(item.getSupplierCertificateRef()),
                    "certificateEnd", Objects.toString(item.getSupplierCertificateValidEnd(), ""),
                    "permit", StrUtil.nullToEmpty(item.getDeviationPermitNo()),
                    "permitEnd", Objects.toString(item.getDeviationValidEnd(), "")));
            WmsMaterialReservationAllocationDTO target = grouped.computeIfAbsent(key,
                    ignored -> new WmsMaterialReservationAllocationDTO()
                            .setRequirementMatCode(item.getRequirementMatCode())
                            .setRequirementMatName(item.getRequirementMatName())
                            .setActualMatCode(item.getActualMatCode())
                            .setActualMatName(item.getActualMatName())
                            .setActualMatUnit(item.getActualMatUnit())
                            .setSubstituteRatio(item.getSubstituteRatio())
                            .setApplicableCustomerCodes(item.getApplicableCustomerCodes())
                            .setCertifiedSupplierCodes(item.getCertifiedSupplierCodes())
                            .setSupplierCertificateRef(item.getSupplierCertificateRef())
                            .setSupplierCertificateValidEnd(item.getSupplierCertificateValidEnd())
                            .setInspectionLevel(item.getInspectionLevel())
                            .setReferenceUnitPrice(item.getReferenceUnitPrice())
                            .setCurrency(item.getCurrency())
                            .setAllowedQualityResults(item.getAllowedQualityResults())
                            .setComplianceMode(item.getComplianceMode())
                            .setDeviationPermitNo(item.getDeviationPermitNo())
                            .setDeviationReason(item.getDeviationReason())
                            .setDeviationValidEnd(item.getDeviationValidEnd())
                            .setStockAllocations(new ArrayList<>())
                            .setRequirementNum(BigDecimal.ZERO)
                            .setActualNum(BigDecimal.ZERO));
            target.setRequirementNum(target.getRequirementNum().add(item.getRequirementNum()));
            target.setActualNum(target.getActualNum().add(item.getActualNum()));
            mergeStockAllocations(target, item);
        }
        grouped.values().forEach(item -> validateStockAllocations(item, item.getActualNum()));
        return new ArrayList<>(grouped.values());
    }

    private void mergeStockAllocations(WmsMaterialReservationAllocationDTO target,
                                       WmsMaterialReservationAllocationDTO source) {
        if (CollUtil.isEmpty(source.getStockAllocations())) {
            return;
        }
        Map<Long, WmsMaterialReservationStockAllocationDTO> merged = target.getStockAllocations().stream()
                .collect(java.util.stream.Collectors.toMap(WmsMaterialReservationStockAllocationDTO::getStockId,
                        item -> item, (left, right) -> left, LinkedHashMap::new));
        for (WmsMaterialReservationStockAllocationDTO item : source.getStockAllocations()) {
            if (item == null || item.getStockId() == null || item.getAllocatedNum() == null
                    || item.getAllocatedNum().compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("精确库存行Pegging的库存ID和分配数量必须有效");
            }
            WmsMaterialReservationStockAllocationDTO existing = merged.get(item.getStockId());
            if (existing == null) {
                merged.put(item.getStockId(), new WmsMaterialReservationStockAllocationDTO()
                        .setStockId(item.getStockId()).setAllocatedNum(item.getAllocatedNum())
                        .setAllocationSequence(item.getAllocationSequence()));
            } else {
                existing.setAllocatedNum(existing.getAllocatedNum().add(item.getAllocatedNum()));
                if (existing.getAllocationSequence() == null || item.getAllocationSequence() != null
                        && item.getAllocationSequence() < existing.getAllocationSequence()) {
                    existing.setAllocationSequence(item.getAllocationSequence());
                }
            }
        }
        target.setStockAllocations(merged.values().stream().sorted(Comparator
                .comparing(WmsMaterialReservationStockAllocationDTO::getAllocationSequence,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(WmsMaterialReservationStockAllocationDTO::getStockId)).toList());
    }

    private void validateStockAllocations(WmsMaterialReservationAllocationDTO allocation, BigDecimal expected) {
        if (CollUtil.isEmpty(allocation.getStockAllocations())) {
            return;
        }
        BigDecimal total = allocation.getStockAllocations().stream()
                .map(WmsMaterialReservationStockAllocationDTO::getAllocatedNum)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(expected) != 0) {
            throw new CheckedException("物料[" + allocation.getActualMatCode()
                    + "]精确库存行Pegging数量与申请预留数量不一致");
        }
    }

    private void reserveAllocation(WmsMaterialReservationEntity reservation,
                                   WmsMaterialReservationAllocationDTO allocation) {
        reserveAllocation(reservation, allocation, null, DETAIL_ORIGINAL);
    }

    private List<WmsMaterialReservationDetailEntity> reserveAllocation(
            WmsMaterialReservationEntity reservation,
            WmsMaterialReservationAllocationDTO allocation,
            Long adjustmentId,
            String detailType) {
        validateAllocationCompliance(reservation, allocation);
        List<WmsLiveStockEntity> allStocks = liveStockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getMatCode, allocation.getActualMatCode())
                .eq(StrUtil.isNotBlank(reservation.getFactoryCode()), WmsLiveStockEntity::getFactoryCode,
                        reservation.getFactoryCode())
                .eq(WmsLiveStockEntity::getWarehouseCode, reservation.getWarehouseCode())
                .eq(WmsLiveStockEntity::getStatus, 0)
                .gt(WmsLiveStockEntity::getMatNum, 0)
                .and(wrapper -> wrapper.isNull(WmsLiveStockEntity::getMatValidEndDate)
                        .or().ge(WmsLiveStockEntity::getMatValidEndDate, LocalDate.now()))
                .orderByAsc(WmsLiveStockEntity::getMatValidEndDate)
                .orderByAsc(WmsLiveStockEntity::getInTime)
                .last("FOR UPDATE")).stream().filter(WmsStockAvailability::isUsable).toList();
        List<WmsLiveStockEntity> stocks = allStocks.stream()
                .filter(stock -> isCompliantStock(stock, allocation)).toList();
        Map<Long, BigDecimal> preferred = CollUtil.isEmpty(allocation.getStockAllocations()) ? Map.of()
                : allocation.getStockAllocations().stream().collect(java.util.stream.Collectors.toMap(
                WmsMaterialReservationStockAllocationDTO::getStockId,
                WmsMaterialReservationStockAllocationDTO::getAllocatedNum, BigDecimal::add,
                LinkedHashMap::new));
        if (!preferred.isEmpty()) {
            Set<Long> eligibleIds = stocks.stream().map(WmsLiveStockEntity::getId)
                    .collect(java.util.stream.Collectors.toSet());
            List<Long> invalid = preferred.keySet().stream().filter(id -> !eligibleIds.contains(id)).toList();
            if (!invalid.isEmpty()) {
                throw new CheckedException("物料[" + allocation.getActualMatCode()
                        + "]Pegging库存行已不存在、跨仓或不再满足质量/合规约束：" + invalid);
            }
            Map<Long, WmsLiveStockEntity> byId = stocks.stream().collect(java.util.stream.Collectors.toMap(
                    WmsLiveStockEntity::getId, item -> item));
            stocks = allocation.getStockAllocations().stream().map(item -> byId.get(item.getStockId())).toList();
        }
        BigDecimal physical = allStocks.stream().map(this::stockNum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal compliantPhysical = stocks.stream().map(this::stockNum).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal safety = safetyStock(allocation.getActualMatCode());
        Set<Long> stockIds = allStocks.stream().map(WmsLiveStockEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> salesReserved = salesStockReservationService.activeReservedNums(stockIds);
        BigDecimal activeReserved = allStocks.stream().map(stock -> activeReservedNum(stock.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(salesReserved.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal compliantReserved = stocks.stream().map(stock -> activeReservedNum(stock.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(stocks.stream()
                        .map(stock -> salesReserved.getOrDefault(stock.getId(), BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal usable = physical.subtract(safety).subtract(activeReserved).max(BigDecimal.ZERO)
                .min(compliantPhysical.subtract(compliantReserved).max(BigDecimal.ZERO));
        if (usable.compareTo(allocation.getActualNum()) < 0) {
            throw new CheckedException("物料[" + allocation.getActualMatCode() + "]扣除安全库存和已有预留后仅可预留"
                    + usable.stripTrailingZeros().toPlainString() + "，本次需要"
                    + allocation.getActualNum().stripTrailingZeros().toPlainString());
        }

        BigDecimal remaining = allocation.getActualNum();
        List<WmsMaterialReservationDetailEntity> created = new ArrayList<>();
        for (WmsLiveStockEntity stock : stocks) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal rowAvailable = stockNum(stock).subtract(activeReservedNum(stock.getId()))
                    .subtract(salesReserved.getOrDefault(stock.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            BigDecimal requested = preferred.get(stock.getId());
            BigDecimal take = requested == null ? rowAvailable.min(remaining) : requested;
            if (requested != null && rowAvailable.compareTo(requested) < 0) {
                throw new CheckedException("Pegging库存行[" + stock.getId() + "]当前仅剩可预留"
                        + rowAvailable.stripTrailingZeros().toPlainString() + "，计划占用"
                        + requested.stripTrailingZeros().toPlainString() + "；请重新执行MRP");
            }
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            WmsSerialStockGuard.requireWhole(stock, take);
            WmsMaterialReservationDetailEntity detail = new WmsMaterialReservationDetailEntity();
            detail.setReservationId(reservation.getId());
            detail.setReservationNo(reservation.getReservationNo());
            detail.setLiveStockId(stock.getId());
            detail.setRequirementMatCode(allocation.getRequirementMatCode());
            detail.setActualMatCode(allocation.getActualMatCode());
            detail.setActualMatName(allocation.getActualMatName());
            detail.setActualMatUnit(allocation.getActualMatUnit());
            detail.setSubstituteRatio(allocation.getSubstituteRatio());
            detail.setRequirementNum(take.divide(allocation.getSubstituteRatio(), 8, java.math.RoundingMode.HALF_UP));
            detail.setReservedNum(take);
            detail.setConsumedNum(BigDecimal.ZERO);
            detail.setReturnedNum(BigDecimal.ZERO);
            detail.setReleasedNum(BigDecimal.ZERO);
            detail.setDetailType(detailType);
            detail.setAdjustmentId(adjustmentId);
            detail.setWarehouseCode(stock.getWarehouseCode());
            detail.setWarehouseName(stock.getWarehouseName());
            detail.setSiteCode(stock.getSiteCode());
            detail.setSiteName(stock.getSiteName());
            detail.setContainerCode(stock.getContainerCode());
            detail.setMatBatch(stock.getMatBatch());
            detail.setSupplierCode(stock.getSupplierCode());
            detail.setQualityResult(stock.getQualityResult());
            detail.setComplianceMode(allocation.getComplianceMode());
            detail.setComplianceReferenceNo("DEVIATION".equals(allocation.getComplianceMode())
                    ? allocation.getDeviationPermitNo()
                    : allocation.getSupplierCertificateRef());
            detail.setComplianceValidEnd("DEVIATION".equals(allocation.getComplianceMode())
                    ? allocation.getDeviationValidEnd()
                    : allocation.getSupplierCertificateValidEnd());
            detail.setComplianceSnapshot(JSONUtil.toJsonStr(allocation));
            detail.setStatus(ACTIVE);
            detail.setFactoryCode(reservation.getFactoryCode());
            if (detailMapper.insert(detail) != 1) {
                throw new CheckedException("物料预留分配明细保存失败，整笔预留回滚");
            }
            created.add(detail);
            remaining = remaining.subtract(take);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new CheckedException("物料[" + allocation.getActualMatCode() + "]可用库存分配失败，请重试");
        }
        return created;
    }

    private void normalizeCompliance(WmsMaterialReservationAllocationDTO item) {
        item.setApplicableCustomerCodes(normalizeCodes(item.getApplicableCustomerCodes(), false));
        item.setCertifiedSupplierCodes(normalizeCodes(item.getCertifiedSupplierCodes(), false));
        item.setAllowedQualityResults(normalizeCodes(item.getAllowedQualityResults(), true));
        item.setComplianceMode(StrUtil.blankToDefault(item.getComplianceMode(), "STANDARD").toUpperCase(Locale.ROOT));
        item.setInspectionLevel(StrUtil.blankToDefault(item.getInspectionLevel(), "NORMAL").toUpperCase(Locale.ROOT));
        if (!Set.of("STANDARD", "DEVIATION").contains(item.getComplianceMode())) {
            throw new CheckedException("物料[" + item.getActualMatCode() + "]合规方式无效");
        }
        if (!Set.of("NORMAL", "TIGHTENED").contains(item.getInspectionLevel())) {
            throw new CheckedException("物料[" + item.getActualMatCode() + "]检验等级无效");
        }
    }

    private List<String> normalizeCodes(List<String> source, boolean uppercase) {
        if (CollUtil.isEmpty(source)) {
            return List.of();
        }
        return source.stream().filter(StrUtil::isNotBlank).map(StrUtil::trim)
                .map(code -> uppercase ? code.toUpperCase(Locale.ROOT) : code)
                .distinct().toList();
    }

    private void validateAllocationCompliance(WmsMaterialReservationEntity reservation,
                                              WmsMaterialReservationAllocationDTO allocation) {
        if (CollUtil.isNotEmpty(allocation.getApplicableCustomerCodes())
                && !allocation.getApplicableCustomerCodes().contains(reservation.getCustomerCode())) {
            throw new CheckedException("替代料[" + allocation.getActualMatCode() + "]未授权给客户["
                    + StrUtil.blankToDefault(reservation.getCustomerCode(), "未指定") + "]");
        }
        LocalDate requiredDate = Optional.ofNullable(reservation.getRequiredDate()).orElse(LocalDate.now());
        LocalDate validationDate = requiredDate.isAfter(LocalDate.now()) ? requiredDate : LocalDate.now();
        if (CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())
                && (StrUtil.isBlank(allocation.getSupplierCertificateRef())
                || allocation.getSupplierCertificateValidEnd() == null
                || validationDate.isAfter(allocation.getSupplierCertificateValidEnd()))) {
            throw new CheckedException("替代料[" + allocation.getActualMatCode() + "]供应商认证缺失或在使用日期前失效");
        }
        if ("DEVIATION".equals(allocation.getComplianceMode())
                && (StrUtil.isBlank(allocation.getDeviationPermitNo())
                || StrUtil.isBlank(allocation.getDeviationReason())
                || allocation.getDeviationValidEnd() == null
                || validationDate.isAfter(allocation.getDeviationValidEnd()))) {
            throw new CheckedException("替代料[" + allocation.getActualMatCode() + "]偏离许可缺失或在使用日期前失效");
        }
        validateCurrentAuthorizationLedger(reservation, allocation, validationDate);
    }

    private boolean isCompliantStock(WmsLiveStockEntity stock, WmsMaterialReservationAllocationDTO allocation) {
        if (CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())
                && !allocation.getCertifiedSupplierCodes().contains(stock.getSupplierCode())) {
            return false;
        }
        String quality = StrUtil.blankToDefault(StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        if ("TIGHTENED".equals(allocation.getInspectionLevel()) && !"QUALIFIED".equals(quality)) {
            return false;
        }
        return CollUtil.isEmpty(allocation.getAllowedQualityResults())
                || allocation.getAllowedQualityResults().contains(quality);
    }

    private BigDecimal safetyStock(String materialCode) {
        try {
            MdmMaterialEntity material = remoteMaterialService.getDetails(materialCode).getData();
            return material == null || material.getMinStock() == null ? BigDecimal.ZERO : material.getMinStock();
        } catch (Exception ex) {
            throw new CheckedException("读取物料[" + materialCode + "]安全库存失败，禁止降级预留", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean release(WmsMaterialReservationReleaseDTO command) {
        requireScopedOrganization();
        if (command == null || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getSourceType())
                || command.getSourceId() == null || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("释放预留缺少请求号、来源或原因");
        }
        if (command.getRequestId().length() > 64 || command.getSourceType().length() > 50
                || command.getReason().length() > 500) {
            throw new CheckedException("释放预留请求号最多64位、来源类型最多50位、原因最多500位");
        }
        WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getSourceType, command.getSourceType())
                        .eq(WmsMaterialReservationEntity::getSourceId, command.getSourceId())
                        .last("FOR UPDATE"),
                false);
        if (reservation == null) {
            return Boolean.TRUE;
        }
        if (RELEASED.equals(reservation.getStatus())) {
            // 历史零行写入可能留下已释放主单和活动明细，不能只凭主状态返回释放成功。
            Long activeDetails = detailMapper.selectCount(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                    .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                    .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE));
            if (!Long.valueOf(0).equals(activeDetails)) {
                throw new CheckedException("已释放预留仍有活动明细或明细状态不明，请先核对预留账务");
            }
            if (!Objects.equals(reservation.getReleaseRequestId(), command.getRequestId())
                    || !Objects.equals(reservation.getReleaseReason(), command.getReason())) {
                throw new CheckedException("已释放预留的原请求号或原因不一致，请核对原释放记录");
            }
            return Boolean.TRUE;
        }
        if (CONSUMED.equals(reservation.getStatus())) {
            throw new CheckedException("预留[" + reservation.getReservationNo() + "]已全部出库，不能释放");
        }
        if (!ACTIVE.equals(reservation.getStatus())) {
            throw new CheckedException("预留[" + reservation.getReservationNo() + "]状态无效，不能释放");
        }
        List<WmsMaterialReservationDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                        .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE));
        for (WmsMaterialReservationDetailEntity detail : details) {
            BigDecimal remaining = remaining(detail);
            if (detailMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationDetailEntity>()
                    .eq(WmsMaterialReservationDetailEntity::getId, detail.getId())
                    .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE)
                    .set(WmsMaterialReservationDetailEntity::getReleasedNum,
                            optional(detail.getReleasedNum()).add(remaining))
                    .set(WmsMaterialReservationDetailEntity::getStatus, RELEASED)) != 1) {
                throw new CheckedException("物料预留明细释放失败，整笔释放回滚");
            }
        }
        MomUser user = SecurityUtils.getUser();
        if (reservationMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationEntity>()
                .eq(WmsMaterialReservationEntity::getId, reservation.getId())
                .eq(WmsMaterialReservationEntity::getStatus, ACTIVE)
                .set(WmsMaterialReservationEntity::getStatus, RELEASED)
                .set(WmsMaterialReservationEntity::getReleaseRequestId, command.getRequestId())
                .set(WmsMaterialReservationEntity::getReleaseReason, command.getReason())
                .set(WmsMaterialReservationEntity::getReleaseBy, user == null ? "SYSTEM" : user.getUsername())
                .set(WmsMaterialReservationEntity::getReleaseTime, LocalDateTime.now())) != 1) {
            throw new CheckedException("物料预留主单释放失败，整笔释放回滚");
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean reallocate(WmsMaterialReservationReallocateDTO command) {
        requireScopedOrganization();
        validateReallocate(command);
        WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getSourceType, command.getSourceType())
                        .eq(WmsMaterialReservationEntity::getSourceId, command.getSourceId())
                        .last("FOR UPDATE"),
                false);
        if (reservation == null || !ACTIVE.equals(reservation.getStatus())) {
            throw new CheckedException("来源记录没有有效的WMS物料预留，不能重配批次");
        }
        WmsMaterialReservationAdjustmentEntity existing = adjustmentMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationAdjustmentEntity>()
                        .eq(WmsMaterialReservationAdjustmentEntity::getRequestId, command.getRequestId()),
                false);
        if (existing != null) {
            if (Objects.equals(existing.getReservationId(), reservation.getId())
                    && "SUCCEEDED".equals(existing.getStatus())
                    && Objects.equals(existing.getReason(), command.getReason())) {
                return Boolean.TRUE;
            }
            throw new CheckedException("批次重配请求号已被使用，请刷新后重试");
        }

        List<WmsMaterialReservationDetailEntity> allDetails = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId)
                        .last("FOR UPDATE"));
        if (allDetails.stream().anyMatch(item -> optional(item.getConsumedNum()).compareTo(BigDecimal.ZERO) > 0)) {
            throw new CheckedException("预留[" + reservation.getReservationNo()
                    + "]已经发生实物消耗，必须通过退料或业务冲销处理，不能自动重配批次");
        }
        Long outDetailCount = outBillDetailMapper.selectCount(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                .eq(WmsOutBillDetailEntity::getReservationNo, reservation.getReservationNo()));
        if (outDetailCount != null && outDetailCount > 0) {
            throw new CheckedException("预留[" + reservation.getReservationNo()
                    + "]已经生成出库明细，请先撤销仓库拣配/出库单后再重配");
        }
        List<WmsMaterialReservationDetailEntity> activeDetails = allDetails.stream()
                .filter(item -> ACTIVE.equals(item.getStatus()) && remaining(item).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (activeDetails.isEmpty()) {
            throw new CheckedException("预留[" + reservation.getReservationNo() + "]没有可重配的有效余额");
        }

        Set<Long> stockIds = activeDetails.stream().map(WmsMaterialReservationDetailEntity::getLiveStockId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, WmsLiveStockEntity> stocks = stockIds.isEmpty() ? Map.of()
                : liveStockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                        .in(WmsLiveStockEntity::getId, stockIds)
                        .orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE")).stream()
                .collect(java.util.stream.Collectors.toMap(WmsLiveStockEntity::getId, item -> item));
        Map<Long, BigDecimal> activeByStock = new HashMap<>();
        Map<Long, BigDecimal> activeSalesByStock = salesStockReservationService.activeReservedNums(stockIds);
        for (Long stockId : stockIds) {
            activeByStock.put(stockId, activeReservedNum(stockId)
                    .add(activeSalesByStock.getOrDefault(stockId, BigDecimal.ZERO)));
        }

        List<WmsMaterialReservationDetailEntity> riskyDetails = new ArrayList<>();
        List<Map<String, Object>> riskSnapshot = new ArrayList<>();
        Map<String, WmsMaterialReservationAllocationDTO> reallocationGroups = new LinkedHashMap<>();
        for (WmsMaterialReservationDetailEntity detail : activeDetails) {
            WmsLiveStockEntity stock = stocks.get(detail.getLiveStockId());
            List<String> issues = new ArrayList<>(complianceIntegrityIssues(reservation, detail, stock));
            List<String> blocking = issues.stream().filter(NON_REALLOCATABLE_COMPLIANCE_ISSUES::contains).toList();
            if (!blocking.isEmpty()) {
                throw new CheckedException("预留[" + reservation.getReservationNo() + "]的认证/许可授权已失效或快照不完整（"
                        + String.join("、", blocking) + "），必须撤销替代方案并重新审批，禁止自动重配绕过授权");
            }
            boolean stockDeficit = stock == null || stockNum(stock).compareTo(
                    activeByStock.getOrDefault(detail.getLiveStockId(), BigDecimal.ZERO)) < 0;
            boolean reallocationRisk = !WmsStockAvailability.isUsable(stock) || stockDeficit
                    || issues.stream().anyMatch(REALLOCATABLE_COMPLIANCE_ISSUES::contains);
            if (!reallocationRisk) {
                continue;
            }
            if (!WmsStockAvailability.isUsable(stock)) {
                addIssue(issues, "ACTIVE_RESERVED_STOCK_UNAVAILABLE");
            }
            if (stockDeficit) {
                addIssue(issues, "ACTIVE_RESERVED_STOCK_DEFICIT");
            }
            riskyDetails.add(detail);
            riskSnapshot.add(reallocationSnapshot(detail, issues));
            WmsMaterialReservationAllocationDTO allocation;
            try {
                allocation = JSONUtil.toBean(detail.getComplianceSnapshot(), WmsMaterialReservationAllocationDTO.class);
                normalizeCompliance(allocation);
            } catch (Exception ex) {
                throw new CheckedException("预留明细[" + detail.getId() + "]合规快照损坏，必须撤销替代方案后重新审批");
            }
            String groupKey = detail.getComplianceSnapshot();
            WmsMaterialReservationAllocationDTO grouped = reallocationGroups.get(groupKey);
            if (grouped == null) {
                grouped = allocation;
                grouped.setActualNum(BigDecimal.ZERO);
                grouped.setRequirementNum(BigDecimal.ZERO);
                reallocationGroups.put(groupKey, grouped);
            }
            BigDecimal quantity = remaining(detail);
            grouped.setActualNum(grouped.getActualNum().add(quantity));
            grouped.setRequirementNum(grouped.getRequirementNum().add(quantity.divide(
                    grouped.getSubstituteRatio(), 8, java.math.RoundingMode.HALF_UP)));
        }
        if (riskyDetails.isEmpty()) {
            throw new CheckedException("预留[" + reservation.getReservationNo() + "]当前批次已恢复健康，无需重配");
        }

        MomUser user = SecurityUtils.getUser();
        WmsMaterialReservationAdjustmentEntity adjustment = new WmsMaterialReservationAdjustmentEntity();
        adjustment.setReservationId(reservation.getId());
        adjustment.setReservationNo(reservation.getReservationNo());
        adjustment.setRequestId(command.getRequestId());
        adjustment.setAdjustmentType(ADJUSTMENT_COMPLIANCE_REALLOCATION);
        adjustment.setReason(command.getReason());
        adjustment.setStatus("EXECUTING");
        adjustment.setRiskSnapshot(JSONUtil.toJsonStr(riskSnapshot));
        adjustment.setOperatedBy(user == null ? "SYSTEM" : user.getUsername());
        adjustment.setOperatedByName(user == null ? "系统" : user.getName());
        adjustment.setOperatedTime(LocalDateTime.now());
        adjustment.setFactoryCode(reservation.getFactoryCode());
        if (adjustmentMapper.insert(adjustment) != 1) {
            throw new CheckedException("物料预留重配记录保存失败，整笔重配回滚");
        }

        for (WmsMaterialReservationDetailEntity detail : riskyDetails) {
            BigDecimal quantity = remaining(detail);
            if (detailMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationDetailEntity>()
                    .eq(WmsMaterialReservationDetailEntity::getId, detail.getId())
                    .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE)
                    .set(WmsMaterialReservationDetailEntity::getReleasedNum,
                            optional(detail.getReleasedNum()).add(quantity))
                    .set(WmsMaterialReservationDetailEntity::getStatus, RELEASED)) != 1) {
                throw new CheckedException("物料预留重配原明细释放失败，整笔重配回滚");
            }
        }
        List<WmsMaterialReservationDetailEntity> created = new ArrayList<>();
        for (WmsMaterialReservationAllocationDTO allocation : reallocationGroups.values()) {
            created.addAll(reserveAllocation(reservation, allocation, adjustment.getId(),
                    DETAIL_COMPLIANCE_REALLOCATION));
        }
        List<Map<String, Object>> resultSnapshot = created.stream()
                .map(item -> reallocationSnapshot(item, List.of())).toList();
        if (adjustmentMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationAdjustmentEntity>()
                .eq(WmsMaterialReservationAdjustmentEntity::getId, adjustment.getId())
                .eq(WmsMaterialReservationAdjustmentEntity::getStatus, "EXECUTING")
                .set(WmsMaterialReservationAdjustmentEntity::getStatus, "SUCCEEDED")
                .set(WmsMaterialReservationAdjustmentEntity::getResultSnapshot,
                        JSONUtil.toJsonStr(resultSnapshot))) != 1) {
            throw new CheckedException("物料预留重配完成状态保存失败，整笔重配回滚");
        }
        return Boolean.TRUE;
    }

    private void validateReallocate(WmsMaterialReservationReallocateDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getSourceType())
                || command.getSourceId() == null || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("批次重配缺少请求号、来源或操作原因");
        }
        if (command.getRequestId().length() > 64 || command.getSourceType().length() > 50
                || command.getReason().length() > 500) {
            throw new CheckedException("批次重配请求号最多64位、来源类型最多50位、原因最多500位");
        }
    }

    private Map<String, Object> reallocationSnapshot(WmsMaterialReservationDetailEntity detail,
                                                     List<String> issues) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("detailId", detail.getId());
        snapshot.put("liveStockId", detail.getLiveStockId());
        snapshot.put("requirementMatCode", detail.getRequirementMatCode());
        snapshot.put("actualMatCode", detail.getActualMatCode());
        snapshot.put("quantity", remaining(detail));
        snapshot.put("warehouseCode", detail.getWarehouseCode());
        snapshot.put("siteCode", detail.getSiteCode());
        snapshot.put("containerCode", detail.getContainerCode());
        snapshot.put("matBatch", detail.getMatBatch());
        snapshot.put("supplierCode", detail.getSupplierCode());
        snapshot.put("qualityResult", detail.getQualityResult());
        snapshot.put("complianceMode", detail.getComplianceMode());
        snapshot.put("complianceReferenceNo", detail.getComplianceReferenceNo());
        snapshot.put("complianceValidEnd", detail.getComplianceValidEnd());
        snapshot.put("issues", issues);
        return snapshot;
    }

    @Override
    public BigDecimal activeReservedNum(Long liveStockId) {
        requireScopedOrganization();
        if (liveStockId == null) {
            return BigDecimal.ZERO;
        }
        return detailMapper.selectList(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getLiveStockId, liveStockId)
                        .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE))
                .stream().map(this::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
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
            detailMapper.selectList(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                            .in(WmsMaterialReservationDetailEntity::getLiveStockId, batch)
                            .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE))
                    .forEach(detail -> result.merge(detail.getLiveStockId(), remaining(detail), BigDecimal::add));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsLiveStockEntity> availableReservedStocks(String reservationNo, String actualMatCode,
                                                            String warehouseCode) {
        requireScopedOrganization();
        if (StrUtil.isBlank(reservationNo) || StrUtil.isBlank(actualMatCode)) {
            return List.of();
        }
        WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getReservationNo, reservationNo)
                        .last("FOR UPDATE"),
                false);
        if (reservation == null || !ACTIVE.equals(reservation.getStatus())) {
            throw new CheckedException("预留[" + reservationNo + "]不存在或已失效");
        }
        List<WmsMaterialReservationDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationNo, reservationNo)
                        .eq(WmsMaterialReservationDetailEntity::getActualMatCode, actualMatCode)
                        .eq(StrUtil.isNotBlank(warehouseCode), WmsMaterialReservationDetailEntity::getWarehouseCode,
                                warehouseCode)
                        .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE)
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId));
        Map<Long, BigDecimal> quantitiesByStock = new LinkedHashMap<>();
        for (WmsMaterialReservationDetailEntity detail : details) {
            BigDecimal quantity = remaining(detail);
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            quantitiesByStock.merge(detail.getLiveStockId(), quantity, BigDecimal::add);
        }
        List<WmsLiveStockEntity> result = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : quantitiesByStock.entrySet()) {
            WmsLiveStockEntity stock = liveStockMapper.selectById(entry.getKey());
            if (WmsStockAvailability.isUsable(stock)) {
                stock.setMatNum(entry.getValue().min(stockNum(stock)).doubleValue());
                result.add(stock);
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateOutboundReservation(List<WmsOutBillDetailEntity> details) {
        requireScopedOrganization();
        List<WmsOutBillDetailEntity> reservedDetails = Optional.ofNullable(details).orElse(List.of()).stream()
                .filter(item -> item != null && StrUtil.isNotBlank(item.getReservationNo())
                        && !StrUtil.startWith(item.getReservationNo(), WmsSalesStockReservationService.PREFIX))
                .toList();
        if (reservedDetails.isEmpty()) {
            return;
        }
        Map<String, List<WmsOutBillDetailEntity>> byReservation = reservedDetails.stream()
                .collect(java.util.stream.Collectors.groupingBy(WmsOutBillDetailEntity::getReservationNo,
                        TreeMap::new, java.util.stream.Collectors.toList()));
        Map<Long, BigDecimal> physicalBalances = new HashMap<>();
        for (Map.Entry<String, List<WmsOutBillDetailEntity>> entry : byReservation.entrySet()) {
            WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                    new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                            .eq(WmsMaterialReservationEntity::getReservationNo, entry.getKey())
                            .last("FOR UPDATE"),
                    false);
            if (reservation == null || !ACTIVE.equals(reservation.getStatus())) {
                throw new CheckedException("预留[" + entry.getKey() + "]不存在或已失效，请重新生成出库批次");
            }
            List<WmsMaterialReservationDetailEntity> candidates = detailMapper.selectList(
                    new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                            .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                            .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE)
                            .orderByAsc(WmsMaterialReservationDetailEntity::getId)
                            .last("FOR UPDATE"));
            Map<Long, BigDecimal> balances = candidates.stream().collect(java.util.stream.Collectors.toMap(
                    WmsMaterialReservationDetailEntity::getId, this::remaining));
            Set<Long> stockIds = candidates.stream().map(WmsMaterialReservationDetailEntity::getLiveStockId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            Map<Long, WmsLiveStockEntity> stocks = stockIds.isEmpty() ? Map.of()
                    : liveStockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                            .in(WmsLiveStockEntity::getId, stockIds)
                            .orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE")).stream()
                    .collect(java.util.stream.Collectors.toMap(WmsLiveStockEntity::getId, item -> item));
            stocks.values().forEach(stock -> physicalBalances.putIfAbsent(stock.getId(), stockNum(stock)));
            for (WmsOutBillDetailEntity out : entry.getValue()) {
                if (out.getMatNum() == null || out.getMatNum() <= 0) {
                    throw new CheckedException("预留出库明细的物料数量必须大于0");
                }
                BigDecimal required = BigDecimal.valueOf(out.getMatNum());
                for (WmsMaterialReservationDetailEntity candidate : candidates) {
                    if (required.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    if (!outboundMatches(out, candidate)) {
                        continue;
                    }
                    BigDecimal balance = balances.getOrDefault(candidate.getId(), BigDecimal.ZERO);
                    if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    WmsLiveStockEntity stock = stocks.get(candidate.getLiveStockId());
                    if (!WmsStockAvailability.isUsable(stock) || !matchesIssueBarcode(out, stock)
                            || !complianceIntegrityIssues(reservation, candidate, stock).isEmpty()) {
                        continue;
                    }
                    BigDecimal physicalBalance = physicalBalances.getOrDefault(
                            candidate.getLiveStockId(), BigDecimal.ZERO);
                    BigDecimal take = balance.min(physicalBalance).min(required);
                    if (take.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    WmsSerialStockGuard.requireWhole(stock, take);
                    balances.put(candidate.getId(), balance.subtract(take));
                    physicalBalances.put(candidate.getLiveStockId(), physicalBalance.subtract(take));
                    required = required.subtract(take);
                }
                if (required.compareTo(BigDecimal.ZERO) > 0) {
                    throw new CheckedException("预留[" + entry.getKey() + "]中所选物料[" + out.getMatCode()
                            + "]批次或合规余额已变化，请重新生成出库批次");
                }
            }
        }
    }

    private boolean outboundMatches(WmsOutBillDetailEntity out,
                                    WmsMaterialReservationDetailEntity candidate) {
        return (out.getSourceStockId() == null
                || Objects.equals(out.getSourceStockId(), candidate.getLiveStockId()))
                && Objects.equals(out.getMatCode(), candidate.getActualMatCode())
                && (StrUtil.isBlank(out.getRequirementMatCode())
                || Objects.equals(out.getRequirementMatCode(), candidate.getRequirementMatCode()))
                && (StrUtil.isBlank(out.getWarehouseCode())
                || Objects.equals(out.getWarehouseCode(), candidate.getWarehouseCode()))
                && (StrUtil.isBlank(out.getSiteCode()) || Objects.equals(out.getSiteCode(), candidate.getSiteCode()))
                && (StrUtil.isBlank(out.getContainerCode())
                || Objects.equals(out.getContainerCode(), candidate.getContainerCode()))
                && (StrUtil.isBlank(out.getMatBatch()) || Objects.equals(out.getMatBatch(), candidate.getMatBatch()))
                && (StrUtil.isBlank(out.getSupplierCode())
                || Objects.equals(out.getSupplierCode(), candidate.getSupplierCode()));
    }

    /** 从实际预留库存选取所扫实物。单件须指定库存原行或序列号，填写的条码不能被同批其它行替换。 */
    private boolean matchesIssueBarcode(WmsOutBillDetailEntity out, WmsLiveStockEntity stock) {
        if (StrUtil.isNotBlank(out.getMatBarcode())) {
            return Objects.equals(out.getMatBarcode(), stock.getMatBarcode());
        }
        return !Boolean.TRUE.equals(stock.getSerialManaged()) || Objects.equals(out.getSourceStockId(), stock.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsLiveStockEntity> consume(WmsOutBillDetailEntity out) {
        requireScopedOrganization();
        if (out == null || StrUtil.isBlank(out.getReservationNo())) {
            throw new CheckedException("预留出库缺少预留单号");
        }
        if (out.getMatNum() == null || !Double.isFinite(out.getMatNum()) || out.getMatNum() <= 0) {
            throw new CheckedException("生产预留消费数量必须为大于0的有限数值");
        }
        WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getReservationNo, out.getReservationNo())
                        .last("FOR UPDATE"),
                false);
        if (reservation == null || !ACTIVE.equals(reservation.getStatus())) {
            throw new CheckedException("预留[" + out.getReservationNo() + "]不存在或已失效");
        }
        BigDecimal remainingOut = BigDecimal.valueOf(out.getMatNum());
        List<WmsMaterialReservationDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                        .eq(WmsMaterialReservationDetailEntity::getActualMatCode, out.getMatCode())
                        .eq(StrUtil.isNotBlank(out.getRequirementMatCode()),
                                WmsMaterialReservationDetailEntity::getRequirementMatCode, out.getRequirementMatCode())
                        .eq(StrUtil.isNotBlank(out.getWarehouseCode()),
                                WmsMaterialReservationDetailEntity::getWarehouseCode, out.getWarehouseCode())
                        .eq(StrUtil.isNotBlank(out.getSiteCode()), WmsMaterialReservationDetailEntity::getSiteCode,
                                out.getSiteCode())
                        .eq(StrUtil.isNotBlank(out.getContainerCode()),
                                WmsMaterialReservationDetailEntity::getContainerCode, out.getContainerCode())
                        .eq(StrUtil.isNotBlank(out.getMatBatch()), WmsMaterialReservationDetailEntity::getMatBatch,
                                out.getMatBatch())
                        .eq(StrUtil.isNotBlank(out.getSupplierCode()),
                                WmsMaterialReservationDetailEntity::getSupplierCode, out.getSupplierCode())
                        .eq(out.getSourceStockId() != null, WmsMaterialReservationDetailEntity::getLiveStockId,
                                out.getSourceStockId())
                        .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE)
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId)
                        .last("FOR UPDATE"));
        BigDecimal reservable = details.stream().map(this::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reservable.compareTo(remainingOut) < 0) {
            throw new CheckedException("预留[" + out.getReservationNo() + "]中物料[" + out.getMatCode() + "]剩余数量不足");
        }
        List<WmsLiveStockEntity> reduced = new ArrayList<>();
        for (WmsMaterialReservationDetailEntity detail : details) {
            if (remainingOut.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal take = remaining(detail).min(remainingOut);
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            WmsLiveStockEntity stock = liveStockMapper.selectOne(new LambdaQueryWrapper<WmsLiveStockEntity>()
                    .eq(WmsLiveStockEntity::getId, detail.getLiveStockId()).last("FOR UPDATE"), false);
            if (stock != null && !matchesIssueBarcode(out, stock)) {
                continue;
            }
            if (!WmsStockAvailability.isUsable(stock) || stockNum(stock).compareTo(take) < 0) {
                throw new CheckedException("预留库存行[" + detail.getLiveStockId() + "]实物数量不足，禁止出库");
            }
            validateReservedStockCompliance(reservation, detail, stock);
            WmsSerialStockGuard.requireWhole(stock, take);
            // 实物扣减和预留账必须共同确认，任一写入失败均由消费事务回滚。
            BigDecimal balance = stockNum(stock).subtract(take);
            if (balance.compareTo(BigDecimal.ZERO) == 0) {
                if (liveStockMapper.deleteById(stock.getId()) != 1) {
                    throw new CheckedException("生产预留源库存删除未确认，消费整体回滚");
                }
            } else {
                if (liveStockMapper.update(null, new LambdaUpdateWrapper<WmsLiveStockEntity>()
                        .eq(WmsLiveStockEntity::getId, stock.getId())
                        .set(WmsLiveStockEntity::getMatNum, balance.doubleValue())) != 1) {
                    throw new CheckedException("生产预留源库存扣减未确认，消费整体回滚");
                }
            }
            stock.setMatNum(take.doubleValue());
            reduced.add(stock);
            BigDecimal consumed = optional(detail.getConsumedNum()).add(take);
            BigDecimal detailBalance = optional(detail.getReservedNum()).subtract(consumed)
                    .subtract(optional(detail.getReleasedNum()));
            if (detailMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationDetailEntity>()
                    .eq(WmsMaterialReservationDetailEntity::getId, detail.getId())
                    .set(WmsMaterialReservationDetailEntity::getConsumedNum, consumed)
                    .set(detailBalance.compareTo(BigDecimal.ZERO) == 0,
                            WmsMaterialReservationDetailEntity::getStatus, CONSUMED)) != 1) {
                throw new CheckedException("生产预留明细消费记账未确认，消费整体回滚");
            }
            remainingOut = remainingOut.subtract(take);
        }
        if (remainingOut.signum() > 0) {
            throw new CheckedException("所选预留库存与实物条码或数量不符，请选择原库存或扫描对应序列号");
        }
        long activeDetails = detailMapper.selectCount(new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                .eq(WmsMaterialReservationDetailEntity::getStatus, ACTIVE));
        if (activeDetails == 0) {
            if (reservationMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationEntity>()
                    .eq(WmsMaterialReservationEntity::getId, reservation.getId())
                    .set(WmsMaterialReservationEntity::getStatus, CONSUMED)) != 1) {
                throw new CheckedException("生产预留完成状态更新未确认，消费整体回滚");
            }
        }
        return reduced;
    }

    /**
     * 生产退料入库通过后，将退回的实物库存重新绑定到原正式预留。 原消费明细保留 returned_num 审计，新库存建立 RETURN_REPLENISH 子明细，避免改写历史批次轨迹。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean replenishFromProductionReturn(WmsInBillDetailEntity inDetail, Long liveStockId) {
        requireScopedOrganization();
        if (inDetail == null || StrUtil.isBlank(inDetail.getReservationNo()) || liveStockId == null) {
            return Boolean.FALSE;
        }
        if (inDetail.getId() == null || inDetail.getMatNum() == null || !Double.isFinite(inDetail.getMatNum())
                || inDetail.getMatNum() <= 0) {
            throw new CheckedException("生产退料回补缺少入库明细标识或有效数量");
        }
        WmsMaterialReservationEntity reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getReservationNo, inDetail.getReservationNo())
                        .last("FOR UPDATE"),
                false);
        if (reservation == null) {
            throw new CheckedException("生产退料关联的预留[" + inDetail.getReservationNo() + "]不存在");
        }
        // 计划已取消/释放时，退回库存作为普通可用库存，不重新制造已取消需求。
        if (RELEASED.equals(reservation.getStatus())) {
            return Boolean.FALSE;
        }
        if (!ACTIVE.equals(reservation.getStatus()) && !CONSUMED.equals(reservation.getStatus())) {
            throw new CheckedException("生产退料关联的预留状态无效，禁止回补或重新激活");
        }

        BigDecimal returnNum = BigDecimal.valueOf(inDetail.getMatNum());
        List<WmsMaterialReservationDetailEntity> existingReplenishments = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                        .eq(WmsMaterialReservationDetailEntity::getReturnBillDetailId, inDetail.getId())
                        .last("FOR UPDATE"));
        if (CollUtil.isNotEmpty(existingReplenishments)) {
            BigDecimal replenished = existingReplenishments.stream()
                    .map(WmsMaterialReservationDetailEntity::getReservedNum)
                    .map(this::optional).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (replenished.compareTo(returnNum) == 0
                    && existingReplenishments.stream()
                    .allMatch(item -> Objects.equals(item.getLiveStockId(), liveStockId))) {
                return Boolean.TRUE;
            }
            throw new CheckedException("退料入库明细[" + inDetail.getId() + "]已存在不一致的预留回补记录");
        }

        List<WmsMaterialReservationDetailEntity> consumedDetails = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .eq(WmsMaterialReservationDetailEntity::getReservationId, reservation.getId())
                        .eq(WmsMaterialReservationDetailEntity::getActualMatCode, inDetail.getMatCode())
                        .eq(StrUtil.isNotBlank(inDetail.getRequirementMatCode()),
                                WmsMaterialReservationDetailEntity::getRequirementMatCode,
                                inDetail.getRequirementMatCode())
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId)
                        .last("FOR UPDATE"));
        List<WmsMaterialReservationDetailEntity> returnableDetails = consumedDetails.stream()
                .filter(item -> optional(item.getConsumedNum()).subtract(optional(item.getReturnedNum()))
                        .compareTo(BigDecimal.ZERO) > 0)
                .toList();
        BigDecimal returnable = returnableDetails.stream()
                .map(item -> optional(item.getConsumedNum()).subtract(optional(item.getReturnedNum())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (returnable.compareTo(returnNum) < 0) {
            throw new CheckedException("预留[" + inDetail.getReservationNo() + "]物料[" + inDetail.getMatCode()
                    + "]最多可退回已消费数量" + returnable.stripTrailingZeros().toPlainString()
                    + "，本次退料数量为" + returnNum.stripTrailingZeros().toPlainString());
        }

        BigDecimal remainingReturn = returnNum;
        for (WmsMaterialReservationDetailEntity source : returnableDetails) {
            if (remainingReturn.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal sourceReturnable = optional(source.getConsumedNum()).subtract(optional(source.getReturnedNum()));
            BigDecimal take = sourceReturnable.min(remainingReturn);
            BigDecimal returned = optional(source.getReturnedNum()).add(take);
            // 已消费来源的退回量和新回补预留必须同时落账，不能只冻结其中一侧。
            if (detailMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationDetailEntity>()
                    .eq(WmsMaterialReservationDetailEntity::getId, source.getId())
                    .set(WmsMaterialReservationDetailEntity::getReturnedNum, returned)) != 1) {
                throw new CheckedException("生产退料原消费明细回写失败，整笔入库回滚");
            }

            BigDecimal ratio = optional(source.getSubstituteRatio());
            if (ratio.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CheckedException("预留明细[" + source.getId() + "]替代比例无效，不能回补");
            }
            WmsMaterialReservationDetailEntity replenish = new WmsMaterialReservationDetailEntity();
            replenish.setReservationId(reservation.getId());
            replenish.setReservationNo(reservation.getReservationNo());
            replenish.setLiveStockId(liveStockId);
            replenish.setRequirementMatCode(source.getRequirementMatCode());
            replenish.setActualMatCode(source.getActualMatCode());
            replenish.setActualMatName(inDetail.getMatName());
            replenish.setActualMatUnit(inDetail.getMatUnit());
            replenish.setSubstituteRatio(ratio);
            replenish.setRequirementNum(take.divide(ratio, 8, java.math.RoundingMode.HALF_UP));
            replenish.setReservedNum(take);
            replenish.setConsumedNum(BigDecimal.ZERO);
            replenish.setReturnedNum(BigDecimal.ZERO);
            replenish.setReleasedNum(BigDecimal.ZERO);
            replenish.setDetailType(DETAIL_RETURN_REPLENISH);
            replenish.setParentDetailId(source.getId());
            replenish.setReturnBillDetailId(inDetail.getId());
            replenish.setWarehouseCode(inDetail.getWarehouseCode());
            replenish.setWarehouseName(inDetail.getWarehouseName());
            replenish.setSiteCode(inDetail.getSiteCode());
            replenish.setSiteName(inDetail.getSiteName());
            replenish.setContainerCode(inDetail.getContainerCode());
            replenish.setMatBatch(inDetail.getMatBatch());
            replenish.setSupplierCode(inDetail.getSupplierCode());
            replenish.setQualityResult(inDetail.getQualityResult());
            replenish.setComplianceMode(source.getComplianceMode());
            replenish.setComplianceReferenceNo(source.getComplianceReferenceNo());
            replenish.setComplianceValidEnd(source.getComplianceValidEnd());
            replenish.setComplianceSnapshot(source.getComplianceSnapshot());
            replenish.setStatus(ACTIVE);
            replenish.setFactoryCode(inDetail.getFactoryCode());
            if (detailMapper.insert(replenish) != 1) {
                throw new CheckedException("生产退料回补明细保存失败，整笔入库回滚");
            }
            remainingReturn = remainingReturn.subtract(take);
        }
        if (reservationMapper.update(null, new LambdaUpdateWrapper<WmsMaterialReservationEntity>()
                .eq(WmsMaterialReservationEntity::getId, reservation.getId())
                .set(WmsMaterialReservationEntity::getStatus, ACTIVE)) != 1) {
            throw new CheckedException("生产退料预留状态回写失败，整笔入库回滚");
        }
        return Boolean.TRUE;
    }

    private void validateReservedStockCompliance(WmsMaterialReservationEntity reservation,
                                                 WmsMaterialReservationDetailEntity detail,
                                                 WmsLiveStockEntity stock) {
        if (StrUtil.isBlank(detail.getComplianceSnapshot())) {
            return;
        }
        WmsMaterialReservationAllocationDTO allocation;
        try {
            allocation = JSONUtil.toBean(detail.getComplianceSnapshot(), WmsMaterialReservationAllocationDTO.class);
        } catch (Exception ex) {
            throw new CheckedException("预留明细[" + detail.getId() + "]合规快照损坏，禁止出库");
        }
        normalizeCompliance(allocation);
        validateAllocationCompliance(reservation, allocation);
        if (!isCompliantStock(stock, allocation)) {
            throw new CheckedException("预留库存行[" + detail.getLiveStockId()
                    + "]供应商或质量结论已不满足替代授权，禁止出库");
        }
    }

    @Override
    public Map<Long, String> statuses(String sourceType, Set<Long> sourceIds) {
        requireScopedOrganization();
        if (StrUtil.isBlank(sourceType) || CollUtil.isEmpty(sourceIds)) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        reservationMapper.selectList(new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .eq(WmsMaterialReservationEntity::getSourceType, sourceType)
                        .in(WmsMaterialReservationEntity::getSourceId, sourceIds))
                .forEach(item -> result.put(item.getSourceId(), item.getStatus()));
        return result;
    }

    /**
     * 提供跨服务对账所需的WMS权威预留账本，并在账本侧先完成数量、代次和实物库存完整性检查。
     */
    @Override
    public List<WmsMaterialReservationLedgerDTO> ledgers(Set<String> reservationNos) {
        requireScopedOrganization();
        if (CollUtil.isEmpty(reservationNos)) {
            return List.of();
        }
        Set<String> normalized = reservationNos.stream().filter(StrUtil::isNotBlank)
                .map(String::trim).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (normalized.size() > 200) {
            throw new CheckedException("单次最多查询200个物料预留账本");
        }
        List<WmsMaterialReservationEntity> reservations = reservationMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationEntity>()
                        .in(WmsMaterialReservationEntity::getReservationNo, normalized));
        if (reservations.isEmpty()) {
            return List.of();
        }
        Set<Long> reservationIds = reservations.stream().map(WmsMaterialReservationEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<WmsMaterialReservationDetailEntity> details = detailMapper.selectList(
                new LambdaQueryWrapper<WmsMaterialReservationDetailEntity>()
                        .in(WmsMaterialReservationDetailEntity::getReservationId, reservationIds)
                        .orderByAsc(WmsMaterialReservationDetailEntity::getId));
        Set<Long> stockIds = details.stream().map(WmsMaterialReservationDetailEntity::getLiveStockId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, WmsLiveStockEntity> stocks = stockIds.isEmpty() ? Map.of()
                : liveStockMapper.selectBatchIds(stockIds).stream()
                .collect(java.util.stream.Collectors.toMap(WmsLiveStockEntity::getId, item -> item));
        Map<Long, WmsMaterialReservationDetailEntity> detailById = details.stream()
                .collect(java.util.stream.Collectors.toMap(WmsMaterialReservationDetailEntity::getId, item -> item));
        Map<Long, List<WmsMaterialReservationDetailEntity>> detailsByReservation = details.stream()
                .collect(java.util.stream.Collectors.groupingBy(WmsMaterialReservationDetailEntity::getReservationId));

        List<WmsMaterialReservationLedgerDTO> result = new ArrayList<>();
        for (WmsMaterialReservationEntity reservation : reservations) {
            WmsMaterialReservationLedgerDTO ledger = new WmsMaterialReservationLedgerDTO()
                    .setReservationNo(reservation.getReservationNo())
                    .setSourceType(reservation.getSourceType())
                    .setSourceId(reservation.getSourceId())
                    .setSourceNo(reservation.getSourceNo())
                    .setStatus(reservation.getStatus())
                    .setFactoryCode(reservation.getFactoryCode())
                    .setWarehouseCode(reservation.getWarehouseCode())
                    .setWarehouseName(reservation.getWarehouseName())
                    .setCustomerCode(reservation.getCustomerCode())
                    .setRequiredDate(reservation.getRequiredDate());
            Map<String, WmsMaterialReservationLedgerLineDTO> lines = new LinkedHashMap<>();
            Map<String, Set<Long>> lineStockIds = new HashMap<>();
            Map<Long, BigDecimal> activeByStock = new HashMap<>();
            for (WmsMaterialReservationDetailEntity detail : detailsByReservation
                    .getOrDefault(reservation.getId(), List.of())) {
                String lineKey = StrUtil.nullToEmpty(detail.getRequirementMatCode()) + "#"
                        + StrUtil.nullToEmpty(detail.getActualMatCode());
                WmsMaterialReservationLedgerLineDTO line = lines.computeIfAbsent(lineKey,
                        ignored -> new WmsMaterialReservationLedgerLineDTO()
                                .setRequirementMatCode(detail.getRequirementMatCode())
                                .setActualMatCode(detail.getActualMatCode())
                                .setActualMatName(detail.getActualMatName())
                                .setSubstituteRatio(detail.getSubstituteRatio())
                                .setComplianceMode(detail.getComplianceMode())
                                .setComplianceReferenceNo(detail.getComplianceReferenceNo())
                                .setComplianceValidEnd(detail.getComplianceValidEnd()));
                if (StrUtil.isNotBlank(detail.getSupplierCode())
                        && !line.getReservedSupplierCodes().contains(detail.getSupplierCode())) {
                    line.getReservedSupplierCodes().add(detail.getSupplierCode());
                }
                if (StrUtil.isNotBlank(detail.getQualityResult())
                        && !line.getReservedQualityResults().contains(detail.getQualityResult())) {
                    line.getReservedQualityResults().add(detail.getQualityResult());
                }
                BigDecimal reserved = optional(detail.getReservedNum());
                BigDecimal consumed = optional(detail.getConsumedNum());
                BigDecimal returned = optional(detail.getReturnedNum());
                BigDecimal released = optional(detail.getReleasedNum());
                BigDecimal active = ACTIVE.equals(detail.getStatus())
                        ? reserved.subtract(consumed).subtract(released).max(BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                line.setReservedNum(line.getReservedNum().add(reserved));
                line.setConsumedNum(line.getConsumedNum().add(consumed));
                line.setReturnedNum(line.getReturnedNum().add(returned));
                line.setReleasedNum(line.getReleasedNum().add(released));
                line.setActiveReservedNum(line.getActiveReservedNum().add(active));
                ledger.setReservedNum(ledger.getReservedNum().add(reserved));
                ledger.setConsumedNum(ledger.getConsumedNum().add(consumed));
                ledger.setReturnedNum(ledger.getReturnedNum().add(returned));
                ledger.setReleasedNum(ledger.getReleasedNum().add(released));
                ledger.setActiveReservedNum(ledger.getActiveReservedNum().add(active));
                if (detail.getLiveStockId() != null) {
                    lineStockIds.computeIfAbsent(lineKey, ignored -> new LinkedHashSet<>())
                            .add(detail.getLiveStockId());
                }
                if (reserved.compareTo(BigDecimal.ZERO) <= 0 || consumed.compareTo(BigDecimal.ZERO) < 0
                        || released.compareTo(BigDecimal.ZERO) < 0 || returned.compareTo(BigDecimal.ZERO) < 0
                        || consumed.add(released).compareTo(reserved) > 0 || returned.compareTo(consumed) > 0) {
                    addIssue(line.getIntegrityIssues(), "DETAIL_QUANTITY_BROKEN");
                    addIssue(ledger.getIntegrityIssues(), "DETAIL_QUANTITY_BROKEN");
                }
                if (StrUtil.isBlank(reservation.getWarehouseCode())
                        || !Objects.equals(reservation.getWarehouseCode(), detail.getWarehouseCode())) {
                    addIssue(line.getIntegrityIssues(), "WAREHOUSE_SCOPE_BROKEN");
                    addIssue(ledger.getIntegrityIssues(), "WAREHOUSE_SCOPE_BROKEN");
                }
                if (DETAIL_RETURN_REPLENISH.equals(detail.getDetailType())
                        && (detail.getParentDetailId() == null || detail.getReturnBillDetailId() == null
                        || !detailById.containsKey(detail.getParentDetailId()))) {
                    addIssue(line.getIntegrityIssues(), "RETURN_LINEAGE_BROKEN");
                    addIssue(ledger.getIntegrityIssues(), "RETURN_LINEAGE_BROKEN");
                }
                if (active.compareTo(BigDecimal.ZERO) > 0) {
                    activeByStock.merge(detail.getLiveStockId(), active, BigDecimal::add);
                    WmsLiveStockEntity stock = stocks.get(detail.getLiveStockId());
                    boolean unavailable = false;
                    if (!WmsStockAvailability.isUsable(stock)) {
                        addIssue(line.getIntegrityIssues(), "ACTIVE_RESERVED_STOCK_UNAVAILABLE");
                        addIssue(ledger.getIntegrityIssues(), "ACTIVE_RESERVED_STOCK_UNAVAILABLE");
                        unavailable = true;
                    }
                    for (String complianceIssue : complianceIntegrityIssues(reservation, detail, stock)) {
                        addIssue(line.getIntegrityIssues(), complianceIssue);
                        addIssue(ledger.getIntegrityIssues(), complianceIssue);
                        unavailable = true;
                    }
                    if (unavailable) {
                        line.setUnavailableActiveNum(line.getUnavailableActiveNum().add(active));
                    }
                }
            }
            for (Map.Entry<String, WmsMaterialReservationLedgerLineDTO> entry : lines.entrySet()) {
                BigDecimal physical = lineStockIds.getOrDefault(entry.getKey(), Set.of()).stream()
                        .map(stocks::get).filter(Objects::nonNull).map(this::stockNum)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                entry.getValue().setPhysicalStockNum(physical);
            }
            for (Map.Entry<Long, BigDecimal> entry : activeByStock.entrySet()) {
                WmsLiveStockEntity stock = stocks.get(entry.getKey());
                if (stockNum(stock).compareTo(entry.getValue()) < 0) {
                    addIssue(ledger.getIntegrityIssues(), "ACTIVE_RESERVED_STOCK_DEFICIT");
                }
            }
            boolean hasActiveBalance = ledger.getActiveReservedNum().compareTo(BigDecimal.ZERO) > 0;
            if ((ACTIVE.equals(reservation.getStatus()) && !hasActiveBalance)
                    || (!ACTIVE.equals(reservation.getStatus()) && hasActiveBalance)
                    || detailsByReservation.getOrDefault(reservation.getId(), List.of()).isEmpty()) {
                addIssue(ledger.getIntegrityIssues(), "HEADER_STATUS_BROKEN");
            }
            ledger.setLines(new ArrayList<>(lines.values()));
            result.add(ledger);
        }
        return result;
    }

    private List<String> complianceIntegrityIssues(WmsMaterialReservationEntity reservation,
                                                   WmsMaterialReservationDetailEntity detail,
                                                   WmsLiveStockEntity stock) {
        if (StrUtil.isBlank(detail.getComplianceSnapshot())) {
            return reservation.getRequiredDate() == null ? List.of() : List.of("COMPLIANCE_SNAPSHOT_MISSING");
        }
        WmsMaterialReservationAllocationDTO allocation;
        try {
            allocation = JSONUtil.toBean(detail.getComplianceSnapshot(), WmsMaterialReservationAllocationDTO.class);
            normalizeCompliance(allocation);
        } catch (Exception ex) {
            return List.of("COMPLIANCE_SNAPSHOT_BROKEN");
        }
        List<String> issues = new ArrayList<>();
        if (CollUtil.isNotEmpty(allocation.getApplicableCustomerCodes())
                && !allocation.getApplicableCustomerCodes().contains(reservation.getCustomerCode())) {
            issues.add("CUSTOMER_AUTHORIZATION_DRIFT");
        }
        LocalDate requiredDate = Optional.ofNullable(reservation.getRequiredDate()).orElse(LocalDate.now());
        LocalDate validationDate = requiredDate.isAfter(LocalDate.now()) ? requiredDate : LocalDate.now();
        if (CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())) {
            if (StrUtil.isBlank(allocation.getSupplierCertificateRef())
                    || allocation.getSupplierCertificateValidEnd() == null) {
                issues.add("SUPPLIER_CERTIFICATION_INVALID");
            } else if (validationDate.isAfter(allocation.getSupplierCertificateValidEnd())) {
                issues.add("SUPPLIER_CERTIFICATION_EXPIRED");
            }
            if (stock != null && !allocation.getCertifiedSupplierCodes().contains(stock.getSupplierCode())) {
                issues.add("SUPPLIER_BATCH_COMPLIANCE_DRIFT");
            }
        }
        String quality = stock == null ? ""
                : StrUtil.blankToDefault(
                StrUtil.trim(stock.getQualityResult()), "").toUpperCase(Locale.ROOT);
        if (stock != null && CollUtil.isNotEmpty(allocation.getAllowedQualityResults())
                && !allocation.getAllowedQualityResults().contains(quality)) {
            issues.add("QUALITY_RESULT_COMPLIANCE_DRIFT");
        }
        if ("DEVIATION".equals(allocation.getComplianceMode())) {
            if (StrUtil.isBlank(allocation.getDeviationPermitNo()) || StrUtil.isBlank(allocation.getDeviationReason())
                    || allocation.getDeviationValidEnd() == null) {
                issues.add("DEVIATION_PERMIT_INVALID");
            } else if (validationDate.isAfter(allocation.getDeviationValidEnd())) {
                issues.add("DEVIATION_PERMIT_EXPIRED");
            }
        }
        if ("STANDARD".equals(allocation.getComplianceMode())) {
            try {
                if (CollUtil.isNotEmpty(allocation.getApplicableCustomerCodes())
                        && !hasCurrentAuthorization("CUSTOMER_APPROVAL", allocation.getActualMatCode(),
                        reservation.getCustomerCode(), validationDate)) {
                    issues.add("CUSTOMER_AUTHORIZATION_DRIFT");
                }
                if (stock != null && CollUtil.isNotEmpty(allocation.getCertifiedSupplierCodes())
                        && !hasCurrentAuthorization("SUPPLIER_CERTIFICATION", allocation.getActualMatCode(),
                        stock.getSupplierCode(), validationDate)) {
                    issues.add("SUPPLIER_CERTIFICATION_INVALID");
                }
            } catch (Exception ex) {
                issues.add("AUTHORIZATION_LEDGER_UNAVAILABLE");
            }
        }
        return issues;
    }

    private void validateCurrentAuthorizationLedger(WmsMaterialReservationEntity reservation,
                                                    WmsMaterialReservationAllocationDTO allocation,
                                                    LocalDate validationDate) {
        if (!"STANDARD".equals(allocation.getComplianceMode())) {
            return;
        }
        try {
            if (CollUtil.isNotEmpty(allocation.getApplicableCustomerCodes())
                    && !hasCurrentAuthorization("CUSTOMER_APPROVAL", allocation.getActualMatCode(),
                    reservation.getCustomerCode(), validationDate)) {
                throw new CheckedException("替代料[" + allocation.getActualMatCode() + "]客户批准已失效或被撤销");
            }
            for (String supplierCode : allocation.getCertifiedSupplierCodes()) {
                if (!hasCurrentAuthorization("SUPPLIER_CERTIFICATION", allocation.getActualMatCode(),
                        supplierCode, validationDate)) {
                    throw new CheckedException("替代料[" + allocation.getActualMatCode() + "]供应商["
                            + supplierCode + "]认证已失效或被撤销");
                }
            }
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("读取替代料权威授权台账失败，禁止在合规状态未知时预留", ex);
        }
    }

    private boolean hasCurrentAuthorization(String type, String materialCode, String partnerCode, LocalDate bizDate) {
        R<List<MdmMaterialAuthorizationEntity>> response = remoteMaterialAuthorizationService.active(
                type, materialCode, partnerCode, bizDate);
        return response != null && response.isOk() && CollUtil.isNotEmpty(response.getData());
    }

    @Override
    public WmsMaterialCompensationResultDTO compensate(WmsMaterialCompensationCommandDTO command) {
        requireScopedOrganization();
        if (command == null || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getAction())
                || StrUtil.isBlank(command.getReservationNo()) || StrUtil.isBlank(command.getRequirementMatCode())
                || StrUtil.isBlank(command.getActualMatCode())) {
            throw new CheckedException("物料补偿缺少请求号、动作、预留号或物料范围");
        }
        if (command.getRequestId().length() > 64) {
            throw new CheckedException("物料补偿请求号最多64位");
        }
        if (!"REPLAY_WMS_MES_CALLBACK".equals(command.getAction())) {
            throw new CheckedException("WMS不支持的物料补偿动作：" + command.getAction());
        }
        List<WmsOutBillDetailEntity> targetDetails = outBillDetailMapper.selectList(
                new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                        .eq(WmsOutBillDetailEntity::getReservationNo, command.getReservationNo())
                        .eq(WmsOutBillDetailEntity::getRequirementMatCode, command.getRequirementMatCode())
                        .eq(WmsOutBillDetailEntity::getMatCode, command.getActualMatCode()));
        if (CollUtil.isEmpty(targetDetails)) {
            throw new CheckedException("WMS未找到可重放的生产领料出库明细");
        }
        if (targetDetails.stream().anyMatch(item -> item.getId() == null || item.getBillId() == null
                || item.getMatNum() == null || item.getMatNum() <= 0)) {
            throw new CheckedException("WMS待重放出库明细缺少业务键、出库单或有效数量");
        }
        Map<Long, List<WmsOutBillDetailEntity>> detailsByBill = targetDetails.stream()
                .collect(java.util.stream.Collectors.groupingBy(WmsOutBillDetailEntity::getBillId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        WmsMaterialCompensationResultDTO result = new WmsMaterialCompensationResultDTO()
                .setRequestId(command.getRequestId()).setAction(command.getAction())
                .setReplayedBillCount(0).setReplayedDetailCount(0);
        for (Map.Entry<Long, List<WmsOutBillDetailEntity>> entry : detailsByBill.entrySet()) {
            WmsOutBillEntity bill = outBillMapper.selectById(entry.getKey());
            if (bill == null || (!InOutBillStateEnum.PASSED.getCode().equals(bill.getState())
                    && !InOutBillStateEnum.FINISHED.getCode().equals(bill.getState()))) {
                throw new CheckedException("WMS出库单尚未完成，禁止重放MES发料回调");
            }
            WmsOutNoticeEntity notice = outNoticeMapper.selectById(bill.getNoticeId());
            if (notice == null || notice.getDocumentId() == null
                    || !DocumentEnum.MES_PICKING.getCode().equals(notice.getDocumentTable())) {
                throw new CheckedException("WMS出库单未关联有效的MES生产领料单，禁止补偿");
            }
            List<MesWarehouseMaterialInBillEntity> materialDetails = new ArrayList<>();
            for (WmsOutBillDetailEntity item : entry.getValue()) {
                MesWarehouseMaterialInBillEntity material = new MesWarehouseMaterialInBillEntity();
                BeanUtil.copyProperties(item, material, "id", "siteCode", "siteName", "containerCode");
                material.setWmsOutDetailId(item.getId());
                material.setResourceNo(bill.getBillNo());
                material.setInspectionStatus(item.getQualityResult());
                material.setNoticeInNum(BigDecimal.valueOf(item.getMatNum()));
                materialDetails.add(material);
            }
            MesPickingWmsOutBillBackDTO callback = new MesPickingWmsOutBillBackDTO();
            callback.setId(notice.getDocumentId());
            callback.setMatCodes(entry.getValue().stream().map(WmsOutBillDetailEntity::getMatCode).distinct().toList());
            callback.setMatDetails(materialDetails);
            R<Boolean> response = remoteWarehousePickingBillService.wmsOutBillBack(callback);
            if (response == null || !response.isOk() || !Boolean.TRUE.equals(response.getData())) {
                throw new CheckedException("MES发料回调补偿失败：" + (response == null ? "无响应" : response.getMsg()));
            }
            result.setReplayedBillCount(result.getReplayedBillCount() + 1);
            result.setReplayedDetailCount(result.getReplayedDetailCount() + materialDetails.size());
            result.getReplayedBillNos().add(bill.getBillNo());
        }
        return result;
    }

    private void addIssue(List<String> issues, String issue) {
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
    }

    private WmsMaterialReservationResultDTO result(WmsMaterialReservationEntity entity,
                                                   List<WmsMaterialReservationAllocationDTO> allocations) {
        return new WmsMaterialReservationResultDTO().setReservationNo(entity.getReservationNo())
                .setStatus(entity.getStatus())
                .setWarehouseCode(entity.getWarehouseCode())
                .setWarehouseName(entity.getWarehouseName())
                .setAllocations(allocations);
    }

    private BigDecimal stockNum(WmsLiveStockEntity stock) {
        return stock == null || stock.getMatNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(stock.getMatNum());
    }

    private BigDecimal remaining(WmsMaterialReservationDetailEntity detail) {
        return optional(detail.getReservedNum()).subtract(optional(detail.getConsumedNum()))
                .subtract(optional(detail.getReleasedNum())).max(BigDecimal.ZERO);
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
