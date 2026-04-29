package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmMaintenanceRecordEntity;
import com.kitex.mom.tpm.service.TpmMaintenanceRecordService;
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
 * 保养记录表
 *
 * @author kitex
 * @date 2025-02-06 15:13:49
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmMaintenanceRecord")
@Tag(description = "tpmMaintenanceRecord", name = "保养记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmMaintenanceRecordController {

    private final TpmMaintenanceRecordService tpmMaintenanceRecordService;

    /**
     * 分页查询
     *
     * @param page                 分页对象
     * @param tpmMaintenanceRecord 保养记录表
     * @return
     */
    @Operation(summary = "保养记录分页查询", description = "分页查询保养记录列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_view')")
    public R<Page<TpmMaintenanceRecordEntity>> getTpmMaintenanceRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmMaintenanceRecordEntity tpmMaintenanceRecord) {
        LambdaQueryWrapper<TpmMaintenanceRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getTaskCode()), TpmMaintenanceRecordEntity::getTaskCode, tpmMaintenanceRecord.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getPlanName()), TpmMaintenanceRecordEntity::getPlanName, tpmMaintenanceRecord.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getDeviceName()), TpmMaintenanceRecordEntity::getDeviceName, tpmMaintenanceRecord.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getHandlePersonName()), TpmMaintenanceRecordEntity::getHandlePersonName, tpmMaintenanceRecord.getHandlePersonName());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenanceRecord.getState()), TpmMaintenanceRecordEntity::getState, tpmMaintenanceRecord.getState());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmMaintenanceRecordService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 统计
     */
    @Operation(summary = "分页查询 - 统计", description = "分页查询 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject SearchPage page, @ParameterObject TpmMaintenanceRecordEntity tpmMaintenanceRecord) {
        LambdaQueryWrapper<TpmMaintenanceRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getTaskCode()), TpmMaintenanceRecordEntity::getTaskCode, tpmMaintenanceRecord.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getPlanName()), TpmMaintenanceRecordEntity::getPlanName, tpmMaintenanceRecord.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getDeviceName()), TpmMaintenanceRecordEntity::getDeviceName, tpmMaintenanceRecord.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceRecord.getHandlePersonName()), TpmMaintenanceRecordEntity::getHandlePersonName, tpmMaintenanceRecord.getHandlePersonName());
        return R.ok(tpmMaintenanceRecordService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmMaintenanceRecordEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询保养记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询保养记录详情", description = "通过id查询保养记录详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_view')")
    public R<TpmMaintenanceRecordEntity> getRecordById(@PathVariable("id") Long id) {
        return R.ok(tpmMaintenanceRecordService.getRecordById(id));
    }

    /**
     * 新增保养记录表
     *
     * @param tpmMaintenanceRecord 保养记录表
     * @return R
     */
    @Operation(summary = "新增保养记录表", description = "新增保养记录表")
    @SysLog("新增保养记录表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_add')")
    public R<Boolean> save(@RequestBody TpmMaintenanceRecordEntity tpmMaintenanceRecord) {
        return R.ok(tpmMaintenanceRecordService.save(tpmMaintenanceRecord));
    }

    /**
     * 修改保养记录表
     *
     * @param tpmMaintenanceRecord 保养记录表
     * @return R
     */
    @Operation(summary = "修改保养记录表", description = "修改保养记录表")
    @SysLog("修改保养记录表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_edit')")
    public R updateById(@RequestBody TpmMaintenanceRecordEntity tpmMaintenanceRecord) {
        return R.ok(tpmMaintenanceRecordService.updateById(tpmMaintenanceRecord));
    }

    /**
     * 通过id删除保养记录表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除保养记录表", description = "通过id删除保养记录表")
    @SysLog("通过id删除保养记录表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(tpmMaintenanceRecordService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmMaintenanceRecord 查询条件
     * @param ids                  导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceRecord_export')")
    @Operation(summary = "导出保养记录", description = "导出保养记录数据到Excel")
    public List<TpmMaintenanceRecordEntity> export(TpmMaintenanceRecordEntity tpmMaintenanceRecord, Long[] ids) {
        return tpmMaintenanceRecordService.list(Wrappers.lambdaQuery(tpmMaintenanceRecord).in(ArrayUtil.isNotEmpty(ids), TpmMaintenanceRecordEntity::getId, ids));
    }
}