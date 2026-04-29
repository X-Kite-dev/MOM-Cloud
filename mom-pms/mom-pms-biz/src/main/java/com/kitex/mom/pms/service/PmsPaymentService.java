package com.kitex.mom.pms.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.entity.PmsPaymentEntity;

import java.math.BigDecimal;
import java.util.Map;

public interface PmsPaymentService extends MPJDeepService<PmsPaymentEntity> {
    Boolean saveDeep(PmsPaymentEntity pmsAdvancePayment);

    Boolean updateDeep(PmsPaymentEntity pmsAdvancePayment);

    Boolean removeDeep(Long[] ids);

    /**
     * 查询预付款和票到付款的付款金额总数和退款金额总数
     */
    Map<String, BigDecimal> getAmount(PmsPaymentEntity pmsAdvancePayment);
}