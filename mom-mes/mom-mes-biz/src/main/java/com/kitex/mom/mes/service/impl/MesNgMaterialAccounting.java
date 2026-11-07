package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.service.MesProductionMaterialOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 新旧协议显式分域；原NONE不被补耗或由双账掩盖。
 */
@Component
@RequiredArgsConstructor
public class MesNgMaterialAccounting {
	private final MesProductionMaterialOperationService old;
	private final MesNgMaterialPolicyService policy;

	/**
	 * 仅精确POLICY进入新协议；其余输入沿既有NONE或FEED校验，不补旧来源耗料。
	 */
	public String mode(String requested) {
		return "POLICY".equals(requested) ? "POLICY" : old.mode(requested);
	}

	/**
	 * 同时核对新旧原账以返回原NG真实耗料模式；发现双账或任一证据不可核验即拒绝，不把已恢复POLICY标成NONE。
	 */
	public String originalMode(MesTaskReportUnqualifiedEntity source) {
		var legacy = old.original(source);
		var current = policy.original(source);
		MesPhysicalTransformCodec.require(legacy == null || current == null, "原NG出现新旧双耗用账，不能猜耗料方式");
		return current != null ? "POLICY" : legacy == null ? "NONE" : legacy.getAccountingMode();
	}
}
