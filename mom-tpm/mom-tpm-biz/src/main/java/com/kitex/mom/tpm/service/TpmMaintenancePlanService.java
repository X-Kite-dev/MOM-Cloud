package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface TpmMaintenancePlanService extends IService<TpmMaintenancePlanEntity> {

    Page<TpmMaintenancePlanEntity> pageList(Page toMpPage, TpmMaintenancePlanEntity tpmMaintenancePlan);

    Map<String, Long> getCount(TpmMaintenancePlanEntity tpmMaintenancePlan);

    /**
     * 通过id查询保养计划表
     *
     * @param id id
     */
    TpmMaintenancePlanEntity getPlanById(Long id);

    /**
     * 通过id删除保养计划表
     *
     * @param toList id列表
     */
    Boolean removePlanBatchByIds(ArrayList<Long> toList);

    /**
     * 查询所有设备以及设备结构
     */
    List<MdmDeviceClassEntity> getDevice();

    /**
     * 新增保养计划表
     *
     * @param tpmMaintenancePlan 保养计划表
     */
    Boolean savePlan(TpmMaintenancePlanEntity tpmMaintenancePlan);

    /**
     * 修改保养计划表
     *
     * @param tpmMaintenancePlan 保养计划表
     */
    Boolean updatePlanById(TpmMaintenancePlanEntity tpmMaintenancePlan);

}