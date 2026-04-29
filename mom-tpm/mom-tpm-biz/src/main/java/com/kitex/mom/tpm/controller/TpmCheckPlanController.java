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
import com.kitex.mom.tpm.entity.TpmCheckPlanEntity;
import com.kitex.mom.tpm.service.TpmCheckPlanService;
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
 * 点/巡检计划
 *
 * @author kitex
 * @date 2025-02-18 17:44:03
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmCheckPlan")
@Tag(description = "tpmCheckPlan", name = "点/巡检计划管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmCheckPlanController {

    private final TpmCheckPlanService tpmCheckPlanService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param tpmCheckPlan 点/巡检计划
     * @return
     */
    @Operation(summary = "点/巡检计划分页查询", description = "点/巡检计划分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_view')")
    public R<Page<TpmCheckPlanEntity>> getTpmCheckPlanPage(@ParameterObject SearchPage page, @ParameterObject TpmCheckPlanEntity tpmCheckPlan) {
        return R.ok(tpmCheckPlanService.pageList(page.toMpPage(), tpmCheckPlan));
    }

    /**
     * 分页查询统计
     */
    @GetMapping("/getCount")
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    public R<Map<String, Long>> getCount(@ParameterObject TpmCheckPlanEntity tpmCheckPlan) {
        return R.ok(tpmCheckPlanService.getCount(tpmCheckPlan));
    }

    /**
     * 查询所有设备以及设备结构
     */
    @Operation(summary = "查询所有设备以及设备结构", description = "查询所有设备以及设备结构")
    @GetMapping("/getDevice")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_view')")
    public R<List<MdmDeviceClassEntity>> getDevice() {
        return R.ok(tpmCheckPlanService.getDevice());
    }


    /**
     * 通过id查询点/巡检计划
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询点/巡检计划", description = "通过id查询点/巡检计划")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_view')")
    public R<TpmCheckPlanEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmCheckPlanService.getById(id));
    }

    /**
     * 新增点/巡检计划
     *
     * @param tpmCheckPlan 点/巡检计划
     * @return R
     */
    @Operation(summary = "新增点/巡检计划", description = "新增点/巡检计划")
    @SysLog("新增点/巡检计划")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_add')")
    public R<Boolean> savePlan(@RequestBody TpmCheckPlanEntity tpmCheckPlan) {
        return R.ok(tpmCheckPlanService.savePlan(tpmCheckPlan));
    }

    /**
     * 修改点/巡检计划
     *
     * @param tpmCheckPlan 点/巡检计划
     * @return R
     */
    @Operation(summary = "修改点/巡检计划", description = "修改点/巡检计划")
    @SysLog("修改点/巡检计划")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_edit')")
    public R<Boolean> updateById(@RequestBody TpmCheckPlanEntity tpmCheckPlan) {
        return R.ok(tpmCheckPlanService.updateById(tpmCheckPlan));
    }

    /**
     * 通过id删除点/巡检计划
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除点/巡检计划", description = "通过id删除点/巡检计划")
    @SysLog("通过id删除点/巡检计划")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmCheckPlanService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmCheckPlan 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckPlan_export')")
    @Operation(summary = "导出点/巡检计划", description = "导出点/巡检计划数据到Excel")
    public List<TpmCheckPlanEntity> export(TpmCheckPlanEntity tpmCheckPlan, Long[] ids) {
        return tpmCheckPlanService.list(Wrappers.lambdaQuery(tpmCheckPlan).in(ArrayUtil.isNotEmpty(ids), TpmCheckPlanEntity::getId, ids));
    }
}