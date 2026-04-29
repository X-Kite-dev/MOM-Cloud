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
import com.kitex.mom.tpm.entity.TpmSparePartsMoveBillEntity;
import com.kitex.mom.tpm.service.TpmSparePartsMoveBillService;
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
 * 备件移库记录表
 *
 * @author kitex
 * @date 2025-02-11 16:48:31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsMoveBill")
@Tag(description = "tpmSparePartsMoveBill", name = "备件移库记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsMoveBillController {

    private final TpmSparePartsMoveBillService tpmSparePartsMoveBillService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param tpmSparePartsMoveBill 备件移库记录表
     * @return
     */
    @Operation(summary = "移库记录分页查询", description = "分页查询备件移库记录列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_view')")
    public R<Page<TpmSparePartsMoveBillEntity>> getTpmSparePartsMoveBillPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsMoveBillEntity tpmSparePartsMoveBill) {
        LambdaQueryWrapper<TpmSparePartsMoveBillEntity> wrapper = Wrappers.lambdaQuery();
        return R.ok(tpmSparePartsMoveBillService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询备件移库记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询移库记录详情", description = "通过id查询备件移库记录详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_view')")
    public R<TpmSparePartsMoveBillEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsMoveBillService.getById(id));
    }

    /**
     * 新增备件移库记录表
     *
     * @param tpmSparePartsMoveBill 备件移库记录表
     * @return R
     */
    @Operation(summary = "新增移库记录", description = "新增备件移库记录")
    @SysLog("新增备件移库记录表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_add')")
    public R<Boolean> save(@RequestBody TpmSparePartsMoveBillEntity tpmSparePartsMoveBill) {
        return R.ok(tpmSparePartsMoveBillService.save(tpmSparePartsMoveBill));
    }

    /**
     * 修改备件移库记录表
     *
     * @param tpmSparePartsMoveBill 备件移库记录表
     * @return R
     */
    @Operation(summary = "修改移库记录", description = "修改备件移库记录信息")
    @SysLog("修改备件移库记录表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_edit')")
    public R<Boolean> updateById(@RequestBody TpmSparePartsMoveBillEntity tpmSparePartsMoveBill) {
        return R.ok(tpmSparePartsMoveBillService.updateById(tpmSparePartsMoveBill));
    }

    /**
     * 通过id删除备件移库记录表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除移库记录", description = "通过id删除备件移库记录")
    @SysLog("通过id删除备件移库记录表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsMoveBillService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsMoveBill 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsMoveBill_export')")
    public List<TpmSparePartsMoveBillEntity> export(TpmSparePartsMoveBillEntity tpmSparePartsMoveBill, Long[] ids) {
        return tpmSparePartsMoveBillService.list(Wrappers.lambdaQuery(tpmSparePartsMoveBill).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsMoveBillEntity::getId, ids));
    }
}