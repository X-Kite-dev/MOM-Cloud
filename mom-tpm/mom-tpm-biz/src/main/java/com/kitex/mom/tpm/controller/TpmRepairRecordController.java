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
import com.kitex.mom.tpm.entity.TpmRepairRecordEntity;
import com.kitex.mom.tpm.service.TpmRepairRecordService;
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
 * 维修记录表
 *
 * @author kitex
 * @date 2025-02-06 15:35:28
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmRepairRecord")
@Tag(description = "tpmRepairRecord", name = "维修记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmRepairRecordController {

    private final TpmRepairRecordService tpmRepairRecordService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param tpmRepairRecord 维修记录表
     * @return
     */
    @Operation(summary = "维修记录分页查询", description = "分页查询维修记录列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRecord_view')")
    public R<Page<TpmRepairRecordEntity>> getTpmRepairRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairRecordEntity tpmRepairRecord) {
        LambdaQueryWrapper<TpmRepairRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmRepairRecord.getTaskCode()), TpmRepairRecordEntity::getTaskCode, tpmRepairRecord.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairRecord.getDeviceName()), TpmRepairRecordEntity::getDeviceName, tpmRepairRecord.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairRecord.getFaultClassName()), TpmRepairRecordEntity::getFaultClassName, tpmRepairRecord.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairRecord.getSubmitByName()), TpmRepairRecordEntity::getSubmitByName, tpmRepairRecord.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairRecord.getRepairByName()), TpmRepairRecordEntity::getRepairByName, tpmRepairRecord.getRepairByName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmRepairRecordService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过id查询维修记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询维修记录详情", description = "通过id查询维修记录详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRecord_view')")
    public R<TpmRepairRecordEntity> getRecordById(@PathVariable("id") Long id) {
        return R.ok(tpmRepairRecordService.getRecordById(id));
    }

    /**
     * 导出excel 表格
     *
     * @param tpmRepairRecord 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairRecord_export')")
    @Operation(summary = "导出维修记录", description = "导出维修记录数据到Excel")
    public List<TpmRepairRecordEntity> export(TpmRepairRecordEntity tpmRepairRecord, Long[] ids) {
        return tpmRepairRecordService.list(Wrappers.lambdaQuery(tpmRepairRecord).in(ArrayUtil.isNotEmpty(ids), TpmRepairRecordEntity::getId, ids));
    }

    @PostMapping
    public R<Boolean> save(@RequestBody TpmRepairRecordEntity tpmRepairRecord) {
        return R.ok(tpmRepairRecordService.save(tpmRepairRecord));
    }
}