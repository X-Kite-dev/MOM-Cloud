package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsReplaceEntity;
import com.kitex.mom.tpm.service.TpmSparePartsApplicationService;
import com.kitex.mom.tpm.service.TpmSparePartsReplaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * TPM备件更换审批单据表
 *
 * @author kitex
 * @date 2025-03-18 10:27:11
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmSparePartsReplace")
@Tag(description = "tpmSparePartsReplace", name = "TPM备件更换审批单据表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmSparePartsReplaceController {

    private final TpmSparePartsReplaceService tpmSparePartsReplaceService;

    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;

    /**
     * 分页查询
     *
     * @param page                 分页对象
     * @param tpmSparePartsReplace TPM备件更换审批单据表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsReplace_view')")
    public R getTpmSparePartsReplacePage(@ParameterObject SearchPage page, @ParameterObject TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        LambdaQueryWrapper<TpmSparePartsReplaceEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDocSn()), TpmSparePartsReplaceEntity::getDocSn, tpmSparePartsReplace.getDocSn());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDeviceCode()), TpmSparePartsReplaceEntity::getDeviceCode, tpmSparePartsReplace.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDeviceName()), TpmSparePartsReplaceEntity::getDeviceName, tpmSparePartsReplace.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getSparePartCode()), TpmSparePartsReplaceEntity::getSparePartCode, tpmSparePartsReplace.getSparePartCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getSparePartName()), TpmSparePartsReplaceEntity::getSparePartName, tpmSparePartsReplace.getSparePartName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getOperatorName()), TpmSparePartsReplaceEntity::getOperatorName, tpmSparePartsReplace.getOperatorName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getApplicantName()), TpmSparePartsReplaceEntity::getApplicantName, tpmSparePartsReplace.getApplicantName());
        wrapper.eq(StrUtil.isNotBlank(tpmSparePartsReplace.getStatus()), TpmSparePartsReplaceEntity::getStatus, tpmSparePartsReplace.getStatus());
        wrapper.orderByAsc(TpmSparePartsReplaceEntity::getStatus);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmSparePartsReplaceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 统计
     */
    @Operation(summary = "分页查询 - 统计", description = "分页查询 - 统计")
    @GetMapping("/getCount")
    public R getCount(@ParameterObject TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        LambdaQueryWrapper<TpmSparePartsReplaceEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDocSn()), TpmSparePartsReplaceEntity::getDocSn, tpmSparePartsReplace.getDocSn());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDeviceCode()), TpmSparePartsReplaceEntity::getDeviceCode, tpmSparePartsReplace.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getDeviceName()), TpmSparePartsReplaceEntity::getDeviceName, tpmSparePartsReplace.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getSparePartCode()), TpmSparePartsReplaceEntity::getSparePartCode, tpmSparePartsReplace.getSparePartCode());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getSparePartName()), TpmSparePartsReplaceEntity::getSparePartName, tpmSparePartsReplace.getSparePartName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getOperatorName()), TpmSparePartsReplaceEntity::getOperatorName, tpmSparePartsReplace.getOperatorName());
        wrapper.like(StrUtil.isNotBlank(tpmSparePartsReplace.getApplicantName()), TpmSparePartsReplaceEntity::getApplicantName, tpmSparePartsReplace.getApplicantName());
        return R.ok(tpmSparePartsReplaceService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmSparePartsReplaceEntity::getStatus, Collectors.counting())));
    }


    /**
     * 通过id查询TPM备件更换审批
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsReplace_view')")
    public R getReplaceById(@PathVariable("id") Long id) {
        return R.ok(tpmSparePartsReplaceService.getReplaceById(id));
    }

    /**
     * 新增TPM备件更换审批单据表
     *
     * @param tpmSparePartsReplace TPM备件更换审批单据表
     * @return R
     */
    @Operation(summary = "新增TPM备件更换审批单据表", description = "新增TPM备件更换审批单据表")
    @SysLog("新增TPM备件更换审批单据表")
    @PostMapping
    public R saveReplace(@RequestBody TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        return R.ok(tpmSparePartsReplaceService.saveReplace(tpmSparePartsReplace));
    }

    /**
     * 申请备件
     */
    @Operation(summary = "申请备件", description = "申请备件")
    @PostMapping("/addApplication")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsReplace_apply')")
    public R<Boolean> addApplication(@RequestBody TpmSparePartsApplicationEntity entity) {
        return R.ok(tpmSparePartsApplicationService.addOutApplication(entity));
    }


    /**
     * 处理
     */
    @Operation(summary = "处理", description = "处理")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('tpm_tpmSparePartsReplace_handle')")
    public R<Boolean> handle(@RequestBody TpmSparePartsReplaceEntity tpmSparePartsReplace) {
        return R.ok(tpmSparePartsReplaceService.handle(tpmSparePartsReplace));
    }


}