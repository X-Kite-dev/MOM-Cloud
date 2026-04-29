package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.common.core.util.R;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.entity.*;
import com.kitex.mom.tpm.enums.*;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.mapper.TpmCheckTaskMapper;
import com.kitex.mom.tpm.service.*;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点/巡检任务
 *
 * @author kitex
 * @date 2025-02-18 17:49:05
 */
@Service
@AllArgsConstructor
public class TpmCheckTaskServiceImpl extends ServiceImpl<TpmCheckTaskMapper, TpmCheckTaskEntity> implements TpmCheckTaskService {

    private final TpmCheckTaskDetailsService tpmCheckTaskDetailsService;
    private final TpmCheckRecordService tpmCheckRecordService;
    private final RemoteDeviceService remoteDeviceService;
    private final TpmDeviceItemService tpmDeviceItemService;
    private final TpmCheckItemService tpmCheckItemService;
    private final RemoteSeqConfigService remoteSeqConfigService;
    private final EventPublisherUtil eventPublisherUtil;
    private final TpmDeviceMoldRelaService moldRelaService;

    /**
     * 获取任务明细
     *
     * @param id id
     * @return byId
     */
    @Override
    public TpmCheckTaskEntity getTaskById(Long id) {
        //查询主表
        TpmCheckTaskEntity byId = this.getById(id);
        //查询子表
        List<TpmCheckTaskDetailsEntity> detailsList = tpmCheckTaskDetailsService.list(new LambdaQueryWrapper<TpmCheckTaskDetailsEntity>()
                .eq(TpmCheckTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }

    /**
     * 任务处理
     *
     * @param tpmCheckTask 任务
     * @return 处理结果
     */
    @Override
    public Boolean handle(TpmCheckTaskEntity tpmCheckTask) {
        //查询设备
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(tpmCheckTask.getDeviceCode());
        MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
        if (ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }

        List<TpmCheckTaskDetailsEntity> detailsList = tpmCheckTask.getDetailsList();
        //每一项判断通过不通过
        for (TpmCheckTaskDetailsEntity tpmCheckTaskDetails : detailsList) {
            if (StrUtil.isNotBlank(tpmCheckTaskDetails.getResult())) {
                //如果是确认类型
                if (tpmCheckTaskDetails.getJudgeType().equals(YesNoEnum.NO.getCode())) {
                    tpmCheckTaskDetails.setJudgeResult(tpmCheckTaskDetails.getResult().equals(CheckJudgeTypeEnum.OK.getCode()) ?
                            YesOrNoStateEnum.YES.getCode() : YesOrNoStateEnum.NO.getCode());
                }
                //如果是填写值类型
                if (tpmCheckTaskDetails.getJudgeType().equals(YesNoEnum.YES.getCode())) {
                    String result = tpmCheckTaskDetails.getResult();
                    if (new BigDecimal(result).compareTo(tpmCheckTaskDetails.getStandardMax()) <= 0
                            && new BigDecimal(result).compareTo(tpmCheckTaskDetails.getStandardMin()) >= 0) {
                        tpmCheckTaskDetails.setJudgeResult(YesOrNoStateEnum.YES.getCode());
                    } else {
                        tpmCheckTaskDetails.setJudgeResult(YesOrNoStateEnum.NO.getCode());
                    }
                }
            }
        }


        Long count = detailsList.stream().filter(item -> StrUtil.isBlank(item.getResult())).count();
        //如果count 为 0 全部完成
        if (count.compareTo(0L) == 0) {
            //如果有一项 判断结果为否，则任务不通过
            Long count1 = detailsList.stream().filter(item -> YesOrNoStateEnum.NO.getCode().equals(item.getJudgeResult())).count();
            if (count1.compareTo(0L) == 0) {
                tpmCheckTask.setJudgeResult(YesOrNoStateEnum.YES.getCode());
            } else {
                tpmCheckTask.setJudgeResult(YesOrNoStateEnum.NO.getCode());
            }
            //更改任务状态为已完成
            tpmCheckTask.setState(CheckTaskStateEnum.HANDLE.getCode());
            tpmCheckTask.setHandleTime(LocalDateTime.now());
            //生产点/巡检记录
            TpmCheckRecordEntity checkRecord = new TpmCheckRecordEntity();
            BeanUtils.copyProperties(tpmCheckTask, checkRecord, "id");
            tpmCheckRecordService.save(checkRecord);

            if (tpmCheckTask.getSource().equals(CheckTaskSourceEnum.POWER.getCode())) {
                //如果来源是设备开机，更改设备状态为进行中
                data.setDeviceState(DeviceStateEnum.RUNNING.getCode());
                data.setDeviceUsable(YesNoEnum.YES.getCode());
                remoteDeviceService.updateById(data);
            }

        }
        if (count.compareTo(0L) > 0 && count.compareTo((long) detailsList.size()) < 0) {
            //更改任务状态为进行中
            tpmCheckTask.setState(CheckTaskStateEnum.ING.getCode());
        }
        tpmCheckTaskDetailsService.updateBatchById(detailsList);
        return this.updateById(tpmCheckTask);
    }

    /**
     * 新增点检任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addTask(String deviceCode) {
        //查询设备
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(deviceCode);
        MdmDeviceEntity data = remoteDeviceService.getDetails(mdmDevice).getData();
        if (ObjectUtil.isEmpty(data)) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        List<TpmDeviceItemEntity> itemList = tpmDeviceItemService.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .eq(TpmDeviceItemEntity::getDeviceCode, deviceCode)
                .eq(TpmDeviceItemEntity::getType, CheckTypeEnum.SPOT.getCode()));
        List<Long> itemIds = itemList.stream().filter(item -> ObjectUtil.isNotEmpty(item.getItemId())).map(TpmDeviceItemEntity::getItemId).distinct().toList();
        List<TpmCheckItemEntity> tpmCheckItemEntities = new ArrayList<>();
        if (CollUtil.isNotEmpty(itemIds)) {
            tpmCheckItemEntities = tpmCheckItemService.listByIds(itemIds);
        }
        if (CollUtil.isEmpty(tpmCheckItemEntities)) {
            throw new RuntimeException("创建点检任务失败，请前往设备台账-编辑中维护点检项目！");
        }
        //查询设备点检任务，如果存在提示已创建点检任务
        List<TpmCheckTaskEntity> checkList = this.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getDeviceCode, deviceCode)
                .eq(TpmCheckTaskEntity::getSource, CheckTaskSourceEnum.POWER.getCode()));

        // 暂停状态判断是否已点检完成
        if (DeviceStateEnum.PAUSE.getCode().equals(data.getDeviceState())) {
            long handleCount = checkList.stream().filter(item -> CheckTaskStateEnum.HANDLE.getCode().equals(item.getState())).count();
            // 点检已完成 直接更新设备状态
            if (handleCount > 0) {
                data.setDeviceState(DeviceStateEnum.RUNNING.getCode());
                data.setDeviceUsable(YesNoEnum.YES.getCode());
                remoteDeviceService.updateById(data);
                return Boolean.TRUE;
            }
        }


        long count = checkList.stream().filter(item -> !CheckTaskStateEnum.HANDLE.getCode().equals(item.getState())).count();
        if (count > 0L) {
            throw new RuntimeException("已创建点检任务，请等待点检完成！");
        }

        MomUser user = SecurityUtils.getUser();
        //创建开机记录
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(data.getDeviceCode());
            entity.setDeviceCode(data.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.POWER_ON.getCode());
            entity.setDescription("设备: " + data.getDeviceName() + "【" + data.getDeviceCode() + "】已开机");
        });

        //构建主表数据
        String taskCode = remoteSeqConfigService.create("device_check_task").getData();
        TpmCheckTaskEntity task = new TpmCheckTaskEntity();
        task.setTaskCode(taskCode);
        task.setType(CheckTypeEnum.SPOT.getCode());
        task.setSource(CheckTaskSourceEnum.POWER.getCode());
        task.setDeviceCode(data.getDeviceCode());
        task.setDeviceName(data.getDeviceName());
        task.setState(CheckTaskStateEnum.WAIT.getCode());
        //构建子表数据
        List<TpmCheckTaskDetailsEntity> detailsList = new ArrayList<>();
        for (TpmCheckItemEntity tpmCheckItemEntity : tpmCheckItemEntities) {
            TpmCheckTaskDetailsEntity checkTaskDetail = new TpmCheckTaskDetailsEntity();
            BeanUtils.copyProperties(tpmCheckItemEntity, checkTaskDetail, "id");
            checkTaskDetail.setTaskCode(taskCode);
            checkTaskDetail.setItemCode(tpmCheckItemEntity.getCode());
            checkTaskDetail.setItemName(tpmCheckItemEntity.getName());
            detailsList.add(checkTaskDetail);
        }
        tpmCheckTaskDetailsService.saveBatch(detailsList);
        return this.save(task);
    }

    /**
     * 批量指派人员
     */
    @Override
    public Boolean assign(List<Long> ids, String handlePersonCode, String handlePersonName) {
        List<TpmCheckTaskEntity> checkTaskEntities = this.listByIds(ids);
        checkTaskEntities.forEach(item -> {
            item.setState(CheckTaskStateEnum.ASSIGNED.getCode());
            item.setHandlePersonCode(handlePersonCode);
            item.setHandlePersonName(handlePersonName);
        });
        return this.updateBatchById(checkTaskEntities);
    }


    private MdmDeviceEntity getDevice(String deviceCode) {
        MdmDeviceEntity mdmDevice = new MdmDeviceEntity();
        mdmDevice.setDeviceCode(deviceCode);
        R<MdmDeviceEntity> data = remoteDeviceService.getDetails(mdmDevice);
        if (data.getCode() != 0) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        if (ObjectUtil.isEmpty(data.getData())) {
            throw new RuntimeException("查询设备为空！");
        }
        return data.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(DeviceCheckTaskStartDto startDto) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", Boolean.FALSE);
        MomUser user = SecurityUtils.getUser();
        List<TpmCheckTaskEntity> results = new ArrayList<>();
        List<TpmCheckTaskDetailsEntity> detailsResultsList = new ArrayList<>();
        //查询设备
        MdmDeviceEntity device = getDevice(startDto.getDeviceCode());

        //处于保养中和维修中的设备不可开机
        if (DeviceStateEnum.MAINTENANCE.getCode().equals(device.getDeviceState())) {
//			throw new RuntimeException("设备处于保养状态不可开机！");
            resultMap.put("msg", "设备处于保养状态不可开机！");
            return resultMap;
        }
        if (DeviceStateEnum.REPAIR.getCode().equals(device.getDeviceState())) {
//			throw new RuntimeException("设备处于维修状态不可开机！");
            resultMap.put("msg", "设备处于维修状态不可开机！");
            return resultMap;
        }
        //如果处于开机状态，不走下面逻辑
        if (device.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())) {
//			return Boolean.TRUE;
            resultMap.put("data", Boolean.TRUE);
            return resultMap;
        }


        //如果存在模具，则更新模具状态为运行中
        if (ObjectUtil.isNotEmpty(startDto.getMoldCode())) {

            // 判断是否能够绑定该模具
            List<TpmDeviceMoldRelaEntity> relations = moldRelaService.list(new LambdaQueryWrapper<TpmDeviceMoldRelaEntity>()
                    .eq(TpmDeviceMoldRelaEntity::getDeviceCode, device.getDeviceCode()));
            if (!relations.stream().map(TpmDeviceMoldRelaEntity::getMoldCode).toList().contains(startDto.getMoldCode())) {
                resultMap.put("msg", "设备与模具未设置绑定关系，开机失败！");
                return resultMap;
            }

            MdmDeviceEntity mold = getDevice(startDto.getMoldCode());
            if (DeviceStateEnum.PAUSE.getCode().equals(device.getDeviceState())) {
                mold.setDeviceState(DeviceStateEnum.RUNNING.getCode());
                mold.setDeviceUsable(YesNoEnum.NO.getCode());
                remoteDeviceService.updateById(mold);
            }

            mold.setDeviceState(DeviceStateEnum.RUNNING.getCode());
            mold.setDeviceUsable(YesNoEnum.NO.getCode());
            remoteDeviceService.updateById(mold);
            //保存模具的点检任务
            //推送模具的事件
            createTask(mold, startDto.getMoldDetailsList(), results, detailsResultsList, user);
            publish(mold, user);
        }

        // 暂停状态判断 重新启动
        if (DeviceStateEnum.PAUSE.getCode().equals(device.getDeviceState())) {
            device.setDeviceState(DeviceStateEnum.RUNNING.getCode());
            device.setDeviceUsable(YesNoEnum.NO.getCode());
            remoteDeviceService.updateById(device);
            resultMap.put("data", Boolean.TRUE);
            return resultMap;
        }

        device.setDeviceState(DeviceStateEnum.RUNNING.getCode());
        device.setDeviceUsable(YesNoEnum.NO.getCode());
        remoteDeviceService.updateById(device);

        //创建两个点检任务 以及对应的明细记录
        createTask(device, startDto.getDeviceDetailsList(), results, detailsResultsList, user);
        this.saveBatch(results);
        tpmCheckTaskDetailsService.saveBatch(detailsResultsList);
        //创建开机记录
        publish(device, user);
        resultMap.put("data", Boolean.TRUE);
        return resultMap;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeMold(DeviceChangeMoldDto moldDto) {
        MomUser user = SecurityUtils.getUser();
        //模具更换逻辑
        //将之前的模具状态改为空闲，可用状态改为可用，推送关机事件
        MdmDeviceEntity oldMold = getDevice(moldDto.getOldMoldCode());
        oldMold.setDeviceState(DeviceStateEnum.IDLE.getCode());
        oldMold.setDeviceUsable(YesNoEnum.YES.getCode());
        remoteDeviceService.updateById(oldMold);

        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(oldMold.getDeviceCode());
            entity.setDeviceName(oldMold.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.CLOSE.getCode());
            entity.setDescription("设备: " + oldMold.getDeviceName() + "【" + oldMold.getDeviceCode() + "】已关机");
        });

        //新模具状态改为运行中，不可用状态改为不可用，推送开机事件，保存点检任务
        MdmDeviceEntity newMold = getDevice(moldDto.getNewMoldCode());
        newMold.setDeviceState(DeviceStateEnum.RUNNING.getCode());
        newMold.setDeviceUsable(YesNoEnum.NO.getCode());
        remoteDeviceService.updateById(oldMold);
        //创建点检任务 以及对应的明细记录
        List<TpmCheckTaskEntity> results = new ArrayList<>();
        List<TpmCheckTaskDetailsEntity> detailsResultsList = new ArrayList<>();
        createTask(newMold, moldDto.getNewMoldDetailsList(), results, detailsResultsList, user);
        this.saveBatch(results);
        tpmCheckTaskDetailsService.saveBatch(detailsResultsList);
        //开机事件
        publish(newMold, user);
        return Boolean.TRUE;
    }

