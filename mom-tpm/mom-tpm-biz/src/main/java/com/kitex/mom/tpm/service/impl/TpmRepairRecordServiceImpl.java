package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmRepairRecordEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskDetailsEntity;
import com.kitex.mom.tpm.mapper.TpmRepairRecordMapper;
import com.kitex.mom.tpm.service.TpmRepairRecordService;
import com.kitex.mom.tpm.service.TpmRepairTaskDetailsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 维修记录表
 *
 * @author kitex
 * @date 2025-02-06 15:35:28
 */
@Service
@AllArgsConstructor
public class TpmRepairRecordServiceImpl extends ServiceImpl<TpmRepairRecordMapper, TpmRepairRecordEntity> implements TpmRepairRecordService {

    private final TpmRepairTaskDetailsService tpmRepairTaskDetailsService;

    @Override
    public TpmRepairRecordEntity getRecordById(Long id) {
        TpmRepairRecordEntity byId = this.getById(id);
        //查询子表
        List<TpmRepairTaskDetailsEntity> detailsList = tpmRepairTaskDetailsService.list(new LambdaQueryWrapper<TpmRepairTaskDetailsEntity>()
                .eq(TpmRepairTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }
}