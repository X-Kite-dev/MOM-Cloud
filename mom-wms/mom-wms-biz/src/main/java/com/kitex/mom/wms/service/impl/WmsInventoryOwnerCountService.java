package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRecordMapper;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 原范围的准备与原号查账；查账不依赖当前库存、手机开关或未完成状态。
 */
@Service
@RequiredArgsConstructor
public class WmsInventoryOwnerCountService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final PermissionService pms;
	private final WmsInventoryStockCheckService stocks;
	private final WmsInventoryOwnerCountEvidence evidence;
	private final WmsInventoryRecountRecordMapper records;
	private final WmsWarehouseBusinessGuard warehouseGuard;
	private final WmsInventoryAdjustmentApprovalService approvals;

	@Transactional(rollbackFor = Exception.class)
	public Context context(Long detailId) {
		return context(access.detail(detailId));
	}

	private Context context(WmsInsideInventoryPlanDetailEntity row) {
		var plan = access.plan(row.getPlanId(), false);
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()), "盘点明细与原计划仓库不一致");
		permissions.require("view", plan.getWarehouseCode());
		var user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername())
				&& Objects.equals(TenantContextHolder.getTenantId(), user.getTenantId()), "登录身份与当前租户不一致");
		return new Context(1, TenantContextHolder.getTenantId().toString(), factory(), user.getUsername(),
				row.getWarehouseCode(), row.getId().toString());
	}

	@Transactional(rollbackFor = Exception.class)
	public Prepare prepare(Long detailId, boolean mobile) {
		var row = access.detail(detailId);
		var context = context(row);
		var plan = access.plan(row.getPlanId(), false);
		var check = stocks.inspect(row);
		boolean enabled = "1".equals(plan.getStatus()) && "0".equals(plan.getHandleState())
				&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus());
		String message = enabled ? "" : "请先完成整张计划实盘录入，且明细须尚未调账";
		String base = "wms_wmsInsideInventoryPlan_hand";
		if (!pms.hasPermission(base, base + "_" + row.getWarehouseCode(), "wms_wmsInsideInventoryPlan_edit")) {
			enabled = false;
			message = "当前账号没有该仓库复盘权限";
		}
		try {
			approvals.requireNoActive(row.getId());
		} catch (RuntimeException ex) {
			enabled = false;
			message = "当前明细审批处理中或状态未核定，请先处理原申请";
		}
		if (mobile) {
			try {
				if (!warehouseGuard.mobileEnabled(row.getWarehouseCode())) {
					enabled = false;
					message = "该仓库未开启手机端作业";
				}
			} catch (RuntimeException ex) {
				enabled = false;
				message = "手机端配置暂不可用，请稍后重新读取";
			}
		}
		if (check.balances().isEmpty() || !Objects.equals(check.bookQuantity(), check.liveQuantity())
				|| List.of("UNIT_MISMATCH", "INVALID_OWNERSHIP", "STOCK_CHANGED").contains(check.status())) {
			enabled = false;
			message = "当前库存依据不适合分货权复盘，请先核对原账面和库存";
		}
		return new Prepare(context, evidence.basisVersion(row, check.basis()),
				row.getLastRecountId() == null ? null : row.getLastRecountId().toString(),
				number(row.getInventoryNum(), "原实盘数量").stripTrailingZeros().toPlainString(),
				row.getMatCode(), row.getMatName(), row.getSiteCode(), row.getMatBatch(), row.getMatUnit(),
				check.bookQuantity(), evidence.groups(row, check.balances()), enabled, message);
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryRecountRecordEntity lookup(Long detailId, String requestId) {
		var row = access.detail(detailId);
		context(row);
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "复核请求号无效");
		return records.selectOne(Wrappers.<WmsInventoryRecountRecordEntity>lambdaQuery()
				.eq(WmsInventoryRecountRecordEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryRecountRecordEntity::getFactoryCode, factory())
				.eq(WmsInventoryRecountRecordEntity::getPlanDetailId, row.getId())
				.eq(WmsInventoryRecountRecordEntity::getRequestId, requestId));
	}

	public record Context(int contractVersion, String tenantId, String factoryCode, String operatorCode,
	                      String warehouseCode, String planDetailId) {
	}

	public record Prepare(Context context, String basisVersion, String expectedRecountId, String expectedInventoryNum,
	                      String matCode, String matName, String siteCode, String matBatch, String matUnit,
	                      String bookQuantity,
	                      List<WmsInventoryOwnerCountEvidence.Group> groups, boolean writeAllowed, String message) {
	}
}
