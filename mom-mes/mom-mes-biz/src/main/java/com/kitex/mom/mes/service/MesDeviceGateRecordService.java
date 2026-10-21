package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesDeviceGateDecision;
import com.kitex.mom.mes.entity.MesDeviceGateRecordEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

public interface MesDeviceGateRecordService extends IService<MesDeviceGateRecordEntity> {

    void record(MesDeviceGateDecision decision, MesTaskArrangeEntity arrange, String factoryCode);
}
