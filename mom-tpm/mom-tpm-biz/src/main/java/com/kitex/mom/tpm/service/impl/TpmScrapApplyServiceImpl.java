package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.DeviceUsableEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.mapper.TpmScrapApplyMapper;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备报废申请表
 *
 * @author kitex
 * @date 2025-02-21 11:34:10
 */
@Service
@AllArgsConstructor
public class TpmScrapApplyServiceImpl extends ServiceImpl<TpmScrapApplyMapper, TpmScrapApplyEntity> implements TpmScrapApplyService {

    private final RemoteDeviceService remoteDeviceService;
    private final TpmScrapRecordService tpmScrapRecordService;
    private final TpmMaintenancePlanService tpmMaintenancePlanService;
    private final TpmMaintenancePlanDetailsService tpmMaintenancePlanDetailsService;
    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    private final TpmMaintenanceTaskDetailsService tpmMaintenanceTaskDetailsService;
    private final TpmRepairTaskService tpmRepairTaskService;
    private final TpmRepairTaskDetailsService tpmRepairTaskDetailsService;
    private final TpmCheckPlanService tpmCheckPlanService;
    private final TpmCheckTaskService tpmCheckTaskService;
    private final TpmCheckTaskDetailsService tpmCheckTaskDetailsService;
    private final EventPublisherUtil eventPublisherUtil;
    private final TpmDeviceMoldRelaService tpmDeviceMoldRelaService;

