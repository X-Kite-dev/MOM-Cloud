package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseBaseEntity;
import com.kitex.mom.tpm.service.TpmDevicePurchaseBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备采购单表
 *
 * @author kitex
 * @date 2025-03-26 10:13:32
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDevicePurchaseBase")
@Tag(description = "tpmDevicePurchaseBase", name = "设备采购单表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDevicePurchaseBaseController {

    private final TpmDevicePurchaseBaseService tpmDevicePurchaseBaseService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param tpmDevicePurchaseBase 设备采购单表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseBase_view')")
    public R<Page<TpmDevicePurchaseBaseEntity>> getTpmDevicePurchaseBasePage(@ParameterObject SearchPage page, @ParameterObject TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase) {
        LambdaQueryWrapper<TpmDevicePurchaseBaseEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchaseCode()), TpmDevicePurchaseBaseEntity::getPurchaseCode, tpmDevicePurchaseBase.getPurchaseCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchaseName()), TpmDevicePurchaseBaseEntity::getPurchaseName, tpmDevicePurchaseBase.getPurchaseName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchasePersonName()), TpmDevicePurchaseBaseEntity::getPurchasePersonName, tpmDevicePurchaseBase.getPurchasePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getSupplierName()), TpmDevicePurchaseBaseEntity::getSupplierName, tpmDevicePurchaseBase.getSupplierName());
        wrapper.eq(StrUtil.isNotBlank(tpmDevicePurchaseBase.getState()), TpmDevicePurchaseBaseEntity::getState, tpmDevicePurchaseBase.getState());
        wrapper.orderByAsc(TpmDevicePurchaseBaseEntity::getState);
        wrapper.orderByDesc(TpmDevicePurchaseBaseEntity::getCreateTime);
        return R.ok(tpmDevicePurchaseBaseService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase) {
        LambdaQueryWrapper<TpmDevicePurchaseBaseEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchaseCode()), TpmDevicePurchaseBaseEntity::getPurchaseCode, tpmDevicePurchaseBase.getPurchaseCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchaseName()), TpmDevicePurchaseBaseEntity::getPurchaseName, tpmDevicePurchaseBase.getPurchaseName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getPurchasePersonName()), TpmDevicePurchaseBaseEntity::getPurchasePersonName, tpmDevicePurchaseBase.getPurchasePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseBase.getSupplierName()), TpmDevicePurchaseBaseEntity::getSupplierName, tpmDevicePurchaseBase.getSupplierName());
        return R.ok(tpmDevicePurchaseBaseService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmDevicePurchaseBaseEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询设备采购单表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseBase_view')")
    public R<TpmDevicePurchaseBaseEntity> getDeepById(@PathVariable("id") Long id) {
        return R.ok(tpmDevicePurchaseBaseService.getDeepById(id));
    }

    /**
     * 生成设备采购单表
     */
    @Operation(summary = "生成设备采购单表", description = "生成设备采购单表")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseBase_add,tpm_tpmDevicePurchaseNeedReview_create')")
    public R savePurchaseBase(@RequestBody TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase) {
        return R.ok(tpmDevicePurchaseBaseService.savePurchaseBase(tpmDevicePurchaseBase));
    }


    /**
     * 推送质检
     */
    @Operation(summary = "推送质检", description = "推送质检")
    @GetMapping("/pushQc/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseBase_push')")
    public R<Boolean> pushQc(@PathVariable("id") Long id) {
        return R.ok(tpmDevicePurchaseBaseService.pushQc(id));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDevicePurchaseBase 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseBase_export')")
    public List<TpmDevicePurchaseBaseEntity> export(TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase, Long[] ids) {
        return tpmDevicePurchaseBaseService.list(Wrappers.lambdaQuery(tpmDevicePurchaseBase).in(ArrayUtil.isNotEmpty(ids), TpmDevicePurchaseBaseEntity::getId, ids));
    }
}