package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.entity.IotDeviceConnectionEntity;
import com.kitex.mom.iot.service.IotDeviceConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * IoT设备连接控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device/connection")
@Tag(name = "IoT设备连接管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotDeviceConnectionController {

    private final IotDeviceConnectionService deviceConnectionService;

    /**
     * 分页查询设备连接列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询设备连接列表")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IPage<IotDeviceConnectionEntity>> getDeviceConnectionPage(SearchPage page, IotDeviceConnectionEntity connection) {
        return R.ok(deviceConnectionService.getDeviceConnectionPage(page.toMpPage(), connection));
    }

    /**
     * 根据设备ID获取最新连接信息
     */
    @GetMapping("/device/{deviceId}")
    @Operation(summary = "根据设备ID获取最新连接信息")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceConnectionEntity> getLatestConnectionByDeviceId(@PathVariable("deviceId") Long deviceId) {
        return R.ok(deviceConnectionService.getLatestConnectionByDeviceId(deviceId));
    }

    /**
     * 根据字段查询连接详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询连接详情")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceConnectionEntity> getConnectionDetails(
            @Parameter(description = "设备ID") @RequestParam(required = false) Long deviceId,
            @Parameter(description = "设备编码") @RequestParam(required = false) String deviceCode,
            @Parameter(description = "产品ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "产品编码") @RequestParam(required = false) String productCode,
            @Parameter(description = "客户端ID") @RequestParam(required = false) String clientId,
            @Parameter(description = "连接状态") @RequestParam(required = false) Integer connectStatus) {

        LambdaQueryWrapper<IotDeviceConnectionEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(deviceId != null, IotDeviceConnectionEntity::getDeviceId, deviceId)
                .eq(StrUtil.isNotBlank(deviceCode), IotDeviceConnectionEntity::getDeviceCode, deviceCode)
                .eq(productId != null, IotDeviceConnectionEntity::getProductId, productId)
                .eq(StrUtil.isNotBlank(productCode), IotDeviceConnectionEntity::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(clientId), IotDeviceConnectionEntity::getClientId, clientId)
                .eq(connectStatus != null, IotDeviceConnectionEntity::getConnectStatus, connectStatus)
                .orderByDesc(IotDeviceConnectionEntity::getConnectTime);

        return R.ok(deviceConnectionService.getOne(queryWrapper));
    }
} 