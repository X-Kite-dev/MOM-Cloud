package com.kitex.mom.pms.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.mom.pms.entity.PmsContractEntity;

public interface PmsContractService extends MPJDeepService<PmsContractEntity> {
    Boolean saveDeep(PmsContractEntity pmsContract);

    Boolean updateDeep(PmsContractEntity pmsContract);

    Boolean removeDeep(Long[] ids);

    Boolean removeChild(Long[] ids);

    /**
     * 通过采购订单号改变合同状态
     *
     * @param pmsOrderNo 采购订单号
     * @param status     状态
     * @return R
     */
    Boolean statusUpdate(String pmsOrderNo, String status);
}