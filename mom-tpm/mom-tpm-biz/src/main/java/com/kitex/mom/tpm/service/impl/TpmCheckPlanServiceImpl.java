package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceClassService;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmCheckPlanEntity;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.enums.PlanExecuteStateEnum;
import com.kitex.mom.tpm.mapper.TpmCheckPlanMapper;
import com.kitex.mom.tpm.service.TpmCheckPlanService;
import com.kitex.mom.tpm.service.TpmDeviceItemService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 点/巡检计划
 *
 * @author kitex
 * @date 2025-02-18 17:44:03
 */
@Service
@AllArgsConstructor
public class TpmCheckPlanServiceImpl extends ServiceImpl<TpmCheckPlanMapper, TpmCheckPlanEntity> implements TpmCheckPlanService {

    //设备
    private final RemoteDeviceClassService remoteDeviceClassService;
    private final RemoteDeviceService remoteDeviceService;
    private final TpmDeviceItemService tpmDeviceItemService;

    /**
     * 查询所有设备以及设备结构
     */
    @Override
    public List<MdmDeviceClassEntity> getDevice() {
        List<MdmDeviceClassEntity> data = remoteDeviceClassService.getDeviceClassTreeList().getData();
        if (CollUtil.isEmpty(data)) {
            throw new RuntimeException("获取设备分类信息错误，请维护或授权设备分类权限！");
        }
        return data;
    }

