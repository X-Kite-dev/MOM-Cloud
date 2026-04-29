package com.kitex.mom.iot.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotProductCategoryDTO;
import com.kitex.mom.iot.entity.IotProductCategoryEntity;

import java.util.List;

/**
 * IoT产品分类服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotProductCategoryService extends IService<IotProductCategoryEntity> {

    /**
     * 分页查询分类列表
     *
     * @param page     分页参数
     * @param category 查询条件
     * @return 分页结果
     */
    IPage<IotProductCategoryEntity> getCategoryPage(Page<IotProductCategoryEntity> page, IotProductCategoryEntity category);

    /**
     * 查询分类树
     *
     * @param parentId 父分类ID，为null时查询所有
     * @return 分类树
     */
    List<Tree<Long>> getCategoryTree(Long parentId);

    /**
     * 创建分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    Boolean createCategory(IotProductCategoryDTO categoryDTO);

    /**
     * 更新分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    Boolean updateCategory(IotProductCategoryDTO categoryDTO);

    /**
     * 删除分类
     *
     * @param id 分类ID
     * @return 操作结果
     */
    Boolean removeCategory(Long id);
} 