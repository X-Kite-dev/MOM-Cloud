package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.vo.WmsInsideInventoryPlanExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

@Service
@RequiredArgsConstructor
public class WmsInsideInventoryPlanServiceImpl
		extends ServiceImpl<WmsInsideInventoryPlanMapper, WmsInsideInventoryPlanEntity>
		implements WmsInsideInventoryPlanService {
	private final WmsInsideInventoryPlanDetailMapper wmsInsideInventoryPlanDetailMapper;
	private final WmsLiveStockMapper wmsLiveStockMapper;
	private final RemoteWarehouseLocationService remoteWarehouseLocationService;
	private final RemoteMaterialService remoteMaterialService;
	private final RemoteWarehouseService remoteWarehouseService;
	private final WmsInventoryPlanAccess access;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final WmsInventoryAdjustmentService adjustments;

	@Override
	public Boolean countFreezeAvailable() {
		return baseMapper.countFreezeReady() == 1;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveDeep(WmsInsideInventoryPlanEntity plan) {
		return saveDeep(plan, false);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveDeepMobile(WmsInsideInventoryPlanEntity plan) {
		return saveDeep(plan, true);
	}

	private Boolean saveDeep(WmsInsideInventoryPlanEntity plan, boolean mobile) {
		require(plan != null && CollUtil.isNotEmpty(plan.getWmsInsideInventoryPlanDetailList()), "请选择盘点物料和库位");
		String planNo = StrUtil.trim(plan.getPlanNo());
		require(StrUtil.isNotBlank(planNo) && planNo.length() <= 32, "盘点编号不能为空且不能超过32个字符");
		plan.setPlanNo(planNo);
		String freezeMode = StrUtil.isBlank(plan.getStockFreezeMode())
				? "SNAPSHOT" : plan.getStockFreezeMode().trim();
		require("SNAPSHOT".equals(freezeMode) || "FREEZE_COUNT".equals(freezeMode), "盘点库存写入模式无效");
		plan.setStockFreezeMode(freezeMode);
		if ("FREEZE_COUNT".equals(freezeMode)) {
			require(countFreezeAvailable(), "盘点冻结保护未就绪，请先完成数据库升级");
		}
		String factory = factory();
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		require(warehouseGuard.check(plan.getWarehouseCode(), "other_inventory", true), "仓库不允许盘点");
		validateWarehouse(plan, factory);
		requireUniquePlanNo(planNo, factory);
		validateLocations(plan, factory);
		validateMaterials(plan, factory);
		requireNoOverlappingPlan(plan, factory);
		plan.setId(null);
		plan.setFactoryCode(factory);
		plan.setTenantId(TenantContextHolder.getTenantId());
		plan.setStatus("0");
		plan.setHandleState("0");
		require(baseMapper.insert(plan) == 1, "保存盘点计划失败");
		Map<InventoryScope, Set<String>> scopes = new HashMap<>();
		for (var row : plan.getWmsInsideInventoryPlanDetailList()) {
			require(StrUtil.isNotBlank(row.getMatCode()) && StrUtil.isNotBlank(row.getSiteCode())
					&& StrUtil.isNotBlank(row.getMatUnit()), "盘点物料、库位和单位不能为空");
			String batch = StrUtil.isBlank(row.getMatBatch()) ? "" : row.getMatBatch().trim();
			Set<String> countedBatches = scopes.computeIfAbsent(
					new InventoryScope(row.getMatCode(), row.getSiteCode()), ignored -> new HashSet<>());
			// 空批次代表此物料库位的全部批次，不能再与单批次范围重叠。
			require((batch.isEmpty() ? countedBatches.isEmpty() : !countedBatches.contains(""))
					&& countedBatches.add(batch), "同一物料库位的盘点批次重复或与全批次范围重叠");
			row.setMatBatch(batch.isEmpty() ? null : batch);
			var stocks = wmsLiveStockMapper.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
					.eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(WmsLiveStockEntity::getFactoryCode, factory)
					.eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
					.eq(WmsLiveStockEntity::getWarehouseCode, plan.getWarehouseCode())
					.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
					.eq(StrUtil.isNotBlank(row.getMatBatch()), WmsLiveStockEntity::getMatBatch, row.getMatBatch())
					.orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
			BigDecimal quantity = BigDecimal.ZERO;
			for (var stock : stocks) {
				require(Objects.equals(row.getMatUnit(), stock.getMatUnit()), "盘点范围库存单位不一致，请先核对");
				quantity = quantity.add(number(stock.getMatNum(), "库存数量"));
			}
			row.setId(null);
			row.setPlanId(plan.getId());
			row.setCurrentNumber(quantity.doubleValue());
			row.setInventoryNum(null);
			row.setWarehouseCode(plan.getWarehouseCode());
			row.setWarehouseName(plan.getWarehouseName());
			row.setStatus("0");
			row.setPlStatus("0");
			row.setFactoryCode(factory);
			row.setTenantId(TenantContextHolder.getTenantId());
			require(wmsInsideInventoryPlanDetailMapper.insert(row) == 1, "保存盘点明细失败");
		}
		return true;
	}

	private void requireUniquePlanNo(String planNo, String factory) {
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0, "当前租户无效");
		String lockKey = "WMS_INVENTORY_PLAN_NO|" + tenant + "|" + factory + "|" + planNo;
		require(baseMapper.lockCountScope(lockKey) == 1, "锁定盘点编号失败");
		require(baseMapper.countActivePlanNo(tenant, factory, planNo) == 0,
				"盘点编号已存在，请到任务列表核对原计划");
	}

	private void requireNoOverlappingPlan(WmsInsideInventoryPlanEntity plan, String factory) {
		Long tenant = TenantContextHolder.getTenantId();
		String lockKey = "WMS_INVENTORY_PLAN|" + tenant + "|" + factory + "|" + plan.getWarehouseCode();
		require(baseMapper.lockCountScope(lockKey) == 1, "锁定仓库盘点范围失败");
		var existingRows = baseMapper.findUnfinishedDetails(tenant, factory, plan.getWarehouseCode());
		require(existingRows != null, "查询未结盘点明细失败");
		for (var existing : existingRows) {
			require(existing != null && existing.getPlanId() != null
							&& Objects.equals(existing.getTenantId(), tenant)
							&& Objects.equals(existing.getFactoryCode(), factory)
							&& Objects.equals(existing.getWarehouseCode(), plan.getWarehouseCode())
							&& StrUtil.isNotBlank(existing.getMatCode()) && StrUtil.isNotBlank(existing.getSiteCode()),
					"未结盘点明细范围不完整，请核对");
			for (var requested : plan.getWmsInsideInventoryPlanDetailList()) {
				if (!Objects.equals(existing.getMatCode(), requested.getMatCode())
						|| !Objects.equals(existing.getSiteCode(), requested.getSiteCode())) {
					continue;
				}
				String priorBatch = StrUtil.trimToEmpty(existing.getMatBatch());
				String nextBatch = StrUtil.trimToEmpty(requested.getMatBatch());
				require(!priorBatch.isEmpty() && !nextBatch.isEmpty() && !priorBatch.equals(nextBatch),
						"同一物料库位批次已有未结盘点计划，请先完成或作废原计划");
			}
		}
	}

	private void validateWarehouse(WmsInsideInventoryPlanEntity plan, String factory) {
		R<MdmWarehouseEntity> response = remoteWarehouseService.getByCode(plan.getWarehouseCode());
		require(response != null && response.isOk(), "读取盘点仓库失败");
		MdmWarehouseEntity warehouse = response.getData();
		require(warehouse != null && Objects.equals(plan.getWarehouseCode(), warehouse.getWarehouseCode())
						&& Objects.equals(TenantContextHolder.getTenantId(), warehouse.getTenantId())
						&& Objects.equals(factory, warehouse.getFactoryCode())
						&& Boolean.TRUE.equals(warehouse.getStatus())
						&& StrUtil.isNotBlank(warehouse.getWarehouseName()),
				"盘点仓库不存在、未启用或不属于当前组织");
		plan.setWarehouseName(warehouse.getWarehouseName());
	}

	private void validateLocations(WmsInsideInventoryPlanEntity plan, String factory) {
		Map<String, MdmWarehouseLocationEntity> validated = new HashMap<>();
		for (var row : plan.getWmsInsideInventoryPlanDetailList()) {
			require(row != null && StrUtil.isNotBlank(row.getSiteCode()), "盘点库位不能为空");
			String code = row.getSiteCode().trim();
			row.setSiteCode(code);
			MdmWarehouseLocationEntity location = validated.get(code);
			if (location == null) {
				R<MdmWarehouseLocationEntity> response = remoteWarehouseLocationService.getByCode(code);
				require(response != null && response.isOk(), "读取盘点库位失败");
				location = response.getData();
				require(location != null && Objects.equals(code, location.getLocationCode())
								&& Objects.equals(plan.getWarehouseCode(), location.getWarehouseCode())
								&& Objects.equals(TenantContextHolder.getTenantId(), location.getTenantId())
								&& (StrUtil.isBlank(location.getFactoryCode())
								|| Objects.equals(factory, location.getFactoryCode()))
								&& Boolean.TRUE.equals(location.getStatus())
								&& Objects.equals(3, location.getLocationLevel())
								&& StrUtil.isNotBlank(location.getLocationName()),
						"盘点库位不存在、未启用、不是实际库位或不属于当前组织仓库");
				validated.put(code, location);
			}
			row.setSiteName(location.getLocationName());
		}
	}

	private void validateMaterials(WmsInsideInventoryPlanEntity plan, String factory) {
		Set<String> requested = new LinkedHashSet<>();
		for (var row : plan.getWmsInsideInventoryPlanDetailList()) {
			require(StrUtil.isNotBlank(row.getMatCode()), "盘点物料不能为空");
			row.setMatCode(row.getMatCode().trim());
			requested.add(row.getMatCode());
		}
		List<String> codes = new ArrayList<>(requested);
		Map<String, MdmMaterialEntity> materials = new HashMap<>();
		for (int start = 0; start < codes.size(); start += 50) {
			List<String> batch = codes.subList(start, Math.min(start + 50, codes.size()));
			R<List<MdmMaterialEntity>> response = remoteMaterialService.getListByCodes(batch);
			require(response != null && response.isOk() && response.getData() != null, "读取盘点物料失败");
			for (MdmMaterialEntity material : response.getData()) {
				require(material != null && requested.contains(material.getMaterialCode())
								&& Objects.equals(TenantContextHolder.getTenantId(), material.getTenantId())
								&& (StrUtil.isBlank(material.getFactoryCode())
								|| Objects.equals(factory, material.getFactoryCode()))
								&& StrUtil.isNotBlank(material.getMaterialName())
								&& StrUtil.isNotBlank(material.getBasicUnit()),
						"盘点物料不存在或不属于当前组织");
				require(materials.putIfAbsent(material.getMaterialCode(), material) == null,
						"盘点物料编码重复，请核对主数据");
			}
		}
		require(materials.keySet().containsAll(requested), "存在未建档的盘点物料");
		for (var row : plan.getWmsInsideInventoryPlanDetailList()) {
			MdmMaterialEntity material = materials.get(row.getMatCode());
			row.setMatName(material.getMaterialName());
			row.setMatUnit(material.getBasicUnit());
			row.setMatSpec(material.getSpecificationModel());
			row.setMatGroup(material.getGroupCode());
			row.setMatGroupName(material.getGroupName());
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean inventory(WmsInsideInventoryPlanEntity command) {
		require(command != null, "盘点录入不能为空");
		var plan = access.plan(command.getId(), true);
		require("0".equals(plan.getStatus()) && "0".equals(plan.getHandleState()),
				"盘点录入已结束，禁止修改实盘数量");
		var rows = access.details(plan.getId(), true);
		var submitted = command.getWmsInsideInventoryPlanDetailList();
		require(submitted != null && submitted.size() == rows.size(), "请完整提交原盘点明细");
		Map<Long, WmsInsideInventoryPlanDetailEntity> byId = new HashMap<>();
		for (var row : submitted) {
			require(row != null && byId.put(row.getId(), row) == null, "盘点明细重复或为空");
		}
		for (var row : rows) {
			var input = byId.get(row.getId());
			require(input != null, "盘点明细不属于该计划");
			updateCount(row, input.getInventoryNum());
		}
		plan.setStatus("1");
		require(baseMapper.updateById(plan) == 1, "保存盘点状态失败");
		return true;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean appInventory(WmsInsideInventoryPlanDetailEntity input) {
		require(input != null, "盘点录入不能为空");
		var row = access.detail(input.getId());
		var plan = access.plan(row.getPlanId(), true);
		require("0".equals(plan.getStatus()) && "0".equals(plan.getHandleState()),
				"盘点录入已结束，禁止修改实盘数量");
		warehouseGuard.checkMobile(plan.getWarehouseCode());
		updateCount(row, input.getInventoryNum());
		return true;
	}

	private void updateCount(WmsInsideInventoryPlanDetailEntity row, Double input) {
		var quantity = number(input, "实盘数量");
		if ("1".equals(row.getPlStatus())) {
			require(quantity.compareTo(number(row.getInventoryNum(), "原实盘数量")) == 0,
					"该明细已调账，禁止改写原实盘数量");
			return;
		}
		row.setInventoryNum(quantity.doubleValue());
		row.setStatus("1");
		require(wmsInsideInventoryPlanDetailMapper.updateById(row) == 1, "保存实盘数量失败");
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean appInventorySuccess(Long id) {
		var plan = access.plan(id, true);
		require(("0".equals(plan.getStatus()) || "1".equals(plan.getStatus()))
				&& "0".equals(plan.getHandleState()), "盘点计划已处理或已作废");
		if ("0".equals(plan.getStatus())) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		for (var row : access.details(id, true)) {
			requireCount(row);
		}
		if ("1".equals(plan.getStatus())) {
			return true;
		}
		plan.setStatus("1");
		require(baseMapper.updateById(plan) == 1, "保存盘点状态失败");
		return true;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean complete(Long id) {
		return complete(id, false);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean completeMobile(Long id) {
		return complete(id, true);
	}

	private Boolean complete(Long id, boolean mobile) {
		var plan = access.plan(id, true);
		require("1".equals(plan.getStatus()), "请先完成整张盘点计划的实盘录入");
		var rows = access.details(id, true);
		if (mobile) {
			for (var row : rows) {
				require(Objects.equals(row.getPlanId(), plan.getId())
								&& Objects.equals(row.getWarehouseCode(), plan.getWarehouseCode())
								&& Objects.equals(row.getTenantId(), plan.getTenantId())
								&& Objects.equals(row.getFactoryCode(), plan.getFactoryCode()),
						"盘点明细与原计划范围不一致，请先核对原账");
			}
		}
		if (mobile && "1".equals(plan.getHandleState())) {
			// 原确认只读核验；已完成计划不能借重放补写遗留明细。
			for (var row : rows) {
				requireCount(row);
				require("1".equals(row.getPlStatus()), "已完成计划存在未处理明细，请先核对原账");
				adjustments.requireReceipt(row);
			}
			return true;
		}
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		for (var row : rows) {
			resolveOrReject(row);
		}
		plan.setHandleState("1");
		plan.setStatus("1");
		require(baseMapper.updateById(plan) == 1, "保存盘点完成状态失败");
		return true;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean detailComplete(Long id) {
		resolveFinalizedDetail(id);
		return true;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean completeDetails(Long id) {
		// The old 'cancel handling' endpoint must not silently waive a real stock difference.
		resolveFinalizedDetail(id);
		return true;
	}

	private void resolveFinalizedDetail(Long id) {
		var row = access.detail(id);
		var plan = access.plan(row.getPlanId(), true);
		require("1".equals(plan.getStatus()), "请先完成整张盘点计划的实盘录入");
		resolveOrReject(row);
	}

	private void requireCount(WmsInsideInventoryPlanDetailEntity row) {
		require("1".equals(row.getStatus()), "存在未录入的盘点明细，请明确填写实盘数量，不能自动视为零");
		number(row.getInventoryNum(), "实盘数量");
		number(row.getCurrentNumber(), "账面数量");
	}

	private void resolveOrReject(WmsInsideInventoryPlanDetailEntity row) {
		requireCount(row);
		boolean hasDifference = number(row.getCurrentNumber(), "账面数量")
				.compareTo(number(row.getInventoryNum(), "实盘数量")) != 0;
		if (hasDifference) {
			require("1".equals(row.getPlStatus()), "存在未调账差异，请从损益处理统一办理，不能直接完成或取消处理");
		}
		adjustments.requireReceipt(row);
		if (!hasDifference && !"1".equals(row.getPlStatus())) {
			requireUnchangedZeroDifference(row);
			row.setPlStatus("1");
			require(wmsInsideInventoryPlanDetailMapper.updateById(row) == 1, "保存盘点明细状态失败");
		}
	}

	private void requireUnchangedZeroDifference(WmsInsideInventoryPlanDetailEntity row) {
		require(StrUtil.isNotBlank(row.getWarehouseCode()) && StrUtil.isNotBlank(row.getMatCode())
						&& StrUtil.isNotBlank(row.getSiteCode()) && StrUtil.isNotBlank(row.getMatUnit()),
				"零差异盘点范围不完整，请先核对");
		var stocks = wmsLiveStockMapper.selectList(Wrappers.<WmsLiveStockEntity>lambdaQuery()
				.eq(WmsLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsLiveStockEntity::getFactoryCode, factory())
				.eq(WmsLiveStockEntity::getWarehouseCode, row.getWarehouseCode())
				.eq(WmsLiveStockEntity::getMatCode, row.getMatCode())
				.eq(WmsLiveStockEntity::getSiteCode, row.getSiteCode())
				.eq(StrUtil.isNotBlank(row.getMatBatch()), WmsLiveStockEntity::getMatBatch, row.getMatBatch())
				.orderByAsc(WmsLiveStockEntity::getId).last("FOR UPDATE"));
		BigDecimal balance = BigDecimal.ZERO;
		for (var stock : stocks) {
			require(Objects.equals(row.getMatUnit(), stock.getMatUnit()), "盘点范围库存单位不一致，请先核对");
			balance = balance.add(number(stock.getMatNum(), "实时库存数量"));
		}
		require(balance.compareTo(number(row.getCurrentNumber(), "原账面数量")) == 0,
				"盘点后库存已发生变化，请先核对库存及原实盘依据，禁止直接完成零差异盘点");
	}

	@Override
	public Page<WmsInsideInventoryPlanEntity> appPage(Page page, WmsInsideInventoryPlanQueryDTO query) {
		query.setFactoryCode(factory());
		query.setTenantId(TenantContextHolder.getTenantId());
		return baseMapper.appPage(page, query);
	}

	@Override
	public List<WmsInsideInventoryPlanExcelVO> export(WmsInsideInventoryPlanEntity query, Long[] ids) {
		return list(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
				.eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
				.like(StrUtil.isNotBlank(query.getPlanNo()), WmsInsideInventoryPlanEntity::getPlanNo,
						query.getPlanNo())
				.eq(StrUtil.isNotBlank(query.getWarehouseCode()), WmsInsideInventoryPlanEntity::getWarehouseCode,
						query.getWarehouseCode())
				.eq(StrUtil.isNotBlank(query.getStatus()), WmsInsideInventoryPlanEntity::getStatus,
						query.getStatus())
				.eq(StrUtil.isNotBlank(query.getHandleState()), WmsInsideInventoryPlanEntity::getHandleState,
						query.getHandleState())
				.in(ArrayUtil.isNotEmpty(ids), WmsInsideInventoryPlanEntity::getId,
						Arrays.asList(ids == null ? new Long[0] : ids)))
				.stream().map(row -> {
					var result = new WmsInsideInventoryPlanExcelVO();
					BeanUtil.copyProperties(row, result);
					result.setStockFreezeMode("FREEZE_COUNT".equals(row.getStockFreezeMode())
							? "实盘期间冻结" : "动态盘点");
					return result;
				})
				.collect(Collectors.toList());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeDeep(Long[] ids) {
		require(ids != null && ids.length > 0, "请选择盘点计划");
		for (Long id : Arrays.stream(ids).sorted().toList()) {
			var plan = access.plan(id, true);
			var rows = access.details(id, true);
			require("0".equals(plan.getStatus()) && "0".equals(plan.getHandleState())
					&& rows.stream().allMatch(row -> !"1".equals(row.getStatus())
					&& !"1".equals(row.getPlStatus())), "已录入或已处理的盘点必须保留原记录，禁止删除");
			// 先在同一事务停用计划冻结，再逻辑删除明细；任何失败都会回滚两步。
			require(baseMapper.deleteById(id) == 1, "删除盘点计划失败");
			int deleted = wmsInsideInventoryPlanDetailMapper.delete(Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery()
					.eq(WmsInsideInventoryPlanDetailEntity::getPlanId, id)
					.eq(WmsInsideInventoryPlanDetailEntity::getTenantId, TenantContextHolder.getTenantId())
					.eq(WmsInsideInventoryPlanDetailEntity::getFactoryCode, factory()));
			require(deleted == rows.size(), "盘点明细删除数量不一致，已取消删除");
		}
		return true;
	}

	@Override
	public WmsInsideInventoryPlanEntity getDetails(Long id) {
		var plan = access.plan(id, false);
		plan.setWmsInsideInventoryPlanDetailList(access.details(id, false));
		return plan;
	}

	private record InventoryScope(String materialCode, String siteCode) {
	}
}
