package com.kitex.mom.iot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.dto.DeviceMetadataQueryDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.service.DeviceMetadataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备元数据查询控制器
 *
 * @author system
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device/metadata")
@Tag(name = "设备元数据查询接口")
public class DeviceMetadataController {

    private final DeviceMetadataService deviceMetadataService;

    /**
     * 查询设备元数据
     *
     * @param page     分页参数
     * @param queryDTO 查询条件
     * @return 设备属性数据分页结果
     */
    @GetMapping("/query")
    @Operation(summary = "查询设备元数据", description = "根据产品和物模型查询设备元数据")
    public R<IPage<IotDevicePropertyEntity>> queryMetadata(Page<IotDevicePropertyEntity> page, DeviceMetadataQueryDTO queryDTO) {
        return R.ok(deviceMetadataService.queryMetadata(page, queryDTO));
    }

    /**
     * 获取指定产品的物模型列表
     *
     * @param productId 产品ID
     * @return 物模型列表
     */
    @GetMapping("/product/models")
    @Operation(summary = "获取产品物模型", description = "获取指定产品的物模型列表")
    public R<List<IotProductModelEntity>> getProductModels(@RequestParam("productId") Long productId) {
        return R.ok(deviceMetadataService.getProductModels(productId));
    }

    /**
     * 获取指定产品的设备列表
     *
     * @param productId 产品ID
     * @return 设备列表
     */
    @GetMapping("/product/devices")
    @Operation(summary = "获取产品设备", description = "获取指定产品的设备列表")
    public R<List<IotDeviceEntity>> getProductDevices(@RequestParam("productId") Long productId) {
        return R.ok(deviceMetadataService.getProductDevices(productId));
    }
} 