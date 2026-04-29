package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.service.WmsInsideInventoryPlanService;
import com.kitex.mom.wms.vo.WmsInsideInventoryPlanExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param wmsInsideInventoryPlan 仓库盘点计划
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
    public R<Page<WmsInsideInventoryPlanEntity>> getWmsInsideInventoryPlanPage(@ParameterObject SearchPage page, @ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        LambdaQueryWrapper<WmsInsideInventoryPlanEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getWarehouseCode()), WmsInsideInventoryPlanEntity::getWarehouseCode, wmsInsideInventoryPlan.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsInsideInventoryPlan.getPlanNo()), WmsInsideInventoryPlanEntity::getPlanNo, wmsInsideInventoryPlan.getPlanNo());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getStatus()), WmsInsideInventoryPlanEntity::getStatus, wmsInsideInventoryPlan.getStatus());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getHandleState()), WmsInsideInventoryPlanEntity::getHandleState, wmsInsideInventoryPlan.getHandleState());
        wrapper.orderByAsc(WmsInsideInventoryPlanEntity::getStatus).orderByAsc(WmsInsideInventoryPlanEntity::getHandleState).orderByDesc(WmsInsideInventoryPlanEntity::getCreateTime);
        return R.ok(wmsInsideInventoryPlanService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
        LambdaQueryWrapper<WmsInsideInventoryPlanEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getWarehouseCode()), WmsInsideInventoryPlanEntity::getWarehouseCode, wmsInsideInventoryPlan.getWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsInsideInventoryPlan.getPlanNo()), WmsInsideInventoryPlanEntity::getPlanNo, wmsInsideInventoryPlan.getPlanNo());
        wrapper.eq(StrUtil.isNotBlank(wmsInsideInventoryPlan.getStatus()), WmsInsideInventoryPlanEntity::getStatus, wmsInsideInventoryPlan.getStatus());
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
    public R<Page<WmsInsideInventoryPlanEntity>> getWmsInsideInventoryPlanAppPage(@ParameterObject SearchPage page, @ParameterObject WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan) {
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
    public R<WmsInsideInventoryPlanEntity> details(String planNo) {
        return R.ok(wmsInsideInventoryPlanService
                .getOne(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery().eq(WmsInsideInventoryPlanEntity::getPlanNo, planNo)));
    }

    /**
     * 通过id查询仓库盘点计划
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_view')")
    public R<WmsInsideInventoryPlanEntity> getById(@PathVariable("id") Long id) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add')")
    public R<Boolean> save(@RequestBody WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> inventory(@RequestBody WmsInsideInventoryPlanEntity wmsInsideInventoryPlan) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> appInventory(@RequestBody WmsInsideInventoryPlanDetailEntity wmsInsideInventoryPlanDetail) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> appInventorySuccess(@PathVariable("id") Long id) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> detailComplete(@PathVariable("id") Long id) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_edit')")
    public R<Boolean> complete(@PathVariable("id") Long id) {
        return R.ok(wmsInsideInventoryPlanService.complete(id));
    }


    /**
     * 明细完成损益处理
     *
     * @param id 仓库盘点计划明细id
     */
    @Operation(summary = "明细完成损益处理", description = "明细完成损益处理")
    @SysLog("明细完成损益处理")
    @PutMapping("/completeDetails/{id}")
    public R<Boolean> completeDetails(@PathVariable("id") Long id) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
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
//    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_export')" )
    public List<WmsInsideInventoryPlanExcelVO> export(WmsInsideInventoryPlanEntity wmsInsideInventoryPlan, Long[] ids) {
        return wmsInsideInventoryPlanService.export(wmsInsideInventoryPlan, ids);
    }
}