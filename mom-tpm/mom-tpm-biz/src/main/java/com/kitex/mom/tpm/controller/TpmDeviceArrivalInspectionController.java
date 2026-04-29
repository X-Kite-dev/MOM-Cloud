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
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionEntity;
import com.kitex.mom.tpm.enums.PurchaseQcStateEnum;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionService;
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
 * 设备到货质检表
 *
 * @author kitex
 * @date 2025-03-26 10:21:32
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceArrivalInspection")
@Tag(description = "tpmDeviceArrivalInspection", name = "设备到货质检表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceArrivalInspectionController {

    private final TpmDeviceArrivalInspectionService tpmDeviceArrivalInspectionService;

    /**
     * 分页查询
     *
     * @param page                       分页对象
     * @param tpmDeviceArrivalInspection 设备到货质检表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspection_view')")
    public R<Page<TpmDeviceArrivalInspectionEntity>> getTpmDeviceArrivalInspectionPage(@ParameterObject SearchPage page, @ParameterObject TpmDeviceArrivalInspectionEntity tpmDeviceArrivalInspection) {
        LambdaQueryWrapper<TpmDeviceArrivalInspectionEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspection.getQcCode()), TpmDeviceArrivalInspectionEntity::getQcCode, tpmDeviceArrivalInspection.getQcCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspection.getPurchaseCode()), TpmDeviceArrivalInspectionEntity::getPurchaseCode, tpmDeviceArrivalInspection.getPurchaseCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspection.getPurchaseName()), TpmDeviceArrivalInspectionEntity::getPurchaseName, tpmDeviceArrivalInspection.getPurchaseName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspection.getPurchasePersonName()), TpmDeviceArrivalInspectionEntity::getPurchasePersonName, tpmDeviceArrivalInspection.getPurchasePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspection.getQcPersonName()), TpmDeviceArrivalInspectionEntity::getQcPersonName, tpmDeviceArrivalInspection.getQcPersonName());
        wrapper.eq(TpmDeviceArrivalInspectionEntity::getState, PurchaseQcStateEnum.WAIT_QC.getCode());
        wrapper.orderByAsc(TpmDeviceArrivalInspectionEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmDeviceArrivalInspectionService.page(page.toMpPage(), wrapper));
    }


    /**
     * 通过id查询设备到货质检表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspection_view')")
    public R<TpmDeviceArrivalInspectionEntity> getDeepById(@PathVariable("id") Long id) {
        return R.ok(tpmDeviceArrivalInspectionService.getDeepById(id));
    }

    /**
     * 审核
     * type 0 否  1是
     */
    @Operation(summary = "审核", description = "审核")
    @PostMapping("/approval")
//    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspection_approval')")
    public R<Boolean> approval(@RequestBody TpmDeviceArrivalInspectionEntity entity) {
        try {
            return R.ok(tpmDeviceArrivalInspectionService.approval(entity));
        } catch (RuntimeException e) {
            return R.failed(e.getMessage());
        }
    }


    /**
     * 新增设备到货质检表
     */
    @Operation(summary = "新增设备到货质检表", description = "新增设备到货质检表")
    @SysLog("新增设备到货质检表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspection_add')")
    public R<Boolean> save(@RequestBody TpmDeviceArrivalInspectionEntity tpmDeviceArrivalInspection) {
        return R.ok(tpmDeviceArrivalInspectionService.save(tpmDeviceArrivalInspection));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDeviceArrivalInspection 查询条件
     * @param ids                        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspection_export')")
    public List<TpmDeviceArrivalInspectionEntity> export(TpmDeviceArrivalInspectionEntity tpmDeviceArrivalInspection, Long[] ids) {
        return tpmDeviceArrivalInspectionService.list(Wrappers.lambdaQuery(tpmDeviceArrivalInspection).in(ArrayUtil.isNotEmpty(ids), TpmDeviceArrivalInspectionEntity::getId, ids));
    }
}