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
import com.kitex.mom.tpm.entity.TpmSupplierEntity;
import com.kitex.mom.tpm.service.TpmSupplierService;
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
 * 设备供应商主表
 *
 * @author kitex
 * @date 2025-03-25 11:57:15
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSupplier")
@Tag(description = "tpmSupplier", name = "设备供应商主表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSupplierController {

    private final TpmSupplierService tpmSupplierService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param tpmSupplier 设备供应商主表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_view')")
    public R<Page<TpmSupplierEntity>> getTpmSupplierPage(@ParameterObject SearchPage page, @ParameterObject TpmSupplierEntity tpmSupplier) {
        LambdaQueryWrapper<TpmSupplierEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSupplier.getCode()), TpmSupplierEntity::getCode, tpmSupplier.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSupplier.getName()), TpmSupplierEntity::getName, tpmSupplier.getName());
        wrapper.like(StrUtil.isNotBlank(tpmSupplier.getClassName()), TpmSupplierEntity::getClassName, tpmSupplier.getClassName());
        wrapper.eq(StrUtil.isNotBlank(tpmSupplier.getClassCode()), TpmSupplierEntity::getClassCode, tpmSupplier.getClassCode());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSupplierService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询设备供应商主表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_view')")
    public R<TpmSupplierEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSupplierService.getById(id));
    }

    /**
     * 新增设备供应商主表
     *
     * @param tpmSupplier 设备供应商主表
     * @return R
     */
    @Operation(summary = "新增设备供应商主表", description = "新增设备供应商主表")
    @SysLog("新增设备供应商主表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_add')")
    public R<Boolean> save(@RequestBody TpmSupplierEntity tpmSupplier) {
        Long count = tpmSupplierService.count(new LambdaQueryWrapper<TpmSupplierEntity>()
                .eq(TpmSupplierEntity::getCode, tpmSupplier.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的编码！");
        }
        return R.ok(tpmSupplierService.save(tpmSupplier));
    }

    /**
     * 修改设备供应商主表
     *
     * @param tpmSupplier 设备供应商主表
     * @return R
     */
    @Operation(summary = "修改设备供应商主表", description = "修改设备供应商主表")
    @SysLog("修改设备供应商主表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_edit')")
    public R<Boolean> updateById(@RequestBody TpmSupplierEntity tpmSupplier) {
        return R.ok(tpmSupplierService.updateById(tpmSupplier));
    }

    /**
     * 通过id删除设备供应商主表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除设备供应商主表", description = "通过id删除设备供应商主表")
    @SysLog("通过id删除设备供应商主表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmSupplierService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSupplier 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSupplier_export')")
    public List<TpmSupplierEntity> export(TpmSupplierEntity tpmSupplier, Long[] ids) {
        return tpmSupplierService.list(Wrappers.lambdaQuery(tpmSupplier).in(ArrayUtil.isNotEmpty(ids), TpmSupplierEntity::getId, ids));
    }
}