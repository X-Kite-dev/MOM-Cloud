package com.kitex.mom.mdm.mq;

import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;

/**
 * 动态刷新MDM 基础数据接口
 * 在数据进行变更之后 发送数据到redis 。订阅redis 的通知进行刷新业务数据
 */
public interface DynamicRefreshMdm {


    void refreshMdm(RefreshMdmEntity obj);


    /**
     * 物料数据
     */
    void refreshMdmMaterial(MdmMaterialEntity obj);


    /**
     * BOM数据
     */
    void refreshMdmBom(MdmBomEntity obj);


    /**
     * 客户数据
     */
    void refreshMdmCustomer(RefreshMdmEntity obj);

    /**
     * 供应商数据
     */
    void refreshMdmSupplier(RefreshMdmEntity obj);


}
