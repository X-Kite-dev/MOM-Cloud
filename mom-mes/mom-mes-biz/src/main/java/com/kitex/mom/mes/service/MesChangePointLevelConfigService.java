package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesChangePointLevelConfigEntity;

public interface MesChangePointLevelConfigService extends IService<MesChangePointLevelConfigEntity> {

    void setByConfigCode(MesChangePointLevelConfigEntity config);
}
