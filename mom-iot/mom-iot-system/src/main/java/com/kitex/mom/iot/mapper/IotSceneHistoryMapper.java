package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 场景联动历史记录Mapper接口
 */
@Mapper
public interface IotSceneHistoryMapper extends BaseMapper<IotSceneHistoryEntity> {

    /**
     * 分页查询场景执行历史
     *
     * @param page  分页对象
     * @param query 查询参数
     * @return 分页结果
     */
    IPage<IotSceneHistoryEntity> selectHistoryPage(Page<IotSceneHistoryEntity> page, @Param("query") IotSceneHistoryEntity query);
} 