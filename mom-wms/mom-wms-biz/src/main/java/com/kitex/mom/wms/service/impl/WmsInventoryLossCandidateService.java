package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

/**
 * HTTP候选入口先核验原任务，不暴露独立的库存ID查询权限。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryLossCandidateService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsInventoryVarianceGate variance;
	private final WmsWarehouseBusinessGuard guard;
	private final WmsInventoryLossSelectionService selection;

	public record Result(int contractVersion, String tenantId, String factoryCode, String operatorCode, String planId,
	                     String planDetailId, String warehouseCode, String matCode, String siteCode, String matUnit,
	                     Double expectedCurrentNumber,
	                     Double expectedInventoryNum, WmsInventoryLossSelectionService.Page page) {
	}

	@Transactional(rollbackFor = Exception.class)
	public Result candidates(Long detailId, String keyword, String cursor, boolean mobile) {
		var row = access.detail(detailId);
		var plan = access.plan(row.getPlanId(), true);
		permissions.require("hand", plan.getWarehouseCode());
		var user = SecurityUtils.getUser();
		require(user != null && Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()) &&
				StrUtil.isNotBlank(user.getUsername()), "无法核验当前操作员与租户");
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()) && "1".equals(plan.getStatus())
						&& "0".equals(plan.getHandleState())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus())
						&& number(row.getCurrentNumber(), "账面数量").compareTo(number(row.getInventoryNum(), "实盘数量")) > 0,
				"仅能为已完成实盘、尚未调账的盘亏选择实物");
		approvals.requireNoActive(row.getId());
		if (mobile) {
			guard.checkMobile(plan.getWarehouseCode());
		}
		variance.freeze(row);
		require(Boolean.TRUE.equals(guard.check(plan.getWarehouseCode(), "other_inventory", false)),
				"仓库未启用盘亏出库业务");
		return new Result(1, TenantContextHolder.getTenantId().toString(), factory(), user.getUsername(),
				plan.getId().toString(),
				row.getId().toString(), row.getWarehouseCode(), row.getMatCode(), row.getSiteCode(), row.getMatUnit(),
				row.getCurrentNumber(),
				row.getInventoryNum(), selection.candidates(row, keyword, cursor));
	}
}