    @Override
    public Page<TpmCheckPlanEntity> pageList(Page toMpPage, TpmCheckPlanEntity tpmCheckPlan) {
        LambdaQueryWrapper<TpmCheckPlanEntity> wrapper = Wrappers.lambdaQuery();
        if (StrUtil.isNotBlank(tpmCheckPlan.getDeviceClass())) {
            //设备分类查询本级以及子级数据
            List<MdmDeviceClassEntity> data = remoteDeviceClassService.getMdmDeviceClassList().getData();
            if (CollUtil.isEmpty(data)) {
                throw new RuntimeException("查询设备分类数据失败，请授权或检查主数据服务是否运行！");
            }
            List<MdmDeviceClassEntity> fatherList = data.stream().filter(item -> tpmCheckPlan.getDeviceClass().equals(item.getCode())).toList();
            List<String> results = getAllCode(new ArrayList<>(), fatherList, data);
            List<MdmDeviceEntity> dataList = remoteDeviceService.getListByClass(results).getData();
            if (CollUtil.isNotEmpty(dataList)) {
                List<String> codeList = dataList.stream().map(MdmDeviceEntity::getDeviceCode).toList();
                wrapper.in(TpmCheckPlanEntity::getDeviceCode, codeList);
            } else {
                return new Page<>();
            }
        }
        wrapper.eq(StrUtil.isNotBlank(tpmCheckPlan.getExecuteState()), TpmCheckPlanEntity::getExecuteState, tpmCheckPlan.getExecuteState());
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getName()), TpmCheckPlanEntity::getName, tpmCheckPlan.getName());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckPlan.getType()), TpmCheckPlanEntity::getType, tpmCheckPlan.getType());
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getDeviceName()), TpmCheckPlanEntity::getDeviceName, tpmCheckPlan.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getHandlePersonName()), TpmCheckPlanEntity::getHandlePersonName, tpmCheckPlan.getHandlePersonName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return this.page(toMpPage, wrapper);
    }

    @Override
    public Map<String, Long> getCount(TpmCheckPlanEntity tpmCheckPlan) {
        LambdaQueryWrapper<TpmCheckPlanEntity> wrapper = Wrappers.lambdaQuery();
        if (StrUtil.isNotBlank(tpmCheckPlan.getDeviceClass())) {
            //设备分类查询本级以及子级数据
            List<MdmDeviceClassEntity> data = remoteDeviceClassService.getMdmDeviceClassList().getData();
            if (CollUtil.isEmpty(data)) {
                throw new RuntimeException("查询设备分类数据失败，请授权或检查主数据服务是否运行！");
            }
            List<MdmDeviceClassEntity> fatherList = data.stream().filter(item -> tpmCheckPlan.getDeviceClass().equals(item.getCode())).toList();
            List<String> results = getAllCode(new ArrayList<>(), fatherList, data);
            List<MdmDeviceEntity> dataList = remoteDeviceService.getListByClass(results).getData();
            if (CollUtil.isNotEmpty(dataList)) {
                List<String> codeList = dataList.stream().map(MdmDeviceEntity::getDeviceCode).toList();
                wrapper.in(TpmCheckPlanEntity::getDeviceCode, codeList);
            } else {
                return new HashMap<>();
            }
        }
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getName()), TpmCheckPlanEntity::getName, tpmCheckPlan.getName());
        wrapper.eq(StrUtil.isNotBlank(tpmCheckPlan.getType()), TpmCheckPlanEntity::getType, tpmCheckPlan.getType());
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getDeviceName()), TpmCheckPlanEntity::getDeviceName, tpmCheckPlan.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmCheckPlan.getHandlePersonName()), TpmCheckPlanEntity::getHandlePersonName, tpmCheckPlan.getHandlePersonName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return this.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmCheckPlanEntity::getExecuteState, Collectors.counting()));
    }

    private List<String> getAllCode(List<String> results, List<MdmDeviceClassEntity> fatherList, List<MdmDeviceClassEntity> allList) {
        for (MdmDeviceClassEntity father : fatherList) {
            results.add(father.getCode());
            List<MdmDeviceClassEntity> children = allList.stream().filter(item -> father.getCode().equals(item.getParentCode())).toList();
            if (CollUtil.isNotEmpty(children)) {
                getAllCode(results, children, allList);
            }
        }
        return results;
    }


    /**
     * 新增巡检计划
     *
     * @param tpmCheckPlan 计划
     */
    @Override
    public Boolean savePlan(TpmCheckPlanEntity tpmCheckPlan) {
        String deviceCode = tpmCheckPlan.getDeviceCode();
        List<String> deviceCodes = Arrays.asList(deviceCode.split(","));
        List<TpmDeviceItemEntity> deviceItemList = tpmDeviceItemService.list(new LambdaQueryWrapper<TpmDeviceItemEntity>()
                .in(TpmDeviceItemEntity::getDeviceCode, deviceCodes)
                .eq(TpmDeviceItemEntity::getType, tpmCheckPlan.getType()));
        List<String> bandingCodes = deviceItemList.stream().map(TpmDeviceItemEntity::getDeviceCode).distinct().toList();
        //校验
        List<String> unBandingCodes = deviceCodes.stream().filter(item -> !bandingCodes.contains(item)).toList();
        if (CollUtil.isNotEmpty(unBandingCodes)) {
            throw new RuntimeException(String.format("以下设备未绑定检项，请前往设备台账中配置项目！未绑定的设备:【%s】", String.join(",", unBandingCodes)));
        }
        List<MdmDeviceEntity> deviceList = remoteDeviceService.getAllMdmDeviceList().getData();
        if (CollUtil.isEmpty(deviceList)) {
            throw new RuntimeException("访问设备数据失败！");
        }
        Map<String, MdmDeviceEntity> deviceMap = deviceList.stream().collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (k1, k2) -> k1));

        List<TpmCheckPlanEntity> results = new ArrayList<>();
        for (String code : deviceCodes) {
            TpmCheckPlanEntity entity = new TpmCheckPlanEntity();
            BeanUtils.copyProperties(tpmCheckPlan, entity, "id");
            entity.setExecuteState(PlanExecuteStateEnum.ING.getCode());
            entity.setDeviceCode(code);
            MdmDeviceEntity mdmDevice = deviceMap.get(code);
            entity.setDeviceName(ObjectUtil.isEmpty(mdmDevice) ? null : mdmDevice.getDeviceName());
            results.add(entity);
        }
        return this.saveBatch(results);
    }
}