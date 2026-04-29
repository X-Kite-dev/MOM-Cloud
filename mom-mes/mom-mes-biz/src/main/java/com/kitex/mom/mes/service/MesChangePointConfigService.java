package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;


/**
 * 变化点配置
 *
 * @author kitex
 * @since 2025-05-26
 */
public interface MesChangePointConfigService extends IService<MesChangePointConfigEntity> {

    /**
     * 保存配置
     *
     * @param config
     */
    void set(MesChangePointConfigEntity config);

}
