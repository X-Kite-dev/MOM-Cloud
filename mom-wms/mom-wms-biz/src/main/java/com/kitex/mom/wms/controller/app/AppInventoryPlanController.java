package com.kitex.mom.wms.controller.app;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalReview;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalSubmit;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.dto.app.WmsMobileInventoryContext;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentApprovalService;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentService;
import com.kitex.mom.wms.service.impl.WmsInventoryMobileAccess;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 手机端活跃盘点作业的窄端口，沿原粗授权和实际计划逐仓授权。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/app/inventoryPlan")
public class AppInventoryPlanController {

	private static final String VIEW = "@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')";
	private static final String ADD = "@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')";
	private static final String ENTRY = "@pms.hasPermission('wms_wmsInsideInventoryPlan_entry_*', 'wms_wmsInsideInventoryPlan_entry', 'wms_wmsInsideInventoryPlan_edit')";
	private static final String HAND = "@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')";

	private final WmsInsideInventoryPlanService plans;
	private final WmsInventoryAdjustmentService adjustments;
	private final WmsInventoryAdjustmentApprovalService approvals;
	private final WmsInventoryMobileAccess mobile;
	private final WmsInventoryWarehousePermission permissions;

	@GetMapping("/context")
	@PreAuthorize(ADD)
	public R<WmsMobileInventoryContext> context(@RequestParam String warehouseCode) {
		return R.ok(mobile.context(warehouseCode));
	}

	@GetMapping("/page")
	@PreAuthorize(VIEW)
	public R<Page<WmsInsideInventoryPlanEntity>> page(@ParameterObject SearchPage page,
	                                                  @ParameterObject WmsInsideInventoryPlanQueryDTO query) {
		permissions.require("view", query.getWarehouseCode());
		return R.ok(plans.appPage(page.toMpPage(), query));
	}

	@GetMapping("/details")
	@PreAuthorize(VIEW)
	public R<WmsInsideInventoryPlanEntity> byNumber(@RequestParam String planNo) {
		require(StrUtil.isNotBlank(planNo) && planNo.length() <= 32, "请输入盘点编号");
		var plan = plans.getOne(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
				.eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId())
				.eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
				.eq(WmsInsideInventoryPlanEntity::getPlanNo, planNo.trim()));
		if (plan != null) {
			permissions.require("view", plan.getWarehouseCode());
		}
		return R.ok(plan);
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public R<WmsInsideInventoryPlanEntity> detail(@PathVariable Long id) {
		mobile.plan(id, "view");
		return R.ok(plans.getDetails(id));
	}

	@PostMapping
	@PreAuthorize(ADD)
	@SysLog("手机端新建盘点计划")
	public R<Boolean> create(@RequestBody WmsInsideInventoryPlanEntity command) {
		require(command != null, "盘点计划不能为空");
		permissions.require("add", command.getWarehouseCode());
		return R.ok(plans.saveDeepMobile(command));
	}

	@PutMapping("/inventory")
	@PreAuthorize(ENTRY)
	@SysLog("手机端盘点录入")
	public R<Boolean> enter(@RequestBody WmsInsideInventoryPlanDetailEntity command) {
		require(command != null, "盘点录入不能为空");
		mobile.detail(command.getId(), "entry");
		return R.ok(plans.appInventory(command));
	}

	@PutMapping("/inventory/{id}")
	@PreAuthorize(ENTRY)
	@SysLog("手机端完成盘点录入")
	public R<Boolean> finishEntry(@PathVariable Long id) {
		mobile.plan(id, "entry");
		return R.ok(plans.appInventorySuccess(id));
	}

	@PostMapping("/adjust")
	@PreAuthorize(HAND)
	@SysLog("手机端统一盘点调账")
	public R<Boolean> adjust(@RequestBody WmsInventoryAdjustmentCommand command) {
		require(command != null, "请选择盘点差异明细");
		mobile.detail(command.getPlanDetailId(), "hand");
		return R.ok(adjustments.adjustMobile(command));
	}

	@GetMapping("/{id}/adjustment-approvals")
	@PreAuthorize(VIEW)
	public R<List<WmsInventoryAdjustmentApprovalEntity>> history(@PathVariable Long id) {
		mobile.plan(id, "view");
		return R.ok(approvals.history(id));
	}

	@PostMapping("/adjustment-approvals")
	@PreAuthorize(HAND)
	@SysLog("手机端提交盘点差异审批")
	public R<WmsInventoryAdjustmentApprovalEntity> submitApproval(@RequestBody WmsInventoryAdjustmentApprovalSubmit command) {
		require(command != null, "请选择盘点差异明细");
		mobile.detail(command.getPlanDetailId(), "hand");
		return R.ok(approvals.submitMobile(command));
	}

	@PostMapping("/adjustment-approvals/review")
	@PreAuthorize(HAND)
	@SysLog("手机端审核盘点差异")
	public R<WmsInventoryAdjustmentApprovalEntity> reviewApproval(@RequestBody WmsInventoryAdjustmentApprovalReview command) {
		require(command != null && command.getApprovalId() != null, "请选择审批申请");
		var approval = approvals.get(command.getApprovalId());
		mobile.detail(approval.getPlanDetailId(), "hand");
		return R.ok(approvals.reviewMobile(command));
	}

	@PutMapping("/complete/{id}")
	@PreAuthorize(HAND)
	@SysLog("手机端完成盘点损益处理")
	public R<Boolean> complete(@PathVariable Long id) {
		mobile.plan(id, "hand");
		return R.ok(plans.completeMobile(id));
	}
}
