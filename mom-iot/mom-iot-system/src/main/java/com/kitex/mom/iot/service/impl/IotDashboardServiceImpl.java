package com.kitex.mom.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.iot.dto.IotDashboardStatisticsDTO;
import com.kitex.mom.iot.dto.IotMessageTrendDTO;
import com.kitex.mom.iot.entity.*;
import com.kitex.mom.iot.mapper.*;
import com.kitex.mom.iot.service.IotDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * IoT设备看板服务实现
 *
 * @author claude
 * @date 2025-03-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDashboardServiceImpl implements IotDashboardService {

    private final IotProductMapper productMapper;
    private final IotDeviceDataAccess dataAccess;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceConnectionMapper deviceConnectionMapper;
    private final IotDevicePropertyMapper devicePropertyMapper;
    private final IotDeviceEventMapper deviceEventMapper;

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    @Override
    public IotDashboardStatisticsDTO getStatistics() {
        dataAccess.factory("read");
        IotDashboardStatisticsDTO statistics = new IotDashboardStatisticsDTO();

        // 产品统计
        int productTotal = Math.toIntExact(productMapper.selectCount(null));
        int productNormal = Math.toIntExact(productMapper.selectCount(
                new LambdaQueryWrapper<IotProductEntity>().eq(IotProductEntity::getStatus, 1)));
        int productDisabled = productTotal - productNormal;

        statistics.setProductTotal(productTotal);
        statistics.setProductNormal(productNormal);
        statistics.setProductDisabled(productDisabled);

        // 设备统计
        int deviceTotal = Math.toIntExact(deviceMapper.selectCount(null));
        int deviceOnline = Math.toIntExact(deviceMapper.selectCount(
                new LambdaQueryWrapper<IotDeviceEntity>().eq(IotDeviceEntity::getStatus, 2)));
        int deviceOffline = Math.toIntExact(deviceMapper.selectCount(
                new LambdaQueryWrapper<IotDeviceEntity>().eq(IotDeviceEntity::getStatus, 3)));

        statistics.setDeviceTotal(deviceTotal);
        statistics.setDeviceOnline(deviceOnline);
        statistics.setDeviceOffline(deviceOffline);

        // 当前在线统计
        int currentOnline = Math.toIntExact(deviceConnectionMapper.selectCount(
                new LambdaQueryWrapper<IotDeviceConnectionEntity>().eq(IotDeviceConnectionEntity::getConnectStatus, 1)));
        int totalOnline = Math.toIntExact(deviceConnectionMapper.selectCount(null));

        statistics.setCurrentOnline(currentOnline);
        statistics.setTotalOnline(totalOnline);

        // 消息统计
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime monthStart = LocalDateTime.of(LocalDate.now().withDayOfMonth(1), LocalTime.MIN);

        // 属性消息
        int todayPropertyMessages = Math.toIntExact(devicePropertyMapper.selectCount(
                new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true).ge(IotDevicePropertyEntity::getCollectTime, todayStart)));
        int monthPropertyMessages = Math.toIntExact(devicePropertyMapper.selectCount(
                new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true).ge(IotDevicePropertyEntity::getCollectTime, monthStart)));

        // 事件消息
        int todayEventMessages = Math.toIntExact(deviceEventMapper.selectCount(
                new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true).ge(IotDeviceEventEntity::getEventTime, todayStart)));
        int monthEventMessages = Math.toIntExact(deviceEventMapper.selectCount(
                new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true).ge(IotDeviceEventEntity::getEventTime, monthStart)));

        statistics.setTodayMessages(todayPropertyMessages + todayEventMessages);
        statistics.setMonthMessages(monthPropertyMessages + monthEventMessages);

        return statistics;
    }

    /**
     * 获取消息趋势
     *
     * @param timeRange 时间范围类型（hour-最近1小时, day-最近24小时, week-最近一周）
     * @param startTime 开始时间（自定义时间范围）
     * @param endTime   结束时间（自定义时间范围）
     * @return 消息趋势数据
     */
    @Override
    public IotMessageTrendDTO getMessageTrend(String timeRange, LocalDateTime startTime, LocalDateTime endTime) {
        dataAccess.factory("read");
        com.kitex.mom.iot.service.impl.IotAlarmService.require((startTime==null&&endTime==null)||(startTime!=null&&endTime!=null&&startTime.isBefore(endTime)&&!endTime.isAfter(startTime.plusDays(31))),"自定义统计时间须成对提供且跨度最多31天");
        IotMessageTrendDTO trendDTO = new IotMessageTrendDTO();
        List<String> xAxis = new ArrayList<>();
        List<Integer> series = new ArrayList<>();

        // 如果提供了自定义时间范围，则使用自定义范围
        if (startTime != null && endTime != null) {
            generateCustomRangeTrend(startTime, endTime, trendDTO);
            return trendDTO;
        }

        // 根据时间范围类型生成趋势数据
        switch (timeRange) {
            case "hour":
                generateHourlyTrend(trendDTO);
                break;
            case "day":
                generateDailyTrend(trendDTO);
                break;
            case "week":
                generateWeeklyTrend(trendDTO);
                break;
            default:
                generateHourlyTrend(trendDTO);
        }

        return trendDTO;
    }

    /**
     * 生成最近1小时的趋势数据
     *
     * @param trendDTO 趋势数据DTO
     */
    private void generateHourlyTrend(IotMessageTrendDTO trendDTO) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(1);

        List<String> xAxis = new ArrayList<>();
        List<Integer> series = new ArrayList<>();

        // 生成12个5分钟间隔的时间点
        for (int i = 0; i < 12; i++) {
            LocalDateTime pointTime = startTime.plusMinutes(i * 5);
            LocalDateTime nextPointTime = pointTime.plusMinutes(5);

            xAxis.add(pointTime.format(DateTimeFormatter.ofPattern("HH:mm")));

            // 统计该时间段内的消息数量
            int propertyCount = Math.toIntExact(devicePropertyMapper.selectCount(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true)
                            .ge(IotDevicePropertyEntity::getCollectTime, pointTime)
                            .lt(IotDevicePropertyEntity::getCollectTime, nextPointTime)));

            int eventCount = Math.toIntExact(deviceEventMapper.selectCount(
                    new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true)
                            .ge(IotDeviceEventEntity::getEventTime, pointTime)
                            .lt(IotDeviceEventEntity::getEventTime, nextPointTime)));

            series.add(propertyCount + eventCount);
        }

        trendDTO.setXAxis(xAxis);
        trendDTO.setSeries(series);
    }

    /**
     * 生成最近24小时的趋势数据
     *
     * @param trendDTO 趋势数据DTO
     */
    private void generateDailyTrend(IotMessageTrendDTO trendDTO) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(24);

        List<String> xAxis = new ArrayList<>();
        List<Integer> series = new ArrayList<>();

        // 生成24个1小时间隔的时间点
        for (int i = 0; i < 24; i++) {
            LocalDateTime pointTime = startTime.plusHours(i);
            LocalDateTime nextPointTime = pointTime.plusHours(1);

            xAxis.add(pointTime.format(DateTimeFormatter.ofPattern("HH:00")));

            // 统计该时间段内的消息数量
            int propertyCount = Math.toIntExact(devicePropertyMapper.selectCount(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true)
                            .ge(IotDevicePropertyEntity::getCollectTime, pointTime)
                            .lt(IotDevicePropertyEntity::getCollectTime, nextPointTime)));

            int eventCount = Math.toIntExact(deviceEventMapper.selectCount(
                    new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true)
                            .ge(IotDeviceEventEntity::getEventTime, pointTime)
                            .lt(IotDeviceEventEntity::getEventTime, nextPointTime)));

            series.add(propertyCount + eventCount);
        }

        trendDTO.setXAxis(xAxis);
        trendDTO.setSeries(series);
    }

    /**
     * 生成最近一周的趋势数据
     *
     * @param trendDTO 趋势数据DTO
     */
    private void generateWeeklyTrend(IotMessageTrendDTO trendDTO) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(7);

        List<String> xAxis = new ArrayList<>();
        List<Integer> series = new ArrayList<>();

        // 生成7个1天间隔的时间点
        for (int i = 0; i < 7; i++) {
            LocalDateTime pointTime = startTime.plusDays(i);
            LocalDateTime nextPointTime = pointTime.plusDays(1);

            xAxis.add(pointTime.format(DateTimeFormatter.ofPattern("MM-dd")));

            // 统计该时间段内的消息数量
            int propertyCount = Math.toIntExact(devicePropertyMapper.selectCount(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true)
                            .ge(IotDevicePropertyEntity::getCollectTime, pointTime)
                            .lt(IotDevicePropertyEntity::getCollectTime, nextPointTime)));

            int eventCount = Math.toIntExact(deviceEventMapper.selectCount(
                    new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true)
                            .ge(IotDeviceEventEntity::getEventTime, pointTime)
                            .lt(IotDeviceEventEntity::getEventTime, nextPointTime)));

            series.add(propertyCount + eventCount);
        }

        trendDTO.setXAxis(xAxis);
        trendDTO.setSeries(series);
    }

    /**
     * 生成自定义时间范围的趋势数据
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @param trendDTO  趋势数据DTO
     */
    private void generateCustomRangeTrend(LocalDateTime startTime, LocalDateTime endTime, IotMessageTrendDTO trendDTO) {
        // 计算时间间隔（小时）
        double hours = java.time.Duration.between(startTime, endTime).toMillis() / 3600000.0;

        List<String> xAxis = new ArrayList<>();
        List<Integer> series = new ArrayList<>();

        // 根据时间范围长度决定间隔
        int intervalHours;
        DateTimeFormatter formatter;

        if (hours <= 24) {
            // 24小时内，每小时一个点
            intervalHours = 1;
            formatter = DateTimeFormatter.ofPattern("HH:00");
        } else if (hours <= 168) {
            // 一周内，每6小时一个点
            intervalHours = 6;
            formatter = DateTimeFormatter.ofPattern("MM-dd HH:00");
        } else {
            // 超过一周，每天一个点
            intervalHours = 24;
            formatter = DateTimeFormatter.ofPattern("MM-dd");
        }

        // 计算点数
        int points = (int) Math.ceil(hours / (double) intervalHours);

        // 生成时间点和数据
        for (int i = 0; i < points; i++) {
            LocalDateTime pointTime = startTime.plusHours(i * intervalHours);
            LocalDateTime nextPointTime = pointTime.plusHours(intervalHours);

            // 确保不超过结束时间
            if (nextPointTime.isAfter(endTime)) {
                nextPointTime = endTime;
            }

            xAxis.add(pointTime.format(formatter));

            // 统计该时间段内的消息数量
            int propertyCount = Math.toIntExact(devicePropertyMapper.selectCount(
                    new LambdaQueryWrapper<IotDevicePropertyEntity>().eq(IotDevicePropertyEntity::getScopeVerified,true)
                            .ge(IotDevicePropertyEntity::getCollectTime, pointTime)
                            .lt(IotDevicePropertyEntity::getCollectTime, nextPointTime)));

            int eventCount = Math.toIntExact(deviceEventMapper.selectCount(
                    new LambdaQueryWrapper<IotDeviceEventEntity>().eq(IotDeviceEventEntity::getScopeVerified,true)
                            .ge(IotDeviceEventEntity::getEventTime, pointTime)
                            .lt(IotDeviceEventEntity::getEventTime, nextPointTime)));

            series.add(propertyCount + eventCount);
        }

        trendDTO.setXAxis(xAxis);
        trendDTO.setSeries(series);
    }
}
