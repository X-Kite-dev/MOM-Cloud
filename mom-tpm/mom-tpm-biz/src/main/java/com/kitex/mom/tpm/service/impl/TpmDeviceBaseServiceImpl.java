package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.constant.enums.YesNoEnum;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceClassService;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;
import com.kitex.mom.tpm.enums.DeviceLifeCycleEventEnum;
import com.kitex.mom.tpm.event.EventPublisherUtil;
import com.kitex.mom.tpm.service.*;
import com.kitex.mom.tpm.vo.MdmDeviceExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备台账
 *
 * @author kitex
 * @date 2025-02-06 15:04:12
 */
@Service
@AllArgsConstructor
public class TpmDeviceBaseServiceImpl implements TpmDeviceBaseService {

    private final RemoteDeviceService remoteDeviceService;
    private final RemoteDeviceClassService remoteDeviceClassService;
    private final TpmMaintenancePlanService tpmMaintenancePlanService;
    private final TpmMaintenanceTaskService tpmMaintenanceTaskService;
    private final TpmRepairTaskService tpmRepairTaskService;
    private final TpmDeviceItemService tpmDeviceItemService;
    private final EventPublisherUtil eventPublisherUtil;
    private final RemotePrintService remotePrintService;

    @Override
    public Page<MdmDeviceEntity> getAllPage(Page toMpPage, MdmDeviceEntity mdmDeviceEntity) {
        return remoteDeviceService.getAllPage((int) toMpPage.getCurrent(), (int) toMpPage.getSize(), mdmDeviceEntity).getData();
    }

    @Override
    public Map<Integer, Long> getCount(MdmDeviceEntity mdmDeviceEntity) {
        return remoteDeviceService.getCount(mdmDeviceEntity).getData();
    }

    @Override
    public Page<MdmDeviceEntity> page(Page toMpPage, MdmDeviceEntity mdmDeviceEntity) {
        return remoteDeviceService.getMdmDevicePage((int) toMpPage.getCurrent(), (int) toMpPage.getSize(), mdmDeviceEntity).getData();
    }

    @Override
    public MdmDeviceEntity getById(Long id) {
        return remoteDeviceService.getById(id).getData();
    }

    @Override
    public List<MdmDeviceClassEntity> getDevice() {
        R<List<MdmDeviceClassEntity>> results = remoteDeviceClassService.getDeviceClassTreeList();
        if (results.getCode() != 0) {
            throw new RuntimeException("获取设备分类信息错误，请维护或授权设备分类权限！");
        }
        return results.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateById(MdmDeviceEntity mdmDeviceEntity) {
        R<Boolean> data = remoteDeviceService.updateById(mdmDeviceEntity);
        if (data.getCode() != 0) {
            throw new RuntimeException("修改失败，请授权设备编辑权限或检查主数据服务是否运行！");
        }
        if (ObjectUtil.isEmpty(data.getData())) {
            Boolean data1 = data.getData();
            if (!data1) {
                throw new RuntimeException("数据为空！");
            }
        }
        //更改信息时，同时修改保养计划信息
        tpmMaintenancePlanService.update(new UpdateWrapper<TpmMaintenancePlanEntity>().lambda()
                .set(TpmMaintenancePlanEntity::getDeviceName, mdmDeviceEntity.getDeviceName())
                .set(TpmMaintenancePlanEntity::getDeviceClass, mdmDeviceEntity.getClassCode())
                .eq(TpmMaintenancePlanEntity::getDeviceCode, mdmDeviceEntity.getDeviceCode()));
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeBatchByIds(Long[] ids) {
        List<MdmDeviceEntity> data = remoteDeviceService.getAllMdmDeviceList().getData();
        if (CollUtil.isEmpty(data)) {
            throw new RuntimeException("删除失败，请授权设备编辑权限或检查主数据服务是否运行！");
        }
        List<Long> longs = Arrays.asList(ids);
        List<MdmDeviceEntity> mdmDeviceEntities = data.stream().filter(item -> longs.contains(item.getId())).toList();
        List<String> deviceCodeList = mdmDeviceEntities.stream().map(MdmDeviceEntity::getDeviceCode).toList();

        remoteDeviceService.removeById(ids).getData();
        tpmMaintenancePlanService.remove(new LambdaQueryWrapper<TpmMaintenancePlanEntity>()
                .in(TpmMaintenancePlanEntity::getDeviceCode, deviceCodeList));
        tpmDeviceItemService.remove(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .in(TpmDeviceItemEntity::getDeviceCode, deviceCodeList));
        return Boolean.TRUE;
    }

    @Override
    public Boolean saveDevice(MdmDeviceEntity mdmDevice) {
        MomUser user = SecurityUtils.getUser();
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(mdmDevice.getDeviceCode());
            entity.setDeviceName(mdmDevice.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.ADD.getCode());
            entity.setDescription("设备: " + mdmDevice.getDeviceName() + "【" + mdmDevice.getDeviceCode() + "】被创建");
        });
        return remoteDeviceService.save(mdmDevice).getData();
    }

