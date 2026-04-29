package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.ApplyBySourceCodePageDto;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationDetailsEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;
import com.kitex.mom.tpm.enums.BillOperateTypeEnum;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationService;
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
 * 备件出入库申请主表
 *
 * @author kitex
 * @date 2025-03-18 10:32:15
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsApplication")
@Tag(description = "tpmSparePartsApplication", name = "备件出入库申请主表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsApplicationController {

    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;

    /**
     * 分页查询 - 申请退回
     */
    @Operation(summary = "分页查询 - 申请退回", description = "分页查询 - 申请退回")
    @GetMapping("/in/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsApplication_in_view')")
    public R<Page<TpmSparePartsApplicationEntity>> getTpmSparePartsApplicationInPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsApplicationEntity tpmSparePartsApplication) {
        LambdaQueryWrapper<TpmSparePartsApplicationEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsApplicationEntity::getType, BillOperateTypeEnum.IN.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getApplyCode()), TpmSparePartsApplicationEntity::getApplyCode, tpmSparePartsApplication.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getSourceCode()), TpmSparePartsApplicationEntity::getSourceCode, tpmSparePartsApplication.getSourceCode());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getSource()), TpmSparePartsApplicationEntity::getSource, tpmSparePartsApplication.getSource());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getRequesterName()), TpmSparePartsApplicationEntity::getRequesterName, tpmSparePartsApplication.getRequesterName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getApprovalStatus()), TpmSparePartsApplicationEntity::getApprovalStatus, tpmSparePartsApplication.getApprovalStatus());
        wrapper.orderByAsc(TpmSparePartsApplicationEntity::getApprovalStatus);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsApplicationService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 备件申请 - 统计
     */
    @Operation(summary = "分页查询 - 备件申请 - 统计", description = "分页查询 - 备件申请 - 统计")
    @GetMapping("/in/getCount")
    public R<Map<String, Long>> inGetCount(@ParameterObject TpmSparePartsApplicationEntity tpmSparePartsApplication) {
        LambdaQueryWrapper<TpmSparePartsApplicationEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsApplicationEntity::getType, BillOperateTypeEnum.IN.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getApplyCode()), TpmSparePartsApplicationEntity::getApplyCode, tpmSparePartsApplication.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getSourceCode()), TpmSparePartsApplicationEntity::getSourceCode, tpmSparePartsApplication.getSourceCode());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getSource()), TpmSparePartsApplicationEntity::getSource, tpmSparePartsApplication.getSource());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getRequesterName()), TpmSparePartsApplicationEntity::getRequesterName, tpmSparePartsApplication.getRequesterName());
        return R.ok(tpmSparePartsApplicationService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmSparePartsApplicationEntity::getApprovalStatus, Collectors.counting())));
    }


    /**
     * 分页查询 - 备件申请
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/out/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsApplication_out_view')")
    public R<Page<TpmSparePartsApplicationEntity>> getTpmSparePartsApplicationOutPage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsApplicationEntity tpmSparePartsApplication) {
        LambdaQueryWrapper<TpmSparePartsApplicationEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsApplicationEntity::getType, BillOperateTypeEnum.OUT.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getApplyCode()), TpmSparePartsApplicationEntity::getApplyCode, tpmSparePartsApplication.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getSourceCode()), TpmSparePartsApplicationEntity::getSourceCode, tpmSparePartsApplication.getSourceCode());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getSource()), TpmSparePartsApplicationEntity::getSource, tpmSparePartsApplication.getSource());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getRequesterName()), TpmSparePartsApplicationEntity::getRequesterName, tpmSparePartsApplication.getRequesterName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getApprovalStatus()), TpmSparePartsApplicationEntity::getApprovalStatus, tpmSparePartsApplication.getApprovalStatus());
        wrapper.orderByAsc(TpmSparePartsApplicationEntity::getApprovalStatus);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsApplicationService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 备件申请 - 统计
     */
    @Operation(summary = "分页查询 - 备件申请 - 统计", description = "分页查询 - 备件申请 - 统计")
    @GetMapping("/out/getCount")
    public R<Map<String, Long>> outGetCount(@ParameterObject TpmSparePartsApplicationEntity tpmSparePartsApplication) {
        LambdaQueryWrapper<TpmSparePartsApplicationEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmSparePartsApplicationEntity::getType, BillOperateTypeEnum.OUT.getCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getApplyCode()), TpmSparePartsApplicationEntity::getApplyCode, tpmSparePartsApplication.getApplyCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getSourceCode()), TpmSparePartsApplicationEntity::getSourceCode, tpmSparePartsApplication.getSourceCode());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsApplication.getSource()), TpmSparePartsApplicationEntity::getSource, tpmSparePartsApplication.getSource());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsApplication.getRequesterName()), TpmSparePartsApplicationEntity::getRequesterName, tpmSparePartsApplication.getRequesterName());
        return R.ok(tpmSparePartsApplicationService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmSparePartsApplicationEntity::getApprovalStatus, Collectors.counting())));
    }


    /**
     * 通过id查询备件出入库申请主表
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<TpmSparePartsApplicationEntity> getApplicationById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsApplicationService.getApplicationById(id));
    }

    /**
     * 通过来源编码 和 来源 查询所有备件出库申请单明细
     */
    @Operation(summary = "通过来源编码 和 来源 查询所有备件出库申请单明细", description = "通过来源编码 和 来源 查询所有备件出库申请单明细")
    @GetMapping("/out/getApplyBySourceCode")
    public R<List<TpmSparePartsApplicationDetailsEntity>> getApplyBySourceCode(@RequestParam("sourceCode") String sourceCode,
                                                                               @RequestParam("source") String source) {
        return R.ok(tpmSparePartsApplicationService.getApplyBySourceCode(sourceCode, source));
    }


    /**
     * 通过来源编码 和 来源 查询所有备件出库申请单明细分页 - 审批通过的
     */
    @Operation(summary = "通过来源编码 和 来源 查询所有备件出库申请单明细", description = "通过来源编码 和 来源 查询所有备件出库申请单明细")
    @GetMapping("/out/getApplyBySourceCode/page")
    public R<Page<TpmSparePartsApplicationDetailsEntity>> getApplyBySourceCodePage(@ParameterObject SearchPage page,
                                                                                   @ParameterObject ApplyBySourceCodePageDto dto) {
        return R.ok(tpmSparePartsApplicationService.getApplyBySourceCodePage(page, dto));
    }


    /**
     * 备件申请审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    @Operation(summary = "备件申请审批", description = "备件申请审批")
    @PostMapping("/out/approval/{handleType}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsApplication_out_approval')")
    public R<Boolean> outApproval(@RequestBody TpmSparePartsApplicationEntity application,
                                  @PathVariable("handleType") String handleType) {
        try {
            return R.ok(tpmSparePartsApplicationService.outApproval(application, handleType));
        } catch (RuntimeException e) {
            return R.failed(e.getMessage());
        }
    }


    /**
     * 申请退回审批
     *
     * @param handleType 0 审批不通过  1 审批通过
     */
    @Operation(summary = "申请退回审批", description = "申请退回审批")
    @PostMapping("/in/approval/{handleType}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsApplication_in_approval')")
    public R<Boolean> inApproval(@RequestBody TpmSparePartsApplicationEntity application,
                                 @PathVariable("handleType") String handleType) {
        return R.ok(tpmSparePartsApplicationService.inApproval(application, handleType));
    }


}