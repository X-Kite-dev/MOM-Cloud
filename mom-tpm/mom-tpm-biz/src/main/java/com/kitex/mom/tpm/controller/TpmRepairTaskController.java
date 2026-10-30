package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsApplicationEntity;
import com.kitex.mom.tpm.enums.RepairTaskTypeEnum;
import com.kitex.mom.tpm.service.TpmRepairTaskService;
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
 * 维修任务表
 *
 * @author kitex
 * @date 2025-02-06 16:06:41
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmRepairTask")
@Tag(description = "tpmRepairTask", name = "维修任务表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmRepairTaskController {

    private final TpmRepairTaskService tpmRepairTaskService;

    private final TpmSparePartsApplicationService tpmSparePartsApplicationService;

    /**
     * 分页查询
     */
    @Operation(summary = "维修任务分页查询", description = "分页查询维修任务列表")
    @GetMapping("/page")
    public R<Page<TpmRepairTaskEntity>> getTpmRepairTaskPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairTaskEntity tpmRepairTask) {
        LambdaQueryWrapper<TpmRepairTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getDeviceCode()), TpmRepairTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getTaskCode()), TpmRepairTaskEntity::getTaskCode, tpmRepairTask.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getDeviceName()), TpmRepairTaskEntity::getDeviceName, tpmRepairTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getFaultClassName()), TpmRepairTaskEntity::getFaultClassName, tpmRepairTask.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getSubmitByName()), TpmRepairTaskEntity::getSubmitByName, tpmRepairTask.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getRepairByName()), TpmRepairTaskEntity::getRepairByName, tpmRepairTask.getRepairByName());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getState()), TpmRepairTaskEntity::getState, tpmRepairTask.getState());
        wrapper.orderByAsc(TpmRepairTaskEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmRepairTaskService.page(page.toMpPage(), wrapper));
    }


    /**
     * 分页查询
     */
    @Operation(summary = "维修任务分页查询", description = "分页查询维修任务列表")
    @GetMapping("/internal/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_internal_view')")
    public R<Page<TpmRepairTaskEntity>> getTpmRepairInternalTaskPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairTaskEntity tpmRepairTask) {
        LambdaQueryWrapper<TpmRepairTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmRepairTaskEntity::getType, RepairTaskTypeEnum.INTERNAL.getCode());
        wrapper.eq(tpmRepairTask.getId() != null, TpmRepairTaskEntity::getId, tpmRepairTask.getId());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getDeviceCode()), TpmRepairTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getTaskCode()), TpmRepairTaskEntity::getTaskCode, tpmRepairTask.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getDeviceName()), TpmRepairTaskEntity::getDeviceName, tpmRepairTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getFaultClassName()), TpmRepairTaskEntity::getFaultClassName, tpmRepairTask.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getSubmitByName()), TpmRepairTaskEntity::getSubmitByName, tpmRepairTask.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getRepairByName()), TpmRepairTaskEntity::getRepairByName, tpmRepairTask.getRepairByName());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getState()), TpmRepairTaskEntity::getState, tpmRepairTask.getState());
        wrapper.orderByAsc(TpmRepairTaskEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmRepairTaskService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 统计 - 内部维修
     */
    @Operation(summary = "分页查询 - 统计 - 内部维修", description = "分页查询 - 统计 - 内部维修")
    @GetMapping("/internal/getCount")
    public R<Map<String, Long>> internalGetCount(@ParameterObject TpmRepairTaskEntity tpmRepairTask) {
        LambdaQueryWrapper<TpmRepairTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmRepairTaskEntity::getType, RepairTaskTypeEnum.INTERNAL.getCode());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getDeviceCode()), TpmRepairTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getTaskCode()), TpmRepairTaskEntity::getTaskCode, tpmRepairTask.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getDeviceName()), TpmRepairTaskEntity::getDeviceName, tpmRepairTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getFaultClassName()), TpmRepairTaskEntity::getFaultClassName, tpmRepairTask.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getSubmitByName()), TpmRepairTaskEntity::getSubmitByName, tpmRepairTask.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getRepairByName()), TpmRepairTaskEntity::getRepairByName, tpmRepairTask.getRepairByName());
        return R.ok(tpmRepairTaskService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmRepairTaskEntity::getState, Collectors.counting())));
    }


    /**
     * 分页查询
     */
    @Operation(summary = "维修任务分页查询", description = "分页查询维修任务列表")
    @GetMapping("/outsourcing/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_outsourcing_view')")
    public R<Page<TpmRepairTaskEntity>> getTpmRepairOutsourcingTaskPage(@ParameterObject SearchPage page, @ParameterObject TpmRepairTaskEntity tpmRepairTask) {
        LambdaQueryWrapper<TpmRepairTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmRepairTaskEntity::getType, RepairTaskTypeEnum.OUTSOURCING.getCode());
        wrapper.eq(tpmRepairTask.getId() != null, TpmRepairTaskEntity::getId, tpmRepairTask.getId());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getState()), TpmRepairTaskEntity::getState, tpmRepairTask.getState());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getDeviceCode()), TpmRepairTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getTaskCode()), TpmRepairTaskEntity::getTaskCode, tpmRepairTask.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getDeviceName()), TpmRepairTaskEntity::getDeviceName, tpmRepairTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getFaultClassName()), TpmRepairTaskEntity::getFaultClassName, tpmRepairTask.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getSubmitByName()), TpmRepairTaskEntity::getSubmitByName, tpmRepairTask.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getRepairByName()), TpmRepairTaskEntity::getRepairByName, tpmRepairTask.getRepairByName());
        wrapper.orderByAsc(TpmRepairTaskEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmRepairTaskService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 统计 - 委外维修
     */
    @Operation(summary = "分页查询 - 统计 - 委外维修", description = "分页查询 - 统计 - 委外维修")
    @GetMapping("/outsourcing/getCount")
    public R<Map<String, Long>> outsourcingGetCount(@ParameterObject TpmRepairTaskEntity tpmRepairTask) {
        LambdaQueryWrapper<TpmRepairTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TpmRepairTaskEntity::getType, RepairTaskTypeEnum.OUTSOURCING.getCode());
        wrapper.eq(StrUtil.isNotBlank(tpmRepairTask.getDeviceCode()), TpmRepairTaskEntity::getDeviceCode, tpmRepairTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getTaskCode()), TpmRepairTaskEntity::getTaskCode, tpmRepairTask.getTaskCode());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getDeviceName()), TpmRepairTaskEntity::getDeviceName, tpmRepairTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getFaultClassName()), TpmRepairTaskEntity::getFaultClassName, tpmRepairTask.getFaultClassName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getSubmitByName()), TpmRepairTaskEntity::getSubmitByName, tpmRepairTask.getSubmitByName());
        wrapper.like(StrUtil.isNotBlank(tpmRepairTask.getRepairByName()), TpmRepairTaskEntity::getRepairByName, tpmRepairTask.getRepairByName());
        return R.ok(tpmRepairTaskService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmRepairTaskEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询维修任务表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询维修任务详情", description = "通过id查询维修任务详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_outsourcing_view','tpm_tpmRepairTask_internal_view')")
    public R<TpmRepairTaskEntity> getTaskById(@PathVariable("id") Long id) {
        return R.ok(tpmRepairTaskService.getTaskById(id));
    }

    /**
     * 委外维修
     */
    @Operation(summary = "委外维修", description = "委外维修")
    @GetMapping("/outsourcing/repair/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_outsourcing_repair')")
    public R<Boolean> outsourcingRepair(@PathVariable("id") Long id) {
        return R.ok(tpmRepairTaskService.update(new UpdateWrapper<TpmRepairTaskEntity>()
                .lambda().eq(TpmRepairTaskEntity::getId, id)
                .set(TpmRepairTaskEntity::getType, RepairTaskTypeEnum.OUTSOURCING.getCode())));
    }


    /**
     * 新增维修任务表
     *
     * @param tpmRepairTask 维修任务表
     * @return R
     */
    @Operation(summary = "新增维修任务", description = "新增维修任务信息")
    @SysLog("新增维修任务表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_add')")
    public R<Boolean> saveTask(@RequestBody TpmRepairTaskEntity tpmRepairTask) {
        try {
            return R.ok(tpmRepairTaskService.saveTask(tpmRepairTask));
        } catch (RuntimeException e) {
            // 只对这个接口，把错误信息原样返回
            return R.failed(e.getMessage());
        }
    }

    /**
     * 申请备件
     */
    @Operation(summary = "申请备件", description = "申请备件")
    @PostMapping("/addApplication")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_outsourcing_apply','tpm_tpmRepairTask_internal_apply')")
    public R<Boolean> addApplication(@RequestBody TpmSparePartsApplicationEntity entity) {
        return R.ok(tpmSparePartsApplicationService.addOutApplication(entity));
    }

    /**
     * 处理维修任务
     *
     * @param tpmRepairTask 处理维修任务
     * @return R
     */
    @Operation(summary = "处理维修任务", description = "处理维修任务并记录结果")
    @SysLog("处理维修任务")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_outsourcing_handle','tpm_tpmRepairTask_internal_handle')")
    public R<Boolean> handleTask(@RequestBody TpmRepairTaskEntity tpmRepairTask) {
        return R.ok(tpmRepairTaskService.handleTask(tpmRepairTask));
    }

    /**
     * 通过id删除维修任务表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "删除维修任务", description = "通过id删除维修任务")
    @SysLog("通过id删除维修任务表")
    @DeleteMapping
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmRepairTaskService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmRepairTask 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @Operation(summary = "导出维修任务", description = "导出维修任务数据到Excel")
    @GetMapping("/export")
    public List<TpmRepairTaskEntity> export(TpmRepairTaskEntity tpmRepairTask, Long[] ids) {
        return tpmRepairTaskService.list(Wrappers.lambdaQuery(tpmRepairTask).in(ArrayUtil.isNotEmpty(ids), TpmRepairTaskEntity::getId, ids));
    }

}
