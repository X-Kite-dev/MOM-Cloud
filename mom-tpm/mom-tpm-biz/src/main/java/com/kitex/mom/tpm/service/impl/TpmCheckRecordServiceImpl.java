package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmCheckRecordEntity;
import com.kitex.mom.tpm.entity.TpmCheckTaskDetailsEntity;
import com.kitex.mom.tpm.mapper.TpmCheckRecordMapper;
import com.kitex.mom.tpm.service.TpmCheckRecordService;
import com.kitex.mom.tpm.service.TpmCheckTaskDetailsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 点/巡检记录
 *
 * @author kitex
 * @date 2025-02-18 17:46:05
 */
@Service
@AllArgsConstructor
public class TpmCheckRecordServiceImpl extends ServiceImpl<TpmCheckRecordMapper, TpmCheckRecordEntity> implements TpmCheckRecordService {

    private final TpmCheckTaskDetailsService tpmCheckTaskDetailsService;

    @Override
    public TpmCheckRecordEntity getRecordById(Long id) {
        TpmCheckRecordEntity byId = this.getById(id);
        List<TpmCheckTaskDetailsEntity> detailsList = tpmCheckTaskDetailsService.list(new LambdaQueryWrapper<TpmCheckTaskDetailsEntity>()
                .eq(TpmCheckTaskDetailsEntity::getTaskCode, byId.getTaskCode()));
        byId.setDetailsList(detailsList);
        return byId;
    }
}