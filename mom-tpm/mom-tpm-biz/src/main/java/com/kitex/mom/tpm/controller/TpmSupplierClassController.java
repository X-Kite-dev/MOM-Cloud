package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmSupplierClassEntity;
import com.kitex.mom.tpm.service.TpmSupplierClassService;
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
 * 设备供应商类型表
 *
 * @author kitex
 * @date 2025-03-25 13:42:38
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSupplierClass")
@Tag(description = "tpmSupplierClass", name = "设备供应商类型表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSupplierClassController {

    private final TpmSupplierClassService tpmSupplierClassService;

    /**
     * 分页查询
     *
     * @param page             分页对象
     * @param tpmSupplierClass 设备供应商类型表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_view')")
    public R<Page<TpmSupplierClassEntity>> getTpmSupplierClassPage(@ParameterObject SearchPage page, @ParameterObject TpmSupplierClassEntity tpmSupplierClass) {
        LambdaQueryWrapper<TpmSupplierClassEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSupplierClass.getCode()), TpmSupplierClassEntity::getCode, tpmSupplierClass.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSupplierClass.getName()), TpmSupplierClassEntity::getName, tpmSupplierClass.getName());
        return R.ok(tpmSupplierClassService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询设备供应商类型表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_view')")
    public R<TpmSupplierClassEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSupplierClassService.getById(id));
    }

    /**
     * 获取供应商类型列表
     */
    @Operation(summary = "获取供应商类型列表", description = "获取供应商类型列表")
    @GetMapping("/getList")
    public R<List<TpmSupplierClassEntity>> getList(String name) {
        return R.ok(tpmSupplierClassService.list(new LambdaQueryWrapper<TpmSupplierClassEntity>()
                .like(StrUtil.isNotBlank(name), TpmSupplierClassEntity::getName, name)));
    }

    /**
     * 新增设备供应商类型表
     *
     * @param tpmSupplierClass 设备供应商类型表
     */
    @Operation(summary = "新增设备供应商类型表", description = "新增设备供应商类型表")
    @SysLog("新增设备供应商类型表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_add')")
    public R<Boolean> save(@RequestBody TpmSupplierClassEntity tpmSupplierClass) {
        Long count = tpmSupplierClassService.count(new LambdaQueryWrapper<TpmSupplierClassEntity>()
                .eq(TpmSupplierClassEntity::getCode, tpmSupplierClass.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的编码！");
        }
        return R.ok(tpmSupplierClassService.save(tpmSupplierClass));
    }

    /**
     * 修改设备供应商类型表
     *
     * @param tpmSupplierClass 设备供应商类型表
     * @return R
     */
    @Operation(summary = "修改设备供应商类型表", description = "修改设备供应商类型表")
    @SysLog("修改设备供应商类型表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_edit')")
    public R<Boolean> updateClassById(@RequestBody TpmSupplierClassEntity tpmSupplierClass) {
        return R.ok(tpmSupplierClassService.updateClassById(tpmSupplierClass));
    }

    /**
     * 通过id删除设备供应商类型表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除设备供应商类型表", description = "通过id删除设备供应商类型表")
    @SysLog("通过id删除设备供应商类型表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_del')")
    public R<Boolean> removeClass(@RequestBody Long[] ids) {
        return R.ok(tpmSupplierClassService.removeClass(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSupplierClass 查询条件
     * @param ids              导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplierClass_export')")
    public List<TpmSupplierClassEntity> export(TpmSupplierClassEntity tpmSupplierClass, Long[] ids) {
        return tpmSupplierClassService.list(Wrappers.lambdaQuery(tpmSupplierClass).in(ArrayUtil.isNotEmpty(ids), TpmSupplierClassEntity::getId, ids));
    }
}