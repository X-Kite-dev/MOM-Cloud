package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;

import java.util.List;

public interface TpmMaintenancePlanDetailsService extends IService<TpmMaintenancePlanDetailsEntity> {

    /**
     * 根据计划id查询保养项明细
     */
    List<TpmMaintenancePlanDetailsEntity> getInfoList(Long planId);

    /**
     * 查询所有保养项明细
     */
    List<TpmMaintenancePlanDetailsEntity> listAllInfos();
}