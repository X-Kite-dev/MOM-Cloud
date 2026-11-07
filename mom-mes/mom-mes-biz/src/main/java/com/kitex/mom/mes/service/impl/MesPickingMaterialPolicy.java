package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MdmBomChildDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillDetailEntity;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehousePickingBillDetailMapper;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.wms.dto.WmsMaterialReservationLinkDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.mdm.dto.MdmBomSubstituteOptionDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 领料草稿及正式准备共用的冻结BOM、替代、预留和数量政策；不运输WMS命令。
 */
@Service
@RequiredArgsConstructor
public class MesPickingMaterialPolicy {
	private final MesWarehousePickingBillDetailMapper mesWarehousePickingBillDetailMapper;
	private final MesTaskMapper mesTaskMapper;
	private final MesReworkMaterialPlanService reworkMaterialPlans;
	private final MesActualFactoryAccessService actualFactoryAccess;
	private final MesMaterialExecutionGuard materialExecution;
	private final RemoteWarehouseService warehouses;

	/**
	 * 以工单冻结BOM重新核领料、替代及预留，拒绝客户端伪造依据。
	 *
	 * @param entity   当前真实草稿或已锁原单，逐行归属由本方法绑定
	 * @param strictV1 新V1要求冻结标准单位及精确当量；legacy保留原舍入合同
	 * @return 当前实际组织唯一生产工单，已取得原根互斥和行锁
	 */
	public MesTaskEntity normalize(MesWarehousePickingBillEntity entity, boolean strictV1) {
		if (entity == null || CollUtil.isEmpty(entity.getDetailList())) {
			throw new CheckedException("生产领料明细不能为空");
		}
		String factory = actualFactoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		// 首次task行锁之前参与统一工单互斥；草稿、修改及提交复用同一准入规则。
		materialExecution.task(entity.getTaskCode());
		MesTaskEntity task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getCode, entity.getTaskCode())
				.eq(MesTaskEntity::getTenantId, tenant)
				.eq(MesTaskEntity::getFactoryCode, factory).last("FOR UPDATE NOWAIT"));
		if (task == null) {
			throw new CheckedException("生产工单[" + entity.getTaskCode() + "]不存在");
		}
		reworkMaterialPlans.assertReady(task);
		// 主单和每条明细都以已授权工单为归属来源，不能接受客户端的租户或工厂。
		entity.setFactoryCode(task.getFactoryCode());
		entity.setTenantId(task.getTenantId());
		entity.setMatCode(task.getMatCode());
		entity.setMatName(task.getMatName());
		entity.setMatSpec(task.getMatSpec());
		var frozenRequirements = MesMaterialSubstituteResolver.flattenBom(task.getBomSnapshot());
		if (strictV1) {
			for (var group : frozenRequirements.stream().collect(Collectors.groupingBy(MdmBomChildDTO::getMaterialCode))
					.values()) {
				var first = group.get(0);
				if (group.stream().anyMatch(item -> !Objects.equals(item.getUnit(), first.getUnit())
						|| !Objects.equals(item.getSubstitutePolicy(), first.getSubstitutePolicy()))) {
					throw new CheckedException("同需求物料存在不同冻结单位或替代策略，不能任取首条作为V1来源");
				}
			}
		}
		Map<String, MdmBomChildDTO> requirements = frozenRequirements.stream()
				.collect(Collectors.toMap(MdmBomChildDTO::getMaterialCode, item -> item, (left, right) -> left));
		boolean reservationSnapshotFrozen = StrUtil.isNotBlank(task.getMaterialReservationSnapshot());
		List<WmsMaterialReservationLinkDTO> reservationLinks = reservationSnapshotFrozen
				? JSONUtil.toList(task.getMaterialReservationSnapshot(), WmsMaterialReservationLinkDTO.class)
				: List.of();
		Map<String, BigDecimal> reservationUsed = new HashMap<>();
		Set<String> checkedWarehouses = new HashSet<>();
		for (MesWarehousePickingBillDetailEntity detail : entity.getDetailList()) {
			if (detail.getMatNum() == null || detail.getMatNum().compareTo(BigDecimal.ZERO) <= 0) {
				throw new CheckedException("领料物料[" + detail.getMatCode() + "]数量必须大于0");
			}
			MesMaterialSubstituteResolver.Binding binding = MesMaterialSubstituteResolver.resolveFeedBinding(
					task.getBomSnapshot(), detail.getMatCode(), detail.getRequirementMatCode());
			MdmBomChildDTO requirement = requirements.get(binding.requirementMatCode());
			if (strictV1) {
				if (StrUtil.isBlank(detail.getWarehouseCode()) || detail.getWarehouseCode().length() > 100
						|| !detail.getWarehouseCode().equals(detail.getWarehouseCode().trim())
						|| detail.getWarehouseCode().chars().anyMatch(Character::isISOControl)) {
					throw new CheckedException("请选择准确的实际发料仓库");
				}
				requireFrozenUnit(detail, requirement, binding.substitute());
				if (checkedWarehouses.add(detail.getWarehouseCode())) {
					var response = warehouses.getByCode(detail.getWarehouseCode());
					var warehouse = response != null && response.isOk() ? response.getData() : null;
					if (warehouse == null || !Objects.equals(warehouse.getWarehouseCode(), detail.getWarehouseCode())
							|| !Objects.equals(warehouse.getTenantId(), tenant)
							|| !Objects.equals(warehouse.getFactoryCode(), factory)
							|| !Boolean.TRUE.equals(warehouse.getStatus())) {
						throw new CheckedException("领料仓库不是当前工厂的真实启用仓库");
					}
					detail.setWarehouseName(warehouse.getWarehouseName());
				}
			}
			detail.setRequirementMatCode(binding.requirementMatCode());
			detail.setRequirementMatName(requirement == null ? null : requirement.getMaterialName());
			detail.setSubstituteFlag(binding.substitute());
			detail.setSubstituteRatio(binding.ratio());
			detail.setRequirementMatNum(
					requirementQuantity(detail, binding.ratio(), strictV1 || task.getReworkMaterialPlanId() != null));
			detail.setFactoryCode(task.getFactoryCode());
			detail.setTenantId(task.getTenantId());
			detail.setReservationNo(null);
			if (reservationSnapshotFrozen) {
				List<WmsMaterialReservationLinkDTO> materialLinks = reservationLinks.stream()
						.filter(link -> Objects.equals(link.getRequirementMatCode(), binding.requirementMatCode()))
						.filter(link -> Objects.equals(link.getActualMatCode(), detail.getMatCode()))
						.filter(link -> link.getSubstituteRatio() != null
								&& link.getSubstituteRatio().compareTo(binding.ratio()) == 0)
						.toList();
				if (materialLinks.isEmpty()) {
					if (binding.substitute()) {
						throw new CheckedException("替代料[" + detail.getMatCode()
								+ "]不在工单冻结的PLAN批准预留中");
					}
					continue;
				}
				WmsMaterialReservationLinkDTO matched = materialLinks.stream()
						.filter(link -> StrUtil.isBlank(link.getWarehouseCode())
								|| Objects.equals(link.getWarehouseCode(), detail.getWarehouseCode()))
						.filter(link -> {
							String key = reservationKey(link);
							BigDecimal used = reservationUsed.getOrDefault(key, BigDecimal.ZERO);
							return link.getReservedActualNum() != null
									&& link.getReservedActualNum().subtract(used).compareTo(detail.getMatNum()) >= 0;
						})
						.findFirst()
						.orElseThrow(() -> new CheckedException("物料[" + detail.getMatCode()
								+ "]不属于本次需求仓预留，或预留余额不足；请按预留量拆分领料明细"));
				detail.setReservationNo(matched.getReservationNo());
				reservationUsed.merge(reservationKey(matched), detail.getMatNum(), BigDecimal::add);
			}
		}
		if (task.getReworkMaterialPlanId() != null) {
			assertReworkPickingBudget(task, entity, requirements);
		}
		return task;
	}

	private void assertReworkPickingBudget(MesTaskEntity task, MesWarehousePickingBillEntity incoming,
	                                       Map<String, MdmBomChildDTO> requirements) {
		var prior = mesWarehousePickingBillDetailMapper.reworkBudget(task.getCode(), task.getTenantId(),
				task.getFactoryCode());
		if (prior.size() > 5000) {
			throw new CheckedException("返工领料行超过5000条，须受控扩容，不能截断后重新分配额度");
		}
		Set<Long> replaced = incoming.getDetailList().stream().map(MesWarehousePickingBillDetailEntity::getId)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Map<String, BigDecimal> used = new HashMap<>();
		List<MesWarehousePickingBillDetailEntity> all = new ArrayList<>(prior.stream()
				.filter(d -> !Objects.equals(d.getBillId(), incoming.getId()) || !replaced.contains(d.getId()))
				.toList());
		all.addAll(incoming.getDetailList());
		for (var d : all) {
			if (StrUtil.isBlank(d.getRequirementMatCode()) || d.getRequirementMatNum() == null ||
					d.getRequirementMatNum().signum() <= 0) {
				throw new CheckedException("返工已有领料缺少精确需求量来源，不能绕过批准额度");
			}
			used.merge(d.getRequirementMatCode(), d.getRequirementMatNum(), BigDecimal::add);
		}
		for (var entry : used.entrySet()) {
			var requirement = requirements.get(entry.getKey());
			if (requirement == null || requirement.getQuantity() == null ||
					entry.getValue().compareTo(requirement.getQuantity().multiply(task.getNoticeNum())) > 0) {
				throw new CheckedException("返工物料[" + entry.getKey() +
						"]累计领料含草稿超过批准总量；删去未执行草稿或走后续受控补料，不自动回补退料额度");
			}
		}
	}

	/**
	 * 保存后逐量回读，数据库舍入或缺明细必须回滚，不能把差异值签成原请求。
	 *
	 * @param expected 本事务保存的真实原行及精确数量/需求当量/比例
	 */
	public void verifySavedPickingQuantities(MesWarehousePickingBillDetailEntity expected) {
		var stored = mesWarehousePickingBillDetailMapper.selectOne(
				new LambdaQueryWrapper<MesWarehousePickingBillDetailEntity>()
						.eq(MesWarehousePickingBillDetailEntity::getTenantId, expected.getTenantId())
						.eq(MesWarehousePickingBillDetailEntity::getFactoryCode, expected.getFactoryCode())
						.eq(MesWarehousePickingBillDetailEntity::getId, expected.getId()));
		if (stored == null || stored.getMatNum() == null || stored.getMatNum().compareTo(expected.getMatNum()) != 0
				|| stored.getRequirementMatNum() == null ||
				stored.getRequirementMatNum().compareTo(expected.getRequirementMatNum()) != 0
				|| stored.getSubstituteRatio() == null ||
				stored.getSubstituteRatio().compareTo(expected.getSubstituteRatio()) != 0) {
			throw new CheckedException("领料数量或替代换算保存发生舍入，已回滚");
		}
	}

	private String reservationKey(WmsMaterialReservationLinkDTO link) {
		return link.getReservationNo() + "#" + link.getRequirementMatCode() + "#" + link.getActualMatCode();
	}


	/**
	 * 新V1及返工当量不得先舍入；legacy既有路径保留原八位HALF_UP合同。
	 */
	private BigDecimal requirementQuantity(MesWarehousePickingBillDetailEntity detail, BigDecimal ratio,
	                                       boolean exact) {
		try {
			BigDecimal quantity = detail.getMatNum()
					.divide(ratio, 8, exact ? RoundingMode.UNNECESSARY : RoundingMode.HALF_UP);
			return exact ? quantity.stripTrailingZeros() : quantity;
		} catch (ArithmeticException failure) {
			throw new CheckedException("物料[" + detail.getMatCode() + "]BOM需求当量无法按8位小数准确表达，禁止舍入",
					failure);
		}
	}

	/**
	 * 冻结标准单位按精确实际物料读取；旧替代代码缺单位时拒绝，不借主料单位猜测。
	 */
	private void requireFrozenUnit(MesWarehousePickingBillDetailEntity detail, MdmBomChildDTO requirement,
	                               boolean substitute) {
		String expected = requirement == null ? null : requirement.getUnit();
		if (substitute) {
			if (requirement == null || StrUtil.isBlank(requirement.getSubstitutePolicy())) {
				throw new CheckedException("替代料缺少冻结标准单位，请先核对来源方案");
			}
			var options = JSONUtil.toList(requirement.getSubstitutePolicy(), MdmBomSubstituteOptionDTO.class).stream()
					.filter(option -> Objects.equals(option.getSubstituteCode(), detail.getMatCode())).toList();
			if (options.size() != 1) {
				throw new CheckedException("冻结替代物料单位来源不唯一");
			}
			expected = options.get(0).getSubstituteUnit();
		}
		if (StrUtil.isBlank(expected) || expected.length() > 100 || !expected.equals(expected.trim()) ||
				expected.chars().anyMatch(Character::isISOControl)
				|| !Objects.equals(detail.getMatUnit(), expected)) {
			throw new CheckedException("物料[" + detail.getMatCode() + "]单位与工单冻结的实际物料标准单位不一致");
		}
	}
}
