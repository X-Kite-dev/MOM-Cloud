package com.kitex.mom.mdm.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmSupplierClassEntity;

import java.util.ArrayList;
import java.util.List;

public interface MdmSupplierClassService extends IService<MdmSupplierClassEntity> {

    /**
     * 供应商分类树形数据
     *
     * @param mdmSupplierClass 供应商分类表
     * @return
     */
    List<Tree<Long>> getSupplierClassTree(MdmSupplierClassEntity mdmSupplierClass);

    /**
     * 通过id删除供应商分类表
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeTreeBatchByIds(ArrayList<Long> ids);
}