package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesManufacturingPieceDTO.*;
import com.kitex.mom.mes.service.impl.MesManufacturingPieceService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 操作者声明新实物并领取服务端制造身份、核对原请求回执的生产入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/manufacturing-piece")
public class MesManufacturingPieceController {
	private final MesManufacturingPieceService service;

	/**
	 * 核对原请求在实际工厂及当前操作者下的制造声明回执；未写入返回ABSENT，已写入返回原APPLIED回执。
	 *
	 * @param requestId 原制造声明请求号
	 * @param arrangeId 原派工ID字符串
	 * @return 原回执解析状态，不创建新身份
	 */
	@GetMapping("/receipt")
	@PreAuthorize(
			"@pms.hasPermission('mesProduceTask:start','mesProduceTask:show','mesTaskArrange:show')")
	public R<Resolution> inspect(@RequestParam String requestId, @RequestParam String arrangeId) {
		return R.ok(service.inspect(requestId, arrangeId));
	}

	/**
	 * 在实际人员、工厂与原工单锁内校验声明、初始节点和永久容量，按产品模板铸码并同事务执行原START；历史来源、规则冲突或任一步失败均拒绝并回滚。
	 */
	@PostMapping
	@PreAuthorize("@pms.hasPermission('mesProduceTask:start')")
	public R<Receipt> create(@RequestBody Command command) {
		return R.ok(service.create(command));
	}
}
