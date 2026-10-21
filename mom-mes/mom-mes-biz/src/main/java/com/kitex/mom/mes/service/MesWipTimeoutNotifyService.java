package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.WipTimeoutNotifyResult;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;

public interface MesWipTimeoutNotifyService {

    WipTimeoutNotifyResult notifyLevel(MesWipTimeoutConfigEntity config, MesWipTimeoutHandleEntity handle, int level);

    WipTimeoutNotifyResult notifyVerification(MesWipTimeoutConfigEntity config, MesWipTimeoutHandleEntity handle);
}
