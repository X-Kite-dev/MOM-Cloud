package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * IoT产品Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotProductMapper extends BaseMapper<IotProductEntity> {

    /**
     * 分页查询产品列表
     *
     * @param page    分页参数
     * @param product 查询条件
     * @return 分页结果
     */
    IPage<IotProductEntity> selectProductPage(Page<IotProductEntity> page, @Param("product") IotProductEntity product);
} 