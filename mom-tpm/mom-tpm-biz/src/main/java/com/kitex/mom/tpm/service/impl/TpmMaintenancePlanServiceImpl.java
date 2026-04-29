package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceClassService;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;
import com.kitex.mom.tpm.enums.PlanExecuteStateEnum;
import com.kitex.mom.tpm.mapper.TpmMaintenancePlanMapper;
import com.kitex.mom.tpm.service.TpmMaintenancePlanDetailsService;
import com.kitex.mom.tpm.service.TpmMaintenancePlanService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 保养计划表
 *
 * @author kitex
 * @date 2025-02-06 15:07:31
 */
@Service
@AllArgsConstructor
public class TpmMaintenancePlanServiceImpl extends ServiceImpl<TpmMaintenancePlanMapper, TpmMaintenancePlanEntity> implements TpmMaintenancePlanService {

    private final TpmMaintenancePlanDetailsService tpmMaintenancePlanDetailsService;

    //设备
    private final RemoteDeviceClassService remoteDeviceClassService;
    private final RemoteDeviceService remoteDeviceService;

    @Override
    public Page<TpmMaintenancePlanEntity> pageList(Page toMpPage, TpmMaintenancePlanEntity tpmMaintenancePlan) {
        LambdaQueryWrapper<TpmMaintenancePlanEntity> wrapper = Wrappers.lambdaQuery();
        //设备分类查询本级以及子级数据
        List<MdmDeviceClassEntity> deviceClassList = remoteDeviceClassService.getMdmDeviceClassList().getData();
        if (StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceClass())) {
            List<MdmDeviceClassEntity> fatherList = deviceClassList.stream().filter(item -> tpmMaintenancePlan.getDeviceClass().equals(item.getCode())).toList();
            List<String> results = getAllCode(new ArrayList<>(), fatherList, deviceClassList);
            wrapper.in(TpmMaintenancePlanEntity::getDeviceClass, results);
        }
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getName()), TpmMaintenancePlanEntity::getName, tpmMaintenancePlan.getName());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenancePlan.getType()), TpmMaintenancePlanEntity::getType, tpmMaintenancePlan.getType());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceCode()), TpmMaintenancePlanEntity::getDeviceCode, tpmMaintenancePlan.getDeviceCode());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenancePlan.getExecuteState()), TpmMaintenancePlanEntity::getExecuteState, tpmMaintenancePlan.getExecuteState());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceName()), TpmMaintenancePlanEntity::getDeviceName, tpmMaintenancePlan.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getHandlePersonName()), TpmMaintenancePlanEntity::getHandlePersonName, tpmMaintenancePlan.getHandlePersonName());
        wrapper.orderByDesc(BaseCore::getCreateTime);
        return this.page(toMpPage, wrapper);
    }

    @Override
    public Map<String, Long> getCount(TpmMaintenancePlanEntity tpmMaintenancePlan) {
        LambdaQueryWrapper<TpmMaintenancePlanEntity> wrapper = Wrappers.lambdaQuery();
        //设备分类查询本级以及子级数据
        List<MdmDeviceClassEntity> deviceClassList = remoteDeviceClassService.getMdmDeviceClassList().getData();

        if (StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceClass())) {
            List<MdmDeviceClassEntity> fatherList = deviceClassList.stream().filter(item -> tpmMaintenancePlan.getDeviceClass().equals(item.getCode())).toList();
            List<String> results = getAllCode(new ArrayList<>(), fatherList, deviceClassList);
            wrapper.in(TpmMaintenancePlanEntity::getDeviceClass, results);
        }
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getName()), TpmMaintenancePlanEntity::getName, tpmMaintenancePlan.getName());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenancePlan.getType()), TpmMaintenancePlanEntity::getType, tpmMaintenancePlan.getType());
        wrapper.eq(StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceCode()), TpmMaintenancePlanEntity::getDeviceCode, tpmMaintenancePlan.getDeviceCode());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getDeviceName()), TpmMaintenancePlanEntity::getDeviceName, tpmMaintenancePlan.getDeviceName());
        wrapper.like(StrUtil.isNotBlank(tpmMaintenancePlan.getHandlePersonName()), TpmMaintenancePlanEntity::getHandlePersonName, tpmMaintenancePlan.getHandlePersonName());
        return this.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(TpmMaintenancePlanEntity::getExecuteState, Collectors.counting()));
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
     * 根据计划id查询计划以及明细
     *
     * @param id id
     */
    @Override
    public TpmMaintenancePlanEntity getPlanById(Long id) {
        TpmMaintenancePlanEntity byId = this.getById(id);
        //查询明细
        List<TpmMaintenancePlanDetailsEntity> detailsList = tpmMaintenancePlanDetailsService.getInfoList(id);
        byId.setDetailsList(detailsList);
        return byId;
    }

    /**
     * 通过id删除保养计划表
     *
     * @param toList id列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removePlanBatchByIds(ArrayList<Long> toList) {
        //删除主子表
        tpmMaintenancePlanDetailsService.remove(new LambdaQueryWrapper<TpmMaintenancePlanDetailsEntity>()
                .in(TpmMaintenancePlanDetailsEntity::getPlanId, toList));
        return this.removeBatchByIds(toList);
    }

    /**
     * 查询所有设备以及设备结构
     */
    @Override
    public List<MdmDeviceClassEntity> getDevice() {
        List<MdmDeviceClassEntity> deviceClassList = remoteDeviceClassService.getDeviceClassTreeList().getData();
        return deviceClassList;
    }

    /**
     * 新增保养计划表
     *
     * @param tpmMaintenancePlan 保养计划表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean savePlan(TpmMaintenancePlanEntity tpmMaintenancePlan) {
        //校验时间
        LocalDateTime startTime = tpmMaintenancePlan.getStartTime();
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new RuntimeException("计划开始时间不能早于当前时间！");
        }
        List<TpmMaintenancePlanDetailsEntity> planDetailsList = tpmMaintenancePlan.getDetailsList();
        if (CollUtil.isEmpty(planDetailsList)) {
            throw new RuntimeException("保养项目不能为空！");
        }
        List<MdmDeviceEntity> deviceList = remoteDeviceService.getAllMdmDeviceList().getData();
        if (CollUtil.isEmpty(deviceList)) {
            throw new RuntimeException("访问设备数据失败！");
        }
        Map<String, MdmDeviceEntity> deviceMap = deviceList.stream().collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (k1, k2) -> k1));
        //保存主子表
        List<TpmMaintenancePlanEntity> planEntityList = new ArrayList<>();
        List<TpmMaintenancePlanDetailsEntity> detailsList = new ArrayList<>();

        String deviceCode = tpmMaintenancePlan.getDeviceCode();
        for (String code : deviceCode.split(",")) {
            TpmMaintenancePlanEntity plan = new TpmMaintenancePlanEntity();
            BeanUtils.copyProperties(tpmMaintenancePlan, plan);
            MdmDeviceEntity mdmDevice = deviceMap.get(code);
            plan.setDeviceCode(code);
            plan.setDeviceName(mdmDevice.getDeviceName());
            plan.setDeviceClass(mdmDevice.getClassCode());
            plan.setExecuteState(PlanExecuteStateEnum.ING.getCode());
            plan.setNoticeFlag(YesOrNoStateEnum.NO.getCode());
            planEntityList.add(plan);
        }
        this.saveBatch(planEntityList);
        for (TpmMaintenancePlanEntity plan : planEntityList) {
            List<TpmMaintenancePlanDetailsEntity> detailsEntities = planDetailsList.stream().map(item -> {
                TpmMaintenancePlanDetailsEntity planDetails = new TpmMaintenancePlanDetailsEntity();
                BeanUtils.copyProperties(item, planDetails);
                return planDetails;
            }).toList();
            detailsEntities.forEach(item -> item.setPlanId(plan.getId()));
            detailsList.addAll(detailsEntities);
        }
        tpmMaintenancePlanDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePlanById(TpmMaintenancePlanEntity tpmMaintenancePlan) {
        List<TpmMaintenancePlanDetailsEntity> detailsList = tpmMaintenancePlan.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("保养项目不能为空！");
        }
        //重新保存子表 并且更新主表
        tpmMaintenancePlanDetailsService.remove(new LambdaQueryWrapper<TpmMaintenancePlanDetailsEntity>()
                .eq(TpmMaintenancePlanDetailsEntity::getPlanId, tpmMaintenancePlan.getId()));
        detailsList.forEach(item -> {
            item.setPlanId(tpmMaintenancePlan.getId());
        });
        tpmMaintenancePlanDetailsService.saveBatch(detailsList);
        return this.updateById(tpmMaintenancePlan);
    }

}