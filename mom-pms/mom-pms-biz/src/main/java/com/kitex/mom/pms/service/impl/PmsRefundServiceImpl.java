package com.kitex.mom.pms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.entity.PmsPaymentEntity;
import com.kitex.mom.pms.entity.PmsPaymentInvoiceEntity;
import com.kitex.mom.pms.entity.PmsPaymentReconciliationEntity;
import com.kitex.mom.pms.entity.PmsRefundEntity;
import com.kitex.mom.pms.mapper.PmsPaymentInvoiceMapper;
import com.kitex.mom.pms.mapper.PmsPaymentReconciliationMapper;
import com.kitex.mom.pms.mapper.PmsRefundMapper;
import com.kitex.mom.pms.service.PmsPaymentService;
import com.kitex.mom.pms.service.PmsRefundService;
import com.kitex.mom.pms.vo.PmsRefundVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 退款表
 *
 * @author kitex
 * @date 2025-04-08 17:24:20
 */
@Service
@RequiredArgsConstructor
public class PmsRefundServiceImpl extends ServiceImpl<PmsRefundMapper, PmsRefundEntity> implements PmsRefundService {

    private final PmsPaymentService pmsPaymentService;

    private final PmsPaymentInvoiceMapper pmsPaymentInvoiceMapper;

    private final PmsPaymentReconciliationMapper pmsPaymentReconciliationMapper;

    /**
     * 通过id查询退款表
     *
     * @param id id
     * @return R
     */
    @Override
    public PmsRefundVO getRefundById(Long id) {
        PmsRefundVO pmsRefundVO = new PmsRefundVO();
        PmsRefundEntity byId = this.getById(id);
        BeanUtils.copyProperties(byId, pmsRefundVO);

        // 查询付款单标
        PmsPaymentEntity pmsPayment = pmsPaymentService.getOne(new LambdaQueryWrapper<PmsPaymentEntity>()
                .eq(PmsPaymentEntity::getPaymentNo, byId.getPaymentNo()), false);
        pmsRefundVO.setPmsPaymentInvoiceList(pmsPaymentInvoiceMapper.selectList(Wrappers.<PmsPaymentInvoiceEntity>lambdaQuery().
                eq(PmsPaymentInvoiceEntity::getPaymentId, pmsPayment.getId())));
        pmsRefundVO.setPmsPaymentReconciliationList(pmsPaymentReconciliationMapper.selectList(Wrappers.<PmsPaymentReconciliationEntity>lambdaQuery().
                eq(PmsPaymentReconciliationEntity::getPaymentId, pmsPayment.getId())));
        return pmsRefundVO;
    }

    /**
     * 新增退款表
     *
     * @param pmsRefund 退款表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveRefund(PmsRefundEntity pmsRefund) {
        // 查询付款单
        PmsPaymentEntity pmsPayment = pmsPaymentService.getById(pmsRefund.getId());
        BigDecimal amount = pmsPayment.getPaidAmount().subtract(pmsPayment.getRefundAmount());
        BigDecimal payMentRefundAmount = pmsPayment.getRefundAmount();
        BigDecimal refundAmount = pmsRefund.getRefundAmount();
        if (amount.compareTo(refundAmount) < 0) {
            throw new CheckedException("可退款金额小于退款金额，退款失败");
        } else {
            pmsPayment.setRefundAmount(payMentRefundAmount.add(refundAmount));
        }
        pmsPaymentService.updateById(pmsPayment);

        pmsRefund.setId(null);
        return this.save(pmsRefund);
    }
}