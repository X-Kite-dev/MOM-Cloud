package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesManufacturingPieceCohortDTO.Query;
import com.kitex.mom.qms.dto.QmsInspectionFirstPassCohortDTO.*;
import com.kitex.mom.qms.service.impl.QmsInspectionFirstPassCohortService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 实际工厂完整制造登记总体的原首轮统计与逐件证据下钻入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/first-pass/cohort")
@PreAuthorize("@pms.hasPermission('procedureTask:show')")
public class QmsInspectionFirstPassCohortController {
	private final QmsInspectionFirstPassCohortService service;

	/**
	 * 读取当前实际工厂精确工单的原已下达工程节点与产品单位；无权限、不唯一或冻结来源损坏时拒绝。
	 */
	@GetMapping("/context")
	public R<Context> context(@RequestParam String orderCode) {
		return R.ok(service.context(orderCode));
	}

	/**
	 * 从MES完整登记总体核对QMS逐件原FULL样本，保持PASS、FAIL、PENDING、UNKNOWN和复检排除互斥。
	 *
	 * @param query 精确工单、稳定检验节点与UTC半开登记周期
	 * @return 登记件数守恒的统计及证据摘要，零分母比率为null
	 */
	@GetMapping
	public R<View> inspect(Query query) {
		return R.ok(service.inspect(query));
	}

	/**
	 * 重新取得MES完整总体并比对集合指纹，只下钻其中成员的原FULL样本；集合变化、身份越界或证据超限时拒绝，不截断来源。
	 */
	@GetMapping("/piece")
	public R<Detail> detail(
			Query query, @RequestParam String collectionHash, @RequestParam String identifier) {
		return R.ok(service.detail(query, collectionHash, identifier));
	}
}
