package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.mom.tpm.dto.TpmCalendarDto;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.CheckTypeEnum;
import com.kitex.mom.tpm.service.TpmCalendarService;
import com.kitex.mom.tpm.service.TpmCheckTaskService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
import com.kitex.mom.tpm.service.TpmRepairTaskService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 设备日历
 */
@Service
@AllArgsConstructor
public class TpmCalendarServiceImpl implements TpmCalendarService {

    //保养任务
    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    //维修任务
    private final TpmRepairTaskService tpmRepairTaskService;
    //点/巡检任务
    private final TpmCheckTaskService tpmCheckTaskService;


    @Override
    public TpmCalendarDto getCalendarMsg(String date) {
        //返回值
        TpmCalendarDto tpmCalendarDto = new TpmCalendarDto();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parse = StrUtil.isNotBlank(date) ? LocalDate.parse(date, formatter) : LocalDate.now();
        //获取时间范围 左闭右开 [startTime,endTime)
        LocalDateTime startTime = LocalDateTime.of(parse.withDayOfMonth(1), LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(parse.withDayOfMonth(1).plusMonths(1), LocalTime.MIN);

        //查询保养任务
        List<TpmMaintenanceTaskEntity> maintenanceTaskList = tpmMaintenanceTaskService.list(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));

        List<String> maintenance = maintenanceTaskList.stream().map(item -> {
            return item.getCreateTime().format(formatter);
        }).distinct().toList();
        tpmCalendarDto.setMaintenance(maintenance);

        //查询维修任务
        List<TpmRepairTaskEntity> repairTaskList = tpmRepairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));
        List<String> repair = repairTaskList.stream().map(item -> {
            return item.getCreateTime().format(formatter);
        }).distinct().toList();
        tpmCalendarDto.setRepair(repair);

        //查询点/巡检任务
        List<TpmCheckTaskEntity> tpmCheckTaskList = tpmCheckTaskService.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));

        List<String> inspection = tpmCheckTaskList.stream().filter(item -> item.getType().equals(CheckTypeEnum.SPOT.getCode())).map(item -> {
            return item.getCreateTime().format(formatter);
        }).distinct().toList();
        List<String> patrol = tpmCheckTaskList.stream().filter(item -> item.getType().equals(CheckTypeEnum.PATROL.getCode())).map(item -> {
            return item.getCreateTime().format(formatter);
        }).distinct().toList();
        tpmCalendarDto.setInspection(inspection);
        tpmCalendarDto.setPatrol(patrol);
        return tpmCalendarDto;
    }

    @Override
    public List<TpmMaintenanceTaskEntity> getMaintenanceTaskByDay(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parse = StrUtil.isNotBlank(date) ? LocalDate.parse(date, formatter) : LocalDate.now();
        //获取时间范围 左闭右开 [startTime,endTime)
        LocalDateTime startTime = LocalDateTime.of(parse, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(parse.plusDays(1), LocalTime.MIN);
        //查询保养任务
        return tpmMaintenanceTaskService.list(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));
    }

    @Override
    public List<TpmRepairTaskEntity> getRepairTaskByDay(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parse = StrUtil.isNotBlank(date) ? LocalDate.parse(date, formatter) : LocalDate.now();
        //获取时间范围 左闭右开 [startTime,endTime)
        LocalDateTime startTime = LocalDateTime.of(parse, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(parse.plusDays(1), LocalTime.MIN);
        //查询维修任务
        return tpmRepairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));
    }

    @Override
    public List<TpmCheckTaskEntity> getCheckTaskByDay(String date, String type) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate parse = StrUtil.isNotBlank(date) ? LocalDate.parse(date, formatter) : LocalDate.now();
        //获取时间范围 左闭右开 [startTime,endTime)
        LocalDateTime startTime = LocalDateTime.of(parse, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(parse.plusDays(1), LocalTime.MIN);
        return tpmCheckTaskService.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getType, type)
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));
    }


}