    private void publish(MdmDeviceEntity device, MomUser user) {
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(device.getDeviceCode());
            entity.setDeviceName(device.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.POWER_ON.getCode());
            entity.setDescription("设备: " + device.getDeviceName() + "【" + device.getDeviceCode() + "】已开机");
        });
    }


    /**
     * 创建点检任务
     *
     * @param device             设备
     * @param detailsList        点检明细
     * @param results            主表结果集
     * @param detailsResultsList 子表结果集
     * @param user               用户
     */
    private void createTask(MdmDeviceEntity device, List<TpmCheckTaskDetailsEntity> detailsList,
                            List<TpmCheckTaskEntity> results,
                            List<TpmCheckTaskDetailsEntity> detailsResultsList,
                            MomUser user) {
        //构建主表数据
        String taskCode = remoteSeqConfigService.create("device_check_task").getData();
        TpmCheckTaskEntity task = new TpmCheckTaskEntity();
        task.setTaskCode(taskCode);
        task.setType(CheckTypeEnum.SPOT.getCode());
        task.setSource(CheckTaskSourceEnum.POWER.getCode());
        task.setDeviceCode(device.getDeviceCode());
        task.setDeviceName(device.getDeviceName());
        task.setState(CheckTaskStateEnum.HANDLE.getCode());
        task.setHandleTime(LocalDateTime.now());
        task.setHandlePersonCode(user.getUsername());
        task.setHandlePersonName(user.getName());
        results.add(task);
        //构建子表数据
        for (TpmCheckTaskDetailsEntity tpmCheckTaskDetails : detailsList) {
            tpmCheckTaskDetails.setId(null);
            tpmCheckTaskDetails.setTaskCode(taskCode);
            detailsResultsList.add(tpmCheckTaskDetails);
        }
    }


}