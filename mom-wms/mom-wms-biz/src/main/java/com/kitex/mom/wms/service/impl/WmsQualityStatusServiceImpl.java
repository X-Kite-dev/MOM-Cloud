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
import com.kitex.mom.wms.service.special.WmsSpecialReturnQualityProofService;
import org.springframework.transaction.annotation.Isolation;
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
	private final WmsSpecialReturnQualityProofService specialReturnProof;
	private final com.kitex.mom.wms.service.rework.WmsNcrReworkProof reworkProof;
	private final com.kitex.mom.wms.service.rework.WmsNcrReworkRepository reworkRepository;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalRepository physicalRepository;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalProof physicalProof;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalAccess physicalAccess;
	private final com.kitex.mom.wms.service.physical.WmsPhysicalService physicalService;

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public WmsQualityStatusResult apply(WmsQualityStatusCommand command) {
		normalizeAndValidate(command);
		require(command.getReholdOfPhysicalOperationId() == null &&
				command.getReholdOfPhysicalRequestId() == null, "定向重隔离必须使用专用接口");
		if (command.getSpecialReturnBinding() == null && command.getRequestId().startsWith("WMS-REHOLD:")) {
			Long pending = baseMapper.selectCount(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>().eq(
							WmsQualityStatusOperationEntity::getFactoryCode, command.getFactoryCode())
					.eq(WmsQualityStatusOperationEntity::getSourceType, command.getSourceType())
					.eq(WmsQualityStatusOperationEntity::getSourceId, command.getSourceId())
					.in(WmsQualityStatusOperationEntity::getAction, "SCRAP", "RETURN")
					.eq(WmsQualityStatusOperationEntity::getStatus, "ACTION_PENDING"));
			require(pending == 0, "普通实物待办存在，必须升级并明确原处置父ID，禁止同批邻件补量");
		}
		// 专用分支先核实名、实际工厂/仓授权并锁同号及原仓；成功重放只返回永久结果。
		specialReturnProof.authorizeCommand(command);
		if (TERMINAL_PHYSICAL_ACTIONS.contains(command.getAction()) &&
				command.getSpecialReturnBinding() == null) {
			physicalAccess.write();
			physicalRepository.ready();
			var template = new WmsQualityStatusOperationEntity().setFactoryCode(command.getFactoryCode())
					.setMatCode(command.getMatCode()).setMatBatch(command.getMatBatch())
					.setMatUnit(command.getMatUnit()).setSupplierCode(command.getSupplierCode())
					.setWarehouseCode(command.getWarehouseCode()).setSiteCode(command.getSiteCode())
					.setContainerCode(command.getContainerCode()).setHoldNo(command.getHoldNo())
					.setSourceType(command.getSourceType()).setSourceId(command.getSourceId());
			var candidateWarehouses = findHeldStocks(template, false).stream()
					.map(WmsLiveStockEntity::getWarehouseCode).distinct().sorted().toList();
			physicalRepository.warehouses(com.kitex.common.data.tenant.TenantContextHolder.getTenantId(),
					command.getFactoryCode(), candidateWarehouses);
		}
		WmsQualityStatusOperationEntity operation = getOne(
				new LambdaQueryWrapper<WmsQualityStatusOperationEntity>()
						.eq(WmsQualityStatusOperationEntity::getRequestId, command.getRequestId())
						.last("FOR UPDATE"), false);
		if (operation != null) {
			if (StrUtil.isBlank(command.getHoldNo())) {
				command.setHoldNo(operation.getHoldNo());
			}
			assertSameCommand(operation, command);
			specialReturnProof.authorizeOperation(operation);
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
				.setFactoryCode(command.getFactoryCode())
				.setSpecialReturnBindingJson(specialReturnProof.encode(command.getSpecialReturnBinding()));
		if (command.getSpecialReturnBinding() != null) {
			operation.setTenantId(Long.valueOf(command.getSpecialReturnBinding().target().tenantId()));
		}
		require(save(operation), "质量库存操作保存失败");
		var persisted = getById(operation.getId());
		require(persisted != null && persisted.getRequestedQuantity() != null
						&& persisted.getRequestedQuantity().compareTo(command.getQuantity()) == 0,
				"质量库存请求数量发生数据库精度舍入，已回滚");
		require(Objects.equals(persisted.getSpecialReturnBindingJson(),
						operation.getSpecialReturnBindingJson()),
				"特殊退回质量原件绑定未完整保存，已回滚");
		if (command.getSpecialReturnBinding() != null) {
			require(persisted.getSpecialReturnSequence() != null && persisted.getSpecialReturnSequence() > 0,
					"特殊接管永久办理序号未由数据库分配，已回滚");
			return process(persisted);
		}
		return process(persisted);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public WmsQualityStatusResult retry(Long operationId) {
		require(operationId != null, "质量库存事务不能为空");
		physicalAccess.read();
		physicalAccess.write();
		var observed = physicalService.load(operationId, false);
		var warehouses = physicalService.warehouses(observed);
		physicalAccess.requireWarehouses(warehouses, "show");
		physicalAccess.requireWarehouses(warehouses, "retry");
		specialReturnProof.authorizeOperation(observed);
		physicalRepository.warehouses(observed.getTenantId(), observed.getFactoryCode(), warehouses);
		var operation = physicalService.load(operationId, true);
		return "PENDING".equals(operation.getStatus()) ? process(operation, true) : result(operation);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public WmsQualityStatusResult execute(WmsQualityActionExecuteCommand command) {
		require(command != null && command.getOperationId() != null, "待执行质量库存事务不能为空");
		physicalAccess.read();
		var operation = physicalService.load(command.getOperationId(), false);
		physicalAccess.requireWarehouses(physicalService.warehouses(operation), "show");
		require("EXECUTED".equals(operation.getStatus()),
				"请升级客户端并重读原操作，首次实物执行仅支持/execution/apply");
		require(Objects.equals(operation.getExecutionRequestId(), command.getRequestId()) &&
						Objects.equals(operation.getExecutionRef(), StrUtil.trim(command.getExecutionRef())) &&
						Objects.equals(operation.getExecutionRemark(), StrUtil.trim(command.getRemark())),
				"原执行请求号、执行单或说明不一致");
		return result(operation);
	}

	/**
	 * 普通定向HOLD先锁父再锁稳定子号，精确原件升序锁；不扫描同批邻件。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public WmsQualityStatusResult applyPhysicalRehold(WmsQualityStatusCommand command) {
		normalizeAndValidate(command);
		physicalAccess.write();
		physicalRepository.ready();
		require(command.getSpecialReturnBinding() == null && "HOLD".equals(command.getAction()) &&
				"QMS_NCR".equals(command.getSourceType()), "仅普通NCR定向HOLD允许父引用");
		long parentId = com.kitex.mom.wms.service.physical.WmsPhysicalRules.id(
				command.getReholdOfPhysicalOperationId());
		require(Objects.equals(command.getRequestId(), "WMS-PH-REHOLD:" + parentId) &&
				StrUtil.isNotBlank(command.getReholdOfPhysicalRequestId()), "定向HOLD必须使用稳定父原号");
		var observed = physicalService.load(parentId, false);
		physicalRepository.warehouses(observed.getTenantId(), observed.getFactoryCode(),
				physicalService.warehouses(observed));
		var parent = physicalService.load(parentId, true);
		require(parent.getSpecialReturnBindingJson() == null &&
						TERMINAL_PHYSICAL_ACTIONS.contains(parent.getAction()) &&
						Objects.equals(parent.getRequestId(), command.getReholdOfPhysicalRequestId()) &&
						Objects.equals(parent.getHoldNo(), command.getHoldNo()) &&
						Objects.equals(parent.getSourceType(), command.getSourceType()) &&
						Objects.equals(parent.getSourceId(), command.getSourceId()) &&
						parent.getRequestedQuantity().compareTo(command.getQuantity()) == 0 &&
						parent.getAffectedQuantity().compareTo(command.getQuantity()) == 0,
				"定向HOLD父身份、来源、原号或整量不一致");
		var existing = getOne(new LambdaQueryWrapper<WmsQualityStatusOperationEntity>().eq(
						WmsQualityStatusOperationEntity::getRequestId, command.getRequestId()).last("FOR UPDATE"),
				false);
		if (existing != null) {
			assertSameCommand(existing, command);
			require(Objects.equals(existing.getReason(), command.getReason()), "定向原号原因原文不一致");
			require(Objects.equals(existing.getReholdOfPhysicalOperationId(), parentId) &&
							"SUCCESS".equals(existing.getStatus()) &&
							Objects.equals(existing.getAffectedStockIds(), parent.getAffectedStockIds()),
					"已有定向HOLD回执不完整");
			return result(existing);
		}
		require(physicalRepository.superseded(parent) == null && "ACTION_PENDING".equals(parent.getStatus()),
				"原处置已经执行或永久重隔离");
		var source = physicalProof.inspect(parent, true, false);
		// 其他原文条件逐项核对，不允许父ID相同但替换原物料或位置。
		require(Objects.equals(parent.getMatUnit(), command.getMatUnit()) &&
				Objects.equals(parent.getWarehouseCode(), command.getWarehouseCode()) &&
				Objects.equals(parent.getMatCode(), command.getMatCode()) &&
				Objects.equals(parent.getMatBatch(), command.getMatBatch()) &&
				Objects.equals(parent.getSupplierCode(), command.getSupplierCode()) &&
				Objects.equals(parent.getSiteCode(), command.getSiteCode()) &&
				Objects.equals(parent.getContainerCode(), command.getContainerCode()), "定向HOLD原条件不一致");
		var child = new WmsQualityStatusOperationEntity().setRequestId(command.getRequestId())
				.setHoldNo(parent.getHoldNo()).setSourceType(parent.getSourceType())
				.setSourceId(parent.getSourceId()).setAction("HOLD").setMatCode(parent.getMatCode())
				.setMatBatch(parent.getMatBatch()).setMatUnit(parent.getMatUnit())
				.setSupplierCode(parent.getSupplierCode()).setWarehouseCode(parent.getWarehouseCode())
				.setSiteCode(parent.getSiteCode()).setContainerCode(parent.getContainerCode())
				.setRequestedQuantity(parent.getRequestedQuantity())
				.setAffectedQuantity(parent.getAffectedQuantity())
				.setAffectedStockIds(parent.getAffectedStockIds()).setSplitRecorded(true)
				.setSplitRemainderStockIds(null).setSplitLineageJson("[]")
				.setSplitRecordedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)).setStatus("SUCCESS")
				.setReason(command.getReason()).setResultMessage("原处置已定向整件恢复隔离")
				.setFactoryCode(parent.getFactoryCode()).setReholdOfPhysicalOperationId(parentId);
		require(save(child), "定向HOLD保存失败");
		for (var piece : source.pieces()) {
			var stock = liveStockMapper.selectById(Long.valueOf(piece.stockId()));
			physicalRepository.clearOwner(parent, stock.getId());
			stock.setQualityPhysicalOperationId(null);
			applyState(stock, child);
			require(liveStockMapper.updateById(stock) == 1, "定向HOLD库存更新失败");
		}
		return result(getById(child.getId()));
	}

	private WmsQualityStatusResult process(WmsQualityStatusOperationEntity operation) {
		return process(operation, false);
	}

	/**
	 * retry候选锁定后、任意拆分或状态写入前再核全部候选仓，FIFO不受路由缩窄。
	 */
	private WmsQualityStatusResult process(WmsQualityStatusOperationEntity operation, boolean browserRetry) {
		// 特殊原件永不进入普通批次OR/FIFO，包括已登记PENDING事务的重试。
		boolean special = operation.getSpecialReturnBindingJson() != null;
		List<WmsLiveStockEntity> stocks = special ? specialReturnProof.resolve(operation)
				: "HOLD".equals(operation.getAction()) ? findAvailableStocks(operation) : findHeldStocks(
				operation);
		if (browserRetry) {
			var ws = stocks.stream().map(WmsLiveStockEntity::getWarehouseCode).distinct().sorted().toList();
			physicalAccess.requireWarehouses(ws, "show");
			physicalAccess.requireWarehouses(ws, "retry");
		}
		boolean physical = TERMINAL_PHYSICAL_ACTIONS.contains(operation.getAction());
		if (physical) {
			physicalAccess.write();
			physicalRepository.ready();
		}
		if (!stocks.isEmpty()) {
			Set<String> units = stocks.stream().map(WmsLiveStockEntity::getMatUnit).collect(Collectors.toSet());
			require(units.size() == 1 && StrUtil.isNotBlank(units.iterator().next()),
					"质量库存候选缺少单位或混用不同单位，禁止直接合并数量");
		}
		boolean ncrRework =
				"QMS_NCR".equals(operation.getSourceType()) && "REWORK".equals(operation.getAction());
		if (ncrRework) {
			// parent已INSERT为PENDING；同事务写owner，DB延迟约束核最终SUCCESS和完整影响ID。
			reworkRepository.ready();
			if (!special) {
				reworkProof.requireNewOrdinaryOrigin(operation, stocks);
			}
		}
		BigDecimal available = stocks.stream().map(this::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (available.compareTo(operation.getRequestedQuantity()) < 0) {
			require(!special, "原特殊接管片段数量不足，禁止重新匹配同批其他库存");
			operation.setStatus("PENDING").setAffectedQuantity(BigDecimal.ZERO).setAffectedStockIds(null)
					.setResultMessage(
							"匹配库存不足：需要" +
									operation.getRequestedQuantity().stripTrailingZeros().toPlainString()
									+ "，当前" + available.stripTrailingZeros().toPlainString() +
									"；等待入库或库存核对后重试");
			updateById(operation);
			return result(operation);
		}
		BigDecimal remaining = operation.getRequestedQuantity();
		List<Long> affectedIds = new ArrayList<>();
		List<Long> remainderIds = new ArrayList<>();
		List<Map<String, String>> splitLinks = new ArrayList<>();
		for (WmsLiveStockEntity stock : stocks) {
			if (remaining.signum() <= 0) {
				break;
			}
			BigDecimal before = quantity(stock);
			if (before.signum() == 0) {
				continue;
			}
			BigDecimal take = before.min(remaining);
			if (special) {
				require(reservationService.activeReservedNum(stock.getId()).signum() == 0,
						"特殊退回原件存在有效预留，不能质量接管或处置");
			}
			WmsSerialStockGuard.requireWhole(stock, take);
			if (before.compareTo(take) > 0) {
				WmsLiveStockEntity remainder = new WmsLiveStockEntity();
				BeanUtil.copyProperties(stock, remainder);
				remainder.setId(null);
				// 所有余量均保持原状态，绝不继承另一个parent的返工所有权。
				remainder.setQualityReworkOperationId(null);
				remainder.setQualityPhysicalOperationId(null);
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
			if (special && "HOLD".equals(operation.getAction())) {
				var binding = specialReturnProof.decode(operation.getSpecialReturnBindingJson());
				if (binding.reholdOperationId() != null && stock.getQualityPhysicalOperationId() != null) {
					// 395已证明同target/root的成功定向REHOLD，显式JDBC清owner，不影响其他MP更新。
					physicalRepository.clearOwner(
							physicalService.load(Long.valueOf(binding.reholdOperationId()), false),
							stock.getId());
					stock.setQualityPhysicalOperationId(null);
				}
			}
			applyState(stock, operation);
			if (physical) {
				require(stock.getQualityPhysicalOperationId() == null, "库存已属于其他实物处置parent");
				stock.setQualityPhysicalOperationId(operation.getId());
			}
			if (ncrRework) {
				stock.setQualityReworkOperationId(operation.getId());
			}
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
				.setStatus(TERMINAL_PHYSICAL_ACTIONS.contains(
						operation.getAction()) ? "ACTION_PENDING" : "SUCCESS")
				.setResultMessage(message(operation));
		if (physical) {
			physicalRepository.completeClassification(operation, physicalProof.inspect(operation, false, true));
		} else {
			require(updateById(operation), "质量库存操作结果保存失败");
		}
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
		return result(persisted);
	}

	private List<WmsLiveStockEntity> findAvailableStocks(WmsQualityStatusOperationEntity operation) {
		LambdaQueryWrapper<WmsLiveStockEntity> query = scope(operation)
				.isNull(WmsLiveStockEntity::getQualityReworkOperationId)
				.and(wrapper -> wrapper
						.and(available -> available.isNull(WmsLiveStockEntity::getQualityHoldNo)
								.and(state -> state.eq(WmsLiveStockEntity::getStatus, 0)
										.or(pending -> pending.eq(WmsLiveStockEntity::getStatus, 1)
												.and(quality -> quality.eq(WmsLiveStockEntity::getQualityStatus,
																"PENDING")
														.or().eq(WmsLiveStockEntity::getIsQualityTest, "1")))))
						.or(rehold -> rehold.eq(WmsLiveStockEntity::getQualityHoldNo, operation.getHoldNo())
								.eq(WmsLiveStockEntity::getQualitySourceType, operation.getSourceType())
								.eq(WmsLiveStockEntity::getQualitySourceId, operation.getSourceId())
								.in(WmsLiveStockEntity::getQualityStatus, "HOLD", "QUALIFIED",
										"CONCESSION", "SCRAP_PENDING", "RETURN_PENDING")))
				.orderByAsc(WmsLiveStockEntity::getInTime).orderByAsc(WmsLiveStockEntity::getId)
				.last("FOR UPDATE");
		return liveStockMapper.selectList(query);
	}

	private List<WmsLiveStockEntity> findHeldStocks(WmsQualityStatusOperationEntity operation) {
		return findHeldStocks(operation, true);
	}

	/**
	 * 先读取全候选仓取得统一仓锁，正式选库再保持原FIFO锁序。
	 */
	private List<WmsLiveStockEntity> findHeldStocks(WmsQualityStatusOperationEntity operation, boolean lock) {
		require(StrUtil.isNotBlank(operation.getHoldNo()), "处置必须关联质量隔离单号");
		var query = scope(operation)
				.eq(WmsLiveStockEntity::getStatus, 1)
				.eq(WmsLiveStockEntity::getQualityHoldNo, operation.getHoldNo())
				.eq(WmsLiveStockEntity::getQualitySourceType, operation.getSourceType())
				.eq(WmsLiveStockEntity::getQualitySourceId, operation.getSourceId())
				.isNull(WmsLiveStockEntity::getQualityReworkOperationId)
				.in(WmsLiveStockEntity::getQualityStatus, "QMS_NCR".equals(operation.getSourceType())
						? Set.of("HOLD") : ACTIVE_HOLD_STATUS)
				.orderByAsc(WmsLiveStockEntity::getInTime).orderByAsc(WmsLiveStockEntity::getId);
		if (lock) {
			query.last("FOR UPDATE");
		}
		return liveStockMapper.selectList(query);
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
		require(Objects.equals(specialReturnProof.decode(operation.getSpecialReturnBindingJson()),
						command.getSpecialReturnBinding()) &&
						(command.getSpecialReturnBinding() == null ||
								Objects.equals(operation.getReason(), command.getReason())) &&
						Objects.equals(operation.getHoldNo(), command.getHoldNo())
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
		var physicalReceipt = "EXECUTED".equals(operation.getStatus()) ? physicalService.appliedExecution(
				operation) : null;
		return new WmsQualityStatusResult().setOperationId(operation.getId()).setHoldNo(operation.getHoldNo())
				.setAction(operation.getAction()).setStatus(operation.getStatus())
				.setRequestedQuantity(operation.getRequestedQuantity())
				.setAffectedQuantity(operation.getAffectedQuantity()).setMessage(operation.getResultMessage())
				.setSpecialReturnBinding(specialReturnProof.resultBinding(operation))
				.setSupersededByOperationId(physicalService.superseded(operation))
				.setReholdOfPhysicalOperationId(operation.getReholdOfPhysicalOperationId() ==
						null ? null : operation.getReholdOfPhysicalOperationId().toString())
				.setExecution("EXECUTED".equals(
						operation.getStatus()) ? new com.kitex.mom.wms.dto.WmsQualityPhysicalDTO.Execution(
						operation.getExecutionRequestId(), operation.getExecutionRef(),
						operation.getExecutionRemark(),
						physicalReceipt == null ? null : physicalReceipt.endedById(), operation.getExecutedBy(),
						operation.getExecutedByName(),
						com.kitex.mom.wms.service.physical.WmsPhysicalRules.time(
								operation.getExecutedTime())) : null)
				.setAffectedStockIds(
						parseIds(operation.getAffectedStockIds()).stream().map(String::valueOf).toList());
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
