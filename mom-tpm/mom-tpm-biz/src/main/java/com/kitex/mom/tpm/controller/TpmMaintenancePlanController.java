package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;
import com.kitex.mom.tpm.service.TpmMaintenancePlanService;
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
 * 保养计划表
 *
 * @author kitex
 * @date 2025-02-06 15:07:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmMaintenancePlan")
@Tag(description = "tpmMaintenancePlan", name = "保养计划表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmMaintenancePlanController {

    private final TpmMaintenancePlanService tpmMaintenancePlanService;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param tpmMaintenancePlan 保养计划表
     * @return
     */
    @Operation(summary = "保养计划分页查询", description = "分页查询保养计划列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_view')")
    public R<Page<TpmMaintenancePlanEntity>> getTpmMaintenancePlanPage(@ParameterObject SearchPage page, @ParameterObject TpmMaintenancePlanEntity tpmMaintenancePlan) {
        return R.ok(tpmMaintenancePlanService.pageList(page.toMpPage(), tpmMaintenancePlan));
    }

    /**
     * 分页查询 - 统计
     */
    @Operation(summary = "分页查询 - 统计", description = "分页查询 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmMaintenancePlanEntity tpmMaintenancePlan) {
        return R.ok(tpmMaintenancePlanService.getCount(tpmMaintenancePlan));
    }

    /**
     * 通过id查询保养计划表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询保养计划详情", description = "通过id查询保养计划详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_view')")
    public R<TpmMaintenancePlanEntity> getPlanById(@PathVariable("id") Long id) {
        return R.ok(tpmMaintenancePlanService.getPlanById(id));
    }


    /**
     * 查询所有设备以及设备结构
     */
    @Operation(summary = "查询所有设备以及设备结构", description = "查询所有设备以及设备结构")
    @GetMapping("/getDevice")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_view')")
    public R<List<MdmDeviceClassEntity>> getDevice() {
        return R.ok(tpmMaintenancePlanService.getDevice());
    }

    /**
     * 新增保养计划表
     *
     * @param tpmMaintenancePlan 保养计划表
     * @return R
     */
    @Operation(summary = "新增保养计划表", description = "新增保养计划表")
    @SysLog("新增保养计划表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_add')")
    public R<Boolean> savePlan(@RequestBody TpmMaintenancePlanEntity tpmMaintenancePlan) {
        return R.ok(tpmMaintenancePlanService.savePlan(tpmMaintenancePlan));
    }

    /**
     * 修改保养计划表
     *
     * @param tpmMaintenancePlan 保养计划表
     * @return R
     */
    @Operation(summary = "修改保养计划表", description = "修改保养计划表")
    @SysLog("修改保养计划表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_edit')")
    public R<Boolean> updatePlanById(@RequestBody TpmMaintenancePlanEntity tpmMaintenancePlan) {
        return R.ok(tpmMaintenancePlanService.updatePlanById(tpmMaintenancePlan));
    }

    /**
     * 通过id删除保养计划表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除保养计划表", description = "通过id删除保养计划表")
    @SysLog("通过id删除保养计划表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_del')")
    public R<Boolean> removePlanById(@RequestBody Long[] ids) {
        return R.ok(tpmMaintenancePlanService.removePlanBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmMaintenancePlan 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenancePlan_export')")
    @Operation(summary = "导出保养计划", description = "导出保养计划数据到Excel")
    public List<TpmMaintenancePlanEntity> export(TpmMaintenancePlanEntity tpmMaintenancePlan, Long[] ids) {
        return tpmMaintenancePlanService.list(Wrappers.lambdaQuery(tpmMaintenancePlan).in(ArrayUtil.isNotEmpty(ids), TpmMaintenancePlanEntity::getId, ids));
    }
}