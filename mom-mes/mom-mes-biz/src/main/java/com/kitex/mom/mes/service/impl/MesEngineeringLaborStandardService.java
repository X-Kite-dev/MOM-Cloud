package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.support.MdmProcedureLaborStandardCodec;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesEngineeringLaborStandardDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读取工单原工程及本节点实际批准/接收来源，不查今日工序，也不以实际计时回填标准。
 */
@Service
@RequiredArgsConstructor
public class MesEngineeringLaborStandardService {
	private final MesActualFactoryAccessService access;
	private final MesTaskMapper tasks;
	private final MesEngineeringPackageService engineering;
	private final MesManualLaborStandardChangeService changes;
	private final MesPlanLaborStandardService planStandards;

	/**
	 * 核实际工厂读取权及唯一原工单；原标准取原包，批准/接收摘要取不可变本地账，历史无包明确未知。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MesEngineeringLaborStandardDTO.View view(String taskCode) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		if (taskCode == null || taskCode.isBlank() || taskCode.length() > 100) {
			throw new CheckedException("请选择明确工单读取原工程标准");
		}
		var matches = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
		if (matches.size() != 1) {
			throw new CheckedException("当前组织未找到唯一原工单");
		}
		var task = matches.get(0);
		if (task.getEngineeringReleaseId() == null && !text(task.getEngineeringContentHash())
				&& !text(task.getEngineeringBundleSnapshot())) {
			planStandards.assertLegacyWithoutLayer(task);
			return legacy(task);
		}
		var frozen = engineering.readIssuedOriginal(task);
		var routes = JSONUtil.toList(frozen.routeSnapshot(), MdmProcessRouteWorkingDTO.class);
		List<MesEngineeringLaborStandardDTO.Node> nodes = new ArrayList<>();
		boolean manualSupported = changes.supports(task);
		boolean planSupported = !manualSupported && planStandards.supports(task);
		var processes = manualSupported ? changes.processes(task)
				: List.<com.kitex.mom.mes.entity.MesProduceTaskEntity>of();
		Map<String, MesPlanLaborStandardService.NodeView> planView = planSupported ? planStandards.view(
				task) : Map.of();
		for (var route : routes) {
			var standard = route.getLaborStandard();
			var effective = effective(task, route, processes);
			var planNode = planView.get(route.getRouteNodeCode());
			var currentApproved = planNode == null ? approved(task, route, effective) : planNode.approved();
			var currentAdoptions = planNode == null ? adoptions(effective) : planNode.adoptions();
			JsonNode source = standard == null ? null : MdmProcedureLaborStandardCodec.read(standard,
					tenant.toString(), factory, route.getProcessCode());
			String batch = value(source, "activityBasicBatch");
			String batchUnit = value(source, "batchUnit");
			boolean validBasis = source != null && source.path("activityBasicBatch").isNumber()
					&& source.path("activityBasicBatch").decimalValue().signum() > 0 && text(batchUnit);
			var basis = new MesEngineeringLaborStandardDTO.Basis(batch, batchUnit, validBasis ? "DECLARED" : "UNKNOWN");
			var measures = List.of(measure(source, "SETUP", "setupTime", true),
					measure(source, "LABOR", "manualProcessingTime", validBasis),
					measure(source, "MACHINE", "machineProcessingTime", validBasis),
					measure(source, "DISASSEMBLY", "disassemblyTime", true));
			nodes.add(new MesEngineeringLaborStandardDTO.Node(route.getRouteNodeCode(), route.getProcessCode(),
					source == null ? "UNKNOWN_HISTORICAL" : "FROZEN_SOURCE", value(source, "sourceId"),
					standard == null ? null : standard.getSourceFingerprint(), value(source, "sourceUpdatedAt"),
					value(source, "sourceUpdatedBy"), basis, measures, currentApproved,
					currentAdoptions));
		}
		return new MesEngineeringLaborStandardDTO.View(task.getCode(), task.getEngineeringReleaseId().toString(),
				task.getEngineeringVersionNo(), task.getEngineeringContentHash(), List.copyOf(nodes),
				changes.changeAvailable(task));
	}

	/**
	 * 保留旧路线节点身份，但不信任无批准联合包的同名标准字段；来源一律未知。
	 */
	private MesEngineeringLaborStandardDTO.View legacy(MesTaskEntity task) {
		List<MesEngineeringLaborStandardDTO.Node> nodes = new ArrayList<>();
		if (text(task.getProcessRouteSnapshot())) {
			List<MdmProcessRouteWorkingDTO> routes;
			try {
				routes = JSONUtil.toList(task.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
			} catch (Exception failure) {
				throw new CheckedException("历史路线原文损坏，无法展示标准来源未知的节点", failure);
			}
			if (routes.size() > 200) {
				throw new CheckedException("历史原路线超过200节点，禁止截断展示标准来源");
			}
			for (var route : routes) {
				if (route == null) {
					throw new CheckedException("历史原路线包含空节点");
				}
				// 路线内即使出现同名字段，没有批准联合包也不能冒充已冻结标准。
				nodes.add(new MesEngineeringLaborStandardDTO.Node(route.getRouteNodeCode(),
						text(route.getProcessCode()) ? route.getProcessCode() : route.getRouteCode(),
						"UNKNOWN_HISTORICAL", null, null, null, null,
						new MesEngineeringLaborStandardDTO.Basis(null, null, "UNKNOWN"),
						List.of(measure(null, "SETUP", "setupTime", false),
								measure(null, "LABOR", "manualProcessingTime", false),
								measure(null, "MACHINE", "machineProcessingTime", false),
								measure(null, "DISASSEMBLY", "disassemblyTime", false)), null, List.of()));
			}
		}
		return new MesEngineeringLaborStandardDTO.View(task.getCode(), null, null, null, List.copyOf(nodes), false);
	}

	/**
	 * 当前批准层仍以原包及稳定节点核对，不能用今日工序或其他同码节点替代。
	 */
	private MesEngineeringLaborStandardDTO.Approved approved(MesTaskEntity task, MdmProcessRouteWorkingDTO route,
	                                                         MesManualLaborStandardChangeService.Effective effective) {
		if (effective == null || effective.change() == null) {
			return null;
		}
		var change = effective.change();
		var standard = JSONUtil.toBean(effective.standard(), com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO.class);
		var source = MdmProcedureLaborStandardCodec.read(standard, task.getTenantId().toString(), task.getFactoryCode(),
				route.getProcessCode());
		String batch = value(source, "activityBasicBatch");
		String unit = value(source, "batchUnit");
		boolean known = source.path("activityBasicBatch").isNumber() &&
				source.path("activityBasicBatch").decimalValue().signum() > 0 && text(unit);
		return new MesEngineeringLaborStandardDTO.Approved(change.getId().toString(), change.getReleaseId().toString(),
				change.getContentHash(),
				change.getReviewedAt().toString(), change.getReason(), standard.getSourceFingerprint(),
				new MesEngineeringLaborStandardDTO.Basis(batch, unit, known ? "DECLARED" : "UNKNOWN"),
				List.of(measure(source, "SETUP", "setupTime", true),
						measure(source, "LABOR", "manualProcessingTime", known),
						measure(source, "MACHINE", "machineProcessingTime", known),
						measure(source, "DISASSEMBLY", "disassemblyTime", true)));
	}

	/**
	 * 实际接收采用证据单独显示，空目录不推断历史未接收。
	 */
	private List<MesEngineeringLaborStandardDTO.Adoption> adoptions(
			MesManualLaborStandardChangeService.Effective effective) {
		if (effective == null) {
			return List.of();
		}
		return effective.adoptions().stream()
				.map(row -> new MesEngineeringLaborStandardDTO.Adoption(row.getId().toString(),
						row.getArrangeId().toString(), row.getChangeId() == null ? null : row.getChangeId().toString(),
						row.getSourceFingerprint(), row.getReceivedAt().toString())).toList();
	}

	private MesManualLaborStandardChangeService.Effective effective(MesTaskEntity task, MdmProcessRouteWorkingDTO route,
	                                                                List<com.kitex.mom.mes.entity.MesProduceTaskEntity> processes) {
		if (!changes.supports(task)) {
			return null;
		}
		var matches = processes.stream()
				.filter(row -> java.util.Objects.equals(row.getSourceRouteNodeCode(), route.getRouteNodeCode()))
				.toList();
		if (matches.isEmpty()) {
			return null;
		}
		if (matches.size() != 1 || !java.util.Objects.equals(matches.get(0).getRouteCode(), route.getProcessCode())) {
			throw new CheckedException("原标准未唯一对应当前实际稳定节点");
		}
		return changes.effective(task, matches.get(0));
	}

	/**
	 * 原样返回单类标准和单位；缺值、单位或必要批量时保持UNKNOWN。
	 */
	private MesEngineeringLaborStandardDTO.Measure measure(JsonNode source, String kind, String field,
	                                                       boolean basisKnown) {
		String value = value(source, field);
		String unit = value(source, field + "Unit");
		return new MesEngineeringLaborStandardDTO.Measure(kind, value, unit,
				value != null && text(unit) && basisKnown ? "DECLARED" : "UNKNOWN");
	}

	/**
	 * 只读原标准字段，数值以精确十进制文本返回，不经过double。
	 */
	private String value(JsonNode source, String field) {
		if (source == null || !source.hasNonNull(field)) {
			return null;
		}
		JsonNode value = source.get(field);
		return value.isNumber() ? value.decimalValue().toPlainString() : value.asText();
	}

	/**
	 * 判断来源文本是否明确非空，不生成默认单位或默认批量。
	 */
	private boolean text(String value) {
		return value != null && !value.isBlank();
	}
}
