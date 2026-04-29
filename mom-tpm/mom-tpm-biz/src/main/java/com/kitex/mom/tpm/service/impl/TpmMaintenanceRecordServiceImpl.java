package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmMaintenanceRecordEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskDetailsEntity;
import com.kitex.mom.tpm.mapper.TpmMaintenanceRecordMapper;
import com.kitex.mom.tpm.service.TpmMaintenanceRecordService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskDetailsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保养记录表
 *
 * @author kitex
 * @date 2025-02-06 15:13:49
 */
@Service
@AllArgsConstructor
public class TpmMaintenanceRecordServiceImpl extends ServiceImpl<TpmMaintenanceRecordMapper, TpmMaintenanceRecordEntity> implements TpmMaintenanceRecordService {

    private final TpmMaintenanceTaskDetailsService tpmMaintenanceTaskDetailsService;

    @Override
    public TpmMaintenanceRecordEntity getRecordById(Long id) {
        TpmMaintenanceRecordEntity byId = this.getById(id);
        List<TpmMaintenanceTaskDetailsEntity> detailsList = tpmMaintenanceTaskDetailsService.list(new LambdaQueryWrapper<TpmMaintenanceTaskDetailsEntity>()
                .eq(TpmMaintenanceTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }
}