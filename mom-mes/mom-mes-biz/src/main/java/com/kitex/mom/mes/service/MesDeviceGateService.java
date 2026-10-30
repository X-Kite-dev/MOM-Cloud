package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.enums.MesDeviceGateStageEnum;

public interface MesDeviceGateService {

    /**
     * 共享正式设备门规则的只读观察，不写设备核验账，不授予开工许可。
     */
    MesDeviceGateDecision previewStart(MesTaskArrangeEntity arrange);

    MesDeviceGateDecision check(MesDeviceGateStageEnum stage, MesTaskArrangeEntity arrange,
                                String sourceDeviceCode, String worksiteCode);

    MesDeviceGateDecision checkDeviceStart(String deviceCode, String worksiteCode, String factoryCode);
}
