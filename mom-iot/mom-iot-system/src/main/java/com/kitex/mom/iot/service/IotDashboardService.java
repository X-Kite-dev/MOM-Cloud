package com.kitex.mom.iot.service;

import com.kitex.mom.iot.dto.IotDashboardStatisticsDTO;
import com.kitex.mom.iot.dto.IotMessageTrendDTO;

import java.time.LocalDateTime;

/**
 * IoT设备看板服务
 *
 * @author claude
 * @date 2025-03-14
 */
public interface IotDashboardService {

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    IotDashboardStatisticsDTO getStatistics();

    /**
     * 获取消息趋势
     *
     * @param timeRange 时间范围类型（hour-最近1小时, day-最近24小时, week-最近一周）
     * @param startTime 开始时间（自定义时间范围）
     * @param endTime   结束时间（自定义时间范围）
     * @return 消息趋势数据
     */
    IotMessageTrendDTO getMessageTrend(String timeRange, LocalDateTime startTime, LocalDateTime endTime);
} 