package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDevicePurchaseNeedReviewEntity;

public interface TpmDevicePurchaseNeedReviewService extends IService<TpmDevicePurchaseNeedReviewEntity> {

    Boolean approval(Long id, String type, String rejectReason);
}