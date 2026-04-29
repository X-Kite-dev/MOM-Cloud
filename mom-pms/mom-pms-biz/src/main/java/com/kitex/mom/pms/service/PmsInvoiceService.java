package com.kitex.mom.pms.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.entity.PmsInvoiceEntity;
import com.kitex.mom.pms.entity.PmsInvoiceReconciliationEntity;

import java.util.List;

public interface PmsInvoiceService extends MPJDeepService<PmsInvoiceEntity> {
    Boolean saveDeep(PmsInvoiceEntity pmsInvoice);

    Boolean updateDeep(PmsInvoiceEntity pmsInvoice);

    Boolean removeDeep(Long[] ids);

    /**
     * 根据发票id查询发票关联的业务对账单
     *
     * @param ids 发票ids
     * @return R
     */
    List<PmsInvoiceReconciliationEntity> getReconciliationByInvoiceId(Long[] ids);
}