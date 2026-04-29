package com.kitex.mom.tpm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.tpm.entity.TpmDeviceArrivalInspectionEntity;

public interface TpmDeviceArrivalInspectionService extends IService<TpmDeviceArrivalInspectionEntity> {

    TpmDeviceArrivalInspectionEntity getDeepById(Long id);

    Boolean approval(TpmDeviceArrivalInspectionEntity entity);
}