package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesOrderVO;
import com.kitex.mom.mes.dto.MesTaskProcessVO;
import com.kitex.mom.mes.dto.ProcessDefectRateDTO;
import com.kitex.mom.mes.dto.WorkOrderStatisticsDTO;
import com.kitex.mom.mes.enums.TimeRangeEnum;
import com.kitex.mom.mes.service.MesStatisticsService;
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
import java.util.List;
import java.util.Map;

/**
 * 生产看板
 *
 * @author kitex
 * @since 2024-04-07
 */
@Tag(description = "statistics", name = "生产看板")
@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class MesStatisticsController {

    private final MesStatisticsService service;

    @Operation(summary = "获取出货产品数（支持时间范围）", description = "获取出货产品数（支持时间范围）")
    @GetMapping("/productNum")
    public R<Map<String, Object>> getProductNum(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        Map<String, Object> result = service.getProductNum(timeRangeEnum, startTime, endTime);
        return R.ok(result);
    }

    @Operation(summary = "获取生产工单数（支持时间范围）", description = "获取生产工单数（支持时间范围）")
    @GetMapping("/taskNum")
    public R<Map<String, Object>> getTaskNum(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        Map<String, Object> result = service.getTaskNum(timeRangeEnum, startTime, endTime);
        return R.ok(result);
    }

    @Operation(summary = "获取在制品数（支持时间范围）", description = "获取在制品数（支持时间范围）")
    @GetMapping("/wipNum")
    public R<Map<String, Object>> getWipNum(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        Map<String, Object> result = service.getWipNum(timeRangeEnum, startTime, endTime);
        return R.ok(result);
    }

    @Operation(summary = "获取不良品数（支持时间范围）", description = "获取不良品数（支持时间范围）")
    @GetMapping("/badNum")
    public R<Map<String, Object>> getBadNum(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        Map<String, Object> result = service.getBadNum(timeRangeEnum, startTime, endTime);
        return R.ok(result);
    }

    @Operation(summary = "获取工单数量统计数据（支持时间范围）", description = "获取工单数量统计数据（支持时间范围）")
    @GetMapping("/taskChartsWithTimeRange")
    public R<List<WorkOrderStatisticsDTO>> taskChartsWithTimeRange(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        List<WorkOrderStatisticsDTO> statistics = service.getWorkOrderStatistics(timeRangeEnum, startTime, endTime);
        return R.ok(statistics);
    }

    @Operation(summary = "工序不良率排名（支持时间范围）", description = "工序不良率排名（支持时间范围）")
    @GetMapping("/badRankWithTimeRange")
    public R<List<ProcessDefectRateDTO>> badRankWithTimeRange(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "10") Integer limit) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        List<ProcessDefectRateDTO> statistics = service.getProcessDefectRateRanking(timeRangeEnum, startTime, endTime, limit);
        return R.ok(statistics);
    }


    @Operation(summary = "获取工单实时进度列表（支持时间范围）", description = "获取工单实时进度列表（支持时间范围）")
    @GetMapping("/taskProgressList")
    public R<List<MesTaskProcessVO>> getTaskProgressList(
            @Parameter(description = "时间范围") @RequestParam(defaultValue = "thisMonth") String timeRange,
            @Parameter(description = "开始时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @Parameter(description = "返回数量限制") @RequestParam(defaultValue = "10") Integer limit) {

        TimeRangeEnum timeRangeEnum = TimeRangeEnum.fromValue(timeRange);
        List<MesTaskProcessVO> result = service.getTaskProgressList(timeRangeEnum, startTime, endTime, limit);
        return R.ok(result);
    }

    @Operation(summary = "出货订单数据", description = "出货订单数据")
    @GetMapping("/orderList")
    public R<List<MesOrderVO>> orderList(String startTime, String endTime) {
        return R.ok(service.getOrderList(startTime, endTime));
    }
}
