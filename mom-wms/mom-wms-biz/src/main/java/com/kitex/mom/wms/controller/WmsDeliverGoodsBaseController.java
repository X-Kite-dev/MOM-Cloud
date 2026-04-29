package com.kitex.mom.wms.controller;


import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.entity.WmsDeliverGoodsBaseEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsBaseService;
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
 * 发货单基础表
 *
 * @author kitex
 * @date 2025-01-13 09:31:20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsBase")
@Tag(description = "wmsDeliverGoodsBase", name = "发货单基础表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsBaseController {

    private final WmsDeliverGoodsBaseService wmsDeliverGoodsBaseService;

    /**
     * 发货单基础表分页查询
     *
     * @param page                分页对象
     * @param wmsDeliverGoodsBase 发货单基础表
     */

    @Operation(summary = "发货单基础表分页查询", description = "分页查询发货单基础表列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<Page<WmsDeliverGoodsBaseEntity>> getWmsDeliverGoodsBasePage(@ParameterObject SearchPage page, @ParameterObject WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.getWmsDeliverGoodsBasePage(page.toMpPage(), wmsDeliverGoodsBase));
    }

    /**
     * 发货单基础表分页 - 统计
     */

    @Operation(summary = "发货单基础表分页 - 统计", description = "发货单基础表分页 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.getCount(wmsDeliverGoodsBase));
    }


    /**
     * 通过ID查询发货单基础表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过ID查询发货单基础表", description = "根据ID获取发货单基础表详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_view')")
    public R<WmsDeliverGoodsBaseEntity> getDeliverBaseById(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.getDeliverBaseById(id));
    }


    /**
     * 新增发货单基础表
     */
    @Operation(summary = "新增发货单基础表", description = "添加新的发货单基础记录")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_add')")
    public R<Boolean> save(@RequestBody WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.saveDeliver(wmsDeliverGoodsBase));
    }

    /**
     * 发货单编辑
     */
    @PutMapping
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_edit')")
    public R edit(@RequestBody WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase) {
        return R.ok(wmsDeliverGoodsBaseService.editDeliver(wmsDeliverGoodsBase));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsDeliverGoodsBase 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_export')")
    public List<WmsDeliverGoodsBaseEntity> export(WmsDeliverGoodsBaseEntity wmsDeliverGoodsBase, Long[] ids) {
        return wmsDeliverGoodsBaseService.list(Wrappers.lambdaQuery(wmsDeliverGoodsBase).in(ArrayUtil.isNotEmpty(ids), WmsDeliverGoodsBaseEntity::getId, ids));
    }

    /**
     * 发货单关闭
     */
    @Operation(summary = "关闭", description = "关闭")
    @GetMapping("/close/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_close')")
    public R close(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.close(id));
    }

    /**
     * 创建销售通知单
     */
    @Operation(summary = "创建销售通知单", description = "创建销售通知单")
    @GetMapping("/create/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_create')")
    public R create(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.create(id));
    }

    /**
     * 撤回销售通知单
     */
    @GetMapping("/retract/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsBase_retract')")
    public R retract(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsBaseService.retract(id));
    }


}