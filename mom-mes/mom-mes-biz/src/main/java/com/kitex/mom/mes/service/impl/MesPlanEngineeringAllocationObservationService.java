package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesInFlightEngineeringImpactMapper;
import com.kitex.mom.plan.dto.PlnEngineeringAllocationDTO;
import com.kitex.mom.plan.feign.RemotePlanEngineeringAllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * MES仅校验原工单与工程后代理PLAN权威观察，不读取PLAN表或以本地空账代替分配事实。
 */
@Service
@RequiredArgsConstructor
public class MesPlanEngineeringAllocationObservationService {

	private final MesActualFactoryAccessService access;
	private final MesInFlightEngineeringImpactMapper mapper;
	private final MesEngineeringPackageService packages;
	private final RemotePlanEngineeringAllocationService plans;

	/**
	 * 沿单次工单SELECT的原工程验证版本对，再核PLAN返回组织/原ID/产品。
	 * 此处不跨HTTP持有本地事务连接：PLAN必须回调MES读取映射，长事务会造成连接池互等。
	 * 原工程解析只读该次已取回的不可变原文；PLAN五表的RR观察由所属域独立负责。
	 */
	public PlnEngineeringAllocationDTO.Observation observe(String taskCode) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(taskCode != null && !taskCode.isBlank() && taskCode.length() <= 100
				&& taskCode.equals(taskCode.trim()), "请选择当前组织的原工单号");
		var task = mapper.task(tenant, factory, taskCode);
		require(task != null && Objects.equals(task.getTenantId(), tenant) && factory.equals(task.getFactoryCode())
						&& !"REWORK".equals(task.getResourceType())
						&& Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE", "FINISH")
						.contains(Objects.toString(task.getStatus(), "")),
				"仅当前工厂已下达普通工单可查询工程分配依据");
		var original = packages.readIssuedOriginal(task);
		var response = plans.observe(taskCode);
		var value = response == null ? null : response.getData();
		require(response != null && response.isOk() && value != null,
				"PLAN实际分配依据暂不可用，不能据此判断无占用");
		require("PLAN_ENGINEERING_ALLOCATION_OBSERVATION_V1".equals(value.schemaVersion()) && !value.executable()
				&& value.versionFence() == null && tenant.toString().equals(value.tenantId())
				&& factory.equals(value.factoryCode()) && taskCode.equals(value.taskCode())
				&& task.getId().toString().equals(value.taskId()) &&
				Objects.equals(task.getMatCode(), value.productCode())
				&& Objects.equals(original.source().getReleaseId().toString(), value.sourceReleaseId())
				&& Objects.equals(original.source().getContentHash(), value.sourceEngineeringHash())
				&& value.observedAt() != null && value.facts() != null && !value.facts().isEmpty()
				&& value.dataGaps() != null && value.mappingFingerprint() != null
				&& value.mappingFingerprint().matches("[0-9a-f]{64}") && value.observationFingerprint() != null
				&& value.observationFingerprint().matches("[0-9a-f]{64}"), "PLAN返回的原工单、工程或只读观察范围不一致");
		return value;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
