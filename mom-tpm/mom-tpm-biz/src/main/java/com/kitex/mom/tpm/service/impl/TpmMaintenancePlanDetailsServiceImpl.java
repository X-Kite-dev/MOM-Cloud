package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;
import com.kitex.mom.tpm.mapper.TpmMaintenancePlanDetailsMapper;
import com.kitex.mom.tpm.service.TpmMaintenancePlanDetailsService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保养计划明细表
 *
 * @author kitex
 * @date 2025-02-06 15:10:29
 */
@Service

public class TpmMaintenancePlanDetailsServiceImpl extends ServiceImpl<TpmMaintenancePlanDetailsMapper, TpmMaintenancePlanDetailsEntity> implements TpmMaintenancePlanDetailsService {


    @Override
    public List<TpmMaintenancePlanDetailsEntity> getInfoList(Long planId) {
        return this.baseMapper.getInfoList(planId);
    }

    @Override
    public List<TpmMaintenancePlanDetailsEntity> listAllInfos() {
        return this.baseMapper.listAllInfos();
    }

}