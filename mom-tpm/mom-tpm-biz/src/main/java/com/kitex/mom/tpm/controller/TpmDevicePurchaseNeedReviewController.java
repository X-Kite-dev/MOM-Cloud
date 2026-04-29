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
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedReviewEntity;
import com.kitex.mom.tpm.service.TpmDevicePurchaseNeedReviewService;
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
 * 设备采购需求审批表
 *
 * @author kitex
 * @date 2025-03-26 10:10:02
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDevicePurchaseNeedReview")
@Tag(description = "tpmDevicePurchaseNeedReview", name = "设备采购需求审批表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDevicePurchaseNeedReviewController {

    private final TpmDevicePurchaseNeedReviewService tpmDevicePurchaseNeedReviewService;

    /**
     * 分页查询
     *
     * @param page                        分页对象
     * @param tpmDevicePurchaseNeedReview 设备采购需求审批表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeedReview_view')")
    public R<Page<TpmDevicePurchaseNeedReviewEntity>> getTpmDevicePurchaseNeedReviewPage(@ParameterObject SearchPage page, @ParameterObject TpmDevicePurchaseNeedReviewEntity tpmDevicePurchaseNeedReview) {
        LambdaQueryWrapper<TpmDevicePurchaseNeedReviewEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getState()), TpmDevicePurchaseNeedReviewEntity::getState, tpmDevicePurchaseNeedReview.getState());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getPurchaseNeedCode()), TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedCode, tpmDevicePurchaseNeedReview.getPurchaseNeedCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getPurchaseNeedName()), TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedName, tpmDevicePurchaseNeedReview.getPurchaseNeedName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getDeviceName()), TpmDevicePurchaseNeedReviewEntity::getDeviceName, tpmDevicePurchaseNeedReview.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getApplicationName()), TpmDevicePurchaseNeedReviewEntity::getApplicationName, tpmDevicePurchaseNeedReview.getApplicationName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getReviewName()), TpmDevicePurchaseNeedReviewEntity::getReviewName, tpmDevicePurchaseNeedReview.getReviewName());
        wrapper.orderByAsc(TpmDevicePurchaseNeedReviewEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmDevicePurchaseNeedReviewService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmDevicePurchaseNeedReviewEntity tpmDevicePurchaseNeedReview) {
        LambdaQueryWrapper<TpmDevicePurchaseNeedReviewEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getPurchaseNeedCode()), TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedCode, tpmDevicePurchaseNeedReview.getPurchaseNeedCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getPurchaseNeedName()), TpmDevicePurchaseNeedReviewEntity::getPurchaseNeedName, tpmDevicePurchaseNeedReview.getPurchaseNeedName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getDeviceName()), TpmDevicePurchaseNeedReviewEntity::getDeviceName, tpmDevicePurchaseNeedReview.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getApplicationName()), TpmDevicePurchaseNeedReviewEntity::getApplicationName, tpmDevicePurchaseNeedReview.getApplicationName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeedReview.getReviewName()), TpmDevicePurchaseNeedReviewEntity::getReviewName, tpmDevicePurchaseNeedReview.getReviewName());
        return R.ok(tpmDevicePurchaseNeedReviewService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmDevicePurchaseNeedReviewEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询设备采购需求审批表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeedReview_view')")
    public R<TpmDevicePurchaseNeedReviewEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmDevicePurchaseNeedReviewService.getById(id));
    }


    /**
     * 审核
     * type 0 否 1 是
     */
    @Operation(summary = "审核", description = "审核")
    @GetMapping("/approval")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeedReview_approval')")
    public R<Boolean> approval(@RequestParam("id") Long id,
                               @RequestParam("type") String type,
                               String rejectReason) {
        return R.ok(tpmDevicePurchaseNeedReviewService.approval(id, type, rejectReason));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDevicePurchaseNeedReview 查询条件
     * @param ids                         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeedReview_export')")
    public List<TpmDevicePurchaseNeedReviewEntity> export(TpmDevicePurchaseNeedReviewEntity tpmDevicePurchaseNeedReview, Long[] ids) {
        return tpmDevicePurchaseNeedReviewService.list(Wrappers.lambdaQuery(tpmDevicePurchaseNeedReview).in(ArrayUtil.isNotEmpty(ids), TpmDevicePurchaseNeedReviewEntity::getId, ids));
    }
}