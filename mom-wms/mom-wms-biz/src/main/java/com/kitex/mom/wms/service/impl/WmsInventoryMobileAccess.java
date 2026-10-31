package com.kitex.mom.wms.service.impl;

import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.app.WmsMobileInventoryContext;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 原计划、明细和仓库授权的移动适配，不接收客户端自报通道或授权范围。
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryMobileAccess {

	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final WmsWarehouseBusinessGuard warehouseGuard;

	public WmsInsideInventoryPlanEntity plan(Long id, String action) {
		var plan = access.plan(id, false);
		permissions.require(action, plan.getWarehouseCode());
		return plan;
	}

	public WmsInsideInventoryPlanEntity detail(Long id, String action) {
		var row = access.detail(id);
		var plan = access.plan(row.getPlanId(), false);
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()), "盘点明细与原计划仓库不一致，请先核对");
		permissions.require(action, plan.getWarehouseCode());
		return plan;
	}

	public WmsMobileInventoryContext context(String warehouseCode) {
		var scope = WmsAllocationMobileReceiptService.requireScope();
		require(Objects.equals(SecurityUtils.getUser().getTenantId(), scope.tenantId()), "登录租户与当前上下文不一致");
		require(warehouseCode != null && !warehouseCode.isBlank() && warehouseCode.length() <= 100
				&& warehouseCode.equals(warehouseCode.trim()) && !warehouseCode.matches(".*[?*].*"), "请从实际仓库进入盘点建单");
		permissions.require("add", warehouseCode);
		boolean enabled;
		String message;
		try {
			enabled = warehouseGuard.mobileEnabled(warehouseCode);
			message = enabled ? "" : "该仓库未开启手机端作业";
		} catch (RuntimeException unavailable) {
			// 只降低前检能力；实际写入仍重新核验，既有原编号查账不依赖此探测。
			enabled = false;
			message = "仓库手机端配置暂不可用，请稍后重新读取";
		}
		return new WmsMobileInventoryContext(1, 1, scope.tenantId(), scope.factoryCode(), scope.operatorCode(),
				warehouseCode, enabled, message);
	}
}
