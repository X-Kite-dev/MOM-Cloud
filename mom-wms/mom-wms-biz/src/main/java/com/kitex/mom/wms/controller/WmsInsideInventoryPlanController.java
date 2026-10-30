package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.dto.WmsInventoryCancelCommand;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentCommand;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalReview;
import com.kitex.mom.wms.dto.WmsInventoryAdjustmentApprovalSubmit;
import com.kitex.mom.wms.dto.WmsInventoryRecountCommand;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInventoryRecountRecordEntity;
import com.kitex.mom.wms.entity.WmsInventoryAdjustmentApprovalEntity;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentService;
import com.kitex.mom.wms.service.impl.WmsInventoryAdjustmentApprovalService;
import com.kitex.mom.wms.service.impl.WmsInventoryCancellationService;
import com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess;
import com.kitex.mom.wms.service.impl.WmsInventoryRecountService;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import com.kitex.mom.wms.vo.WmsInsideInventoryPlanExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.factory;
import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 仓库盘点计划
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan")
@Tag(description = "wmsInsideInventoryPlan", name = "仓库盘点计划管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsInsideInventoryPlanController {

    private final WmsInsideInventoryPlanService wmsInsideInventoryPlanService;
    private final WmsInventoryAdjustmentService inventoryAdjustmentService;
    private final WmsInventoryAdjustmentApprovalService adjustmentApprovalService;
    private final WmsInventoryCancellationService inventoryCancellationService;
    private final WmsInventoryRecountService inventoryRecountService;
    private final WmsInventoryPlanAccess access;
    private final WmsInventoryWarehousePermission warehousePermission;

    @Operation(summary = "作废未调账盘点", description = "保留原计划和明细，记录作废原因、操作人与时间")
    @SysLog("作废未调账盘点")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_cancel_*', 'wms_wmsInsideInventoryPlan_cancel')")
    @PostMapping("/cancel")
    public R<Boolean> cancel(@RequestBody WmsInventoryCancelCommand command) {
        require(command != null && command.getId() != null, "请选择盘点计划");
        requirePlanPermission(command.getId(), "cancel");
        return R.ok(inventoryCancellationService.cancel(command));
    }

    @Operation(summary = "复盘未调账明细", description = "记录原数、新数、原因和操作人；请求号重复提交返回原结果")
    @SysLog("复盘未调账明细")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    @PostMapping("/recount")
    public R<WmsInventoryRecountRecordEntity> recount(@RequestBody WmsInventoryRecountCommand command) {
        return R.ok(inventoryRecountService.recount(command));
    }

    @Operation(summary = "查询盘点复盘履历")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    @GetMapping("/{id}/recounts")
    public R<List<WmsInventoryRecountRecordEntity>> recounts(@PathVariable Long id) {
        return R.ok(inventoryRecountService.history(id));
    }

    @Operation(summary = "统一办理盘点损益", description = "库存、流水及处理状态同事务；同一明细重复请求只返回既有结果")
    @SysLog("统一办理盘点损益")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    @PostMapping("/adjust")
    public R<Boolean> adjust(@RequestBody WmsInventoryAdjustmentCommand command) {
        require(command != null, "盘点损益命令不能为空");
        requireDetailPermission(command.getPlanDetailId(), "hand");
        return R.ok(inventoryAdjustmentService.adjust(command));
    }

    @Operation(summary = "提交盘点差异审批")
    @SysLog("提交盘点差异审批")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    @PostMapping("/adjustment-approvals")
    public R<WmsInventoryAdjustmentApprovalEntity> submitAdjustmentApproval(
            @RequestBody WmsInventoryAdjustmentApprovalSubmit command) {
        require(command != null, "盘点差异审批申请不能为空");
        requireDetailPermission(command.getPlanDetailId(), "hand");
        return R.ok(adjustmentApprovalService.submit(command));
    }

    @Operation(summary = "审核盘点差异")
    @SysLog("审核盘点差异")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    @PostMapping("/adjustment-approvals/review")
    public R<WmsInventoryAdjustmentApprovalEntity> reviewAdjustmentApproval(
            @RequestBody WmsInventoryAdjustmentApprovalReview command) {
        require(command != null && command.getApprovalId() != null, "请选择审批申请");
        WmsInventoryAdjustmentApprovalEntity approval = adjustmentApprovalService.get(command.getApprovalId());
        requireDetailPermission(approval.getPlanDetailId(), "hand");
        return R.ok(adjustmentApprovalService.review(command));
    }

    @Operation(summary = "查询盘点差异审批履历")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    @GetMapping("/{id}/adjustment-approvals")
    public R<List<WmsInventoryAdjustmentApprovalEntity>> adjustmentApprovals(@PathVariable Long id) {
        requirePlanPermission(id, "view");
        return R.ok(adjustmentApprovalService.history(id));
    }

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    public R<Page<WmsInsideInventoryPlanEntity>> getWmsInsideInventoryPlanPage(@ParameterObject SearchPage page,
                                                                               @ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        warehousePermission.require("view", wmsInsideInventoryPlan.getWarehouseCode());
        LambdaQueryWrapper<WmsInsideInventoryPlanEntity> wrapper = Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
                .eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getWarehouseCode()),
                WmsInsideInventoryPlanEntity::getWarehouseCode, wmsInsideInventoryPlan.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsInsideInventoryPlan.getPlanNo()), WmsInsideInventoryPlanEntity::getPlanNo,
                wmsInsideInventoryPlan.getPlanNo());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getStatus()), WmsInsideInventoryPlanEntity::getStatus,
                wmsInsideInventoryPlan.getStatus());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getHandleState()),
                WmsInsideInventoryPlanEntity::getHandleState, wmsInsideInventoryPlan.getHandleState());
        wrapper.orderByAsc(WmsInsideInventoryPlanEntity::getStatus)
                .orderByAsc(WmsInsideInventoryPlanEntity::getHandleState)
                .orderByDesc(WmsInsideInventoryPlanEntity::getCreateTime);
        return R.ok(wmsInsideInventoryPlanService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/getCount")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    public R<Map<String, Long>> getCount(@ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        warehousePermission.require("view", wmsInsideInventoryPlan.getWarehouseCode());
        LambdaQueryWrapper<WmsInsideInventoryPlanEntity> wrapper = Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
                .eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getWarehouseCode()),
                WmsInsideInventoryPlanEntity::getWarehouseCode, wmsInsideInventoryPlan.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsInsideInventoryPlan.getPlanNo()), WmsInsideInventoryPlanEntity::getPlanNo,
                wmsInsideInventoryPlan.getPlanNo());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getStatus()), WmsInsideInventoryPlanEntity::getStatus,
                wmsInsideInventoryPlan.getStatus());
        return R.ok(wmsInsideInventoryPlanService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsInsideInventoryPlanEntity::getHandleState, Collectors.counting())));
    }

    /**
     * app-分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
    @Operation(summary = "app-分页查询", description = "app-分页查询")
    @GetMapping("/app/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    public R<Page<WmsInsideInventoryPlanEntity>> getWmsInsideInventoryPlanAppPage(@ParameterObject SearchPage page,
                                                                                  @ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        warehousePermission.require("view", wmsInsideInventoryPlan.getWarehouseCode());
        return R.ok(wmsInsideInventoryPlanService.appPage(page.toMpPage(), wmsInsideInventoryPlan));
    }

    /**
     * 根据盘点编码获取详情
     *
     * @param planNo 盘点编码
     * @return R
     */
    @Operation(summary = "根据盘点编码获取详情", description = "根据盘点编码获取详情")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    public R<WmsInsideInventoryPlanEntity> details(String planNo) {
        var plan = wmsInsideInventoryPlanService
                .getOne(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
                        .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
                        .eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(WmsInsideInventoryPlanEntity::getPlanNo, planNo));
        if (plan != null) {
            warehousePermission.require("view", plan.getWarehouseCode());
        }
        return R.ok(plan);
    }

    @Operation(summary = "校验盘点编号")
    @GetMapping("/exists")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
    public R<Boolean> exists(@RequestParam String planNo, @RequestParam String warehouseCode) {
        require(StrUtil.isNotBlank(planNo) && StrUtil.isNotBlank(warehouseCode), "请填写盘点编号和仓库");
        warehousePermission.require("add", warehouseCode);
        return R.ok(wmsInsideInventoryPlanService.count(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory())
                .eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsInsideInventoryPlanEntity::getPlanNo, planNo.trim())) > 0);
    }

    @Operation(summary = "查询盘点冻结可用性")
    @GetMapping("/freeze-availability")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
    public R<Boolean> countFreezeAvailability() {
        return R.ok(wmsInsideInventoryPlanService.countFreezeAvailable());
    }

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view_*', 'wms_wmsInsideInventoryPlan_view')")
    public R<WmsInsideInventoryPlanEntity> getById(@PathVariable("id") Long id) {
        requirePlanPermission(id, "view");
        return R.ok(wmsInsideInventoryPlanService.getDetails(id));
    }

    /**
     * 新增仓库盘点计划
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Operation(summary = "新增仓库盘点计划", description = "新增仓库盘点计划")
    @SysLog("新增仓库盘点计划")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
    public R<Boolean> save(@RequestBody WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
        require(wmsInsideInventoryPlan != null, "盘点计划不能为空");
        warehousePermission.require("add", wmsInsideInventoryPlan.getWarehouseCode());
        return R.ok(wmsInsideInventoryPlanService.saveDeep(wmsInsideInventoryPlan));
    }

    /**
     * 盘点录入
     *
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return R
     */
    @Operation(summary = "盘点录入", description = "盘点录入")
    @SysLog("盘点录入")
    @PutMapping("/inventory")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_entry_*', 'wms_wmsInsideInventoryPlan_entry', 'wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> inventory(@RequestBody WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
        require(wmsInsideInventoryPlan != null, "盘点录入不能为空");
        requirePlanPermission(wmsInsideInventoryPlan.getId(), "entry");
        return R.ok(wmsInsideInventoryPlanService.inventory(wmsInsideInventoryPlan));
    }

    /**
     * app-盘点录入
     *
     * @param wmsInsideInventoryPlanDetail 仓库盘点计划详情
     * @return R
     */
    @Operation(summary = "app-盘点录入", description = "app-盘点录入")
    @SysLog("app-盘点录入")
    @PutMapping("/app/inventory")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_entry_*', 'wms_wmsInsideInventoryPlan_entry', 'wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> appInventory(@RequestBody WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail) {
        require(wmsInsideInventoryPlanDetail != null, "盘点录入不能为空");
        requireDetailPermission(wmsInsideInventoryPlanDetail.getId(), "entry");
        return R.ok(wmsInsideInventoryPlanService.appInventory(wmsInsideInventoryPlanDetail));
    }

    /**
     * app-盘点录入完成
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    @Operation(summary = "app-盘点录入完成", description = "app-盘点录入完成")
    @SysLog("app-盘点录入完成")
    @PutMapping("/app/inventory/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_entry_*', 'wms_wmsInsideInventoryPlan_entry', 'wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> appInventorySuccess(@PathVariable("id") Long id) {
        requirePlanPermission(id, "entry");
        return R.ok(wmsInsideInventoryPlanService.appInventorySuccess(id));
    }

    /**
     * 损益处理
     *
     * @param id 仓库盘点计划详情id
     * @return R
     */
    @Operation(summary = "损益处理", description = "损益处理")
    @SysLog("损益处理")
    @PutMapping("/detailComplete/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> detailComplete(@PathVariable("id") Long id) {
        requireDetailPermission(id, "hand");
        return R.ok(wmsInsideInventoryPlanService.detailComplete(id));
    }

    /**
     * 完成损益处理
     *
     * @param id 仓库盘点计划id
     * @return R
     */
    @Operation(summary = "完成损益处理", description = "完成损益处理")
    @SysLog("完成损益处理")
    @PutMapping("/complete/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> complete(@PathVariable("id") Long id) {
        requirePlanPermission(id, "hand");
        return R.ok(wmsInsideInventoryPlanService.complete(id));
    }

    /**
     * 明细完成损益处理
     *
     * @param id 仓库盘点计划明细id
     */
    @Operation(summary = "明细完成损益处理", description = "明细完成损益处理")
    @SysLog("明细完成损益处理")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_hand_*', 'wms_wmsInsideInventoryPlan_hand', 'wms_wmsInsideInventoryPlan_edit')")
    @PutMapping("/completeDetails/{id}")
    public R<Boolean> completeDetails(@PathVariable("id") Long id) {
        requireDetailPermission(id, "hand");
        return R.ok(wmsInsideInventoryPlanService.completeDetails(id));
    }

    /**
     * 通过id删除仓库盘点计划
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除仓库盘点计划", description = "通过id删除仓库盘点计划")
    @SysLog("通过id删除仓库盘点计划")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_del_*', 'wms_wmsInsideInventoryPlan_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        require(ids != null && ids.length > 0, "请选择盘点计划");
        for (Long id : ids) {
            requirePlanPermission(id, "del");
        }
        return R.ok(wmsInsideInventoryPlanService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param wmsInsideInventoryPlan 查询条件
     * @param ids                    导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_export_*', 'wms_wmsInsideInventoryPlan_export')")
    public List<WmsInsideInventoryPlanExcelVO> export(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan, Long[] ids) {
        warehousePermission.require("export", wmsInsideInventoryPlan.getWarehouseCode());
        return wmsInsideInventoryPlanService.export(wmsInsideInventoryPlan, ids);
    }

    private void requirePlanPermission(Long id, String action) {
        var plan = access.plan(id, false);
        warehousePermission.require(action, plan.getWarehouseCode());
    }

    private void requireDetailPermission(Long detailId, String action) {
        var detail = access.detail(detailId);
        requirePlanPermission(detail.getPlanId(), action);
    }
}
