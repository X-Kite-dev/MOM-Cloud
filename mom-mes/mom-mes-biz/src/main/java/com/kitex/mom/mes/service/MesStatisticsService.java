package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesOrderVO;
import com.kitex.mom.mes.dto.MesTaskProcessVO;
import com.kitex.mom.mes.dto.ProcessDefectRateDTO;
import com.kitex.mom.mes.dto.WorkOrderStatisticsDTO;
import com.kitex.mom.mes.enums.TimeRangeEnum;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 生产看板
 *
 * @author kitex
 * @since 2025-04-07
 */
public interface MesStatisticsService {

    /**
     * 出货订单数据
     *
     * @return
     */
    List<MesOrderVO> getOrderList(String startTime, String endTime);

    /**
     * 获取工单数量统计数据（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 工单数量统计数据列表
     */
    List<WorkOrderStatisticsDTO> getWorkOrderStatistics(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取工序不良率排名数据（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @param limit     返回数量限制
     * @return 工序不良率排名数据列表
     */
    List<ProcessDefectRateDTO> getProcessDefectRateRanking(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime, Integer limit);

    /**
     * 获取出货产品数（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 出货产品数统计数据
     */
    Map<String, Object> getProductNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取生产工单数（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 生产工单数统计数据
     */
    Map<String, Object> getTaskNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取在制品数（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 在制品数统计数据
     */
    Map<String, Object> getWipNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取不良品数（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 不良品数统计数据
     */
    Map<String, Object> getBadNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取工单实时进度列表（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @param limit     返回数量限制
     * @return 工单实时进度列表
     */
    List<MesTaskProcessVO> getTaskProgressList(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime, Integer limit);
}
