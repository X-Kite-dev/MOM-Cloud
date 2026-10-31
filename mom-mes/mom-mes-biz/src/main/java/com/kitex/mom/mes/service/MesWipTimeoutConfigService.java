package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;

import java.util.Collection;

public interface MesWipTimeoutConfigService extends IService<MesWipTimeoutConfigEntity> {
    MesWipTimeoutConfigEntity saveData(MesWipTimeoutConfigEntity config);

    void updateData(MesWipTimeoutConfigEntity config);

    void removeUnused(Collection<Long> ids);

    MesWipTimeoutConfigEntity readData(Long id);
}
