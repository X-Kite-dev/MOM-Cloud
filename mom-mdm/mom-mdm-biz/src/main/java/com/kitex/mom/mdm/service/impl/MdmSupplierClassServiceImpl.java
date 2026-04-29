package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.mom.mdm.entity.MdmSupplierClassEntity;
import com.kitex.mom.mdm.mapper.MdmSupplierClassMapper;
import com.kitex.mom.mdm.service.MdmSupplierClassService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 供应商分类表
 *
 * @author kitex
 * @date 2025-03-18 16:46:14
 */
@Service
@AllArgsConstructor
public class MdmSupplierClassServiceImpl extends ServiceImpl<MdmSupplierClassMapper, MdmSupplierClassEntity> implements MdmSupplierClassService {

    /**
     * 供应商分类树形数据
     *
     * @param mdmSupplierClass 供应商分类表
     * @return
     */
    @Override
    public List<Tree<Long>> getSupplierClassTree(MdmSupplierClassEntity mdmSupplierClass) {
        Long parent = mdmSupplierClass.getParentId() == null ? CommonConstants.MENU_TREE_ROOT_ID : mdmSupplierClass.getParentId();
        List<MdmSupplierClassEntity> list = this.list(Wrappers.<MdmSupplierClassEntity>lambdaQuery()
                .like(StrUtil.isNotBlank(mdmSupplierClass.getCode()), MdmSupplierClassEntity::getCode, mdmSupplierClass.getCode())
                .like(StrUtil.isNotBlank(mdmSupplierClass.getName()), MdmSupplierClassEntity::getName, mdmSupplierClass.getName())
                .orderByDesc(MdmSupplierClassEntity::getCreateTime));
        // 模糊查询 不组装树结构 直接返回 表格方便编辑
        if (StrUtil.isNotBlank(mdmSupplierClass.getCode()) || StrUtil.isNotBlank(mdmSupplierClass.getName())) {
            return list.stream().map(node -> {
                Tree<Long> tree = new Tree<>();
                BeanUtils.copyProperties(node, tree);
                return tree;
            }).collect(Collectors.toList());
        }
        return MdmSupplierClassEntity.toTree(list, parent);
    }

    /**
     * 通过id删除供应商分类表
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeTreeBatchByIds(ArrayList<Long> ids) {
        List<Long> removeIds = new ArrayList<>();
        for (Long id : ids) {
            delTreeNodeById(id, removeIds);
        }
        removeIds.addAll(ids);
        return this.removeBatchByIds(removeIds);
    }

    /**
     * 递归获取子供应商分类ID
     *
     * @param parentId  父供应商分类ID
     * @param removeIds 存储供应商分类ID的列表
     */
    private void delTreeNodeById(Long parentId, List<Long> removeIds) {
        List<MdmSupplierClassEntity> list = this.list(new LambdaQueryWrapper<MdmSupplierClassEntity>()
                .eq(MdmSupplierClassEntity::getParentId, parentId)
        );
        list.forEach(node -> {
            removeIds.add(node.getId());
            delTreeNodeById(node.getId(), removeIds);
        });
    }
}