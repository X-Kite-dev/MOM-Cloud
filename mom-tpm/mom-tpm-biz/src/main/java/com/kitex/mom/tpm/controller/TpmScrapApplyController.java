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
import com.kitex.mom.tpm.entity.TpmScrapApplyEntity;
import com.kitex.mom.tpm.service.TpmScrapApplyService;
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
 * 设备报废申请表
 *
 * @author kitex
 * @date 2025-02-21 11:34:10
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmScrapApply")
@Tag(description = "tpmScrapApply", name = "设备报废申请表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmScrapApplyController {

    private final TpmScrapApplyService tpmScrapApplyService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param tpmScrapApply 设备报废申请表
     * @return
     */
    @Operation(summary = "报废申请分页查询", description = "分页查询设备报废申请列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_view')")
    public R<Page<TpmScrapApplyEntity>> getTpmScrapApplyPage(@ParameterObject SearchPage page, @ParameterObject TpmScrapApplyEntity tpmScrapApply) {
        LambdaQueryWrapper<TpmScrapApplyEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getApplyCode()), TpmScrapApplyEntity::getApplyCode, tpmScrapApply.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getDeviceCode()), TpmScrapApplyEntity::getDeviceCode, tpmScrapApply.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getDeviceName()), TpmScrapApplyEntity::getDeviceName, tpmScrapApply.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getReviewerName()), TpmScrapApplyEntity::getReviewerName, tpmScrapApply.getReviewerName());
        wrapper.eq(StrUtil.isNotBlank(tpmScrapApply.getState()), TpmScrapApplyEntity::getState, tpmScrapApply.getState());
        wrapper.orderByAsc(TpmScrapApplyEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmScrapApplyService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmScrapApplyEntity tpmScrapApply) {
        LambdaQueryWrapper<TpmScrapApplyEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getApplyCode()), TpmScrapApplyEntity::getApplyCode, tpmScrapApply.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getDeviceCode()), TpmScrapApplyEntity::getDeviceCode, tpmScrapApply.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getDeviceName()), TpmScrapApplyEntity::getDeviceName, tpmScrapApply.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmScrapApply.getReviewerName()), TpmScrapApplyEntity::getReviewerName, tpmScrapApply.getReviewerName());
        return R.ok(tpmScrapApplyService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmScrapApplyEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询设备报废申请表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询报废申请详情", description = "通过id查询设备报废申请详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_view')")
    public R<TpmScrapApplyEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmScrapApplyService.getById(id));
    }

    /**
     * 新增设备报废申请表
     *
     * @param tpmScrapApply 设备报废申请表
     * @return R
     */
    @Operation(summary = "新增报废申请", description = "新增设备报废申请")
    @SysLog("新增设备报废申请表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_add')")
    public R<Boolean> saveApply(@RequestBody TpmScrapApplyEntity tpmScrapApply) {
        return R.ok(tpmScrapApplyService.saveApply(tpmScrapApply));
    }

    /**
     * 修改设备报废申请表
     *
     * @param tpmScrapApply 设备报废申请表
     * @return R
     */
    @Operation(summary = "修改报废申请", description = "修改设备报废申请信息")
    @SysLog("修改设备报废申请表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_edit')")
    public R<Boolean> updateById(@RequestBody TpmScrapApplyEntity tpmScrapApply) {
        return R.ok(tpmScrapApplyService.updateById(tpmScrapApply));
    }


    /**
     * 批量通过 、作废
     *
     * @param ids  id集合
     * @param type 1 pass 2 cancel
     * @return
     */
    @Operation(summary = "批量处理报废申请", description = "批量通过或作废报废申请")
    @GetMapping("/handle")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_handle')")
    public R<Boolean> handle(@RequestParam("ids") List<Long> ids, @RequestParam("type") String type) {
        return R.ok(tpmScrapApplyService.handle(ids, type));
    }

    /**
     * 通过id删除设备报废申请表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除报废申请", description = "通过id删除设备报废申请")
    @SysLog("通过id删除设备报废申请表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmScrapApplyService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmScrapApply 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出报废申请", description = "导出设备报废申请数据到Excel")
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmScrapApply_export')")
    public List<TpmScrapApplyEntity> export(TpmScrapApplyEntity tpmScrapApply, Long[] ids) {
        return tpmScrapApplyService.list(Wrappers.lambdaQuery(tpmScrapApply).in(ArrayUtil.isNotEmpty(ids), TpmScrapApplyEntity::getId, ids));
    }
}