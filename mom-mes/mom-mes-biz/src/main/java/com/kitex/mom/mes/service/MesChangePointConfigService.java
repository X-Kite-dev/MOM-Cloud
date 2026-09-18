package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;

import java.util.Collection;


/**
 * 变化点配置
 *
 * @author kitex
 * @since 2025-05-26
 */
public interface MesChangePointConfigService extends IService<MesChangePointConfigEntity> {

    MesChangePointConfigEntity saveData(MesChangePointConfigEntity config);

    void updateData(MesChangePointConfigEntity config);

    void removeUnused(Collection<Long> ids);

}
