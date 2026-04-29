package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;

/**
 * 场景联动历史记录Service接口
 */
public interface IotSceneHistoryService extends IService<IotSceneHistoryEntity> {

    /**
     * 分页查询场景执行历史
     *
     * @param page  分页对象
     * @param query 查询参数
     * @return 分页结果
     */
    IPage<IotSceneHistoryEntity> getHistoryPage(Page<IotSceneHistoryEntity> page, IotSceneHistoryEntity query);

    /**
     * 记录场景执行历史
     *
     * @param entity 历史记录
     * @return 是否成功
     */
    boolean addHistory(IotSceneHistoryEntity entity);
} 