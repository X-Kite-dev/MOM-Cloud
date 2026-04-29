package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.tpm.entity.TpmScrapRecordEntity;
import com.kitex.mom.tpm.service.TpmScrapRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备报废记录表
 *
 * @author kitex
 * @date 2025-02-21 11:43:16
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmScrapRecord")
@Tag(description = "tpmScrapRecord", name = "设备报废记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmScrapRecordController {

    private final TpmScrapRecordService tpmScrapRecordService;

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param tpmScrapRecord 设备报废记录表
     * @return
     */
    @Operation(summary = "报废记录分页查询", description = "分页查询设备报废记录列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapRecord_view')")
    public R<Page<TpmScrapRecordEntity>> getTpmScrapRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmScrapRecordEntity tpmScrapRecord) {
        LambdaQueryWrapper<TpmScrapRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmScrapRecord.getApplyCode()), TpmScrapRecordEntity::getApplyCode, tpmScrapRecord.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapRecord.getDeviceCode()), TpmScrapRecordEntity::getDeviceCode, tpmScrapRecord.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapRecord.getDeviceName()), TpmScrapRecordEntity::getDeviceName, tpmScrapRecord.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmScrapRecord.getClassName()), TpmScrapRecordEntity::getClassName, tpmScrapRecord.getClassName());
        return R.ok(tpmScrapRecordService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询设备报废记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询报废记录详情", description = "通过id查询设备报废记录详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapRecord_view')")
    public R<TpmScrapRecordEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmScrapRecordService.getById(id));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmScrapRecord 查询条件
     * @param ids            导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出报废记录", description = "导出设备报废记录数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapRecord_export')")
    public List<TpmScrapRecordEntity> export(TpmScrapRecordEntity tpmScrapRecord, Long[] ids) {
        return tpmScrapRecordService.list(Wrappers.lambdaQuery(tpmScrapRecord).in(ArrayUtil.isNotEmpty(ids), TpmScrapRecordEntity::getId, ids));
    }
}