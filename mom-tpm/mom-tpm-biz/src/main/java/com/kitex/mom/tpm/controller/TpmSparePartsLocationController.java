package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmSparePartsLocationEntity;
import com.kitex.mom.tpm.service.TpmSparePartsLocationService;
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
 * 备件库位表
 *
 * @author kitex
 * @date 2025-02-11 16:46:35
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsLocation")
@Tag(description = "tpmSparePartsLocation", name = "备件库位表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsLocationController {

    private final TpmSparePartsLocationService tpmSparePartsLocationService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param tpmSparePartsLocation 备件库位表
     * @return
     */
    @Operation(summary = "库位分页查询", description = "分页查询备件库位列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_view')")
    public R<Page<TpmSparePartsLocationEntity>> getTpmSparePartsLocationPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsLocationEntity tpmSparePartsLocation) {
        return R.ok(tpmSparePartsLocationService.pageList(page.toMpPage(), tpmSparePartsLocation));
    }


    /**
     * 通过id查询备件库位表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询库位详情", description = "通过id查询备件库位详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_view')")
    public R<TpmSparePartsLocationEntity> getLocationById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsLocationService.getLocationById(id));
    }

    /**
     * 新增备件库位表
     *
     * @param tpmSparePartsLocation 备件库位表
     * @return R
     */
    @Operation(summary = "新增库位", description = "新增备件库位信息")
    @SysLog("新增备件库位表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_add')")
    public R<Boolean> save(@RequestBody TpmSparePartsLocationEntity tpmSparePartsLocation) {
        Long count = tpmSparePartsLocationService.count(new LambdaQueryWrapper<TpmSparePartsLocationEntity>()
                .eq(TpmSparePartsLocationEntity::getCode, tpmSparePartsLocation.getCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复的库位编码！");
        }
        return R.ok(tpmSparePartsLocationService.save(tpmSparePartsLocation));
    }

    /**
     * 修改备件库位表
     *
     * @param tpmSparePartsLocation 备件库位表
     * @return R
     */
    @Operation(summary = "修改库位", description = "修改备件库位信息")
    @SysLog("修改备件库位表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_edit')")
    public R<Boolean> updateById(@RequestBody TpmSparePartsLocationEntity tpmSparePartsLocation) {
        return R.ok(tpmSparePartsLocationService.updateById(tpmSparePartsLocation));
    }

    /**
     * 通过id删除备件库位表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除库位", description = "通过id删除备件库位")
    @SysLog("通过id删除备件库位表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_del')")
    public R<Boolean> removeLocationById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsLocationService.removeLocationById(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsLocation 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsLocation_export')")
    public List<TpmSparePartsLocationEntity> export(TpmSparePartsLocationEntity tpmSparePartsLocation, Long[] ids) {
        return tpmSparePartsLocationService.list(Wrappers.lambdaQuery(tpmSparePartsLocation).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsLocationEntity::getId, ids));
    }
}