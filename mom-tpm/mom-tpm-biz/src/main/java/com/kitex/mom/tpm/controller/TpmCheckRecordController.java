package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.tpm.entity.TpmCheckRecordEntity;
import com.kitex.mom.tpm.service.TpmCheckRecordService;
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
 * 点/巡检记录
 *
 * @author kitex
 * @date 2025-02-18 17:46:05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmCheckRecord")
@Tag(description = "tpmCheckRecord", name = "点/巡检记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmCheckRecordController {

    private final TpmCheckRecordService tpmCheckRecordService;

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param tpmCheckRecord 点/巡检记录
     * @return
     */
    @Operation(summary = "点/巡检记录分页查询", description = "点/巡检记录分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckRecord_view')")
    public R<Page<TpmCheckRecordEntity>> getTpmCheckRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmCheckRecordEntity tpmCheckRecord) {
        LambdaQueryWrapper<TpmCheckRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmCheckRecord.getTaskCode()), TpmCheckRecordEntity::getTaskCode, tpmCheckRecord.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckRecord.getType()), TpmCheckRecordEntity::getType, tpmCheckRecord.getType());
        wrapper.like(StrUtil.isNotBlank(tpmCheckRecord.getPlanName()), TpmCheckRecordEntity::getPlanName, tpmCheckRecord.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckRecord.getDeviceCode()), TpmCheckRecordEntity::getDeviceCode, tpmCheckRecord.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmCheckRecord.getDeviceName()), TpmCheckRecordEntity::getDeviceName, tpmCheckRecord.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckRecord.getHandlePersonName()), TpmCheckRecordEntity::getHandlePersonName, tpmCheckRecord.getHandlePersonName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmCheckRecordService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询点/巡检记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询点/巡检记录", description = "通过id查询点/巡检记录")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckRecord_view')")
    public R<TpmCheckRecordEntity> getRecordById(@PathVariable("id") Long id) {
        return R.ok(tpmCheckRecordService.getRecordById(id));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmCheckRecord 查询条件
     * @param ids            导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckRecord_export')")
    @Operation(summary = "导出点/巡检记录", description = "导出点/巡检记录数据到Excel")
    public List<TpmCheckRecordEntity> export(TpmCheckRecordEntity tpmCheckRecord, Long[] ids) {
        return tpmCheckRecordService.list(Wrappers.lambdaQuery(tpmCheckRecord).in(ArrayUtil.isNotEmpty(ids), TpmCheckRecordEntity::getId, ids));
    }
}