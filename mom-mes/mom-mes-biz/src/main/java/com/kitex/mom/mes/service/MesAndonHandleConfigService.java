package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesAndonHandleConfigVO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigEntity;

public interface MesAndonHandleConfigService extends IService<MesAndonHandleConfigEntity> {

    /**
     * 保存
     *
     * @param config
     */
    void saveData(MesAndonHandleConfigVO config);

    /**
     * 更新
     *
     * @param config
     */
    void updateConfig(MesAndonHandleConfigVO config);

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    MesAndonHandleConfigVO getConfig(Long id);

}