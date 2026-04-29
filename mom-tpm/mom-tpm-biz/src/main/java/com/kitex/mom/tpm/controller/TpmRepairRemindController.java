package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmRepairRemindEntity;
import com.kitex.mom.tpm.service.TpmRepairRemindService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 提醒配置表
 *
 * @author kitex
 * @date 2025-02-06 15:39:14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmRepairRemind")
@Tag(description = "tpmRepairRemind", name = "提醒配置表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmRepairRemindController {

    private final TpmRepairRemindService tpmRepairRemindService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param tpmRepairRemind 提醒配置表
     * @return
     */
    @Operation(summary = "提醒配置分页查询", description = "分页查询提醒配置列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_view')")
    public R<Page<TpmRepairRemindEntity>> getTpmRepairRemindPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairRemindEntity tpmRepairRemind) {
        return R.ok(tpmRepairRemindService.pageList(page.toMpPage(), tpmRepairRemind));
    }


    /**
     * 通过id查询提醒配置表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询提醒配置详情", description = "通过id查询提醒配置详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_view')")
    public R<TpmRepairRemindEntity> getRemindById(@PathVariable("id") Long id) {
        return R.ok(tpmRepairRemindService.getRemindById(id));
    }

    /**
     * 新增提醒配置表
     *
     * @param tpmRepairRemind 提醒配置表
     * @return R
     */
    @Operation(summary = "新增提醒配置表", description = "新增提醒配置表")
    @SysLog("新增提醒配置表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_add')")
    public R<Boolean> saveRemind(@RequestBody TpmRepairRemindEntity tpmRepairRemind) {
        return R.ok(tpmRepairRemindService.saveRemind(tpmRepairRemind));
    }

    /**
     * 修改提醒配置表
     *
     * @param tpmRepairRemind 提醒配置表
     * @return R
     */
    @Operation(summary = "修改提醒配置表", description = "修改提醒配置表")
    @SysLog("修改提醒配置表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_edit')")
    public R updateRemindById(@RequestBody TpmRepairRemindEntity tpmRepairRemind) {
        return R.ok(tpmRepairRemindService.updateRemindById(tpmRepairRemind));
    }

    /**
     * 通过id删除提醒配置表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除提醒配置表", description = "通过id删除提醒配置表")
    @SysLog("通过id删除提醒配置表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_del')")
    public R removeRemindById(@RequestBody Long[] ids) {
        return R.ok(tpmRepairRemindService.removeRemindById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmRepairRemind 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRemind_export')")
    @Operation(summary = "导出提醒配置", description = "导出提醒配置数据到Excel")
    public List<TpmRepairRemindEntity> export(TpmRepairRemindEntity tpmRepairRemind, Long[] ids) {
        return tpmRepairRemindService.list(Wrappers.lambdaQuery(tpmRepairRemind).in(ArrayUtil.isNotEmpty(ids), TpmRepairRemindEntity::getId, ids));
    }
}