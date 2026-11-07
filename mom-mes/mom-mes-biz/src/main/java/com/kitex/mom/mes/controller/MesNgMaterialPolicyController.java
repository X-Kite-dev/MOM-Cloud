package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesNgMaterialPolicyDTO;
import com.kitex.mom.mes.service.impl.MesNgMaterialPolicyRestorationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 登记不良权限不包含恢复权限；原号只读核账仍核实际组织、本人及完整原文。
 */
@RestController
@RequestMapping("/mesNgMaterialPolicy")
@RequiredArgsConstructor
public class MesNgMaterialPolicyController {
	private final MesNgMaterialPolicyRestorationService restorations;

	/**
	 * 以恢复权限提交完整原命令；实际组织、本人和永久幂等由业务服务复核，失败不得视为已恢复。
	 */
	@PostMapping("/restore")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
	public R<MesNgMaterialPolicyDTO.Receipt> restore(@RequestBody String raw) {
		return R.ok(restorations.restore(raw));
	}

	/**
	 * 只读核对原号及另号已恢复状态；结果仍绑定实际本人、组织和原文，不触发耗料或恢复。
	 */
	@PostMapping("/restore/resolve")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
	public R<MesNgMaterialPolicyDTO.Resolution> resolve(@RequestBody String raw) {
		return R.ok(restorations.resolve(raw));
	}

	/**
	 * 只读查询本号永久回执；明确ABSENT与已提交分开返回，权限不替代原文校验。
	 */
	@PostMapping("/restore/receipt")
	@PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
	public R<MesNgMaterialPolicyDTO.Receipt> receipt(@RequestBody String raw) {
		return R.ok(restorations.receipt(raw));
	}
}
