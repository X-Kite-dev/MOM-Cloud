package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
    public Boolean handle(TpmMaintenanceTaskEntity tpmMaintenanceTask) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime planTime = getPlanTime(tpmMaintenanceTask.getHandleTimeUnit(), tpmMaintenanceTask.getHandleTime(), tpmMaintenanceTask.getCreateTime());

        List<TpmMaintenanceTaskDetailsEntity> detailsList = tpmMaintenanceTask.getDetailsList();
        //更改设备状态
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(tpmMaintenanceTask.getDeviceCode());
        MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
        if (ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("获取设备数据失败！");
        }

        if (data.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())
                || data.getDeviceState().equals(DeviceStateEnum.PAUSE.getCode())) {
            throw new RuntimeException("保养失败，设备处于生产状态，请生产完成后进行保养！");
        }

        Long count = detailsList.stream().filter(item -> StrUtil.isBlank(item.getResult())).count();
        if (count.compareTo(0L) == 0) {
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
        if (count.compareTo(0L) > 0 && count.compareTo((long) detailsList.size()) < 0) {
            //保养项目部分填写
            //任务状态为处理中
            tpmMaintenanceTask.setState(MaintenanceTaskStateEnum.ING.getCode());
            //更改设备状态 保养中 ，不可用,如果设备处于维修中状态 ， 状态不变
            if (ObjectUtil.isNotEmpty(data)) {
                if (!DeviceStateEnum.REPAIR.getCode().equals(data.getDeviceState())) {
                    data.setDeviceState(DeviceStateEnum.MAINTENANCE.getCode());
                    data.setDeviceUsable(DeviceUsableEnum.NO.getCode());
                }
            }
        }
        this.updateById(tpmMaintenanceTask);
        return tpmMaintenanceTaskDetailsService.updateBatchById(detailsList);
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