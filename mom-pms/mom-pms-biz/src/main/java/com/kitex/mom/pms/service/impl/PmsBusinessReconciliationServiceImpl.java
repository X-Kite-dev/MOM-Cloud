package com.kitex.mom.pms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.pms.entity.PmsBusinessReconciliationEntity;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.mapper.PmsBusinessReconciliationMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.service.PmsBusinessReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 业务对账
 *
 * @author kitex
 * @date 2025-04-07 14:53:53
 */
@Service
@RequiredArgsConstructor
public class PmsBusinessReconciliationServiceImpl extends ServiceImpl<PmsBusinessReconciliationMapper, PmsBusinessReconciliationEntity> implements PmsBusinessReconciliationService {

    private final PmsOrderDetailMapper pmsOrderDetailMapper;

    private final RemoteSeqConfigService remoteSeqConfigService;

    @Override
    public void saveByPmsOrder(PmsOrderEntity pmsOrderEntity) {
        List<PmsOrderDetailEntity> orderDetailList = pmsOrderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, pmsOrderEntity.getId()));
        List<PmsBusinessReconciliationEntity> saveList = new ArrayList<>();
        for (PmsOrderDetailEntity pmsOrderDetailEntity : orderDetailList) {
            PmsBusinessReconciliationEntity pmsBusinessReconciliationEntity = new PmsBusinessReconciliationEntity();
            BeanUtils.copyProperties(pmsOrderDetailEntity, pmsBusinessReconciliationEntity, "id");
            pmsBusinessReconciliationEntity.setReconciliationNo(remoteSeqConfigService.create("pms_business_reconciliation").getData());
            pmsBusinessReconciliationEntity.setPmsOrderNo(pmsOrderEntity.getPmsOrderNo());
            pmsBusinessReconciliationEntity.setSupplierName(pmsOrderEntity.getSupplierName());
            pmsBusinessReconciliationEntity.setSupplierCode(pmsOrderEntity.getSupplierCode());
            BigDecimal multiply = pmsOrderDetailEntity.getPrice().multiply(BigDecimal.valueOf(pmsOrderDetailEntity.getArrivalNum()));
            pmsBusinessReconciliationEntity.setReconciliationAmount(multiply);
            pmsBusinessReconciliationEntity.setStatus(Boolean.FALSE);
            saveList.add(pmsBusinessReconciliationEntity);
        }
        this.saveBatch(saveList);
    }
}