package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;

/**
 * 包装装箱扫码记录
 *
 * @author kitex
 * @since 2025-04-01
 */
public interface MesWrapRecordService extends IService<MesWrapRecordEntity> {

    /**
     * 删除
     *
     * @param id
     */
    void removeData(Long id);

} 