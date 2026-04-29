package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseBaseEntity;

public interface TpmDevicePurchaseBaseService extends IService<TpmDevicePurchaseBaseEntity> {

    TpmDevicePurchaseBaseEntity getDeepById(Long id);

    Boolean savePurchaseBase(TpmDevicePurchaseBaseEntity tpmDevicePurchaseBase);

    Boolean pushQc(Long id);
}