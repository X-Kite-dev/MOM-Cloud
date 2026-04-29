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
import com.kitex.mom.tpm.entity.TpmSparePartsStoreroomEntity;
import com.kitex.mom.tpm.service.TpmSparePartsStoreroomService;
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
 * 备件库房表
 *
 * @author kitex
 * @date 2025-02-11 16:52:52
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsStoreroom")
@Tag(description = "tpmSparePartsStoreroom", name = "备件库房表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsStoreroomController {

    private final TpmSparePartsStoreroomService tpmSparePartsStoreroomService;

    /**
     * 分页查询
     *
     * @param page                   分页对象
     * @param tpmSparePartsStoreroom 备件库房表
     * @return
     */
    @Operation(summary = "库房分页查询", description = "分页查询备件库房列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_view')")
    public R<Page<TpmSparePartsStoreroomEntity>> getTpmSparePartsStoreroomPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsStoreroomEntity tpmSparePartsStoreroom) {
        LambdaQueryWrapper<TpmSparePartsStoreroomEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsStoreroom.getCode()), TpmSparePartsStoreroomEntity::getCode, tpmSparePartsStoreroom.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsStoreroom.getCode()), TpmSparePartsStoreroomEntity::getCode, tpmSparePartsStoreroom.getCode());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsStoreroomService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询备件库房表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询库房详情", description = "通过id查询备件库房详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsStoreroomService.getById(id));
    }

    /**
     * 新增备件库房表
     *
     * @param tpmSparePartsStoreroom 备件库房表
     * @return R
     */
    @Operation(summary = "新增库房", description = "新增备件库房信息")
    @SysLog("新增备件库房表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_add')")
    public R save(@RequestBody TpmSparePartsStoreroomEntity tpmSparePartsStoreroom) {
        Long count = tpmSparePartsStoreroomService.count(new LambdaQueryWrapper<TpmSparePartsStoreroomEntity>()
                .eq(TpmSparePartsStoreroomEntity::getCode, tpmSparePartsStoreroom.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的库房编码！");
        }
        return R.ok(tpmSparePartsStoreroomService.save(tpmSparePartsStoreroom));
    }

    /**
     * 修改备件库房表
     *
     * @param tpmSparePartsStoreroom 备件库房表
     * @return R
     */
    @Operation(summary = "修改库房", description = "修改备件库房信息")
    @SysLog("修改备件库房表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_edit')")
    public R updateById(@RequestBody TpmSparePartsStoreroomEntity tpmSparePartsStoreroom) {
        return R.ok(tpmSparePartsStoreroomService.updateById(tpmSparePartsStoreroom));
    }

    /**
     * 通过id删除备件库房表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除库房", description = "通过id删除备件库房")
    @SysLog("通过id删除备件库房表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_del')")
    public R removeStoreroomById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsStoreroomService.removeStoreroomByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsStoreroom 查询条件
     * @param ids                    导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出库房", description = "导出备件库房数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStoreroom_export')")
    public List<TpmSparePartsStoreroomEntity> export(TpmSparePartsStoreroomEntity tpmSparePartsStoreroom, Long[] ids) {
        return tpmSparePartsStoreroomService.list(Wrappers.lambdaQuery(tpmSparePartsStoreroom).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsStoreroomEntity::getId, ids));
    }
}