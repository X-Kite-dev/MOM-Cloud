package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedEntity;

import java.util.List;

public interface TpmDevicePurchaseNeedService extends IService<TpmDevicePurchaseNeedEntity> {

    Boolean saveNeed(TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed);

    Boolean updateNeedById(TpmDevicePurchaseNeedEntity tpmDevicePurchaseNeed);

    /**
     * 提交采购需求
     *
     * @param ids ids
     */
    Boolean commitById(List<Long> ids);

}