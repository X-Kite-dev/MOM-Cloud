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
import com.kitex.mom.tpm.entity.TpmRepairFaultClassEntity;
import com.kitex.mom.tpm.service.TpmRepairFaultClassService;
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
 * 故障类型表
 *
 * @author kitex
 * @date 2025-02-06 15:25:03
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmRepairFaultClass")
@Tag(description = "tpmRepairFaultClass", name = "故障类型表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmRepairFaultClassController {

    private final TpmRepairFaultClassService tpmRepairFaultClassService;

    /**
     * 分页查询
     *
     * @param page                分页对象
     * @param tpmRepairFaultClass 故障类型表
     * @return
     */
    @Operation(summary = "故障类型分页查询", description = "分页查询故障类型列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_view')")
    public R<Page<TpmRepairFaultClassEntity>> getTpmRepairFaultClassPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairFaultClassEntity tpmRepairFaultClass) {
        LambdaQueryWrapper<TpmRepairFaultClassEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmRepairFaultClass.getName()), TpmRepairFaultClassEntity::getName, tpmRepairFaultClass.getName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmRepairFaultClassService.page(page.toMpPage(), wrapper));
    }

    /**
     * 获取所有故障类型
     */
    @Operation(summary = "获取所有故障类型", description = "获取所有故障类型列表数据")
    @GetMapping("/list")
    public R getTpmRepairFaultClassList() {
        return R.ok(tpmRepairFaultClassService.list());
    }


    /**
     * 通过id查询故障类型表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询故障类型详情", description = "通过id查询故障类型详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_view')")
    public R<TpmRepairFaultClassEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmRepairFaultClassService.getById(id));
    }

    /**
     * 新增故障类型表
     *
     * @param tpmRepairFaultClass 故障类型表
     * @return R
     */
    @Operation(summary = "新增故障类型", description = "新增故障类型信息")
    @SysLog("新增故障类型表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_add')")
    public R<Boolean> save(@RequestBody TpmRepairFaultClassEntity tpmRepairFaultClass) {
        Long count = tpmRepairFaultClassService.count(new LambdaQueryWrapper<TpmRepairFaultClassEntity>()
                .eq(TpmRepairFaultClassEntity::getName, tpmRepairFaultClass.getName()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("新增失败，存在重复的故障类型！");
        }
        return R.ok(tpmRepairFaultClassService.save(tpmRepairFaultClass));
    }

    /**
     * 修改故障类型表
     *
     * @param tpmRepairFaultClass 故障类型表
     * @return R
     */
    @Operation(summary = "修改故障类型", description = "修改故障类型信息")
    @SysLog("修改故障类型表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_edit')")
    public R updateById(@RequestBody TpmRepairFaultClassEntity tpmRepairFaultClass) {
        Long count = tpmRepairFaultClassService.count(new LambdaQueryWrapper<TpmRepairFaultClassEntity>()
                .eq(TpmRepairFaultClassEntity::getName, tpmRepairFaultClass.getName())
                .ne(TpmRepairFaultClassEntity::getId, tpmRepairFaultClass.getId()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("更新失败，存在重复的故障类型！");
        }
        return R.ok(tpmRepairFaultClassService.updateById(tpmRepairFaultClass));
    }

    /**
     * 通过id删除故障类型表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除故障类型", description = "通过id删除故障类型")
    @SysLog("通过id删除故障类型表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_del')")
    public R removeClassById(@RequestBody Long[] ids) {
        return R.ok(tpmRepairFaultClassService.removeClassById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmRepairFaultClass 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出故障类型", description = "导出故障类型数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairFaultClass_export')")
    public List<TpmRepairFaultClassEntity> export(TpmRepairFaultClassEntity tpmRepairFaultClass, Long[] ids) {
        return tpmRepairFaultClassService.list(Wrappers.lambdaQuery(tpmRepairFaultClass).in(ArrayUtil.isNotEmpty(ids), TpmRepairFaultClassEntity::getId, ids));
    }
}