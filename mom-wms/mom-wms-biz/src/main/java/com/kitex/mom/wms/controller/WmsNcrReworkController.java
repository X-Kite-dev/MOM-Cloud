package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import com.kitex.mom.wms.service.rework.WmsNcrReworkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * QMS实名代理的原位返工协议入口；内部标记只限定调用来源，真实工厂与仓级权限由服务逐次证明。
 * Web仅能读永久摘要，库存终态不能由浏览器直接伪造。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsLiveStock/quality/ncr-rework")
public class WmsNcrReworkController {
	private final WmsNcrReworkService service;

	/**
	 * 读取真实片段并冻结实际基本单位，不写库存。
	 */
	@Inner
	@PostMapping("/inspect")
	public R<Source> inspect(@RequestBody Key key) {
		return R.ok(service.inspect(key));
	}

	/**
	 * 按永久原号查回执，故障不返回NOT_FOUND。
	 */
	@Inner
	@PostMapping("/lookup")
	public R<Lookup> lookup(@RequestBody Command command) {
		return R.ok(service.lookup(command));
	}

	/**
	 * 原独立复核人按完整原文办理原位整量终态。
	 */
	@Inner
	@PostMapping("/apply")
	public R<Receipt> apply(@RequestBody Command command) {
		return R.ok(service.apply(command));
	}

	/**
	 * 与apply同锁写永久取消，已生效返回原事实。
	 */
	@Inner
	@PostMapping("/cancel")
	public R<Receipt> cancel(@RequestBody Command command) {
		return R.ok(service.cancel(command));
	}

	/**
	 * 质量页/库存追溯只读摘要，服务核实际原仓show，原parent状态不改写。
	 */
	@GetMapping("/results")
	public R<List<WmsNcrReworkService.ResultView>> results(
			@RequestParam(required = false) String parentOperationId,
			@RequestParam(required = false) String stockId) {
		return R.ok(service.results(parentOperationId, stockId));
	}
}
