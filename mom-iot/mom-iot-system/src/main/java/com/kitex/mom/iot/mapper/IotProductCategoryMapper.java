package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.iot.entity.IotProductCategoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT产品分类Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotProductCategoryMapper extends BaseMapper<IotProductCategoryEntity> {

    /**
     * 查询分类下的产品数量
     *
     * @param categoryId 分类ID
     * @return 产品数量
     */
    Integer countProductsByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 查询分类树
     *
     * @param parentId 父分类ID
     * @return 分类树
     */
    List<IotProductCategoryEntity> selectCategoryTree(@Param("parentId") Long parentId);
} 