    @Override
    public R importExcel(List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult, String factoryCode) {
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        //纠正行号错误
        if (CollUtil.isNotEmpty(errorMessageList)) {
            errorMessageList.forEach(item -> {
                item.setLineNum(item.getLineNum() + 1);
            });
        }

        //获取数据库所有的数据进行比对
        R<List<MdmDeviceEntity>> allMdmDeviceList = remoteDeviceService.getAllMdmDeviceList();
        if (allMdmDeviceList.getCode() != 0) {
            throw new RuntimeException("访问设备数据失败，请授权或检查主数据服务是否运行!");
        }
        Map<String, MdmDeviceEntity> mdmDeviceMap = allMdmDeviceList.getData()
                .stream()
                .collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (a, b) -> a));
        //获取设备分类数据
        R<List<MdmDeviceClassEntity>> mdmDeviceClassList = remoteDeviceClassService.getMdmDeviceClassList();
        if (mdmDeviceClassList.getCode() != 0) {
            throw new RuntimeException("访问设备分类数据失败，请授权或检查主数据服务是否运行!");
        }
        Map<String, MdmDeviceClassEntity> mdmDeviceClassEntityMap = mdmDeviceClassList.getData()
                .stream()
                .collect(Collectors.toMap(MdmDeviceClassEntity::getName, Function.identity(), (a, b) -> a));


        Map<String, List<MdmDeviceExcelVO>> importDeviceMap = mdmDeviceList.stream().collect(Collectors.groupingBy(MdmDeviceExcelVO::getDeviceCode, Collectors.toList()));

        //1.校验数据库编码是否重复  2.校验导入数据中设备编码是否重复 3.校验设备分类是否合法
        for (MdmDeviceExcelVO mdmDevice : mdmDeviceList) {
            Set<String> errorMsg = new HashSet<>();
            if (mdmDeviceMap.containsKey(mdmDevice.getDeviceCode().trim())) {
                errorMsg.add("数据库存在设备编码为：" + mdmDevice.getDeviceCode() + " 的设备！");
            }
            MdmDeviceClassEntity mdmDeviceClassEntity = mdmDeviceClassEntityMap.get(mdmDevice.getClassName());
            if (ObjectUtil.isNotEmpty(mdmDeviceClassEntity)) {
                mdmDevice.setClassCode(mdmDeviceClassEntity.getCode());
                mdmDevice.setClassName(mdmDeviceClassEntity.getName());
            } else {
                errorMsg.add("设备分类：" + mdmDevice.getClassName() + " 不存在!");
            }
            List<MdmDeviceExcelVO> mdmDeviceExcelVOS = importDeviceMap.get(mdmDevice.getDeviceCode());
            if (mdmDeviceExcelVOS.size() > 1) {
                errorMsg.add("导入数据中存在重复的设备编码为：" + mdmDevice.getDeviceCode() + " 的设备！");
            }

            if (CollUtil.isNotEmpty(errorMsg)) {
                errorMessageList.add(new ErrorMessage(mdmDevice.getLineNum() + 1, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }

        List<MdmDeviceEntity> saveList = mdmDeviceList.stream().map(item -> {
            MdmDeviceEntity entity = new MdmDeviceEntity();
            BeanUtils.copyProperties(item, entity);
            entity.setStatus(true);
            entity.setDeviceState(DeviceStateEnum.IDLE.getCode());
            entity.setDeviceUsable(YesNoEnum.YES.getCode());
            entity.setFactoryCode(factoryCode);
            return entity;
        }).collect(Collectors.toList());

        // 保存信息
        remoteDeviceService.saveBatch(saveList);
        //构建设备生命周期事件
        MomUser user = SecurityUtils.getUser();
        for (MdmDeviceEntity mdmDevice : saveList) {
            eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
                entity.setDeviceCode(mdmDevice.getDeviceCode());
                entity.setDeviceName(mdmDevice.getDeviceName());
                entity.setEventCode(null);
                entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
                entity.setEventSource(DeviceLifeCycleEventEnum.ADD.getCode());
                entity.setDescription("设备: " + mdmDevice.getDeviceName() + "【" + mdmDevice.getDeviceCode() + "】被创建");
            });
        }
        return R.ok(null, "导入成功");
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

    private void publish(MdmDeviceEntity device, MomUser user) {
        eventPublisherUtil.publishDeviceLifeCycleEvent(entity -> {
            entity.setDeviceCode(device.getDeviceCode());
            entity.setDeviceName(device.getDeviceName());
            entity.setEventCode(null);
            entity.setOperator(user.getName() + "(" + user.getUsername() + ")");
            entity.setEventSource(DeviceLifeCycleEventEnum.CLOSE.getCode());
            entity.setDescription("设备: " + device.getDeviceName() + "【" + device.getDeviceCode() + "】已关机");
        });
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean devicePause(String deviceCode, String moldCode) {
        MdmDeviceEntity device = getDevice(deviceCode);
        if (device.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())) {
            device.setDeviceState(DeviceStateEnum.PAUSE.getCode());
            device.setDeviceUsable(YesOrNoStateEnum.YES.getCode());
            remoteDeviceService.updateById(device);
            if (ObjectUtil.isNotEmpty(moldCode)) {
                MdmDeviceEntity mold = getDevice(moldCode);
                mold.setDeviceState(DeviceStateEnum.PAUSE.getCode());
                mold.setDeviceUsable(YesOrNoStateEnum.YES.getCode());
                remoteDeviceService.updateById(mold);
            }
        }
        return Boolean.TRUE;
    }


    @Override
    public Boolean deviceClose(String deviceCode, String moldCode) {
        MdmDeviceEntity device = getDevice(deviceCode);
        if (device.getDeviceState().equals(DeviceStateEnum.PAUSE.getCode()) || device.getDeviceState().equals(DeviceStateEnum.RUNNING.getCode())) {
            device.setDeviceState(DeviceStateEnum.IDLE.getCode());
            device.setDeviceUsable(YesOrNoStateEnum.YES.getCode());
            remoteDeviceService.updateById(device);
            MomUser user = SecurityUtils.getUser();
            publish(device, user);

            if (ObjectUtil.isNotEmpty(moldCode)) {
                MdmDeviceEntity mold = getDevice(moldCode);
                mold.setDeviceState(DeviceStateEnum.IDLE.getCode());
                mold.setDeviceUsable(YesOrNoStateEnum.YES.getCode());
                remoteDeviceService.updateById(mold);
                publish(mold, user);
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 生成设备二维码
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String print(PrintReq printReq) {
        R<MdmDeviceEntity> details = remoteDeviceService.getById(printReq.getIds()[0]);
        if (details.getCode() != 0) {
            throw new RuntimeException("访问设备数据失败，请授权或者检查主数据服务是否运行！");
        }
        MdmDeviceEntity device = details.getData();
        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(printReq.getTemplateCode());
        String jsonData = JSONUtil.toJsonStr(device);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }

    private MdmDeviceEntity insert(MdmDeviceExcelVO mdmDevice, String factoryCode) {
        MdmDeviceEntity device = new MdmDeviceEntity();
        BeanUtils.copyProperties(mdmDevice, device);
        device.setDeviceState("0");
        device.setFactoryCode(factoryCode);
        return device;
    }


}