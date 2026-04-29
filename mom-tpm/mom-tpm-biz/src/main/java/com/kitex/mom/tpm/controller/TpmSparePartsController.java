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
import com.kitex.mom.tpm.entity.TpmSparePartsEntity;
import com.kitex.mom.tpm.service.TpmSparePartsService;
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
 * 备件表
 *
 * @author kitex
 * @date 2025-02-11 16:39:20
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSpareParts")
@Tag(description = "tpmSpareParts", name = "备件表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsController {

    private final TpmSparePartsService tpmSparePartsService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param tpmSpareParts 备件表
     * @return
     */
    @Operation(summary = "备件分页查询", description = "分页查询备件列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_view')")
    public R<Page<TpmSparePartsEntity>> getTpmSparePartsPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsEntity tpmSpareParts) {
        LambdaQueryWrapper<TpmSparePartsEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSpareParts.getCode()), TpmSparePartsEntity::getCode, tpmSpareParts.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSpareParts.getName()), TpmSparePartsEntity::getName, tpmSpareParts.getName());
        wrapper.like(StrUtil.isNotBlank(tpmSpareParts.getSupplierName()), TpmSparePartsEntity::getSupplierName, tpmSpareParts.getSupplierName());
        wrapper.notIn(CollUtil.isNotEmpty(tpmSpareParts.getNeCodeList()), TpmSparePartsEntity::getCode, tpmSpareParts.getNeCodeList());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询备件表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询备件详情", description = "通过id查询备件详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_view')")
    public R<TpmSparePartsEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsService.getById(id));
    }

    /**
     * 新增备件表
     *
     * @param tpmSpareParts 备件表
     * @return R
     */
    @Operation(summary = "新增备件", description = "新增备件信息")
    @SysLog("新增备件表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_add')")
    public R save(@RequestBody TpmSparePartsEntity tpmSpareParts) {
        Long count = tpmSparePartsService.count(new LambdaQueryWrapper<TpmSparePartsEntity>()
                .eq(TpmSparePartsEntity::getCode, tpmSpareParts.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的备件编码！");
        }
        return R.ok(tpmSparePartsService.save(tpmSpareParts));
    }

    /**
     * 修改备件表
     *
     * @param tpmSpareParts 备件表
     * @return R
     */
    @Operation(summary = "修改备件", description = "修改备件信息")
    @SysLog("修改备件表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_edit')")
    public R updateById(@RequestBody TpmSparePartsEntity tpmSpareParts) {
        return R.ok(tpmSparePartsService.updateById(tpmSpareParts));
    }

    /**
     * 通过id删除备件表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除备件", description = "通过id删除备件")
    @SysLog("通过id删除备件表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_del')")
    public R removeSparePartsById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsService.removeSparePartsById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSpareParts 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出备件", description = "导出备件数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSpareParts_export')")
    public List<TpmSparePartsEntity> export(TpmSparePartsEntity tpmSpareParts, Long[] ids) {
        return tpmSparePartsService.list(Wrappers.lambdaQuery(tpmSpareParts).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsEntity::getId, ids));
    }
}