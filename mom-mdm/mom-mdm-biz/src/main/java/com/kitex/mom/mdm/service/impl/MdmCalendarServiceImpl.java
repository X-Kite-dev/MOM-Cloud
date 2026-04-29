package com.kitex.mom.mdm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.mapper.MdmCalendarMapper;
import com.kitex.mom.mdm.service.MdmCalendarService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 工作日历
 *
 * @author kitex
 * @date 2025-02-17 11:58:28
 */
@Service
@AllArgsConstructor
public class MdmCalendarServiceImpl extends ServiceImpl<MdmCalendarMapper, MdmCalendarEntity> implements MdmCalendarService {

    /**
     * 查询工作日历列表
     *
     * @param mdmCalendar 工作日历实体
     * @return R
     */
    @Override
    public List<MdmCalendarEntity> getList(MdmCalendarEntity mdmCalendar) {
        LocalDate time = mdmCalendar.getTime();
        // 获取上个月的月初
        LocalDate lastMonthStart = time.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
        // 获取下个月的月末
        LocalDate nextMonthEnd = time.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        LambdaQueryWrapper<MdmCalendarEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.between(MdmCalendarEntity::getTime, lastMonthStart, nextMonthEnd);
        return this.list(wrapper);
    }
}