package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO.*;
import com.kitex.mom.mes.service.impl.MesManufacturingPieceCohortService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/**
 * QMS沿实际人员与工厂读取原工程节点和完整制造登记总体的内部入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/manufacturing-piece-cohort/internal")
public class MesManufacturingPieceCohortController {
	private final MesManufacturingPieceCohortService service;

	/**
	 * 读取当前实际工厂精确工单的原已下达工程节点与产品单位；无权限、不唯一或冻结来源损坏时拒绝。
	 */
	@Inner
	@GetMapping("/context")
	public R<Context> context(@RequestParam String orderCode) {
		return R.ok(service.context(orderCode));
	}

	/**
	 * 按原人员及实际工厂读取完整制造登记总体，不允许客户端指定或截断成员。
	 *
	 * @param query 精确工单、稳定检验节点及UTC半开登记周期
	 * @return 全部登记成员与原工程、集合指纹
	 */
	@Inner
	@PostMapping("/read")
	public R<View> inspect(@RequestBody Query query) {
		return R.ok(service.inspect(query));
	}
}
