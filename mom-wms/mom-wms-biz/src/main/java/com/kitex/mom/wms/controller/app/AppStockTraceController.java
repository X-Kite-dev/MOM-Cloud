package com.kitex.mom.wms.controller.app;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.app.WmsMobileStockTraceDTO.Context;
import com.kitex.mom.wms.dto.app.WmsMobileStockTraceDTO.View;
import com.kitex.mom.wms.service.WmsMobileStockTraceService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机库存详情追溯入口，只读实际身份和正式WMS证据，不提供库存写入或角色授权能力。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/stockTrace")
public class AppStockTraceController {
	private final WmsMobileStockTraceService service;

	/**
	 * @return 当前实名工厂及本人已有追溯查看能力，仓库范围另沿正式服务核验
	 */
	@GetMapping("/context")
	@Operation(summary = "读取移动库存追溯查看能力")
	public R<Context> context() {
		return R.ok(service.context());
	}

	/**
	 * @param stockId 从库存详情选择的实际库存标识
	 * @return 原库存实际授权范围内的流转证据，不替代当前可用量或跨系统投料判断
	 */
	@GetMapping
	@Operation(summary = "读取所选库存的WMS流转证据")
	@PreAuthorize("@pms.hasPermission('wms_stockTrace_view')")
	public R<View> query(@RequestParam Long stockId) {
		return R.ok(service.query(stockId));
	}
}
