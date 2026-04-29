package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;

public interface MesWipTimeoutConfigService extends IService<MesWipTimeoutConfigEntity> {
    void set(MesWipTimeoutConfigEntity config);
}
