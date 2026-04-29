package com.kitex.mom.wms.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.wms.entity.WmsDeliverGoodsAbnormalRecordEntity;
import com.kitex.mom.wms.service.WmsDeliverGoodsAbnormalRecordService;
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

/**
 * 发货异常记录表
 *
 * @author kitex
 * @date 2025-01-13 09:26:52
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsDeliverGoodsAbnormalRecord")
@Tag(description = "wmsDeliverGoodsAbnormalRecord", name = "发货异常记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsDeliverGoodsAbnormalRecordController {

    private final WmsDeliverGoodsAbnormalRecordService wmsDeliverGoodsAbnormalRecordService;

    /**
     * 发货异常记录分页查询
     *
     * @param page                          分页对象
     * @param wmsDeliverGoodsAbnormalRecord 发货异常记录表
     * @return
     */

    @Operation(summary = "发货异常记录分页查询", description = "分页查询发货异常记录列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsAbnormalRecord_view')")
    public R<Page<WmsDeliverGoodsAbnormalRecordEntity>> getWmsDeliverGoodsAbnormalRecordPage(@ParameterObject SearchPage page, @ParameterObject WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord) {
        LambdaQueryWrapper<WmsDeliverGoodsAbnormalRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getOrderCode()), WmsDeliverGoodsAbnormalRecordEntity::getOrderCode, wmsDeliverGoodsAbnormalRecord.getOrderCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getDeliverPlanCode()), WmsDeliverGoodsAbnormalRecordEntity::getDeliverPlanCode, wmsDeliverGoodsAbnormalRecord.getDeliverPlanCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getProductCode()), WmsDeliverGoodsAbnormalRecordEntity::getProductCode, wmsDeliverGoodsAbnormalRecord.getProductCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getProductName()), WmsDeliverGoodsAbnormalRecordEntity::getProductName, wmsDeliverGoodsAbnormalRecord.getProductName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getCustomerCode()), WmsDeliverGoodsAbnormalRecordEntity::getCustomerCode, wmsDeliverGoodsAbnormalRecord.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getCustomerName()), WmsDeliverGoodsAbnormalRecordEntity::getCustomerName, wmsDeliverGoodsAbnormalRecord.getCustomerName());
        wrapper.eq(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getState()), WmsDeliverGoodsAbnormalRecordEntity::getState, wmsDeliverGoodsAbnormalRecord.getState());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(wmsDeliverGoodsAbnormalRecordService.page(page.toMpPage(), wrapper));
    }


    /**
     * 发货异常记录分页 - 统计
     */

    @Operation(summary = " 发货异常记录分页 - 统计", description = " 发货异常记录分页 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord) {
        LambdaQueryWrapper<WmsDeliverGoodsAbnormalRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getOrderCode()), WmsDeliverGoodsAbnormalRecordEntity::getOrderCode, wmsDeliverGoodsAbnormalRecord.getOrderCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getDeliverPlanCode()), WmsDeliverGoodsAbnormalRecordEntity::getDeliverPlanCode, wmsDeliverGoodsAbnormalRecord.getDeliverPlanCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getProductCode()), WmsDeliverGoodsAbnormalRecordEntity::getProductCode, wmsDeliverGoodsAbnormalRecord.getProductCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getProductName()), WmsDeliverGoodsAbnormalRecordEntity::getProductName, wmsDeliverGoodsAbnormalRecord.getProductName());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getCustomerCode()), WmsDeliverGoodsAbnormalRecordEntity::getCustomerCode, wmsDeliverGoodsAbnormalRecord.getCustomerCode());
        wrapper.like(StrUtil.isNotBlank(wmsDeliverGoodsAbnormalRecord.getCustomerName()), WmsDeliverGoodsAbnormalRecordEntity::getCustomerName, wmsDeliverGoodsAbnormalRecord.getCustomerName());
        return R.ok(wmsDeliverGoodsAbnormalRecordService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsDeliverGoodsAbnormalRecordEntity::getState, Collectors.counting())));
    }


    /**
     * 通过ID查询发货异常记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过ID查询发货异常记录", description = "根据ID获取发货异常记录详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsAbnormalRecord_view')")
    public R<WmsDeliverGoodsAbnormalRecordEntity> getById(@PathVariable("id") Long id) {
        return R.ok(wmsDeliverGoodsAbnormalRecordService.getById(id));
    }


    /**
     * 填报发货异常记录
     */
    @Operation(summary = "填报发货异常记录", description = "通过ID填报发货异常记录")
    @PostMapping("/report")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsAbnormalRecord_report')")
    public R<Boolean> report(@RequestBody WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord) {
        return R.ok(wmsDeliverGoodsAbnormalRecordService.report(wmsDeliverGoodsAbnormalRecord));
    }


    /**
     * 导出excel 表格
     *
     * @param wmsDeliverGoodsAbnormalRecord 查询条件
     * @param ids                           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('wms_wmsDeliverGoodsAbnormalRecord_export')")
    public List<WmsDeliverGoodsAbnormalRecordEntity> export(WmsDeliverGoodsAbnormalRecordEntity wmsDeliverGoodsAbnormalRecord, Long[] ids) {
        return wmsDeliverGoodsAbnormalRecordService.list(Wrappers.lambdaQuery(wmsDeliverGoodsAbnormalRecord).in(ArrayUtil.isNotEmpty(ids), WmsDeliverGoodsAbnormalRecordEntity::getId, ids));
    }
}