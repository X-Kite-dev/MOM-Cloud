package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.constant.DeviceStatusEnum;
import com.kitex.mom.iot.dto.IotDeviceDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.enums.DeviceTypeEnum;
import com.kitex.mom.iot.service.IotDeviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * IoT设备控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device")
@Tag(name = "IoT设备管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotDeviceController {

    private final IotDeviceService deviceService;

    /**
     * 分页查询设备列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询设备列表")
    public R<IPage<IotDeviceEntity>> getDevicePage(SearchPage page, IotDeviceEntity device) {
        return R.ok(deviceService.getDevicePage(page.toMpPage(), device));
    }


    /**
     * 根据产品ID查询设备列表
     */
    @GetMapping("/list/{productId}")
    @Operation(summary = "根据产品ID查询设备列表")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEntity>> getDeviceListByProductId(@PathVariable("productId") Long productId) {
        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEntity::getProductId, productId);
        return R.ok(deviceService.list(queryWrapper));
    }

    /**
     * 根据ID获取设备详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据ID获取设备详情")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceEntity> getDeviceById(@PathVariable("id") Long id) {
        return R.ok(deviceService.getById(id));
    }

    /**
     * 根据设备编码查询设备
     */
    @GetMapping("/code/{deviceCode}")
    @Operation(summary = "根据设备编码查询设备")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceEntity> getDeviceByCode(@PathVariable("deviceCode") String deviceCode) {
        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEntity::getDeviceCode, deviceCode);
        return R.ok(deviceService.getOne(queryWrapper));
    }

    /**
     * 根据字段查询设备详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询设备详情")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceEntity> getDeviceDetails(
            @Parameter(description = "设备编码") @RequestParam(required = false) String deviceCode,
            @Parameter(description = "设备名称") @RequestParam(required = false) String deviceName,
            @Parameter(description = "产品ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "产品编码") @RequestParam(required = false) String productCode,
            @Parameter(description = "设备状态") @RequestParam(required = false) Integer status) {

        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(StrUtil.isNotBlank(deviceCode), IotDeviceEntity::getDeviceCode, deviceCode)
                .eq(StrUtil.isNotBlank(deviceName), IotDeviceEntity::getDeviceName, deviceName)
                .eq(productId != null, IotDeviceEntity::getProductId, productId)
                .eq(StrUtil.isNotBlank(productCode), IotDeviceEntity::getProductCode, productCode)
                .eq(status != null, IotDeviceEntity::getStatus, status);

        return R.ok(deviceService.getOne(queryWrapper));
    }

    /**
     * 创建设备
     */
    @PostMapping
    @SysLog("创建设备")
    @Operation(summary = "创建设备")
    @PreAuthorize("@pms.hasPermission('iot_device_add')")
    public R<Boolean> createDevice(@RequestBody IotDeviceDTO device) {
        return R.ok(deviceService.createDevice(device));
    }

    /**
     * 批量创建设备
     */
    @PostMapping("/batch")
    @SysLog("批量创建设备")
    @Operation(summary = "批量创建设备")
    @PreAuthorize("@pms.hasPermission('iot_device_add')")
    public R<Boolean> batchCreateDevice(@RequestBody List<IotDeviceDTO> devices) {
        return R.ok(deviceService.batchCreateDevice(devices));
    }

    /**
     * 更新设备
     */
    @PutMapping
    @SysLog("更新设备")
    @Operation(summary = "更新设备")
    @PreAuthorize("@pms.hasPermission('iot_device_edit')")
    public R<Boolean> updateDevice(@RequestBody IotDeviceDTO device) {
        return R.ok(deviceService.updateDevice(device));
    }

    /**
     * 删除设备
     */
    @DeleteMapping("/{id}")
    @SysLog("删除设备")
    @Operation(summary = "删除设备")
    @PreAuthorize("@pms.hasPermission('iot_device_del')")
    public R<Boolean> removeDevice(@PathVariable("id") Long id) {
        return R.ok(deviceService.removeById(id));
    }

    /**
     * 更新设备状态
     */
    @PutMapping("/status/{id}/{status}")
    @SysLog("更新设备状态")
    @Operation(summary = "更新设备状态")
    @PreAuthorize("@pms.hasPermission('iot_device_edit')")
    public R<Boolean> updateDeviceStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status) {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setId(id);
        device.setStatus(status);
        return R.ok(deviceService.updateById(device));
    }

    /**
     * 获取设备类型列表
     */
    @GetMapping("/type/list")
    @Operation(summary = "获取设备类型列表")
    public R<List<Map<String, String>>> getDeviceTypeList() {
        List<Map<String, String>> typeList = Arrays.stream(DeviceTypeEnum.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>(2);
                    map.put("label", type.getLabel());
                    map.put("value", type.getValue());
                    return map;
                })
                .collect(Collectors.toList());
        return R.ok(typeList);
    }

    /**
     * 获取可用的网关设备列表
     */
    @GetMapping("/gateway/list")
    @Operation(summary = "获取可用的网关设备列表")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEntity>> getGatewayDeviceList() {
        LambdaQueryWrapper<IotDeviceEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEntity::getDeviceType, DeviceTypeEnum.GATEWAY_DEVICE.getValue())
                .eq(IotDeviceEntity::getStatus, DeviceStatusEnum.ONLINE.getCode());
        return R.ok(deviceService.list(queryWrapper));
    }
} 