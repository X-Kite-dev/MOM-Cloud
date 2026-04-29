package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotSceneEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 场景联动Mapper接口
 */
@Mapper
public interface IotSceneMapper extends BaseMapper<IotSceneEntity> {

    /**
     * 分页查询场景列表
     *
     * @param page  分页对象
     * @param query 查询参数
     * @return 分页结果
     */
    IPage<IotSceneEntity> selectScenePage(Page<IotSceneEntity> page, @Param("query") IotSceneEntity query);
} 