package com.kitex.mom.mes.service;

import com.kitex.mom.mes.dto.ChangePointNotifyResult;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;

public interface MesChangePointNotifyService {
    ChangePointNotifyResult notifyHandlers(MesChangePointConfigEntity config, MesChangePointHandleEntity handle);

    ChangePointNotifyResult notifyVerifiers(MesChangePointConfigEntity config, MesChangePointHandleEntity handle);

    ChangePointNotifyResult notifyReleaseApprovers(MesChangePointConfigEntity config,
                                                   MesChangePointHandleEntity handle);

    ChangePointNotifyResult notifyEscalation(MesChangePointConfigEntity config, MesChangePointHandleEntity handle);
}
