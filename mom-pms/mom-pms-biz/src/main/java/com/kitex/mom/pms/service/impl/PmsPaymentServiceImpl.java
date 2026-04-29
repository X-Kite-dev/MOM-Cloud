package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.pms.entity.PmsPaymentEntity;
import com.kitex.mom.pms.entity.PmsPaymentInvoiceEntity;
import com.kitex.mom.pms.entity.PmsPaymentReconciliationEntity;
import com.kitex.mom.pms.mapper.PmsPaymentInvoiceMapper;
import com.kitex.mom.pms.mapper.PmsPaymentMapper;
import com.kitex.mom.pms.mapper.PmsPaymentReconciliationMapper;
import com.kitex.mom.pms.service.PmsPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 预付款
 *
 * @author kitex
 * @date 2025-04-08 11:08:12
 */
@Service
@RequiredArgsConstructor
public class PmsPaymentServiceImpl extends ServiceImpl<PmsPaymentMapper, PmsPaymentEntity> implements PmsPaymentService {

    private final PmsPaymentInvoiceMapper pmsPaymentInvoiceMapper;

    private final PmsPaymentReconciliationMapper pmsPaymentReconciliationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(PmsPaymentEntity pmsAdvancePayment) {
        pmsAdvancePayment.setStatus("0");
        baseMapper.insert(pmsAdvancePayment);

        // 预付款-发票保存逻辑
        for (PmsPaymentInvoiceEntity pmsAdvancePaymentInvoice : pmsAdvancePayment.getPmsPaymentInvoiceList()) {
            pmsAdvancePaymentInvoice.setPaymentId(pmsAdvancePayment.getId());
            pmsPaymentInvoiceMapper.insert(pmsAdvancePaymentInvoice);
        }
        // 预付款-业务对账保存逻辑
        for (PmsPaymentReconciliationEntity pmsAdvancePaymentReconciliation : pmsAdvancePayment.getPmsPaymentReconciliationList()) {
            pmsAdvancePaymentReconciliation.setPaymentId(pmsAdvancePayment.getId());
            pmsPaymentReconciliationMapper.insert(pmsAdvancePaymentReconciliation);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(PmsPaymentEntity pmsAdvancePayment) {
        baseMapper.updateById(pmsAdvancePayment);
        // 预付款-发票保存逻辑
        if (CollUtil.isNotEmpty(pmsAdvancePayment.getPmsPaymentInvoiceList())) {
            for (PmsPaymentInvoiceEntity pmsAdvancePaymentInvoice : pmsAdvancePayment.getPmsPaymentInvoiceList()) {
                if (Objects.isNull(pmsAdvancePaymentInvoice.getId())) {
                    pmsAdvancePaymentInvoice.setPaymentId(pmsAdvancePayment.getId());
                    pmsPaymentInvoiceMapper.insert(pmsAdvancePaymentInvoice);
                } else {
                    pmsPaymentInvoiceMapper.updateById(pmsAdvancePaymentInvoice);
                }
            }
        }
        // 预付款-业务对账保存逻辑
        if (CollUtil.isNotEmpty(pmsAdvancePayment.getPmsPaymentReconciliationList())) {
            for (PmsPaymentReconciliationEntity pmsAdvancePaymentReconciliation : pmsAdvancePayment.getPmsPaymentReconciliationList()) {
                if (Objects.isNull(pmsAdvancePaymentReconciliation.getId())) {
                    pmsAdvancePaymentReconciliation.setPaymentId(pmsAdvancePayment.getId());
                    pmsPaymentReconciliationMapper.insert(pmsAdvancePaymentReconciliation);
                } else {
                    pmsPaymentReconciliationMapper.updateById(pmsAdvancePaymentReconciliation);
                }
            }
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        ArrayList<Long> payMentIds = CollUtil.toList(ids);
        this.removeBatchByIds(payMentIds);
        pmsPaymentInvoiceMapper.delete(Wrappers.<PmsPaymentInvoiceEntity>lambdaQuery().
                in(PmsPaymentInvoiceEntity::getPaymentId, payMentIds));
        pmsPaymentReconciliationMapper.delete(Wrappers.<PmsPaymentReconciliationEntity>lambdaQuery().
                in(PmsPaymentReconciliationEntity::getPaymentId, payMentIds));
        return Boolean.TRUE;
    }

    /**
     * 查询预付款和票到付款的付款金额总数和退款金额总数
     */
    @Override
    public Map<String, BigDecimal> getAmount(PmsPaymentEntity pmsAdvancePayment) {
        QueryWrapper<PmsPaymentEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierName()), PmsPaymentEntity::getSupplierName, pmsAdvancePayment.getSupplierName())
                .like(StrUtil.isNotBlank(pmsAdvancePayment.getSupplierCode()), PmsPaymentEntity::getSupplierCode, pmsAdvancePayment.getSupplierCode())
                .eq(StrUtil.isNotBlank(pmsAdvancePayment.getType()), PmsPaymentEntity::getType, pmsAdvancePayment.getType())
                .eq(PmsPaymentEntity::getStatus, "1")
                .eq(PmsPaymentEntity::getDelFlag, "0")
                .groupBy(PmsPaymentEntity::getType);
        List<PmsPaymentEntity> list = baseMapper.getAmount(wrapper);
        Map<String, BigDecimal> resultMap = new HashMap<>();
        // 初始化默认值
        resultMap.put("advancePayMentAmount", BigDecimal.ZERO);
        resultMap.put("advanceRefundAmount", BigDecimal.ZERO);
        resultMap.put("invoicePayMentAmount", BigDecimal.ZERO);
        resultMap.put("invoiceRefundAmount", BigDecimal.ZERO);
        for (PmsPaymentEntity entity : list) {
            if ("0".equals(entity.getType())) {
                resultMap.put("advancePayMentAmount", entity.getPaidAmount());
                resultMap.put("advanceRefundAmount", entity.getRefundAmount());
            } else {
                resultMap.put("invoicePayMentAmount", entity.getPaidAmount());
                resultMap.put("invoiceRefundAmount", entity.getRefundAmount());
            }
        }
        return resultMap;
    }
}
