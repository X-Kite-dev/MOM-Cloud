package com.kitex.mom.tpm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.dto.TpmCheckTaskDTO;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.service.TpmCheckTaskService;
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
 * 点/巡检任务
 *
 * @author kitex
 * @date 2025-02-18 17:49:05
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmCheckTask")
@Tag(description = "tpmCheckTask", name = "点/巡检任务管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmCheckTaskController {

    private final TpmCheckTaskService tpmCheckTaskService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param tpmCheckTask 点/巡检任务
     * @return
     */
    @Operation(summary = "点/巡检任务分页查询", description = "分页查询点/巡检任务列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_view')")
    public R getTpmCheckTaskPage(@ParameterObject SearchPage page, @ParameterObject TpmCheckTaskEntity tpmCheckTask) {
        LambdaQueryWrapper<TpmCheckTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getTaskCode()), TpmCheckTaskEntity::getTaskCode, tpmCheckTask.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckTask.getType()), TpmCheckTaskEntity::getType, tpmCheckTask.getType());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getPlanName()), TpmCheckTaskEntity::getPlanName, tpmCheckTask.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getDeviceCode()), TpmCheckTaskEntity::getDeviceCode, tpmCheckTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getDeviceName()), TpmCheckTaskEntity::getDeviceName, tpmCheckTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getHandlePersonName()), TpmCheckTaskEntity::getHandlePersonName, tpmCheckTask.getHandlePersonName());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckTask.getState()), TpmCheckTaskEntity::getState, tpmCheckTask.getState());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckTask.getBlockStatus()), TpmCheckTaskEntity::getBlockStatus, tpmCheckTask.getBlockStatus());
        wrapper.orderByAsc(TpmCheckTaskEntity::getState);
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return R.ok(tpmCheckTaskService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询统计
     */
    @Operation(summary = "分页查询统计", description = "分页查询统计")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject TpmCheckTaskEntity tpmCheckTask) {
        LambdaQueryWrapper<TpmCheckTaskEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getTaskCode()), TpmCheckTaskEntity::getTaskCode, tpmCheckTask.getTaskCode());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckTask.getType()), TpmCheckTaskEntity::getType, tpmCheckTask.getType());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getPlanName()), TpmCheckTaskEntity::getPlanName, tpmCheckTask.getPlanName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getDeviceCode()), TpmCheckTaskEntity::getDeviceCode, tpmCheckTask.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getDeviceName()), TpmCheckTaskEntity::getDeviceName, tpmCheckTask.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckTask.getHandlePersonName()), TpmCheckTaskEntity::getHandlePersonName, tpmCheckTask.getHandlePersonName());
        return R.ok(tpmCheckTaskService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmCheckTaskEntity::getState, Collectors.counting())));
    }


    /**
     * 通过id查询点/巡检任务
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询点/巡检任务详情", description = "通过id查询点/巡检任务详情")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_view')")
    public R getTaskById(@PathVariable("id") Long id) {
        return R.ok(tpmCheckTaskService.getTaskById(id));
    }

    /**
     * 批量指派人员
     */
    @GetMapping("/assign")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_assign')")
    @Operation(summary = "批量指派点/巡检任务", description = "批量指派点/巡检任务给指定人员")
    public R assign(@RequestParam("ids") List<Long> ids,
                    @RequestParam("handlePersonCode") String handlePersonCode,
                    @RequestParam("handlePersonName") String handlePersonName) {
        return R.ok(tpmCheckTaskService.assign(ids, handlePersonCode, handlePersonName));
    }


    /**
     * 新增点检任务
     */
    @GetMapping("/add")
    @Operation(summary = "新增点检任务", description = "根据设备编码新增点检任务")
    public R addTask(@RequestParam("deviceCode") String deviceCode) {
        return R.ok(tpmCheckTaskService.addTask(deviceCode));
    }


    /**
     * 设备点检-开机
     */
    @PostMapping("/start")
    @Operation(summary = "设备点检-开机", description = "设备点检-开机")
    public R<Map<String, Object>> start(@RequestBody DeviceCheckTaskStartDto startDto) {
        return R.ok(tpmCheckTaskService.start(startDto));
    }

    /**
     * 设备换模
     */
    @PostMapping("/changeMold")
    @Operation(summary = "设备换模", description = "设备换模")
    public R<Boolean> changeMold(@RequestBody DeviceChangeMoldDto moldDto) {
        return R.ok(tpmCheckTaskService.changeMold(moldDto));
    }


    /**
     * 处理
     *
     * @param tpmCheckTask 任务
     */
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_handle')")
    @Operation(summary = "处理点/巡检任务", description = "处理点/巡检任务并记录结果")
    public R handle(@RequestBody TpmCheckTaskEntity tpmCheckTask) {
        return R.ok(tpmCheckTaskService.handle(tpmCheckTask));
    }

    @PostMapping("/release-block")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_release')")
    @Operation(summary = "解除点检生产封锁", description = "独立复核点检或维修证据后解除设备生产封锁")
    public R<Boolean> releaseBlock(@RequestBody TpmCheckTaskDTO.ReleaseCommand command) {
        return R.ok(tpmCheckTaskService.releaseBlock(command));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmCheckTask 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmCheckTask_export')")
    @Operation(summary = "导出点/巡检任务", description = "导出点/巡检任务数据到Excel")
    public List<TpmCheckTaskEntity> export(TpmCheckTaskEntity tpmCheckTask, Long[] ids) {
        return tpmCheckTaskService.list(Wrappers.lambdaQuery(tpmCheckTask).in(ArrayUtil.isNotEmpty(ids), TpmCheckTaskEntity::getId, ids));
    }
}