    @Override
    public Boolean saveApply(TpmScrapApplyEntity tpmScrapApply) {
        LocalDate now = LocalDate.now();
        List<MdmDeviceEntity> deviceList = remoteDeviceService.getAllMdmDeviceList().getData();
        if (CollUtil.isEmpty(deviceList)) {
            throw new RuntimeException("访问设备数据失败，请授权或检查服务是否运行!");
        }
        List<String> deviceCodeList = Arrays.asList(tpmScrapApply.getDeviceCode().split(","));
        Map<String, MdmDeviceEntity> deviceMap = deviceList.stream().filter(item -> deviceCodeList.contains(item.getDeviceCode()))
                .collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (k1, k2) -> k1));
        List<TpmScrapApplyEntity> applyList = new ArrayList<>();
        deviceCodeList.forEach(item -> {
            MdmDeviceEntity mdmDevice = deviceMap.get(item);
            TpmScrapApplyEntity apply = new TpmScrapApplyEntity();
            BeanUtils.copyProperties(tpmScrapApply, apply, "id");
            apply.setDeviceCode(item);
            apply.setDeviceName(ObjectUtil.isNotEmpty(mdmDevice) ? mdmDevice.getDeviceName() : null);
            apply.setApplyTime(now);
            apply.setClassCode(ObjectUtil.isNotEmpty(mdmDevice) ? mdmDevice.getClassCode() : null);
            apply.setClassName(ObjectUtil.isNotEmpty(mdmDevice) ? mdmDevice.getClassName() : null);
            apply.setState(ScrapStateEnum.CREATE.getCode());
            applyList.add(apply);
        });
        return this.saveBatch(applyList);
    }


    /**
     * 批量通过 、作废
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean handle(List<Long> toList, String type) {
        MomUser user = SecurityUtils.getUser();
        LocalDateTime timeNow = LocalDateTime.now();
        LocalDate dateNow = LocalDate.now();
        List<TpmScrapApplyEntity> applyList = this.listByIds(toList);
        applyList.forEach(item -> {
            item.setReviewerCode(user.getUsername());
            item.setReviewerName(user.getName());
            item.setReviewerTime(timeNow);
        });

        if (type.equals(ScrapStateEnum.PASS.getCode())) {
            List<String> deviceCodeList = applyList.stream().map(TpmScrapApplyEntity::getDeviceCode).toList();

            //查询设备
            List<MdmDeviceEntity> deviceList = remoteDeviceService.getAllMdmDeviceList().getData();
            if (CollUtil.isEmpty(deviceList)) {
                throw new RuntimeException("访问设备数据失败，请授权或检查主数据服务是否运行!");
            }
            //更改设备状态
            deviceList = deviceList.stream().filter(item -> deviceCodeList.contains(item.getDeviceCode())).toList();
            deviceList.forEach(item -> {
                item.setDeviceUsable(DeviceUsableEnum.NO.getCode());
                item.setDeviceState(DeviceStateEnum.SCRAP.getCode());
                item.setStatus(false);
            });
            MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
            mdmDevice.setDeviceList(deviceList);
            remoteDeviceService.updateBatchById(mdmDevice);
            applyList.forEach(item -> {
                eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
                    entity.setDeviceCode(item.getDeviceCode());
                    entity.setDeviceName(item.getDeviceName());
                    entity.setEventCode(item.getApplyCode());
                    entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
                    entity.setEventSource(DeviceLifeCycleEventEnum.SCRAP.getCode());
                    entity.setDescription("设备: " + item.getDeviceName() + "【" + item.getDeviceCode() + "】已报废,报废申请单号为【" + item.getApplyCode() + "】");
                });
            });

            //创建报废记录
            List<TpmScrapRecordEntity> recordList = new ArrayList<>();
            applyList.forEach(item -> {
                item.setScrapTime(dateNow);
                item.setState(ScrapStateEnum.PASS.getCode());

                TpmScrapRecordEntity record = new TpmScrapRecordEntity();
                BeanUtils.copyProperties(item, record, "id");
                record.setScrapTime(dateNow);
                record.setScrapReason(item.getApplyReason());
                recordList.add(record);
            });
            tpmScrapRecordService.saveBatch(recordList);

            //废除设备的所有信息
            //如果被绑定到模具上，删除绑定关系
            tpmDeviceMoldRelaService.remove(new LambdaQueryWrapper<TpmDeviceMoldRelaEntity>()
                    .in(TpmDeviceMoldRelaEntity::getMoldCode, deviceCodeList));


            //保养计划、未执行和处理中的任务
            List<TpmMaintenancePlanEntity> maintenancePlanEntities = tpmMaintenancePlanService.list(new LambdaQueryWrapper<TpmMaintenancePlanEntity>()
                    .in(TpmMaintenancePlanEntity::getDeviceCode, deviceCodeList));
            tpmMaintenancePlanService.removeBatchByIds(maintenancePlanEntities);
            List<Long> mpIds = maintenancePlanEntities.stream().map(TpmMaintenancePlanEntity::getId).toList();
            if (CollUtil.isNotEmpty(mpIds)) {
                tpmMaintenancePlanDetailsService.remove(new LambdaQueryWrapper<TpmMaintenancePlanDetailsEntity>()
                        .in(TpmMaintenancePlanDetailsEntity::getPlanId, mpIds));
            }
            List<TpmMaintenanceTaskEntity> mainTaskList = tpmMaintenanceTaskService.list(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                    .in(TpmMaintenanceTaskEntity::getDeviceCode, deviceCodeList)
                    .and(wq -> wq.eq(TpmMaintenanceTaskEntity::getState, MaintenanceTaskStateEnum.WAIT.getCode())
                            .or().eq(TpmMaintenanceTaskEntity::getState, MaintenanceTaskStateEnum.ING.getCode())));
            tpmMaintenanceTaskService.removeBatchByIds(mainTaskList);
            List<String> mtCodes = mainTaskList.stream().map(TpmMaintenanceTaskEntity::getTaskCode).toList();
            if (CollUtil.isNotEmpty(mtCodes)) {
                tpmMaintenanceTaskDetailsService.remove(new LambdaQueryWrapper<TpmMaintenanceTaskDetailsEntity>()
                        .in(TpmMaintenanceTaskDetailsEntity::getTaskCode, mtCodes));
            }
            //未执行和处理中的维修任务
            List<TpmRepairTaskEntity> repairTaskList = tpmRepairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                    .in(TpmRepairTaskEntity::getDeviceCode, deviceCodeList)
                    .eq(TpmRepairTaskEntity::getState, RepairStateEnum.CREATE.getCode()));
            tpmRepairTaskService.removeBatchByIds(repairTaskList);
            List<String> rtCodes = repairTaskList.stream().map(TpmRepairTaskEntity::getTaskCode).toList();
            if (CollUtil.isNotEmpty(rtCodes)) {
                tpmRepairTaskDetailsService.remove(new LambdaQueryWrapper<TpmRepairTaskDetailsEntity>()
                        .in(TpmRepairTaskDetailsEntity::getTaskCode, rtCodes));
            }
            //点巡检计划和任务
            tpmCheckPlanService.remove(new LambdaQueryWrapper<TpmCheckPlanEntity>()
                    .in(TpmCheckPlanEntity::getDeviceCode, deviceCodeList));
            List<TpmCheckTaskEntity> checkTaskList = tpmCheckTaskService.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                    .in(TpmCheckTaskEntity::getDeviceCode, deviceCodeList)
                    .and(wq -> wq.eq(TpmCheckTaskEntity::getState, CheckTaskStateEnum.WAIT.getCode())
                            .or().eq(TpmCheckTaskEntity::getState, CheckTaskStateEnum.ING.getCode())));
            tpmCheckTaskService.removeBatchByIds(checkTaskList);
            List<String> ctCodes = checkTaskList.stream().map(TpmCheckTaskEntity::getTaskCode).toList();
            if (CollUtil.isNotEmpty(ctCodes)) {
                tpmCheckTaskDetailsService.remove(new LambdaQueryWrapper<TpmCheckTaskDetailsEntity>()
                        .in(TpmCheckTaskDetailsEntity::getTaskCode, ctCodes));
            }
        }
        if (type.equals(ScrapStateEnum.CANCEL.getCode())) {
            applyList.forEach(item -> {
                item.setState(ScrapStateEnum.CANCEL.getCode());
            });
        }
        return this.updateBatchById(applyList);
    }
}