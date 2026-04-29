package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmCheckItemEntity;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.service.TpmCheckItemService;
import com.kitex.mom.tpm.service.TpmDeviceItemService;
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
 * 点/巡检项目
 *
 * @author kitex
 * @date 2025-02-18 17:40:04
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmCheckItem")
@Tag(description = "tpmCheckItem", name = "点/巡检项目管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmCheckItemController {

    private final TpmCheckItemService tpmCheckItemService;
    private final TpmDeviceItemService tpmDeviceItemService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param tpmCheckItem 点/巡检项目
     * @return
     */
    @Operation(summary = "点/巡检项目分页查询", description = "点/巡检项目分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_view')")
    public R<Page<TpmCheckItemEntity>> getCheckItemPage(@ParameterObject SearchPage page, @ParameterObject TpmCheckItemEntity tpmCheckItem) {
        LambdaQueryWrapper<TpmCheckItemEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmCheckItem.getCode()), TpmCheckItemEntity::getCode, tpmCheckItem.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmCheckItem.getName()), TpmCheckItemEntity::getName, tpmCheckItem.getName());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckItem.getType()), TpmCheckItemEntity::getType, tpmCheckItem.getType());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmCheckItemService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询点/巡检项目
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询点/巡检项目", description = "通过id查询点/巡检项目")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_view')")
    public R<TpmCheckItemEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmCheckItemService.getById(id));
    }

    /**
     * 新增点/巡检项目
     *
     * @param tpmCheckItem 点/巡检项目
     * @return R
     */
    @Operation(summary = "新增点/巡检项目", description = "新增点/巡检项目")
    @SysLog("新增点/巡检项目")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_add')")
    public R<Boolean> save(@RequestBody TpmCheckItemEntity tpmCheckItem) {
        Long count = tpmCheckItemService.count(new LambdaQueryWrapper<TpmCheckItemEntity>()
                .eq(TpmCheckItemEntity::getCode, tpmCheckItem.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的编码！");
        }
        return R.ok(tpmCheckItemService.save(tpmCheckItem));
    }

    /**
     * 修改点/巡检项目
     *
     * @param tpmCheckItem 点/巡检项目
     * @return R
     */
    @Operation(summary = "修改点/巡检项目", description = "修改点/巡检项目")
    @SysLog("修改点/巡检项目")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_edit')")
    public R<Boolean> updateById(@RequestBody TpmCheckItemEntity tpmCheckItem) {
        //更新关联表类型
        tpmDeviceItemService.update(new UpdateWrapper<TpmDeviceItemEntity>().lambda()
                .set(TpmDeviceItemEntity::getType, tpmCheckItem.getType())
                .eq(TpmDeviceItemEntity::getItemId, tpmCheckItem.getId()));
        return R.ok(tpmCheckItemService.updateById(tpmCheckItem));
    }

    /**
     * 通过id删除点/巡检项目
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除点/巡检项目", description = "通过id删除点/巡检项目")
    @SysLog("通过id删除点/巡检项目")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_del')")
    public R<Boolean> removeItemById(@RequestBody Long[] ids) {
        return R.ok(tpmCheckItemService.removeItemById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmCheckItem 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckItem_export')")
    @Operation(summary = "导出点/巡检项目", description = "导出点/巡检项目数据到Excel")
    public List<TpmCheckItemEntity> export(TpmCheckItemEntity tpmCheckItem, Long[] ids) {
        return tpmCheckItemService.list(Wrappers.lambdaQuery(tpmCheckItem).in(ArrayUtil.isNotEmpty(ids), TpmCheckItemEntity::getId, ids));
    }
}