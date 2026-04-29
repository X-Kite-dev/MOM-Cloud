package com.kitex.mom.iot.service.impl;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.iot.dto.IotProductCategoryDTO;
import com.kitex.mom.iot.entity.IotProductCategoryEntity;
import com.kitex.mom.iot.mapper.IotProductCategoryMapper;
import com.kitex.mom.iot.service.IotProductCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * IoT产品分类服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotProductCategoryServiceImpl extends ServiceImpl<IotProductCategoryMapper, IotProductCategoryEntity> implements IotProductCategoryService {

    /**
     * 分页查询分类列表
     *
     * @param page     分页参数
     * @param category 查询条件
     * @return 分页结果
     */
    @Override
    public IPage<IotProductCategoryEntity> getCategoryPage(Page<IotProductCategoryEntity> page, IotProductCategoryEntity category) {
        LambdaQueryWrapper<IotProductCategoryEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.hasLength(category.getCategoryName()), IotProductCategoryEntity::getCategoryName, category.getCategoryName())
                .like(StringUtils.hasLength(category.getCategoryCode()), IotProductCategoryEntity::getCategoryCode, category.getCategoryCode())
                .eq(Objects.nonNull(category.getParentId()), IotProductCategoryEntity::getParentId, category.getParentId())
                .orderByAsc(IotProductCategoryEntity::getSort);
        return baseMapper.selectPage(page, queryWrapper);
    }

    /**
     * 查询分类树
     *
     * @param parentId 父分类ID，为null时查询所有
     * @return 分类树
     */
    @Override
    public List<Tree<Long>> getCategoryTree(Long parentId) {
        List<IotProductCategoryEntity> iotProductCategoryEntities = baseMapper.selectList(null);
        List<Tree<Long>> tree = IotProductCategoryEntity.toTree(iotProductCategoryEntities, IotProductCategoryEntity.ROOT_ID);

        return tree;
    }

    /**
     * 创建分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean createCategory(IotProductCategoryDTO categoryDTO) {
        // 检查分类编码是否已存在
        LambdaQueryWrapper<IotProductCategoryEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductCategoryEntity::getCategoryCode, categoryDTO.getCategoryCode());
        if (baseMapper.selectCount(queryWrapper) > 0) {
            throw new CheckedException("分类编码已存在");
        }

        // 检查父分类是否存在
        if (categoryDTO.getParentId() != null && categoryDTO.getParentId() > 0) {
            IotProductCategoryEntity parent = baseMapper.selectById(categoryDTO.getParentId());
            if (parent == null) {
                throw new CheckedException("父分类不存在");
            }
        }

        // 创建分类
        IotProductCategoryEntity category = new IotProductCategoryEntity();
        BeanUtils.copyProperties(categoryDTO, category);
        if (category.getParentId() == null) {
            category.setParentId(0L); // 默认为顶级分类
        }
        return save(category);
    }

    /**
     * 更新分类
     *
     * @param categoryDTO 分类信息
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateCategory(IotProductCategoryDTO categoryDTO) {
        if (categoryDTO.getId() == null) {
            throw new CheckedException("分类ID不能为空");
        }

        // 检查分类是否存在
        IotProductCategoryEntity existingCategory = baseMapper.selectById(categoryDTO.getId());
        if (existingCategory == null) {
            throw new CheckedException("分类不存在");
        }

        // 检查分类编码是否已被其他分类使用
        if (StringUtils.hasLength(categoryDTO.getCategoryCode()) && !categoryDTO.getCategoryCode().equals(existingCategory.getCategoryCode())) {
            LambdaQueryWrapper<IotProductCategoryEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotProductCategoryEntity::getCategoryCode, categoryDTO.getCategoryCode())
                    .ne(IotProductCategoryEntity::getId, categoryDTO.getId());
            if (baseMapper.selectCount(queryWrapper) > 0) {
                throw new CheckedException("分类编码已被其他分类使用");
            }
        }

        // 检查父分类是否存在，并且不能将自己设为父分类
        if (categoryDTO.getParentId() != null && categoryDTO.getParentId() > 0) {
            if (categoryDTO.getParentId().equals(categoryDTO.getId())) {
                throw new CheckedException("不能将自己设为父分类");
            }
            IotProductCategoryEntity parent = baseMapper.selectById(categoryDTO.getParentId());
            if (parent == null) {
                throw new CheckedException("父分类不存在");
            }
        }

        // 更新分类
        IotProductCategoryEntity category = new IotProductCategoryEntity();
        BeanUtils.copyProperties(categoryDTO, category);
        return updateById(category);
    }

    /**
     * 删除分类
     *
     * @param id 分类ID
     * @return 操作结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeCategory(Long id) {
        if (id == null) {
            throw new CheckedException("分类ID不能为空");
        }

        // 检查是否有子分类
        LambdaQueryWrapper<IotProductCategoryEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductCategoryEntity::getParentId, id);
        if (baseMapper.selectCount(queryWrapper) > 0) {
            throw new CheckedException("该分类下有子分类，无法删除");
        }

        // 检查分类下是否有产品
        Integer productCount = baseMapper.countProductsByCategoryId(id);
        if (productCount != null && productCount > 0) {
            throw new CheckedException("该分类下有产品，无法删除");
        }

        // 删除分类
        return removeById(id);
    }
} 