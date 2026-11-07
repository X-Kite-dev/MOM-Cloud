package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInventoryGainPreparationCommand;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.service.WmsWarehouseBusinessGuard;
import com.kitex.mom.wms.service.WmsWarehousePlacementGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.*;

@Service
@RequiredArgsConstructor
public class WmsInventoryGainPreparationService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryWarehousePermission permissions;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsInventoryVarianceGate variance;
	private final WmsWarehouseBusinessGuard guard;
	private final WmsInventoryGainAttributes attributes;

	@Transactional(rollbackFor = Exception.class)
	public Prepared prepare(WmsInventoryGainPreparationCommand command, boolean mobile) {
		require(command != null && command.getDetail() != null, "请先选择盘盈明细并填写实际属性");
		var row = access.detail(command.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		permissions.require("hand", plan.getWarehouseCode());
		var user = SecurityUtils.getUser();
		String factory = factory();
		require(user != null && Objects.equals(user.getTenantId(), TenantContextHolder.getTenantId()) &&
				StrUtil.isNotBlank(user.getUsername()), "无法核验当前操作员与租户");
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode()) && StrUtil.isNotBlank(row.getMatCode())
						&& StrUtil.isNotBlank(row.getSiteCode()) && "1".equals(plan.getStatus()) &&
						"0".equals(plan.getHandleState())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus()),
				"原盘点尚未完成实盘或已处理，请刷新核对");
		var book = number(row.getCurrentNumber(), "账面数量");
		var counted = number(row.getInventoryNum(), "实盘数量");
		require(counted.compareTo(book) > 0 &&
						book.compareTo(number(command.getExpectedCurrentNumber(), "页面账面数量")) == 0
						&& counted.compareTo(number(command.getExpectedInventoryNum(), "页面实盘数量")) == 0,
				"仅能为数量未变化的原盘盈明细准备属性");
		approvals.requireNoActive(row.getId());
		if (mobile) {
			guard.checkMobile(plan.getWarehouseCode());
		}
		variance.freeze(row);
		require(Boolean.TRUE.equals(guard.check(plan.getWarehouseCode(), "other_inventory", true)),
				"仓库未启用盘盈入库业务");
		guard.checkPlacements(plan.getWarehouseCode(),
				List.of(new WmsWarehousePlacementGuard.Placement(row.getSiteCode(),
						command.getDetail().getContainerCode())));
		var detail = attributes.prepare(row, command.getDetail());
		return new Prepared(1, TenantContextHolder.getTenantId().toString(), factory, user.getUsername(),
				plan.getId().toString(), row.getId().toString(),
				row.getWarehouseCode(), row.getMatCode(), row.getSiteCode(), row.getMatUnit(), row.getCurrentNumber(),
				row.getInventoryNum(), Attributes.from(detail));
	}

	public record Prepared(int contractVersion, String tenantId, String factoryCode, String operatorCode, String planId,
	                       String planDetailId, String warehouseCode, String matCode, String siteCode, String matUnit,
	                       Double expectedCurrentNumber, Double expectedInventoryNum, Attributes detail) {
	}

	public record Attributes(String matBatch, String supplierCode, String supplierName, String containerCode,
	                         String matBarcode,
	                         String matProduceDate, Integer matValidPeriod, String matValidEndDate,
	                         String stockOwnerType, String stockOwnerCode,
	                         String sourceBusinessNo, String matUnit) {
		static Attributes from(WmsInBillDetailEntity d) {
			return new Attributes(d.getMatBatch(), d.getSupplierCode(), d.getSupplierName(), d.getContainerCode(),
					d.getMatBarcode(),
					d.getMatProduceDate() == null ? null : d.getMatProduceDate().toString(), d.getMatValidPeriod(),
					d.getMatValidEndDate() == null ? null : d.getMatValidEndDate().toString(),
					d.getStockOwnerType(), d.getStockOwnerCode(), d.getSourceBusinessNo(), d.getMatUnit());
		}
	}
}
