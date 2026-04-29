package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;


/**
 * 变化点处理
 *
 * @author kitex
 * @since 2025-05-26
 */
public interface MesChangePointHandleService extends IService<MesChangePointHandleEntity> {

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query);

    /**
     * 通知
     *
     * @param mesChangePointHandle
     */
    void notice(MesChangePointHandleEntity mesChangePointHandle);

    /**
     * 处理
     *
     * @param mesChangePointHandle
     */
    void handle(MesChangePointHandleEntity mesChangePointHandle);
}
