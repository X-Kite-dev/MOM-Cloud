package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.dto.IotDeviceGroupDTO;
import com.kitex.mom.iot.dto.IotDeviceGroupRelationDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDeviceGroupEntity;
import com.kitex.mom.iot.service.IotDeviceGroupRelationService;
import com.kitex.mom.iot.service.IotDeviceGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IoT设备分组控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device/group")
@Tag(name = "IoT设备分组管理")
public class IotDeviceGroupController {

    private final IotDeviceGroupService deviceGroupService;
    private final IotDeviceGroupRelationService deviceGroupRelationService;

    /**
     * 分页查询分组列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询分组列表")
    public R<IPage<IotDeviceGroupEntity>> getGroupPage(SearchPage page, IotDeviceGroupEntity group) {
        return R.ok(deviceGroupService.getGroupPage(page.toMpPage(), group));
    }

    /**
     * 查询分组树
     */
    @GetMapping("/tree")
    @Operation(summary = "查询分组树")
    public R<List<IotDeviceGroupEntity>> getGroupTree(@RequestParam(required = false) Long parentId) {
        return R.ok(deviceGroupService.getGroupTree(parentId));
    }

    /**
     * 根据ID获取分组详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取分组详情")
    public R<IotDeviceGroupEntity> getGroupById(@PathVariable("id") Long id) {
        return R.ok(deviceGroupService.getById(id));
    }

    /**
     * 根据字段查询分组详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询分组详情")
    public R<IotDeviceGroupEntity> getGroupDetails(
            @Parameter(description = "分组编码") @RequestParam(required = false) String groupCode,
            @Parameter(description = "分组名称") @RequestParam(required = false) String groupName,
            @Parameter(description = "父分组ID") @RequestParam(required = false) Long parentId) {

        LambdaQueryWrapper<IotDeviceGroupEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(StrUtil.isNotBlank(groupCode), IotDeviceGroupEntity::getGroupCode, groupCode)
                .eq(StrUtil.isNotBlank(groupName), IotDeviceGroupEntity::getGroupName, groupName)
                .eq(parentId != null, IotDeviceGroupEntity::getParentId, parentId);

        return R.ok(deviceGroupService.getOne(queryWrapper));
    }

    /**
     * 创建分组
     */
    @PostMapping
    @SysLog("创建分组")
    @Operation(summary = "创建分组")
    public R<Boolean> createGroup(@RequestBody IotDeviceGroupDTO groupDTO) {
        return R.ok(deviceGroupService.createGroup(groupDTO));
    }

    /**
     * 更新分组
     */
    @PutMapping
    @SysLog("更新分组")
    @Operation(summary = "更新分组")
    public R<Boolean> updateGroup(@RequestBody IotDeviceGroupDTO groupDTO) {
        return R.ok(deviceGroupService.updateGroup(groupDTO));
    }

    /**
     * 删除分组
     */
    @DeleteMapping("/{id}")
    @SysLog("删除分组")
    @Operation(summary = "删除分组")
    public R<Boolean> removeGroup(@PathVariable("id") Long id) {
        return R.ok(deviceGroupService.removeGroup(id));
    }

    /**
     * 分页查询分组下的设备
     */
    @GetMapping("/device/page/{groupId}")
    @Operation(summary = "分页查询分组下的设备")
    public R<IPage<IotDeviceEntity>> getDevicesByGroupId(
            SearchPage page,
            @PathVariable("groupId") Long groupId,
            IotDeviceEntity device) {
        return R.ok(deviceGroupRelationService.getDevicesByGroupId(page.toMpPage(), groupId, device));
    }

    /**
     * 添加设备到分组
     */
    @PostMapping("/device/add")
    @SysLog("添加设备到分组")
    @Operation(summary = "添加设备到分组")
    public R<Boolean> addDevicesToGroup(@RequestBody IotDeviceGroupRelationDTO relationDTO) {
        return R.ok(deviceGroupRelationService.addDevicesToGroup(relationDTO));
    }

    /**
     * 从分组中移除设备
     */
    @PostMapping("/device/remove")
    @SysLog("从分组中移除设备")
    @Operation(summary = "从分组中移除设备")
    public R<Boolean> removeDevicesFromGroup(@RequestBody IotDeviceGroupRelationDTO relationDTO) {
        return R.ok(deviceGroupRelationService.removeDevicesFromGroup(relationDTO));
    }

    /**
     * 查询设备所属的分组
     */
    @GetMapping("/device/{deviceId}")
    @Operation(summary = "查询设备所属的分组")
    public R<List<IotDeviceGroupEntity>> getGroupsByDeviceId(@PathVariable("deviceId") Long deviceId) {
        return R.ok(deviceGroupService.getGroupsByDeviceId(deviceId));
    }
} 