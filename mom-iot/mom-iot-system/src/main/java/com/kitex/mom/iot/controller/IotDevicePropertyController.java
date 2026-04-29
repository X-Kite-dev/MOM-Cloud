package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.service.IotDevicePropertyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * IoT设备属性数据控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device/property")
@Tag(name = "IoT设备属性数据管理")
public class IotDevicePropertyController {

    private final IotDevicePropertyService devicePropertyService;

    /**
     * 分页查询设备属性数据列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询设备属性数据列表")
    public R<IPage<IotDevicePropertyEntity>> getDevicePropertyPage(SearchPage page, IotDevicePropertyEntity property) {
        return R.ok(devicePropertyService.getDevicePropertyPage(page.toMpPage(), property));
    }

    /**
     * 根据设备ID查询最新的属性数据
     */
    @GetMapping("/latest/{deviceId}")
    @Operation(summary = "根据设备ID查询最新的属性数据")
    public R<List<IotDevicePropertyEntity>> getLatestPropertiesByDeviceId(@PathVariable("deviceId") Long deviceId) {
        return R.ok(devicePropertyService.getLatestPropertiesByDeviceId(deviceId));
    }

    /**
     * 根据设备ID和属性标识符查询最新的属性数据
     */
    @GetMapping("/latest/{deviceId}/{identifier}")
    @Operation(summary = "根据设备ID和属性标识符查询最新的属性数据")
    public R<IotDevicePropertyEntity> getLatestPropertyByDeviceIdAndIdentifier(
            @PathVariable("deviceId") Long deviceId,
            @PathVariable("identifier") String identifier) {
        return R.ok(devicePropertyService.getLatestPropertyByDeviceIdAndIdentifier(deviceId, identifier));
    }

    /**
     * 根据设备编码查询最新的属性数据
     */
    @GetMapping("/latest/code/{deviceCode}")
    @Operation(summary = "根据设备编码查询最新的属性数据")
    public R<List<IotDevicePropertyEntity>> getLatestPropertiesByDeviceCode(@PathVariable("deviceCode") String deviceCode) {
        return R.ok(devicePropertyService.getLatestPropertiesByDeviceCode(deviceCode));
    }

    /**
     * 根据字段查询属性数据详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询属性数据详情")
    public R<List<IotDevicePropertyEntity>> getPropertyDetails(
            @Parameter(description = "设备ID") @RequestParam(required = false) Long deviceId,
            @Parameter(description = "设备编码") @RequestParam(required = false) String deviceCode,
            @Parameter(description = "产品ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "产品编码") @RequestParam(required = false) String productCode,
            @Parameter(description = "属性标识符") @RequestParam(required = false) String identifier,
            @Parameter(description = "属性名称") @RequestParam(required = false) String name,
            @Parameter(description = "开始时间") @RequestParam(required = false) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) LocalDateTime endTime) {

        LambdaQueryWrapper<IotDevicePropertyEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 使用条件构造器的条件参数
        queryWrapper.eq(deviceId != null, IotDevicePropertyEntity::getDeviceId, deviceId)
                .eq(StrUtil.isNotBlank(deviceCode), IotDevicePropertyEntity::getDeviceCode, deviceCode)
                .eq(productId != null, IotDevicePropertyEntity::getProductId, productId)
                .eq(StrUtil.isNotBlank(productCode), IotDevicePropertyEntity::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(identifier), IotDevicePropertyEntity::getIdentifier, identifier)
                .eq(StrUtil.isNotBlank(name), IotDevicePropertyEntity::getName, name)
                .ge(startTime != null, IotDevicePropertyEntity::getCollectTime, startTime)
                .le(endTime != null, IotDevicePropertyEntity::getCollectTime, endTime)
                .orderByDesc(IotDevicePropertyEntity::getCollectTime)
                .last("LIMIT 100");

        return R.ok(devicePropertyService.list(queryWrapper));
    }

    /**
     * 查询某个属性某个值的历史数据
     */
    @GetMapping("/history")
    @Operation(summary = "查询某个属性某个值的历史数据")
    public R<List<IotDevicePropertyEntity>> getPropertyHistory(
            @Parameter(description = "设备ID") @RequestParam(required = false) Long deviceId,
            @Parameter(description = "属性标识符") @RequestParam(required = false) String identifier) {
        return R.ok(devicePropertyService.getPropertyHistory(deviceId, identifier));
    }

    /**
     * 查询设备属性一个月内的历史数据用于图表展示
     */
    @GetMapping("/chart")
    @Operation(summary = "查询设备属性一个月内的历史数据用于图表展示")
    public R<Map<String, Object>> getPropertyChartData(
            @Parameter(description = "设备ID") @RequestParam Long deviceId,
            @Parameter(description = "属性标识符") @RequestParam String identifier) {
        return R.ok(devicePropertyService.getPropertyChartData(deviceId, identifier));
    }

    /**
     * 查询设备属性历史数据用于图表展示
     */
    @GetMapping("/chart/range")
    @Operation(summary = "根据时间范围查询设备属性历史数据用于图表展示")
    public R<Map<String, Object>> getPropertyChartDataByTimeRange(
            @Parameter(description = "设备ID") @RequestParam Long deviceId,
            @Parameter(description = "属性标识符") @RequestParam String identifier,
            @Parameter(description = "开始时间") @RequestParam(required = false) LocalDate startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) LocalDate endTime) {
        return R.ok(devicePropertyService.getPropertyChartData(deviceId, identifier, startTime.atStartOfDay(), endTime.atStartOfDay()));
    }
} 