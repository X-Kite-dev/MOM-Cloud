package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInventoryRecountCommand;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRecordMapper;
import com.kitex.mom.wms.mapper.WmsInventoryRecountRequestMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.number;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

@Service
@RequiredArgsConstructor
public class WmsInventoryRecountService {
	private final WmsInventoryPlanAccess access;
	private final WmsInventoryRecountRequestMapper requests;
	private final WmsInsideInventoryPlanDetailMapper details;
	private final WmsInventoryRecountRecordMapper records;
	private final WmsInBillMapper inBills;
	private final WmsOutBillMapper outBills;
	private final PermissionService permissions;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsInventoryOwnerCountEvidence ownerCounts;
	private final WmsInventoryStockCheckService stockChecks;
	private final com.kitex.mom.wms.service.WmsWarehouseBusinessGuard warehouseGuard;

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryRecountRecordEntity recount(WmsInventoryRecountCommand command) {
		return recount(command, false);
	}

	@Transactional(rollbackFor = Exception.class)
	public WmsInventoryRecountRecordEntity recountMobile(WmsInventoryRecountCommand command) {
		return recount(command, true);
	}

	private WmsInventoryRecountRecordEntity recount(WmsInventoryRecountCommand command, boolean mobile) {
		require(command != null && command.getPlanDetailId() != null, "请选择原盘点明细");
		String requestId = StrUtil.trim(command.getRequestId());
		String reason = StrUtil.trim(command.getReason());
		require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "复盘请求号格式不正确");
		require(StrUtil.isNotBlank(reason) && reason.length() <= 500, "复盘原因必填且最多500字");
		BigDecimal expected = number(command.getExpectedInventoryNum(), "页面原实盘数量");
		boolean grouped = command.getOwnerCounts() != null;
		require(grouped || command.getOwnerBasisVersion() == null && command.getOwnerExpectedRecountId() == null,
				"分货权复盘参数不完整");
		BigDecimal counted = grouped ? ownerCounts.proposedTotal(command) : number(command.getRecountNum(), "复盘数量");

		var row = access.detail(command.getPlanDetailId());
		var plan = access.plan(row.getPlanId(), true);
		requirePermission("wms_wmsInsideInventoryPlan_hand", plan.getWarehouseCode(),
				"无该仓库盘点损益权限", true);
		var existing = records.selectOne(Wrappers.<WmsInventoryRecountRecordEntity>lambdaQuery()
				.eq(WmsInventoryRecountRecordEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryRecountRecordEntity::getFactoryCode, factory())
				.eq(WmsInventoryRecountRecordEntity::getRequestId, requestId));
		if (existing != null) {
			require(Objects.equals(existing.getPlanId(), plan.getId())
							&& Objects.equals(existing.getPlanDetailId(), row.getId())
							&& expected.compareTo(number(existing.getPreviousNum(), "原复盘前数量")) == 0
							&& counted.compareTo(number(existing.getRecountNum(), "原复盘数量")) == 0
							&& reason.equals(existing.getReason())
							&& ownerCounts.matchesOriginal(existing.getOwnerCountJson(), command),
					"复盘请求号已用于其他内容，请刷新后核对");
			return existing;
		}

		require(requests.find(row.getTenantId(), row.getFactoryCode(), requestId) == null,
				"该请求已结束或原登记需核对，请先查回原结果");

		require("1".equals(plan.getStatus()) && "0".equals(plan.getHandleState()),
				"仅完成录入、尚未办结的盘点计划允许复盘");
		require(Objects.equals(plan.getWarehouseCode(), row.getWarehouseCode())
						&& "1".equals(row.getStatus()) && "0".equals(row.getPlStatus()),
				"盘点明细范围异常或已经调账，禁止复盘");
		require(expected.compareTo(number(row.getInventoryNum(), "当前实盘数量")) == 0,
				"实盘数量已变化，请刷新后重新核对");
		require(noAdjustmentBill(row.getId()), "该明细已有损益单据，禁止复盘");
		approvals.requireNoActive(row.getId());
		if (mobile) {
			warehouseGuard.checkMobile(plan.getWarehouseCode());
		}
		var capture = grouped ? ownerCounts.capture(row, stockChecks.inspect(row), command) : null;
		MomUser user = SecurityUtils.getUser();
		require(user != null && StrUtil.isNotBlank(user.getUsername()), "无法识别当前复盘人");

		var record = new WmsInventoryRecountRecordEntity();
		record.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
		record.setOwnerCountJson(capture == null ? null : capture.json());
		record.setTenantId(TenantContextHolder.getTenantId());
		record.setFactoryCode(factory());
		record.setPlanId(plan.getId());
		record.setPlanDetailId(row.getId());
		record.setRequestId(requestId);
		record.setPreviousNum(row.getInventoryNum());
		record.setRecountNum(counted.doubleValue());
		record.setReason(reason);
		record.setOperatorCode(user.getUsername());
		record.setOperatedTime(LocalDateTime.now());
		require(records.insert(record) == 1, "复盘履历保存失败");
		row.setInventoryNum(counted.doubleValue());
		row.setLastRecountId(record.getId());
		require(details.updateById(row) == 1, "复盘数量保存失败");
		return record;
	}

	public List<WmsInventoryRecountRecordEntity> history(Long planId) {
		var plan = access.plan(planId, false);
		requirePermission("wms_wmsInsideInventoryPlan_view", plan.getWarehouseCode(),
				"无该仓库盘点查看权限", false);
		return records.selectList(Wrappers.<WmsInventoryRecountRecordEntity>lambdaQuery()
				.eq(WmsInventoryRecountRecordEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInventoryRecountRecordEntity::getFactoryCode, factory())
				.eq(WmsInventoryRecountRecordEntity::getPlanId, plan.getId())
				.orderByDesc(WmsInventoryRecountRecordEntity::getOperatedTime)
				.orderByDesc(WmsInventoryRecountRecordEntity::getId));
	}

	private boolean noAdjustmentBill(Long detailId) {
		Long inCount = inBills.selectCount(Wrappers.<WmsInBillEntity>lambdaQuery()
				.eq(WmsInBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInBillEntity::getFactoryCode, factory())
				.eq(WmsInBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsInBillEntity::getDocumentId, detailId));
		Long outCount = outBills.selectCount(Wrappers.<WmsOutBillEntity>lambdaQuery()
				.eq(WmsOutBillEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsOutBillEntity::getFactoryCode, factory())
				.eq(WmsOutBillEntity::getDocumentTable, WmsInventoryAdjustmentContext.DOCUMENT)
				.eq(WmsOutBillEntity::getDocumentId, detailId));
		return Objects.equals(inCount, 0L) && Objects.equals(outCount, 0L);
	}

	private void requirePermission(String base, String warehouseCode, String message, boolean allowLegacyEdit) {
		require(StrUtil.isNotBlank(warehouseCode), "盘点计划仓库编码为空");
		boolean allowed = permissions.hasPermission(base, base + "_" + warehouseCode);
		if (allowLegacyEdit) {
			allowed = allowed || permissions.hasPermission("wms_wmsInsideInventoryPlan_edit");
		}
		require(allowed, message);
	}
}
