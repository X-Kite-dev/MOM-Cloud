package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT产品数据模型Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotProductModelMapper extends BaseMapper<IotProductModelEntity> {

    /**
     * 分页查询产品数据模型列表
     *
     * @param page  分页参数
     * @param model 查询条件
     * @return 分页结果
     */
    IPage<IotProductModelEntity> selectModelPage(Page<IotProductModelEntity> page, @Param("model") IotProductModelEntity model);

    /**
     * 根据产品ID查询数据模型列表
     *
     * @param productId 产品ID
     * @return 数据模型列表
     */
    List<IotProductModelEntity> selectByProductId(@Param("productId") Long productId);

    /**
     * 根据产品ID和标识符查询物模型
     *
     * @param productId  产品ID
     * @param identifier 物模型标识符
     * @return 物模型
     */
    IotProductModelEntity selectByProductIdAndIdentifier(
            @Param("productId") Long productId,
            @Param("identifier") String identifier
    );
} 