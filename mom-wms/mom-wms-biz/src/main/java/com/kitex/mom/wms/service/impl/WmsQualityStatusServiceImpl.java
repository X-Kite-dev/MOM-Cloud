package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsQualityActionExecuteCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusCommand;
import com.kitex.mom.wms.dto.WmsQualityStatusResult;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsQualityStatusOperationMapper;
import com.kitex.mom.wms.service.WmsMaterialReservationService;
import com.kitex.mom.wms.service.WmsQualityStatusService;
import com.kitex.mom.wms.util.WmsSerialStockGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WMS是库存质量状态的唯一写入方。QMS只提交幂等命令，WMS按数量拆分库存行并保留操作账。
 */
@Service
@RequiredArgsConstructor
public class WmsQualityStatusServiceImpl
        extends ServiceImpl<WmsQualityStatusOperationMapper, WmsQualityStatusOperationEntity>
        implements WmsQualityStatusService {

    private static final Set<String> ACTIONS = Set.of(
            "HOLD", "RELEASE", "CONCESSION", "REWORK", "SCRAP", "RETURN");
    private static final Set<String> TERMINAL_PHYSICAL_ACTIONS = Set.of("SCRAP", "RETURN");
    private static final Set<String> ACTIVE_HOLD_STATUS = Set.of("HOLD", "REWORK");

    private final WmsLiveStockMapper liveStockMapper;
    private final WmsMaterialReservationService reservationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsQualityStatusResult apply(WmsQualityStatusCommand command) {
        normalizeAndValidate(command);
        WmsQualityStatusOperationEntity operation = getOne(
                new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
                        .eq(WmsQualityStatusOperationEntity::getRequestId, command.getRequestId())
                        .last("FOR UPDATE"), false);
        if (operation != null) {
            if (StrUtil.isBlank(command.getHoldNo())) command.setHoldNo(operation.getHoldNo());
            assertSameCommand(operation, command);
            return "PENDING".equals(operation.getStatus()) ? process(operation) : result(operation);
        }
        command.setHoldNo(StrUtil.blankToDefault(command.getHoldNo(),
                "QH-" + IdUtil.getSnowflakeNextIdStr()));
        require("HOLD".equals(command.getAction()) || StrUtil.isNotBlank(command.getHoldNo()),
                "处置必须关联质量隔离单号");
        operation = new WmsQualityStatusOperationEntity()
                .setRequestId(command.getRequestId()).setHoldNo(command.getHoldNo())
                .setSourceType(command.getSourceType()).setSourceId(command.getSourceId())
                .setAction(command.getAction()).setMatCode(command.getMatCode())
                .setMatBatch(command.getMatBatch()).setMatUnit(command.getMatUnit())
                .setSupplierCode(command.getSupplierCode())
                .setWarehouseCode(command.getWarehouseCode()).setSiteCode(command.getSiteCode())
                .setContainerCode(command.getContainerCode()).setRequestedQuantity(command.getQuantity())
                .setAffectedQuantity(BigDecimal.ZERO).setStatus("PENDING").setReason(command.getReason())
                .setFactoryCode(command.getFactoryCode());
        require(save(operation), "质量库存操作保存失败");
        var persisted = getById(operation.getId());
        require(persisted != null && persisted.getRequestedQuantity() != null
                && persisted.getRequestedQuantity().compareTo(command.getQuantity()) == 0, "质量库存请求数量发生数据库精度舍入，已回滚");
        return process(operation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsQualityStatusResult retry(Long operationId) {
        require(operationId != null, "质量库存事务不能为空");
        WmsQualityStatusOperationEntity operation = getOne(
                new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
                        .eq(WmsQualityStatusOperationEntity::getId, operationId).last("FOR UPDATE"), false);
        require(operation != null, "质量库存事务不存在");
        requireFactory(operation.getFactoryCode());
        return "PENDING".equals(operation.getStatus()) ? process(operation) : result(operation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsQualityStatusResult execute(WmsQualityActionExecuteCommand command) {
        require(command != null && command.getOperationId() != null, "待执行质量库存事务不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()), "执行请求号不能为空");
        require(StrUtil.isNotBlank(command.getExecutionRef()), "报废单/退货单等执行单号不能为空");
        require(StrUtil.isNotBlank(command.getRemark()), "实物执行说明不能为空");
        WmsQualityStatusOperationEntity operation = getOne(
                new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
                        .eq(WmsQualityStatusOperationEntity::getId, command.getOperationId()).last("FOR UPDATE"), false);
        require(operation != null, "质量库存事务不存在");
        requireFactory(operation.getFactoryCode());
        require(TERMINAL_PHYSICAL_ACTIONS.contains(operation.getAction()), "仅报废或退货需要仓库实物执行");
        if ("EXECUTED".equals(operation.getStatus())) {
            require(Objects.equals(operation.getExecutionRequestId(), command.getRequestId()),
                    "该质量库存事务已经由其他请求执行");
            return result(operation);
        }
        require("ACTION_PENDING".equals(operation.getStatus()), "当前质量库存事务不在待实物执行状态");

        List<Long> stockIds = parseIds(operation.getAffectedStockIds());
        require(!stockIds.isEmpty(), "质量库存事务没有可执行库存行");
        List<WmsLiveStockEntity> stocks = liveStockMapper.selectList(
                new LambdaQueryWrapper<WmsLiveStockEntity>().in(WmsLiveStockEntity::getId, stockIds)
                        .orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
        require(stocks.size() == stockIds.size(), "隔离库存已发生变化，请先人工核对");
        BigDecimal actual = BigDecimal.ZERO;
        for (WmsLiveStockEntity stock : stocks) {
            require(Objects.equals(stock.getQualityHoldNo(), operation.getHoldNo()), "库存行不再属于当前隔离单");
            require(Objects.equals(stock.getQualitySourceType(), operation.getSourceType())
                            && Objects.equals(stock.getQualitySourceId(), operation.getSourceId()),
                    "库存行已转由其他质量案件处理，不能凭原隔离单执行实物出库");
            require((operation.getAction() + "_PENDING").equals(stock.getQualityStatus()),
                    "库存行质量状态与待执行动作不一致");
            require(reservationService.activeReservedNum(stock.getId()).signum() == 0,
                    "隔离库存仍被生产预留占用，必须先完成预留调整再执行实物出库");
            actual = actual.add(quantity(stock));
        }
        require(actual.compareTo(operation.getAffectedQuantity()) == 0, "隔离库存数量已变化，请先人工核对");
        require(liveStockMapper.deleteByIds(stockIds) == stockIds.size(), "质量实物执行库存归档行数不一致，已回滚");

        MomUser user = SecurityUtils.getUser();
        operation.setStatus("EXECUTED").setExecutionRequestId(command.getRequestId())
                .setExecutionRef(StrUtil.trim(command.getExecutionRef()))
                .setExecutionRemark(StrUtil.trim(command.getRemark()))
                .setExecutedBy(user == null ? "SYSTEM" : user.getUsername())
                .setExecutedByName(user == null ? "系统" : user.getName())
                .setExecutedTime(LocalDateTime.now())
                .setResultMessage(("SCRAP".equals(operation.getAction()) ? "报废" : "退货")
                        + "实物出库已确认，执行单号=" + command.getExecutionRef());
        require(updateById(operation), "质量实物执行回执保存失败，已回滚");
        return result(operation);
    }

    private WmsQualityStatusResult process(WmsQualityStatusOperationEntity operation) {
        List<WmsLiveStockEntity> stocks = "HOLD".equals(operation.getAction())
                ? findAvailableStocks(operation) : findHeldStocks(operation);
        if (!stocks.isEmpty()) {
            Set<String> units = stocks.stream().map(WmsLiveStockEntity::getMatUnit).collect(Collectors.toSet());
            require(units.size() == 1 && StrUtil.isNotBlank(units.iterator().next()), "质量库存候选缺少单位或混用不同单位，禁止直接合并数量");
        }
        BigDecimal available = stocks.stream().map(this::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (available.compareTo(operation.getRequestedQuantity()) < 0) {
            operation.setStatus("PENDING").setAffectedQuantity(BigDecimal.ZERO).setAffectedStockIds(null)
                    .setResultMessage("匹配库存不足：需要" + operation.getRequestedQuantity().stripTrailingZeros().toPlainString()
                            + "，当前" + available.stripTrailingZeros().toPlainString() + "；等待入库或库存核对后重试");
            updateById(operation);
            return result(operation);
        }

        BigDecimal remaining = operation.getRequestedQuantity();
        List<Long> affectedIds = new ArrayList<>();
        List<Long> remainderIds = new ArrayList<>();
        List<Map<String, String>> splitLinks = new ArrayList<>();
        for (WmsLiveStockEntity stock : stocks) {
            if (remaining.signum() <= 0) break;
            BigDecimal before = quantity(stock);
            if (before.signum() == 0) continue;
            BigDecimal take = before.min(remaining);
            WmsSerialStockGuard.requireWhole(stock, take);
            if (before.compareTo(take) > 0) {
                WmsLiveStockEntity remainder = new WmsLiveStockEntity();
                BeanUtil.copyProperties(stock, remainder);
                remainder.setId(null);
                BigDecimal remainderQuantity = before.subtract(take);
                remainder.setMatNum(exactDouble(remainderQuantity));
                require(liveStockMapper.insert(remainder) == 1, "质量库存拆分余量保存失败");
                assertStoredQuantity(remainder.getId(), remainderQuantity);
                remainderIds.add(remainder.getId());
                Map<String, String> splitLink = new LinkedHashMap<>();
                splitLink.put("parentStockId", String.valueOf(stock.getId()));
                splitLink.put("remainderStockId", String.valueOf(remainder.getId()));
                splitLink.put("remainderQuantity", remainderQuantity.stripTrailingZeros().toPlainString());
                splitLinks.add(splitLink);
            }
            stock.setMatNum(exactDouble(take));
            applyState(stock, operation);
            require(liveStockMapper.updateById(stock) == 1, "质量库存状态保存失败");
            assertStoredQuantity(stock.getId(), take);
            affectedIds.add(stock.getId());
            remaining = remaining.subtract(take);
        }
        require(remaining.signum() == 0, "质量库存处置数量不守恒，已回滚");
        operation.setAffectedQuantity(operation.getRequestedQuantity())
                .setAffectedStockIds(affectedIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .setSplitRecorded(true)
                .setSplitRemainderStockIds(remainderIds.isEmpty() ? null
                        : remainderIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .setSplitLineageJson(JSONUtil.toJsonStr(splitLinks))
                .setSplitRecordedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS))
                .setStatus(TERMINAL_PHYSICAL_ACTIONS.contains(operation.getAction()) ? "ACTION_PENDING" : "SUCCESS")
                .setResultMessage(message(operation));
        require(updateById(operation), "质量库存操作结果保存失败");
        var persisted = getById(operation.getId());
        require(persisted != null && persisted.getAffectedQuantity() != null
                        && persisted.getAffectedQuantity().compareTo(operation.getAffectedQuantity()) == 0,
                "质量库存影响量发生精度舍入，已回滚");
        require(Boolean.TRUE.equals(persisted.getSplitRecorded())
                        && Objects.equals(StrUtil.nullToEmpty(persisted.getSplitRemainderStockIds()),
                        StrUtil.nullToEmpty(operation.getSplitRemainderStockIds()))
                        && Objects.equals(persisted.getSplitLineageJson(), operation.getSplitLineageJson())
                        && Objects.equals(persisted.getSplitRecordedAt(), operation.getSplitRecordedAt()),
                "质量库存拆分去向未完整保存，已回滚");
        return result(operation);
    }

    private List<WmsLiveStockEntity> findAvailableStocks(WmsQualityStatusOperationEntity operation) {
        LambdaQueryWrapper<WmsLiveStockEntity> query = scope(operation)
                .and(wrapper -> wrapper
                        .and(available -> available.isNull(WmsLiveStockEntity::getQualityHoldNo)
                                .and(state -> state.eq(WmsLiveStockEntity::getStatus, 0)
                                        .or(pending -> pending.eq(WmsLiveStockEntity::getStatus, 1)
                                                .and(quality -> quality.eq(WmsLiveStockEntity::getQualityStatus, "PENDING")
                                                        .or().eq(WmsLiveStockEntity::getIsQualityTest, "1")))))
                        .or(rehold -> rehold.eq(WmsLiveStockEntity::getQualityHoldNo, operation.getHoldNo())
                                .eq(WmsLiveStockEntity::getQualitySourceType, operation.getSourceType())
                                .eq(WmsLiveStockEntity::getQualitySourceId, operation.getSourceId())
                                .in(WmsLiveStockEntity::getQualityStatus, "HOLD", "REWORK", "QUALIFIED",
                                        "CONCESSION", "SCRAP_PENDING", "RETURN_PENDING")))
                .orderByAsc(WmsLiveStockEntity::getInTime).orderByAsc(WmsLiveStockEntity::getId)
                .last("FOR UPDATE");
        return liveStockMapper.selectList(query);
    }

    private List<WmsLiveStockEntity> findHeldStocks(WmsQualityStatusOperationEntity operation) {
        require(StrUtil.isNotBlank(operation.getHoldNo()), "处置必须关联质量隔离单号");
        return liveStockMapper.selectList(scope(operation)
                .eq(WmsLiveStockEntity::getStatus, 1)
                .eq(WmsLiveStockEntity::getQualityHoldNo, operation.getHoldNo())
                .eq(WmsLiveStockEntity::getQualitySourceType, operation.getSourceType())
                .eq(WmsLiveStockEntity::getQualitySourceId, operation.getSourceId())
                .in(WmsLiveStockEntity::getQualityStatus, ACTIVE_HOLD_STATUS)
                .orderByAsc(WmsLiveStockEntity::getInTime).orderByAsc(WmsLiveStockEntity::getId)
                .last("FOR UPDATE"));
    }

    private LambdaQueryWrapper<WmsLiveStockEntity> scope(WmsQualityStatusOperationEntity operation) {
        return new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getFactoryCode, operation.getFactoryCode())
                .eq(WmsLiveStockEntity::getMatCode, operation.getMatCode())
                .eq(StrUtil.isNotBlank(operation.getMatBatch()), WmsLiveStockEntity::getMatBatch, operation.getMatBatch())
                .eq(StrUtil.isNotBlank(operation.getMatUnit()), WmsLiveStockEntity::getMatUnit, operation.getMatUnit())
                .eq(StrUtil.isNotBlank(operation.getSupplierCode()), WmsLiveStockEntity::getSupplierCode, operation.getSupplierCode())
                .eq(StrUtil.isNotBlank(operation.getWarehouseCode()), WmsLiveStockEntity::getWarehouseCode, operation.getWarehouseCode())
                .eq(StrUtil.isNotBlank(operation.getSiteCode()), WmsLiveStockEntity::getSiteCode, operation.getSiteCode())
                .eq(StrUtil.isNotBlank(operation.getContainerCode()), WmsLiveStockEntity::getContainerCode, operation.getContainerCode())
                .gt(WmsLiveStockEntity::getMatNum, 0);
    }

    private void applyState(WmsLiveStockEntity stock, WmsQualityStatusOperationEntity operation) {
        stock.setQualityHoldNo(operation.getHoldNo()).setQualitySourceType(operation.getSourceType())
                .setQualitySourceId(operation.getSourceId());
        switch (operation.getAction()) {
            case "HOLD" -> stock.setStatus(1).setQualityStatus("HOLD").setQualityResult("UN_QUALIFIED");
            case "RELEASE" -> stock.setStatus(0).setQualityStatus("QUALIFIED").setQualityResult("RELEASE");
            case "CONCESSION" -> stock.setStatus(0).setQualityStatus("CONCESSION").setQualityResult("CONCESSION");
            case "REWORK" -> stock.setStatus(1).setQualityStatus("REWORK").setQualityResult("UN_QUALIFIED");
            case "SCRAP", "RETURN" -> stock.setStatus(1).setQualityStatus(operation.getAction() + "_PENDING")
                    .setQualityResult("UN_QUALIFIED");
            default -> throw new CheckedException("不支持的质量库存动作");
        }
    }

    private void normalizeAndValidate(WmsQualityStatusCommand command) {
        require(command != null, "质量库存命令不能为空");
        command.setRequestId(StrUtil.trim(command.getRequestId()));
        command.setAction(StrUtil.blankToDefault(command.getAction(), "").trim().toUpperCase());
        command.setHoldNo(StrUtil.trim(command.getHoldNo()));
        command.setSourceType(StrUtil.trim(command.getSourceType()));
        command.setSourceId(StrUtil.trim(command.getSourceId()));
        command.setMatCode(StrUtil.trim(command.getMatCode()));
        command.setMatBatch(StrUtil.trim(command.getMatBatch()));
        command.setMatUnit(StrUtil.trim(command.getMatUnit()));
        command.setFactoryCode(StrUtil.trim(command.getFactoryCode()));
        require(StrUtil.isNotBlank(command.getRequestId()), "质量库存请求号不能为空");
        require(ACTIONS.contains(command.getAction()), "质量库存动作无效");
        require("HOLD".equals(command.getAction()) || StrUtil.isNotBlank(command.getHoldNo()),
                "处置必须关联质量隔离单号");
        require(StrUtil.isNotBlank(command.getSourceType()) && StrUtil.isNotBlank(command.getSourceId()),
                "质量库存来源不能为空");
        require(StrUtil.isNotBlank(command.getMatCode()), "质量库存物料不能为空");
        require(StrUtil.isNotBlank(command.getFactoryCode()), "质量库存命令工厂不能为空");
        requireFactory(command.getFactoryCode());
        require(command.getQuantity() != null && command.getQuantity().signum() > 0,
                "质量库存数量必须大于0");
    }

    private void assertSameCommand(WmsQualityStatusOperationEntity operation, WmsQualityStatusCommand command) {
        require(Objects.equals(operation.getHoldNo(), command.getHoldNo())
                        && Objects.equals(operation.getAction(), command.getAction())
                        && Objects.equals(operation.getSourceType(), command.getSourceType())
                        && Objects.equals(operation.getSourceId(), command.getSourceId())
                        && Objects.equals(operation.getMatCode(), command.getMatCode())
                        && Objects.equals(operation.getMatBatch(), command.getMatBatch())
                        && Objects.equals(operation.getMatUnit(), command.getMatUnit())
                        && Objects.equals(operation.getSupplierCode(), command.getSupplierCode())
                        && Objects.equals(operation.getWarehouseCode(), command.getWarehouseCode())
                        && Objects.equals(operation.getSiteCode(), command.getSiteCode())
                        && Objects.equals(operation.getContainerCode(), command.getContainerCode())
                        && Objects.equals(operation.getFactoryCode(), command.getFactoryCode())
                        && operation.getRequestedQuantity().compareTo(command.getQuantity()) == 0,
                "质量库存请求号已被其他业务占用");
    }

    private String message(WmsQualityStatusOperationEntity operation) {
        return switch (operation.getAction()) {
            case "HOLD" -> "库存已按数量隔离，禁止预留、移库和出库";
            case "RELEASE" -> "紧急放行库存已恢复可用";
            case "CONCESSION" -> "让步接收库存已恢复可用并保留质量标识";
            case "REWORK" -> "库存已转返工状态并继续锁定";
            case "SCRAP" -> "库存已隔离为待报废，等待仓库凭报废单确认实物出库";
            case "RETURN" -> "库存已隔离为待退货，等待仓库凭退货单确认实物出库";
            default -> "质量库存状态已更新";
        };
    }

    private WmsQualityStatusResult result(WmsQualityStatusOperationEntity operation) {
        return new WmsQualityStatusResult().setOperationId(operation.getId()).setHoldNo(operation.getHoldNo())
                .setAction(operation.getAction()).setStatus(operation.getStatus())
                .setRequestedQuantity(operation.getRequestedQuantity())
                .setAffectedQuantity(operation.getAffectedQuantity()).setMessage(operation.getResultMessage());
    }

    private BigDecimal quantity(WmsLiveStockEntity stock) {
        require(stock != null && stock.getMatNum() != null && Double.isFinite(stock.getMatNum()) && stock.getMatNum() >= 0,
                "质量库存存在空、非法或负数量，须先核对真实库存");
        return BigDecimal.valueOf(stock.getMatNum());
    }

    private double exactDouble(BigDecimal value) {
        double converted = value.doubleValue();
        require(Double.isFinite(converted) && BigDecimal.valueOf(converted).compareTo(value) == 0,
                "当前WMS数量字段无法无损表达该数量，已回滚，禁止浮点近似入账");
        return converted;
    }

    private void assertStoredQuantity(Long stockId, BigDecimal expected) {
        var saved = liveStockMapper.selectById(stockId);
        require(saved != null && quantity(saved).compareTo(expected) == 0, "质量库存实存数量发生精度舍入，已回滚");
    }

    private List<Long> parseIds(String value) {
        if (StrUtil.isBlank(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                .map(Long::valueOf).distinct().toList();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private void requireFactory(String factoryCode) {
        String selectedFactory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factoryCode) && StrUtil.isNotBlank(selectedFactory)
                        && Objects.equals(selectedFactory, factoryCode),
                "质量库存业务工厂与请求头factory-code不一致");
    }
}
