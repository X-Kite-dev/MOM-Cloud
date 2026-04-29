package com.kitex.mom.iot.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.iot.dto.IotDashboardStatisticsDTO;
import com.kitex.mom.iot.dto.IotMessageTrendDTO;
import com.kitex.mom.iot.service.IotDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * IoT设备看板控制器
 *
 * @author claude
 * @date 2025-03-14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/dashboard")
@Tag(name = "IoT设备看板管理")
public class IotDashboardController {

    private final IotDashboardService dashboardService;

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    @GetMapping("/statistics")
    @Operation(summary = "获取统计数据", description = "获取IoT设备看板统计数据")
    public R<IotDashboardStatisticsDTO> getStatistics() {
        return R.ok(dashboardService.getStatistics());
    }

    /**
     * 获取消息趋势
     *
     * @param timeRange 时间范围类型（hour-最近1小时, day-最近24小时, week-最近一周）
     * @param startTime 开始时间（自定义时间范围）
     * @param endTime   结束时间（自定义时间范围）
     * @return 消息趋势数据
     */
    @GetMapping("/message/trend")
    @Operation(summary = "获取消息趋势", description = "获取IoT设备消息趋势数据")
    public R<IotMessageTrendDTO> getMessageTrend(
            @Parameter(description = "时间范围类型（hour-最近1小时, day-最近24小时, week-最近一周）")
            @RequestParam(value = "timeRange", defaultValue = "hour") String timeRange,

            @Parameter(description = "开始时间（自定义时间范围）")
            @RequestParam(value = "startTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,

            @Parameter(description = "结束时间（自定义时间范围）")
            @RequestParam(value = "endTime", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        return R.ok(dashboardService.getMessageTrend(timeRange, startTime, endTime));
    }
} 