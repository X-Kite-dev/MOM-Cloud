package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
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
            // 整行已撤回且没有实际到货的物料，不生成零金额应付对账明细。
            if (pmsOrderDetailEntity.getArrivalNum() == null || pmsOrderDetailEntity.getArrivalNum() == 0D) {
                continue;
            }
            PmsBusinessReconciliationEntity pmsBusinessReconciliationEntity = new PmsBusinessReconciliationEntity();
            BeanUtils.copyProperties(pmsOrderDetailEntity, pmsBusinessReconciliationEntity, "id");
            var sequence = remoteSeqConfigService.create("pms_business_reconciliation");
            if (sequence == null || !sequence.isOk() || StrUtil.isBlank(sequence.getData())) {
                throw new CheckedException("采购对账单编号生成未确认");
            }
            pmsBusinessReconciliationEntity.setReconciliationNo(sequence.getData());
            pmsBusinessReconciliationEntity.setPmsOrderNo(pmsOrderEntity.getPmsOrderNo());
            pmsBusinessReconciliationEntity.setSupplierName(pmsOrderEntity.getSupplierName());
            pmsBusinessReconciliationEntity.setSupplierCode(pmsOrderEntity.getSupplierCode());
            BigDecimal multiply = pmsOrderDetailEntity.getPrice().multiply(BigDecimal.valueOf(pmsOrderDetailEntity.getArrivalNum()));
            pmsBusinessReconciliationEntity.setReconciliationAmount(multiply);
            pmsBusinessReconciliationEntity.setStatus(Boolean.FALSE);
            saveList.add(pmsBusinessReconciliationEntity);
        }
        if (saveList.isEmpty() || !this.saveBatch(saveList)) {
            throw new CheckedException("采购订单业务对账单未保存");
        }
    }
}
