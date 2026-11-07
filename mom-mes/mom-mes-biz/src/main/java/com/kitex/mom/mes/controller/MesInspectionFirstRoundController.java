package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionFirstRoundDTO.*;
import com.kitex.mom.mes.service.impl.MesInspectionFirstRoundService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/**
 * QMS沿原人员身份申请MES单件同稳定节点永久首轮归属的内部入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection-first-round/internal")
public class MesInspectionFirstRoundController {
	private final MesInspectionFirstRoundService service;

	/**
	 * 核对实际操作者、制造出生、原入站和已确认QMS任务，永久占有同件同节点的原采集归属；同号同事实返回原件，同号异事实拒绝。
	 */
	@Inner
	@PostMapping("/claim")
	public R<View> claim(@RequestBody Claim claim) {
		return R.ok(service.claim(claim));
	}
}
