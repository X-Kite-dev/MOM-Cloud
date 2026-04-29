package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionDetailsEntity;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionRecordEntity;
import com.kitex.mom.tpm.mapper.TpmDeviceArrivalInspectionRecordMapper;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionDetailsService;
import com.kitex.mom.tpm.service.TpmDeviceArrivalInspectionRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备到货质检记录表
 *
 * @author kitex
 * @date 2025-03-26 10:25:25
 */
@Service
@AllArgsConstructor
public class TpmDeviceArrivalInspectionRecordServiceImpl extends ServiceImpl<TpmDeviceArrivalInspectionRecordMapper, TpmDeviceArrivalInspectionRecordEntity> implements TpmDeviceArrivalInspectionRecordService {

    private final TpmDeviceArrivalInspectionDetailsService tpmDeviceArrivalInspectionDetailsService;


    @Override
    public TpmDeviceArrivalInspectionRecordEntity getDeepById(Long id) {
        //查询主表数据
        TpmDeviceArrivalInspectionRecordEntity entity = this.getById(id);
        List<TpmDeviceArrivalInspectionDetailsEntity> detailsEntityList = tpmDeviceArrivalInspectionDetailsService.list(new LambdaQueryWrapper<TpmDeviceArrivalInspectionDetailsEntity>()
                .eq(TpmDeviceArrivalInspectionDetailsEntity::getQcCode, entity.getQcCode()));
        //查询子表数据
        entity.setDetailsList(detailsEntityList);
        return entity;
    }
}