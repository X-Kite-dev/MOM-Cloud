package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.DeviceUsableEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmMaintenanceRecordEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskDetailsEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.enums.MaintenanceTaskStateEnum;
import com.kitex.mom.tpm.enums.TimeUnitEnum;
import com.kitex.mom.tpm.mapper.TpmMaintenanceTaskMapper;
import com.kitex.mom.tpm.service.TpmMaintenanceRecordService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskDetailsService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
import com.kitex.mom.tpm.service.TpmPlanEventOutboxService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 保养任务表
 *
 * @author kitex
 * @date 2025-02-06 15:17:39
 */
@Service
@AllArgsConstructor
public class TpmMaintenanceTaskServiceImpl extends ServiceImpl<TpmMaintenanceTaskMapper, TpmMaintenanceTaskEntity> implements TpmMaintenanceTaskService {

    private final TpmMaintenanceTaskDetailsService tpmMaintenanceTaskDetailsService;

    private final RemoteDeviceService remoteDeviceService;

    private final TpmMaintenanceRecordService tpmMaintenanceRecordService;

    private final TpmPlanEventOutboxService tpmPlanEventOutboxService;


    /**
     * 通过id查询保养任务表
     */
    @Override
    public TpmMaintenanceTaskEntity getByTaskId(Long id) {
        //查询主表
        TpmMaintenanceTaskEntity byId = this.getById(id);
        LocalDateTime planTime = getPlanTime(byId.getHandleTimeUnit(), byId.getHandleTime(), byId.getCreateTime());
        byId.setPlanMaintenanceTime(planTime);
        //查询子表
        List<TpmMaintenanceTaskDetailsEntity> detailsList = tpmMaintenanceTaskDetailsService.list(new LambdaQueryWrapper<TpmMaintenanceTaskDetailsEntity>()
                .eq(TpmMaintenanceTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }

    /**
     * 处理保养任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handle(TpmMaintenanceTaskEntity tpmMaintenanceTask) {
        if (tpmMaintenanceTask == null || tpmMaintenanceTask.getId() == null) {
            throw new RuntimeException("保养任务ID不能为空！");
        }
        TpmMaintenanceTaskEntity persisted = this.getOne(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .eq(TpmMaintenanceTaskEntity::getId, tpmMaintenanceTask.getId()).last("FOR UPDATE"));
        if (persisted == null) {
            throw new RuntimeException("保养任务不存在或已删除！");
        }
        if (MaintenanceTaskStateEnum.HANDLE.getCode().equals(persisted.getState())
                || MaintenanceTaskStateEnum.TIMEOUT_HANDLE.getCode().equals(persisted.getState())) {
            throw new RuntimeException("保养任务已完成，禁止重复处理！");
        }
        mergeAuthoritativeFields(tpmMaintenanceTask, persisted);
        validateStandardDuration(tpmMaintenanceTask);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime planTime = getPlanTime(tpmMaintenanceTask.getHandleTimeUnit(), tpmMaintenanceTask.getHandleTime(), tpmMaintenanceTask.getCreateTime());

        List<TpmMaintenanceTaskDetailsEntity> detailsList = tpmMaintenanceTask.getDetailsList();
        if (detailsList == null || detailsList.isEmpty()) {
            throw new RuntimeException("保养项目不能为空！");
        }
        validateTaskDetails(tpmMaintenanceTask.getTaskCode(), detailsList);
        //更改设备状态
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(tpmMaintenanceTask.getDeviceCode());
        R<MdmDeviceEntity> deviceResponse = remoteDeviceService.getDetails(mdmDevice);
        MdmDeviceEntity data = deviceResponse == null ? null : deviceResponse.getData();
        if (deviceResponse == null || !deviceResponse.isOk() || ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("获取设备数据失败！");
        }

        if (DeviceStateEnum.RUNNING.getCode().equals(data.getDeviceState())
                || DeviceStateEnum.PAUSE.getCode().equals(data.getDeviceState())) {
            throw new RuntimeException("保养失败，设备处于生产状态，请生产完成后进行保养！");
        }

        Long count = detailsList.stream().filter(item -> StrUtil.isBlank(item.getResult())).count();
        boolean completed = count.compareTo(0L) == 0;
        boolean started = count.compareTo(0L) > 0 && count.compareTo((long) detailsList.size()) < 0;
        boolean firstStart = started && !MaintenanceTaskStateEnum.ING.getCode().equals(persisted.getState());
        if (completed) {
            //保养项目全部填写
            //更改单子状态 为已完成 或者超时已完成
            if (now.isBefore(planTime)) {
                tpmMaintenanceTask.setState(MaintenanceTaskStateEnum.HANDLE.getCode());
            } else {
                tpmMaintenanceTask.setState(MaintenanceTaskStateEnum.TIMEOUT_HANDLE.getCode());
            }
            tpmMaintenanceTask.setMaintenanceTime(now);
            //更改设备状态 空闲 ，可用 如果设备处于维修中状态 ， 状态不变
            if (ObjectUtil.isNotEmpty(data)) {
                if (!DeviceStateEnum.REPAIR.getCode().equals(data.getDeviceState())) {
                    data.setDeviceState(DeviceStateEnum.IDLE.getCode());
                    data.setDeviceUsable(DeviceUsableEnum.YES.getCode());
                }
            }
            //创建保养记录信息
            TpmMaintenanceRecordEntity tpmMaintenanceRecord = new TpmMaintenanceRecordEntity();
            BeanUtils.copyProperties(tpmMaintenanceTask, tpmMaintenanceRecord, "id");
            tpmMaintenanceRecordService.save(tpmMaintenanceRecord);
        }
        if (started) {
            //保养项目部分填写
            //任务状态为处理中
            tpmMaintenanceTask.setState(MaintenanceTaskStateEnum.ING.getCode());
            if (firstStart) {
                tpmMaintenanceTask.setMaintenanceStartTime(now);
                tpmMaintenanceTask.setExpectedRecoveryTime(getPlanTime(tpmMaintenanceTask.getHandleTimeUnit(),
                        tpmMaintenanceTask.getHandleTime(), now));
                tpmMaintenanceTask.setRecoveryForecastReason("按保养任务标准处理时长自实际开始时间推算");
            }
            //更改设备状态 保养中 ，不可用,如果设备处于维修中状态 ， 状态不变
            if (ObjectUtil.isNotEmpty(data)) {
                if (!DeviceStateEnum.REPAIR.getCode().equals(data.getDeviceState())) {
                    data.setDeviceState(DeviceStateEnum.MAINTENANCE.getCode());
                    data.setDeviceUsable(DeviceUsableEnum.NO.getCode());
                }
            }
        }
        if (!this.updateById(tpmMaintenanceTask)) {
            throw new RuntimeException("保养任务状态保存失败！");
        }
        if (!tpmMaintenanceTaskDetailsService.updateBatchById(detailsList)) {
            throw new RuntimeException("保养项目结果保存失败！");
        }

        if (firstStart) {
            tpmPlanEventOutboxService.enqueueMaintenanceUnavailable(tpmMaintenanceTask,
                    tpmMaintenanceTask.getMaintenanceStartTime(), tpmMaintenanceTask.getExpectedRecoveryTime());
        }
        if (completed) {
            tpmPlanEventOutboxService.markMaintenanceRecovered(tpmMaintenanceTask);
        }
        // 原实现只修改了Feign查询返回对象但没有提交，本次补齐设备权威状态回写。
        R<Boolean> updateResponse = remoteDeviceService.updateById(data);
        if (updateResponse == null || !updateResponse.isOk() || !Boolean.TRUE.equals(updateResponse.getData())) {
            throw new RuntimeException("设备保养状态回写失败，本次保养处理已回滚！");
        }
        return Boolean.TRUE;
    }

    private void mergeAuthoritativeFields(TpmMaintenanceTaskEntity target, TpmMaintenanceTaskEntity source) {
        target.setTaskCode(source.getTaskCode());
        target.setDeviceCode(source.getDeviceCode());
        target.setDeviceName(source.getDeviceName());
        target.setFactoryCode(source.getFactoryCode());
        target.setCreateTime(source.getCreateTime());
        target.setHandleTime(source.getHandleTime());
        target.setHandleTimeUnit(source.getHandleTimeUnit());
        target.setMaintenanceStartTime(source.getMaintenanceStartTime());
        target.setExpectedRecoveryTime(source.getExpectedRecoveryTime());
        target.setRecoveryForecastReason(source.getRecoveryForecastReason());
    }

    private void validateStandardDuration(TpmMaintenanceTaskEntity task) {
        if (task.getHandleTime() == null || task.getHandleTime() <= 0
                || (!TimeUnitEnum.MINUTE.getCode().equals(task.getHandleTimeUnit())
                && !TimeUnitEnum.HOUR.getCode().equals(task.getHandleTimeUnit())
                && !TimeUnitEnum.DAY.getCode().equals(task.getHandleTimeUnit()))) {
            throw new RuntimeException("保养任务标准处理时长或单位无效，请先维护后再开始保养！");
        }
        if (task.getCreateTime() == null) {
            throw new RuntimeException("保养任务创建时间缺失，无法计算完成期限！");
        }
    }

    private void validateTaskDetails(String taskCode, List<TpmMaintenanceTaskDetailsEntity> submitted) {
        List<TpmMaintenanceTaskDetailsEntity> persisted = tpmMaintenanceTaskDetailsService.list(
                new LambdaQueryWrapper<TpmMaintenanceTaskDetailsEntity>()
                        .eq(TpmMaintenanceTaskDetailsEntity::getTaskCode, taskCode));
        Set<Long> persistedIds = persisted.stream().map(TpmMaintenanceTaskDetailsEntity::getId)
                .collect(Collectors.toSet());
        Set<Long> submittedIds = submitted.stream().map(TpmMaintenanceTaskDetailsEntity::getId)
                .collect(Collectors.toSet());
        if (persistedIds.isEmpty() || persistedIds.size() != persisted.size()
                || submittedIds.contains(null) || submittedIds.size() != submitted.size()
                || !persistedIds.equals(submittedIds)) {
            throw new RuntimeException("保养项目必须完整提交且只能修改当前任务明细！");
        }
    }


    private LocalDateTime getPlanTime(String handleTimeUnit, Integer handleTime, LocalDateTime defaultTime) {
        LocalDateTime results = defaultTime;
        if (TimeUnitEnum.MINUTE.getCode().equals(handleTimeUnit)) {
            results = defaultTime.plusMinutes(handleTime);
        }
        if (TimeUnitEnum.HOUR.getCode().equals(handleTimeUnit)) {
            results = defaultTime.plusHours(handleTime);
        }
        if (TimeUnitEnum.DAY.getCode().equals(handleTimeUnit)) {
            results = defaultTime.plusDays(handleTime);
        }
        return results;
    }
}
