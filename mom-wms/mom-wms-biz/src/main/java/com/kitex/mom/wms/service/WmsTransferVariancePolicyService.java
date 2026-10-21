package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsTransferVariancePolicyEntity;

import java.time.LocalDate;

public interface WmsTransferVariancePolicyService extends IService<WmsTransferVariancePolicyEntity> {
    Page<WmsTransferVariancePolicyEntity> queryPage(Page<WmsTransferVariancePolicyEntity> page,
                                                    WmsTransferVarianceDTO.PolicyQuery query);

    Long savePolicy(WmsTransferVarianceDTO.PolicyCommand command);

    void deletePolicy(Long id);

    WmsTransferVariancePolicyEntity resolveRequired(String factoryCode, String sourceWarehouseCode,
                                                    String targetWarehouseCode, String materialCode,
                                                    LocalDate businessDate);
}
