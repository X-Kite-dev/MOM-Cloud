package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.plan.entity.PlnUpdateLogEntity;
import com.kitex.mom.plan.mapper.PlnUpdateLogMapper;
import com.kitex.mom.plan.service.PlnUpdateLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 修改日志表
 *
 * @author kitex
 * @date 2025-02-26 10:06:07
 */
@Service
public class PlnUpdateLogServiceImpl extends ServiceImpl<PlnUpdateLogMapper, PlnUpdateLogEntity> implements PlnUpdateLogService {

    /**
     * 查询修改日志列表
     *
     * @param time 时间
     * @return
     */
    @Override
    public List<PlnUpdateLogEntity> getPlnUpdateLogList(LocalDate time) {
        // 获取上个月的月初
        LocalDate lastMonthStart = time.minusMonths(1).with(TemporalAdjusters.firstDayOfMonth());
        // 获取下个月的月末
        LocalDate nextMonthEnd = time.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        return this.list(new LambdaQueryWrapper<PlnUpdateLogEntity>().between(PlnUpdateLogEntity::getCreateTime, lastMonthStart, nextMonthEnd)
                .orderByDesc(PlnUpdateLogEntity::getCreateTime));
    }

    /**
     * 保存计划修改日志
     *
     * @param plnUpdateLogs 实体
     * @return
     */
    @Override
    public Boolean saveUpdateLog(List<PlnUpdateLogEntity> plnUpdateLogs) {
        plnUpdateLogs.forEach(plnUpdateLog -> {
            plnUpdateLog.setId(null);
            plnUpdateLog.setCreateTime(null);
            plnUpdateLog.setCreateBy(null);
        });
        return this.saveBatch(plnUpdateLogs);
    }
}