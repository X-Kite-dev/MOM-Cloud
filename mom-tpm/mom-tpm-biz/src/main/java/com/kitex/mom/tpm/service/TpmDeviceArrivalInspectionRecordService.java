package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionRecordEntity;

public interface TpmDeviceArrivalInspectionRecordService extends IService<TpmDeviceArrivalInspectionRecordEntity> {

    TpmDeviceArrivalInspectionRecordEntity getDeepById(Long id);
}