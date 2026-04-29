package com.kitex.mom.wms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
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

/**
 * 发货计划表
 *
 * @author kitex
 * @date 2025-01-13 09:19:26
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsPlanBase")
@Tag(description = "wmsDeliverGoodsPlanBase", name = "发货计划表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsPlanBaseController {

    private final WmsDeliverGoodsPlanBaseService wmsDeliverGoodsPlanBaseService;

    /**
     * 发货计划表分页查询
     *
     * @param page                    分页对象
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return
     */

    @Operation(summary = "发货计划表分页查询", description = "分页查询发货计划表数据")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_view')")
    public R<Page<WmsDeliverGoodsPlanBaseEntity>> getWmsDeliverGoodsPlanBasePage(@ParameterObject SearchPage page, @ParameterObject WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.getWmsDeliverGoodsPlanBasePage(page.toMpPage(), wmsDeliverGoodsPlanBase));
    }

    /**
     * 发货计划表分页 - 统计
     */

    @Operation(summary = "发货计划表分页 - 统计", description = "发货计划表分页 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.getCount(wmsDeliverGoodsPlanBase));
    }


    /**
     * 通过orderCode和明细id查询发货计划
     */
    @Inner
    @GetMapping("/getByOrder")
    public R getByOrder(@RequestParam("orderCode") String orderCode, @RequestParam("detailsId") Long detailsId) {
        return R.ok(wmsDeliverGoodsPlanBaseService.list(new LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity>()
                .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCode, orderCode)
                .eq(WmsDeliverGoodsPlanBaseEntity::getOrderCodeDetails, detailsId)));
    }

    /**
     * 通过ID查询发货计划表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过ID查询发货计划表", description = "根据ID获取发货计划表详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_view')")
    public R<WmsDeliverGoodsPlanBaseEntity> getPlanBaseById(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsPlanBaseService.getPlanBaseById(id));
    }


    /**
     * 新增发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    @Operation(summary = "新增发货计划表", description = "添加新的发货计划记录")
    @SysLog("新增发货计划表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_add')")
    public R<Boolean> saveDeliverPlan(@RequestBody WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.saveDeliverPlan(wmsDeliverGoodsPlanBase));
    }

    /**
     * 批量保存发货计划表
     * 需要提前构造好对象 包含 发货状态以及已发货数量等 以及客户、产品信息准确性
     */
    @Inner
    @Operation(summary = "批量保存发货计划表", description = "批量保存发货计划表")
    @SysLog("批量保存发货计划表")
    @PostMapping("/saveBatchPlanBase")
    public R saveBatchPlanBase(@RequestBody List<WmsDeliverGoodsPlanBaseEntity> planBaseList) {
        return R.ok(wmsDeliverGoodsPlanBaseService.saveBatch(planBaseList));
    }


    /**
     * 修改发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    @Operation(summary = "修改发货计划表", description = "修改发货计划表")
    @SysLog("修改发货计划表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_edit')")
    public R updateById(@RequestBody WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.updatePlanById(wmsDeliverGoodsPlanBase));
    }

    /**
     * 修改发货计划表
     *
     * @param wmsDeliverGoodsPlanBase 发货计划表
     * @return R
     */
    @PutMapping("/update")
    public R update(@RequestBody WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.updateById(wmsDeliverGoodsPlanBase));
    }

    /**
     * 批量修改发货计划表
     */
    @Inner
    @PutMapping("/updateBatchPlanBase")
    public R updateBatch(@RequestBody List<WmsDeliverGoodsPlanBaseEntity> wmsDeliverGoodsPlanBaseEntityList) {
        return R.ok(wmsDeliverGoodsPlanBaseService.updateBatchById(wmsDeliverGoodsPlanBaseEntityList));
    }

    /**
     * 通过id删除发货计划表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除发货计划表", description = "通过id删除发货计划表")
    @SysLog("通过id删除发货计划表")
    @DeleteMapping
    public R removePlanById(@RequestBody Long[] ids) {
        return R.ok(wmsDeliverGoodsPlanBaseService.removePlanById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsDeliverGoodsPlanBase 查询条件
     * @param ids                     导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_export')")
    public List<WmsDeliverGoodsPlanBaseEntity> export(WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase, Long[] ids) {
        return wmsDeliverGoodsPlanBaseService.list(Wrappers.lambdaQuery(wmsDeliverGoodsPlanBase).in(ArrayUtil.isNotEmpty(ids), WmsDeliverGoodsPlanBaseEntity::getId, ids));
    }


    /**
     * 通过发货计划号关闭计划
     */
    @Operation(summary = "通过发货计划号关闭计划", description = "通过发货计划号关闭计划")
    @GetMapping("/close")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_close')")
    public R closePlan(@RequestParam("deliverPlanCode") String deliverPlanCode) {
        return R.ok(wmsDeliverGoodsPlanBaseService.closePlan(deliverPlanCode));
    }


    /**
     * 通过发货计划号强制完成计划
     */
    @Operation(summary = "通过发货计划号强制完成计划", description = "通过发货计划号强制完成计划")
    @GetMapping("/complete")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_complete')")
    public R completePlan(@RequestParam("deliverPlanCode") String deliverPlanCode) {
        return R.ok(wmsDeliverGoodsPlanBaseService.completePlan(deliverPlanCode));
    }

    /**
     * 生成发货单
     */
    @Operation(summary = "生成发货单", description = "生成发货单")
    @PostMapping("/createDeliver")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsPlanBase_create')")
    public R createDeliver(@RequestBody WmsDeliverGoodsBaseEntity deliverGoodsBase) {
        return R.ok(wmsDeliverGoodsPlanBaseService.createDeliver(deliverGoodsBase));
    }

}