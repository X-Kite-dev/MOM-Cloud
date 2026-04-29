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
import com.kitex.mom.tpm.entity.TpmSparePartsInOrOutRecordEntity;
import com.kitex.mom.tpm.service.TpmSparePartsInOrOutRecordService;
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
 * 备件出入库流水表
 *
 * @author kitex
 * @date 2025-02-11 16:44:34
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsInOrOutRecord")
@Tag(description = "tpmSparePartsInOrOutRecord", name = "备件出入库流水表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsInOrOutRecordController {

    private final TpmSparePartsInOrOutRecordService tpmSparePartsInOrOutRecordService;

    /**
     * 分页查询
     *
     * @param page                       分页对象
     * @param tpmSparePartsInOrOutRecord 备件出入库流水表
     * @return
     */
    @Operation(summary = "出入库流水分页查询", description = "分页查询备件出入库流水列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_view')")
    public R<Page<TpmSparePartsInOrOutRecordEntity>> getTpmSparePartsInOrOutRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsInOrOutRecordEntity tpmSparePartsInOrOutRecord) {
        LambdaQueryWrapper<TpmSparePartsInOrOutRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutRecord.getSparePartsCode()), TpmSparePartsInOrOutRecordEntity::getSparePartsCode, tpmSparePartsInOrOutRecord.getSparePartsCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutRecord.getSparePartsName()), TpmSparePartsInOrOutRecordEntity::getSparePartsName, tpmSparePartsInOrOutRecord.getSparePartsName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutRecord.getStoreroomName()), TpmSparePartsInOrOutRecordEntity::getStoreroomName, tpmSparePartsInOrOutRecord.getStoreroomName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsInOrOutRecord.getLocationName()), TpmSparePartsInOrOutRecordEntity::getLocationName, tpmSparePartsInOrOutRecord.getLocationName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsInOrOutRecord.getOperateType()), TpmSparePartsInOrOutRecordEntity::getOperateType, tpmSparePartsInOrOutRecord.getOperateType());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsInOrOutRecordService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询备件出入库流水表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询出入库流水详情", description = "通过id查询备件出入库流水详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_view')")
    public R<TpmSparePartsInOrOutRecordEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsInOrOutRecordService.getById(id));
    }

    /**
     * 新增备件出入库流水表
     *
     * @param tpmSparePartsInOrOutRecord 备件出入库流水表
     * @return R
     */
    @Operation(summary = "新增出入库流水", description = "新增备件出入库流水记录")
    @SysLog("新增备件出入库流水表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_add')")
    public R<Boolean> save(@RequestBody TpmSparePartsInOrOutRecordEntity tpmSparePartsInOrOutRecord) {
        return R.ok(tpmSparePartsInOrOutRecordService.save(tpmSparePartsInOrOutRecord));
    }

    /**
     * 修改备件出入库流水表
     *
     * @param tpmSparePartsInOrOutRecord 备件出入库流水表
     * @return R
     */
    @Operation(summary = "修改出入库流水", description = "修改备件出入库流水信息")
    @SysLog("修改备件出入库流水表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_edit')")
    public R<Boolean> updateById(@RequestBody TpmSparePartsInOrOutRecordEntity tpmSparePartsInOrOutRecord) {
        return R.ok(tpmSparePartsInOrOutRecordService.updateById(tpmSparePartsInOrOutRecord));
    }

    /**
     * 通过id删除备件出入库流水表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除出入库流水", description = "通过id删除备件出入库流水")
    @SysLog("通过id删除备件出入库流水表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmSparePartsInOrOutRecordService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmSparePartsInOrOutRecord 查询条件
     * @param ids                        导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出出入库流水", description = "导出备件出入库流水数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsInOrOutRecord_export')")
    public List<TpmSparePartsInOrOutRecordEntity> export(TpmSparePartsInOrOutRecordEntity tpmSparePartsInOrOutRecord, Long[] ids) {
        return tpmSparePartsInOrOutRecordService.list(Wrappers.lambdaQuery(tpmSparePartsInOrOutRecord).in(ArrayUtil.isNotEmpty(ids), TpmSparePartsInOrOutRecordEntity::getId, ids));
    }
}