package com.kitex.mom.iot.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.iot.entity.IotDeviceEventEntity;
import com.kitex.mom.iot.service.IotDeviceEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT设备事件数据控制器
 *
 * @author system
 * @date 2025-03-12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/device/event")
@Tag(name = "IoT设备事件数据管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class IotDeviceEventController {

    private final IotDeviceEventService deviceEventService;
    private final com.kitex.mom.iot.service.impl.IotMesOutboxService outbox;

    /**
     * 分页查询设备事件数据列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询设备事件数据列表")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IPage<IotDeviceEventEntity>> getDeviceEventPage(SearchPage page, IotDeviceEventEntity event) {
        return R.ok(deviceEventService.getDeviceEventPage(page.toMpPage(), event));
    }

    /**
     * 根据设备ID查询事件数据
     */
    @GetMapping("/list/{deviceId}")
    @Operation(summary = "根据设备ID查询事件数据")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEventEntity>> getEventsByDeviceId(
            @PathVariable("deviceId") Long deviceId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return R.ok(deviceEventService.getRecentEventsByDeviceId(deviceId, limit));
    }

    /**
     * 根据设备编码查询事件数据
     */
    @GetMapping("/list/code/{deviceCode}")
    @Operation(summary = "根据设备编码查询事件数据")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEventEntity>> getEventsByDeviceCode(
            @PathVariable("deviceCode") String deviceCode,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return R.ok(deviceEventService.getRecentEventsByDeviceCode(deviceCode, limit));
    }

    @GetMapping("/detail/{eventId}")
    @Operation(summary = "查询设备事件详情")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<IotDeviceEventEntity> getEventDetail(@PathVariable Long eventId) {
        IotDeviceEventEntity event = deviceEventService.authorizedEvent(eventId,"read");
        return event == null ? R.failed("设备事件不存在") : R.ok(event);
    }

    @GetMapping("/history")
    @Operation(summary = "查询设备同类事件历史")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEventEntity>> getEventHistory(@RequestParam Long deviceId,
                                                         @RequestParam String identifier,
                                                         @RequestParam(required = false, defaultValue = "100") Integer limit) {
        int safeLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        return R.ok(deviceEventService.list(deviceEventService.scoped(deviceId,null,"read")
                .eq(IotDeviceEventEntity::getIdentifier, identifier)
                .orderByDesc(IotDeviceEventEntity::getEventTime)
                .last("LIMIT " + safeLimit)));
    }

    /**
     * 根据字段查询事件数据详情
     */
    @GetMapping("/details")
    @Operation(summary = "根据字段查询事件数据详情")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<List<IotDeviceEventEntity>> getEventDetails(
            @Parameter(description = "设备ID") @RequestParam(required = false) Long deviceId,
            @Parameter(description = "设备编码") @RequestParam(required = false) String deviceCode,
            @Parameter(description = "产品ID") @RequestParam(required = false) Long productId,
            @Parameter(description = "产品编码") @RequestParam(required = false) String productCode,
            @Parameter(description = "事件标识符") @RequestParam(required = false) String identifier,
            @Parameter(description = "事件名称") @RequestParam(required = false) String name,
            @Parameter(description = "事件类型") @RequestParam(required = false) String eventType,
            @Parameter(description = "开始时间") @RequestParam(required = false) LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) LocalDateTime endTime) {

        LambdaQueryWrapper<IotDeviceEventEntity> queryWrapper = deviceEventService.scoped(deviceId,deviceCode,"read");

        // 使用条件构造器的条件参数
        queryWrapper.eq(deviceId != null, IotDeviceEventEntity::getDeviceId, deviceId)
                .eq(StrUtil.isNotBlank(deviceCode), IotDeviceEventEntity::getDeviceCode, deviceCode)
                .eq(productId != null, IotDeviceEventEntity::getProductId, productId)
                .eq(StrUtil.isNotBlank(productCode), IotDeviceEventEntity::getProductCode, productCode)
                .eq(StrUtil.isNotBlank(identifier), IotDeviceEventEntity::getIdentifier, identifier)
                .eq(StrUtil.isNotBlank(name), IotDeviceEventEntity::getName, name)
                .eq(StrUtil.isNotBlank(eventType), IotDeviceEventEntity::getEventType, eventType)
                .ge(startTime != null, IotDeviceEventEntity::getEventTime, startTime)
                .le(endTime != null, IotDeviceEventEntity::getEventTime, endTime)
                .orderByDesc(IotDeviceEventEntity::getEventTime)
                .last("LIMIT 100");

        return R.ok(deviceEventService.list(queryWrapper));
    }

    /**
     * 保存设备事件数据
     */
    @PostMapping
    @SysLog("保存设备事件数据")
    @Operation(summary = "保存设备事件数据")
    @PreAuthorize("@pms.hasPermission('iot_device_edit')")
    public R<Boolean> saveDeviceEvent(@RequestBody IotDeviceEventEntity event) {
        return R.ok(deviceEventService.saveDeviceEvent(event));
    }

    /**
     * 批量保存设备事件数据
     */
    @PreAuthorize("@pms.hasPermission('iot_device_edit')")
    @SysLog("批量保存设备事件")
    @PostMapping("/batch")
    @Operation(summary = "批量保存设备事件数据")
    public R<Boolean> batchSaveDeviceEvent(@RequestBody List<IotDeviceEventEntity> events) {
        return R.ok(deviceEventService.batchSaveDeviceEvent(events));
    }

    @PostMapping("/forward/retry/{eventId}")
    @SysLog("重试IoT生产产量事件转发")
    @Operation(summary = "重试向MES转发生产产量事件")
    @PreAuthorize("@pms.hasPermission('iot_device_event_retry')")
    public R<IotDeviceEventEntity> retryMesReportForward(@PathVariable Long eventId,
            @RequestBody com.kitex.mom.iot.dto.IotMesRetryCommand command) {
        return R.ok(deviceEventService.retryMesReportForward(eventId,command));
    }

    @GetMapping("/forward/evidence/{eventId}")
    @PreAuthorize("@pms.hasPermission('iot_device_view')")
    public R<java.util.Map<String,Object>> forwardEvidence(@PathVariable Long eventId,
            @RequestParam(defaultValue="1") long current) {
        return R.ok(outbox.detail(eventId,current));
    }
}
