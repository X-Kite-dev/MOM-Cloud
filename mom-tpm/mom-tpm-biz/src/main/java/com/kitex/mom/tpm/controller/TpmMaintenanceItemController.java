package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmMaintenanceItemEntity;
import com.kitex.mom.tpm.service.TpmMaintenanceItemService;
import com.kitex.mom.tpm.vo.TpmMaintenanceItemVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 保养项目表
 *
 * @author kitex
 * @date 2025-02-06 15:04:12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmMaintenanceItem")
@Tag(description = "tpmMaintenanceItem", name = "保养项目表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmMaintenanceItemController {

    private final TpmMaintenanceItemService tpmMaintenanceItemService;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param tpmMaintenanceItem 保养项目表
     * @return
     */
    @Operation(summary = "保养项目分页查询", description = "分页查询保养项目列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_view')")
    public R getTpmMaintenanceItemPage(@ParameterObject SearchPage page, @ParameterObject TpmMaintenanceItemEntity tpmMaintenanceItem) {
        LambdaQueryWrapper<TpmMaintenanceItemEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceItem.getCode()), TpmMaintenanceItemEntity::getCode, tpmMaintenanceItem.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceItem.getName()), TpmMaintenanceItemEntity::getName, tpmMaintenanceItem.getName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmMaintenanceItemService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询保养项目表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询保养项目详情", description = "通过id查询保养项目详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(tpmMaintenanceItemService.getById(id));
    }

    /**
     * 新增保养项目表
     *
     * @param tpmMaintenanceItem 保养项目表
     * @return R
     */
    @Operation(summary = "新增保养项目表", description = "新增保养项目表")
    @SysLog("新增保养项目表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_add')")
    public R save(@RequestBody TpmMaintenanceItemEntity tpmMaintenanceItem) {
        Long count = tpmMaintenanceItemService.count(new LambdaQueryWrapper<TpmMaintenanceItemEntity>()
                .eq(TpmMaintenanceItemEntity::getCode, tpmMaintenanceItem.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复编码！");
        }
        return R.ok(tpmMaintenanceItemService.save(tpmMaintenanceItem));
    }

    /**
     * 修改保养项目表
     *
     * @param tpmMaintenanceItem 保养项目表
     * @return R
     */
    @Operation(summary = "修改保养项目表", description = "修改保养项目表")
    @SysLog("修改保养项目表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_edit')")
    public R updateById(@RequestBody TpmMaintenanceItemEntity tpmMaintenanceItem) {
        return R.ok(tpmMaintenanceItemService.updateById(tpmMaintenanceItem));
    }

    /**
     * 通过id删除保养项目表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除保养项目表", description = "通过id删除保养项目表")
    @SysLog("通过id删除保养项目表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_del')")
    public R removeItemById(@RequestBody Long[] ids) {
        return R.ok(tpmMaintenanceItemService.removeItemById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmMaintenanceItem 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceItem_export')")
    @Operation(summary = "导出保养项目", description = "导出保养项目数据到Excel")
    public List<TpmMaintenanceItemEntity> export(TpmMaintenanceItemEntity tpmMaintenanceItem, Long[] ids) {
        return tpmMaintenanceItemService.list(Wrappers.lambdaQuery(tpmMaintenanceItem).in(ArrayUtil.isNotEmpty(ids), TpmMaintenanceItemEntity::getId, ids));
    }

    /**
     * 导入excel 表
     */
    @PostMapping("/import")
    public R importExcel(@RequestExcel(headRowNumber = 3) List<TpmMaintenanceItemVo> tpmMaintenanceItemVoList, BindingResult bindingResult, String factoryCode) {
        return tpmMaintenanceItemService.importExcel(tpmMaintenanceItemVoList, bindingResult, factoryCode);
    }

}