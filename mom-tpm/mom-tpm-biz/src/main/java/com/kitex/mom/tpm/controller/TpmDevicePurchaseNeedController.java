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
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedEntity;
import com.kitex.mom.tpm.service.TpmDevicePurchaseNeedService;
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
 * 设备采购需求表
 *
 * @author kitex
 * @date 2025-03-26 10:04:27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDevicePurchaseNeed")
@Tag(description = "tpmDevicePurchaseNeed", name = "设备采购需求表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDevicePurchaseNeedController {

    private final TpmDevicePurchaseNeedService tpmDevicePurchaseNeedService;

    /**
     * 分页查询
     *
     * @param page                  分页对象
     * @param tpmDevicePurchaseNeed 设备采购需求表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_view')")
    public R<Page<TpmDevicePurchaseNeedEntity>> getTpmDevicePurchaseNeedPage(@ParameterObject SearchPage page, @ParameterObject TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        LambdaQueryWrapper<TpmDevicePurchaseNeedEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getPurchaseNeedCode()), TpmDevicePurchaseNeedEntity::getPurchaseNeedCode, tpmDevicePurchaseNeed.getPurchaseNeedCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getPurchaseNeedName()), TpmDevicePurchaseNeedEntity::getPurchaseNeedName, tpmDevicePurchaseNeed.getPurchaseNeedName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getDeviceName()), TpmDevicePurchaseNeedEntity::getDeviceName, tpmDevicePurchaseNeed.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getApplicationName()), TpmDevicePurchaseNeedEntity::getApplicationName, tpmDevicePurchaseNeed.getApplicationName());
        wrapper.eq(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getState()), TpmDevicePurchaseNeedEntity::getState, tpmDevicePurchaseNeed.getState());
        wrapper.orderByAsc(TpmDevicePurchaseNeedEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmDevicePurchaseNeedService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject SearchPage page, @ParameterObject TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        LambdaQueryWrapper<TpmDevicePurchaseNeedEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getPurchaseNeedCode()), TpmDevicePurchaseNeedEntity::getPurchaseNeedCode, tpmDevicePurchaseNeed.getPurchaseNeedCode());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getPurchaseNeedName()), TpmDevicePurchaseNeedEntity::getPurchaseNeedName, tpmDevicePurchaseNeed.getPurchaseNeedName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getDeviceName()), TpmDevicePurchaseNeedEntity::getDeviceName, tpmDevicePurchaseNeed.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmDevicePurchaseNeed.getApplicationName()), TpmDevicePurchaseNeedEntity::getApplicationName, tpmDevicePurchaseNeed.getApplicationName());
        return R.ok(tpmDevicePurchaseNeedService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmDevicePurchaseNeedEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询设备采购需求表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_view')")
    public R<TpmDevicePurchaseNeedEntity> getById(@PathVariable("id") Long id) {
        return R.ok(tpmDevicePurchaseNeedService.getById(id));
    }

    /**
     * 新增设备采购需求表
     *
     * @param tpmDevicePurchaseNeed 设备采购需求表
     * @return R
     */
    @Operation(summary = "新增设备采购需求表", description = "新增设备采购需求表")
    @SysLog("新增设备采购需求表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_add')")
    public R<Boolean> saveNeed(@RequestBody TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        return R.ok(tpmDevicePurchaseNeedService.saveNeed(tpmDevicePurchaseNeed));
    }

    /**
     * 修改设备采购需求表
     *
     * @param tpmDevicePurchaseNeed 设备采购需求表
     * @return R
     */
    @Operation(summary = "修改设备采购需求表", description = "修改设备采购需求表")
    @SysLog("修改设备采购需求表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_edit')")
    public R<Boolean> updateNeedById(@RequestBody TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed) {
        return R.ok(tpmDevicePurchaseNeedService.updateNeedById(tpmDevicePurchaseNeed));
    }

    /**
     * 通过id删除设备采购需求表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除设备采购需求表", description = "通过id删除设备采购需求表")
    @SysLog("通过id删除设备采购需求表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmDevicePurchaseNeedService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDevicePurchaseNeed 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_export')")
    public List<TpmDevicePurchaseNeedEntity> export(TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed, Long[] ids) {
        return tpmDevicePurchaseNeedService.list(Wrappers.lambdaQuery(tpmDevicePurchaseNeed).in(ArrayUtil.isNotEmpty(ids), TpmDevicePurchaseNeedEntity::getId, ids));
    }

    /**
     * 提交审批
     */
    @Operation(summary = "提交审批", description = "提交审批")
    @PostMapping("/commit")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDevicePurchaseNeed_commit')")
    public R<Boolean> commitById(@RequestBody List<Long> ids) {
        return R.ok(tpmDevicePurchaseNeedService.commitById(ids));
    }


}