package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.dto.*;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.mapper.TpmEventRecordMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsReplaceMapper;
import com.kitex.mom.tpm.service.TpmCheckTaskService;
import com.kitex.mom.tpm.service.TpmDeviceBoardService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
import com.kitex.mom.tpm.service.TpmRepairTaskService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备看板接口
 *
 * @author kitex
 */
@Service
@AllArgsConstructor
public class TpmDeviceBoardServiceImpl implements TpmDeviceBoardService {

    private final TpmEventRecordMapper tpmEventRecordMapper;
    private final TpmRepairTaskService tpmRepairTaskService;
    private final RemoteDeviceService remoteDeviceService;
    private final RemoteClassesService remoteClassesService;
    private final TpmSparePartsReplaceMapper tpmSparePartsReplaceMapper;
    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    private final TpmCheckTaskService tpmCheckTaskService;


    /**
     * 今日报修-统计
     */
    @Override
    public RepairSummaryDto repairSummary() {
        //获取今日时间
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.withHour(0).withMinute(0).withSecond(0);
        List<TpmRepairTaskEntity> taskList = tpmRepairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .le(TpmRepairTaskEntity::getCreateTime, endTime)
                .ge(TpmRepairTaskEntity::getCreateTime, startTime));
        RepairSummaryDto summaryDto = new RepairSummaryDto();
        //今日报修总数 没有派工，派工数为0
        summaryDto.setTotalRepairs(taskList.size());
        //今日待派工总数
        summaryDto.setPendingAssignment(0);
        //今日已派工总数
        summaryDto.setAssigned(taskList.size());
        //今日内部维修总数
        List<TpmRepairTaskEntity> internalList = taskList.stream().filter(item -> item.getType().equals(RepairTaskTypeEnum.INTERNAL.getCode())).toList();
        summaryDto.setInternalTasks(internalList.size());
        //今日内部维修完成总数
        List<TpmRepairTaskEntity> internalCompletedList = internalList.stream().filter(item -> item.getState().equals(RepairStateEnum.COMPLETE.getCode())).toList();
        summaryDto.setInternalCompleted(internalCompletedList.size());
        //今日外部维修总数
        List<TpmRepairTaskEntity> outsourcingList = taskList.stream().filter(item -> item.getType().equals(RepairTaskTypeEnum.OUTSOURCING.getCode())).toList();
        summaryDto.setExternalTasks(outsourcingList.size());
        //今日外部维修完成总数
        List<TpmRepairTaskEntity> outsourcingCompletedList = outsourcingList.stream().filter(item -> item.getState().equals(RepairStateEnum.COMPLETE.getCode())).toList();
        summaryDto.setExternalCompleted(outsourcingCompletedList.size());
        return summaryDto;
    }

    /**
     * 设备使用状态
     */
    @Override
    public EquipmentUsageDto equipmentUsage() {
        R<List<MdmDeviceEntity>> mdmDeviceList = remoteDeviceService.getMdmDeviceList();
        if (mdmDeviceList.getCode() != 0) {
            throw new CheckedException("查询设备数据失败，请检查主数据服务是否运行！");
        }
        List<MdmDeviceEntity> data = mdmDeviceList.getData();

        EquipmentUsageDto equipmentUsageDto = new EquipmentUsageDto();
        //空闲
        equipmentUsageDto.setIdle(data.stream()
                .filter(item -> DeviceStateEnum.IDLE.getCode().equals(item.getDeviceState())).toList().size());
        equipmentUsageDto.setIdleRate(data.size() > 0 ?
                BigDecimal.valueOf(equipmentUsageDto.getIdle()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
        //运行中
        equipmentUsageDto.setRunning(data.stream()
                .filter(item -> DeviceStateEnum.RUNNING.getCode().equals(item.getDeviceState())).toList().size());
        equipmentUsageDto.setRunningRate(data.size() > 0 ?
                BigDecimal.valueOf(equipmentUsageDto.getRunning()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
        //停机
        equipmentUsageDto.setStopped(data.size() - equipmentUsageDto.getIdle() - equipmentUsageDto.getRunning());
        equipmentUsageDto.setStoppedRate(data.size() > 0 ?
                BigDecimal.valueOf(equipmentUsageDto.getStopped()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
        //设备使用率
        equipmentUsageDto.setUtilizationRate(equipmentUsageDto.getRunningRate());
        return equipmentUsageDto;
    }


    /**
     * 设备概览
     */
    @Override
    public EquipmentOverviewDto equipmentOverview() {
        R<List<MdmDeviceEntity>> allListWithScrap = remoteDeviceService.getAllListWithScrap();
        if (allListWithScrap.getCode() != 0) {
            throw new CheckedException("查询设备数据失败，请检查主数据服务是否运行！");
        }
        List<MdmDeviceEntity> data = allListWithScrap.getData();
        EquipmentOverviewDto equipmentOverviewDto = new EquipmentOverviewDto();
        equipmentOverviewDto.setTotal(data.size());
        //暂停
        List<MdmDeviceEntity> pauseList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.PAUSE.getCode())).toList();
        equipmentOverviewDto.setPause(EquipmentOverviewDto.getEquipmentDataInfoDto(pauseList.size(), data.size() > 0 ?
                BigDecimal.valueOf(pauseList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        //已报废
        List<MdmDeviceEntity> scrapList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.SCRAP.getCode())).toList();
        equipmentOverviewDto.setDecommissioned(EquipmentOverviewDto.getEquipmentDataInfoDto(scrapList.size(), data.size() > 0 ?
                BigDecimal.valueOf(scrapList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        //保养中
        List<MdmDeviceEntity> maintenanceList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.MAINTENANCE.getCode())).toList();
        equipmentOverviewDto.setMaintenance(EquipmentOverviewDto.getEquipmentDataInfoDto(maintenanceList.size(), data.size() > 0 ?
                BigDecimal.valueOf(maintenanceList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        //维修中
        List<MdmDeviceEntity> repairList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.REPAIR.getCode())).toList();
        equipmentOverviewDto.setRepairing(EquipmentOverviewDto.getEquipmentDataInfoDto(repairList.size(), data.size() > 0 ?
                BigDecimal.valueOf(repairList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        //空闲
        List<MdmDeviceEntity> idleList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.IDLE.getCode())).toList();
        equipmentOverviewDto.setIdle(EquipmentOverviewDto.getEquipmentDataInfoDto(idleList.size(), data.size() > 0 ?
                BigDecimal.valueOf(idleList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        //运行中
        List<MdmDeviceEntity> runningList = data.stream().filter(item -> item.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())).toList();
        equipmentOverviewDto.setRunning(EquipmentOverviewDto.getEquipmentDataInfoDto(runningList.size(), data.size() > 0 ?
                BigDecimal.valueOf(runningList.size()).divide(BigDecimal.valueOf(data.size()), new MathContext(2, RoundingMode.HALF_UP)).multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO));
        return equipmentOverviewDto;
    }


    /**
     * 本月故障次数排名
     */
    @Override
    public List<EquipmentBoardSortDto> failureRanking() {
        List<LocalDateTime> monthTimeRange = getMonthTimeRange();
        LocalDateTime startTime = monthTimeRange.get(0);
        LocalDateTime endTime = monthTimeRange.get(1);
        List<TpmRepairTaskEntity> taskList = tpmRepairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .lt(TpmRepairTaskEntity::getCreateTime, endTime)
                .ge(TpmRepairTaskEntity::getCreateTime, startTime));
        //根据设备编码分组，查看每个设备的基本信息
        Map<String, TpmRepairTaskEntity> taskMap = taskList.stream().collect(Collectors.toMap(TpmRepairTaskEntity::getDeviceCode, Function.identity(), (k1, k2) -> k2));
        //根据设备编码分组，统计每个设备报修次数
        Map<String, Long> taskValMap = taskList.stream().collect(Collectors.groupingBy(TpmRepairTaskEntity::getDeviceCode,
                Collectors.counting()));
        return taskValMap.entrySet().stream()
                .map(entry -> new EquipmentBoardSortDto(entry.getKey(), taskMap.get(entry.getKey()).getDeviceName(), entry.getValue().intValue(), taskList.size()))
                .sorted(Comparator.comparing(EquipmentBoardSortDto::getValue).reversed())
                .limit(8)
                .collect(Collectors.toList());
    }

    /**
     * 本月停机排名
     */
    @Override
    public List<EquipmentBoardSortDto> downtimeRanking() {
        //获取时间范围
        //获取0点时间对应的班次
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime time = now.withHour(0).withMinute(0).withSecond(0);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        R<List<MdmClassesEntity>> mdmClassesEntityR = remoteClassesService.getClassesBydate(time.format(formatter), 1);
        if (mdmClassesEntityR.getCode() != 0) {
            throw new CheckedException("查询班次数据失败，请检查主数据服务是否运行！");
        }
        if (CollUtil.isEmpty(mdmClassesEntityR.getData())) {
            throw new CheckedException("查询班次数据为空！");
        }
        MdmClassesEntity data = mdmClassesEntityR.getData().get(0);
        //获取每月的开始时间和结束时间 左闭右开
        LocalDate localDate = LocalDate.now();
        LocalDateTime orgTime = LocalDateTime.of(localDate.withDayOfMonth(1), ObjectUtil.isNotEmpty(data) ? data.getEndTime() : LocalTime.MIN);
        LocalDateTime startTime = now.isBefore(orgTime) ? orgTime.minusMonths(1) : orgTime;
        LocalDateTime endTime = now;
        //查询时间范围内，设备停机的时长总和(停机时长 = 月内的上班时间 - 开机时间至关机时间)
        List<EquipmentRunDto> equipmentRunList = tpmEventRecordMapper.countDowntimeRankin(startTime, endTime, DeviceLifeCycleEventEnum.POWER_ON.getCode(), DeviceLifeCycleEventEnum.CLOSE.getCode());

        //获取开始时间和结束时间之间的分钟数
        Duration duration = Duration.between(startTime, endTime);
        int minutesDifference = Math.toIntExact(duration.toMinutes());

        return equipmentRunList.stream()
                .map(item -> new EquipmentBoardSortDto(item.getDeviceCode(), item.getDeviceName(), (minutesDifference - item.getRunTime()), minutesDifference))
                .sorted(Comparator.comparing(EquipmentBoardSortDto::getValue).reversed())
                .limit(8)
                .collect(Collectors.toList());
    }


    /**
     * 备件更换 - 暂定一个月的
     */
    @Override
    public SparePartsReplacementDto sparePartsReplacement() {
        List<LocalDateTime> monthTimeRange = getMonthTimeRange();
        LocalDateTime startTime = monthTimeRange.get(0);
        LocalDateTime endTime = monthTimeRange.get(1);
        //查询备件消耗
        List<RankingDto> rankingList = tpmSparePartsReplaceMapper.sparePartsReplacement(startTime, endTime);
        //todo 备件花费价格
        SparePartsReplacementDto sparePartsReplacementDto = new SparePartsReplacementDto();
        sparePartsReplacementDto.setRanking(rankingList);
//		sparePartsReplacementDto.setTotalCost(rankingList.stream().map(RankingDto::getCost).reduce(BigDecimal.ZERO, BigDecimal::add));
        sparePartsReplacementDto.setTotalReplacement(rankingList.stream().map(RankingDto::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        return sparePartsReplacementDto;
    }

    @Override
    public MaintenanceStatusDto maintenanceStatus() {
        List<LocalDateTime> monthTimeRange = getMonthTimeRange();
        LocalDateTime startTime = monthTimeRange.get(0);
        LocalDateTime endTime = monthTimeRange.get(1);
        //查询本月的保养任务进行统计
        List<TpmMaintenanceTaskEntity> list = tpmMaintenanceTaskService.list(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));

        MaintenanceStatusDto maintenanceStatusDto = new MaintenanceStatusDto();
        maintenanceStatusDto.setTotalTasks(list.size());
        maintenanceStatusDto.setCompletedTasks((int) list.stream()
                .filter(item -> MaintenanceTaskStateEnum.HANDLE.getCode().equals(item.getState())
                        || MaintenanceTaskStateEnum.TIMEOUT_HANDLE.getCode().equals(item.getState())).count());
        maintenanceStatusDto.setTimelyTasks((int) list.stream()
                .filter(item -> item.getState().equals(MaintenanceTaskStateEnum.HANDLE.getCode())).count());
        maintenanceStatusDto.setCompletionRate(list.size() > 0 ? BigDecimal.valueOf(maintenanceStatusDto.getCompletedTasks())
                .divide(BigDecimal.valueOf(maintenanceStatusDto.getTotalTasks()), 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)) : BigDecimal.ZERO);
        maintenanceStatusDto.setTimelinessRate(list.size() > 0 ? BigDecimal.valueOf(maintenanceStatusDto.getTimelyTasks())
                .divide(BigDecimal.valueOf(maintenanceStatusDto.getTotalTasks()), 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)) : BigDecimal.ZERO);
        return maintenanceStatusDto;
    }

    @Override
    public InspectionStatusDto inspectionStatus() {
        List<LocalDateTime> monthTimeRange = getMonthTimeRange();
        LocalDateTime startTime = monthTimeRange.get(0);
        LocalDateTime endTime = monthTimeRange.get(1);
        //查询本月的巡检任务进行统计
        List<TpmCheckTaskEntity> checkTaskList = tpmCheckTaskService.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .lt(BaseCore::getCreateTime, endTime)
                .ge(BaseCore::getCreateTime, startTime));
        InspectionStatusDto inspectionStatusDto = new InspectionStatusDto();
        inspectionStatusDto.setPendingCount((int) checkTaskList.stream()
                .filter(item -> item.getState().equals(CheckTaskStateEnum.ASSIGNED.getCode())).count());
        inspectionStatusDto.setOverdueCount((int) checkTaskList.stream()
                .filter(item -> item.getState().equals(CheckTaskStateEnum.ING.getCode())).count());
        inspectionStatusDto.setCompleted((int) checkTaskList.stream()
                .filter(item -> item.getState().equals(CheckTaskStateEnum.HANDLE.getCode())).count());
        inspectionStatusDto.setCompletionRate(checkTaskList.size() > 0 ?
                BigDecimal.valueOf(inspectionStatusDto.getCompleted()).divide(BigDecimal.valueOf(checkTaskList.size()), 2, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100)) : BigDecimal.ZERO);
        return inspectionStatusDto;
    }


    /**
     * 获取本月时间范围  左闭右开
     */
    private List<LocalDateTime> getMonthTimeRange() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime end = start.plusMonths(1);
        return Arrays.asList(start, end);
    }


}
