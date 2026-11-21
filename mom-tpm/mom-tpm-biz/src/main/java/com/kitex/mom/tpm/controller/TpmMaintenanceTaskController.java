package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.tpm.agent.service.TpmAgentFactoryAccessService;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
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
 * 保养任务表
 *
 * @author kitex
 * @date 2025-02-06 15:17:39
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmMaintenanceTask")
@Tag(description = "tpmMaintenanceTask", name = "保养任务表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmMaintenanceTaskController {

    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    private final TpmAgentFactoryAccessService factoryAccess;

    /**
     * 分页查询
     *
     * @param page               分页对象
     * @param tpmMaintenanceTask 保养任务表
     * @param agentTaskId 设备调查下钻的原任务主键，普通列表不传
     * @return
     */
    @Operation(summary = "保养任务分页查询", description = "分页查询保养任务列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceTask_view')")
    public R<Page<TpmMaintenanceTaskEntity>> getTpmMaintenanceTaskPage(@ParameterObject SearchPage page, @ParameterObject TpmMaintenanceTaskEntity tpmMaintenanceTask,
            @RequestParam(required = false) Long agentTaskId) {
        if (agentTaskId != null) {
            if (agentTaskId <= 0 || StrUtil.isBlank(tpmMaintenanceTask.getTaskCode()) || StrUtil.isBlank(tpmMaintenanceTask.getDeviceCode())) {
                throw new CheckedException("设备调查定位需要原任务主键、任务号和设备编码");
            }
            // 台账精确定位以实名工厂和原任务身份为准，不走普通模糊筛选。
            String factory = factoryAccess.requireRead(FactoryContextHolder.getFactoryCode());
            return R.ok(tpmMaintenanceTaskService.page(page.toMpPage(), Wrappers.<TpmMaintenanceTaskEntity>lambdaQuery()
                    .eq(TpmMaintenanceTaskEntity::getId, agentTaskId)
                    .eq(TpmMaintenanceTaskEntity::getFactoryCode, factory)
                    .eq(TpmMaintenanceTaskEntity::getTaskCode, tpmMaintenanceTask.getTaskCode())
                    .eq(TpmMaintenanceTaskEntity::getDeviceCode, tpmMaintenanceTask.getDeviceCode())));
        }
        LambdaQueryWrapper<TpmMaintenanceTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenanceTask.getDeviceCode()), TpmMaintenanceTaskEntity::getDeviceCode, tpmMaintenanceTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getDeviceName()), TpmMaintenanceTaskEntity::getDeviceName, tpmMaintenanceTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getHandlePersonName()), TpmMaintenanceTaskEntity::getHandlePersonName, tpmMaintenanceTask.getHandlePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getPlanName()), TpmMaintenanceTaskEntity::getPlanName, tpmMaintenanceTask.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getTaskCode()), TpmMaintenanceTaskEntity::getTaskCode, tpmMaintenanceTask.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenanceTask.getState()), TpmMaintenanceTaskEntity::getState, tpmMaintenanceTask.getState());
        wrapper.orderByAsc(TpmMaintenanceTaskEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmMaintenanceTaskService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询 - 统计
     */
    @Operation(summary = "分页查询 - 统计", description = "分页查询 - 统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmMaintenanceTaskEntity tpmMaintenanceTask) {
        LambdaQueryWrapper<TpmMaintenanceTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenanceTask.getDeviceCode()), TpmMaintenanceTaskEntity::getDeviceCode, tpmMaintenanceTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getDeviceName()), TpmMaintenanceTaskEntity::getDeviceName, tpmMaintenanceTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getHandlePersonName()), TpmMaintenanceTaskEntity::getHandlePersonName, tpmMaintenanceTask.getHandlePersonName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getPlanName()), TpmMaintenanceTaskEntity::getPlanName, tpmMaintenanceTask.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenanceTask.getTaskCode()), TpmMaintenanceTaskEntity::getTaskCode, tpmMaintenanceTask.getTaskCode());
        return R.ok(tpmMaintenanceTaskService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmMaintenanceTaskEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询保养任务表
     */
    @Operation(summary = "查询保养任务详情", description = "通过id查询保养任务详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceTask_view')")
    public R<TpmMaintenanceTaskEntity> getByTaskCode(@PathVariable("id") Long id) {
        return R.ok(tpmMaintenanceTaskService.getByTaskId(id));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmMaintenanceTask 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @Operation(summary = "导出保养任务", description = "导出保养任务数据到Excel")
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceTask_export')")
    public List<TpmMaintenanceTaskEntity> export(TpmMaintenanceTaskEntity tpmMaintenanceTask, Long[] ids) {
        return tpmMaintenanceTaskService.list(Wrappers.lambdaQuery(tpmMaintenanceTask).in(ArrayUtil.isNotEmpty(ids), TpmMaintenanceTaskEntity::getId, ids));
    }


    /**
     * 处理保养任务
     */
    @Operation(summary = "处理保养任务", description = "处理保养任务并记录结果")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('tpm_tpmMaintenanceTask_handle')")
    public R<Boolean> handle(@RequestBody TpmMaintenanceTaskEntity tpmMaintenanceTask) {
        return R.ok(tpmMaintenanceTaskService.handle(tpmMaintenanceTask));
    }

}