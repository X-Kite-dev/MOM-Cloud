package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionRecordEntity;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionRecordService;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备到货质检记录表
 *
 * @author kitex
 * @date 2025-03-26 10:25:25
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceArrivalInspectionRecord")
@Tag(description = "tpmDeviceArrivalInspectionRecord", name = "设备到货质检记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceArrivalInspectionRecordController {

    private final TpmDeviceArrivalInspectionRecordService tpmDeviceArrivalInspectionRecordService;

    /**
     * 分页查询
     *
     * @param page                             分页对象
     * @param tpmDeviceArrivalInspectionRecord 设备到货质检记录表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspectionRecord_view')")
    public R<Page<TpmDeviceArrivalInspectionRecordEntity>> getTpmDeviceArrivalInspectionRecordPage(@ParameterObject SearchPage page, @ParameterObject TpmDeviceArrivalInspectionRecordEntity tpmDeviceArrivalInspectionRecord) {
        LambdaQueryWrapper<TpmDeviceArrivalInspectionRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getQcCode()), TpmDeviceArrivalInspectionRecordEntity::getQcCode, tpmDeviceArrivalInspectionRecord.getQcCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchaseCode()), TpmDeviceArrivalInspectionRecordEntity::getPurchaseCode, tpmDeviceArrivalInspectionRecord.getPurchaseCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchaseName()), TpmDeviceArrivalInspectionRecordEntity::getPurchaseName, tpmDeviceArrivalInspectionRecord.getPurchaseName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchasePersonName()), TpmDeviceArrivalInspectionRecordEntity::getPurchasePersonName, tpmDeviceArrivalInspectionRecord.getPurchasePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getQcPersonName()), TpmDeviceArrivalInspectionRecordEntity::getQcPersonName, tpmDeviceArrivalInspectionRecord.getQcPersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getState()), TpmDeviceArrivalInspectionRecordEntity::getState, tpmDeviceArrivalInspectionRecord.getState());
        wrapper.orderByAsc(TpmDeviceArrivalInspectionRecordEntity::getState);
        wrapper.orderByDesc(TpmDeviceArrivalInspectionRecordEntity::getCreateTime);
        return R.ok(tpmDeviceArrivalInspectionRecordService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmDeviceArrivalInspectionRecordEntity tpmDeviceArrivalInspectionRecord) {
        LambdaQueryWrapper<TpmDeviceArrivalInspectionRecordEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getQcCode()), TpmDeviceArrivalInspectionRecordEntity::getQcCode, tpmDeviceArrivalInspectionRecord.getQcCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchaseCode()), TpmDeviceArrivalInspectionRecordEntity::getPurchaseCode, tpmDeviceArrivalInspectionRecord.getPurchaseCode());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchaseName()), TpmDeviceArrivalInspectionRecordEntity::getPurchaseName, tpmDeviceArrivalInspectionRecord.getPurchaseName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getPurchasePersonName()), TpmDeviceArrivalInspectionRecordEntity::getPurchasePersonName, tpmDeviceArrivalInspectionRecord.getPurchasePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDeviceArrivalInspectionRecord.getQcPersonName()), TpmDeviceArrivalInspectionRecordEntity::getQcPersonName, tpmDeviceArrivalInspectionRecord.getQcPersonName());
        return R.ok(tpmDeviceArrivalInspectionRecordService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmDeviceArrivalInspectionRecordEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询设备到货质检记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspectionRecord_view')")
    public R<TpmDeviceArrivalInspectionRecordEntity> getDeepById(@PathVariable("id") Long id) {
        return R.ok(tpmDeviceArrivalInspectionRecordService.getDeepById(id));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDeviceArrivalInspectionRecord 查询条件
     * @param ids                              导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceArrivalInspectionRecord_export')")
    public List<TpmDeviceArrivalInspectionRecordEntity> export(TpmDeviceArrivalInspectionRecordEntity tpmDeviceArrivalInspectionRecord, Long[] ids) {
        return tpmDeviceArrivalInspectionRecordService.list(Wrappers.lambdaQuery(tpmDeviceArrivalInspectionRecord).in(ArrayUtil.isNotEmpty(ids), TpmDeviceArrivalInspectionRecordEntity::getId, ids));
    }
}