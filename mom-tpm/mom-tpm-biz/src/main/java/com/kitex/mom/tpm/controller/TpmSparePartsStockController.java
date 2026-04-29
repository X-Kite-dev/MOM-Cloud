package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmSparePartsStockEntity;
import com.kitex.mom.tpm.service.TpmSparePartsStockService;
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
 * 备件库存表
 *
 * @author kitex
 * @date 2025-02-11 16:51:06
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsStock")
@Tag(description = "tpmSparePartsStock", name = "备件库存表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsStockController {

    private final TpmSparePartsStockService tpmSparePartsStockService;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param tpmSparePartsStock 备件库存表
     * @return
     */
    @Operation(summary = "库存分页查询", description = "分页查询备件库存列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_view')")
    public R<Page<TpmSparePartsStockEntity>> getTpmSparePartsStockPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsStockEntity tpmSparePartsStock) {
        return R.ok(tpmSparePartsStockService.pageList(page.toMpPage(), tpmSparePartsStock));
    }


    @PostMapping("/list")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_view')")
    @Operation(summary = "查询库存列表", description = "根据条件查询备件库存列表")
    public R getTpmSparePartsStockList(@RequestBody TpmSparePartsStockEntity tpmSparePartsStock) {
        return R.ok(tpmSparePartsStockService.getTpmSparePartsStockList(tpmSparePartsStock.getIds()));
    }


    /**
     * 通过id查询备件库存表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询库存详情", description = "通过id查询备件库存详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_view')")
    public R getStockById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsStockService.getStockById(id));
    }

    /**
     * 新增备件库存表
     *
     * @param tpmSparePartsStock 备件库存表
     * @return R
     */
    @Operation(summary = "新增库存", description = "新增备件库存信息")
    @SysLog("新增备件库存表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_add')")
    public R save(@RequestBody TpmSparePartsStockEntity tpmSparePartsStock) {
        return R.ok(tpmSparePartsStockService.save(tpmSparePartsStock));
    }

    /**
     * 修改备件库存表
     *
     * @param tpmSparePartsStock 备件库存表
     * @return R
     */
    @Operation(summary = "修改库存", description = "修改备件库存信息")
    @SysLog("修改备件库存表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_edit')")
    public R updateById(@RequestBody TpmSparePartsStockEntity tpmSparePartsStock) {
        return R.ok(tpmSparePartsStockService.updateById(tpmSparePartsStock));
    }

    /**
     * 通过id删除备件库存表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除库存", description = "通过id删除备件库存")
    @SysLog("通过id删除备件库存表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsStockService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsStock 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsStock_export')")
    @Operation(summary = "导出库存", description = "导出备件库存数据到Excel")
    public List<TpmSparePartsStockEntity> export(TpmSparePartsStockEntity tpmSparePartsStock, Long[] ids) {
        return tpmSparePartsStockService.list(Wrappers.lambdaQuery(tpmSparePartsStock).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsStockEntity::getId, ids));
    }
}