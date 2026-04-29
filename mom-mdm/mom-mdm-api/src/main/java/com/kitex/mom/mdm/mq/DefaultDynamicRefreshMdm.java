package com.kitex.mom.mdm.mq;

import cn.hutool.core.bean.BeanUtil;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultDynamicRefreshMdm implements DynamicRefreshMdm {
    @Override
    public void refreshMdm(RefreshMdmEntity obj) {
        log.info("获取的数据变化实体  ---> {}", obj.toString());
        //

        String type = obj.getType();
        try {
            Class<?> aClass = Class.forName(type);
            if (aClass.isAssignableFrom(MdmMaterialEntity.class)) {
                MdmMaterialEntity bean = BeanUtil.toBean(obj.getObj(), MdmMaterialEntity.class);
                refreshMdmMaterial(bean);
            } else if (aClass.isAssignableFrom(MdmBomEntity.class)) {
                MdmBomEntity bean = BeanUtil.toBean(obj.getObj(), MdmBomEntity.class);
                refreshMdmBom(bean);
            }

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }


    }

    @Override
    public void refreshMdmMaterial(MdmMaterialEntity obj) {
        log.info("获取物料的数据变化实体  ---> {}", obj.toString());

    }

    @Override
    public void refreshMdmBom(MdmBomEntity obj) {
        log.info("获取BOM的数据变化实体  ---> {}", obj.toString());

    }

    @Override
    public void refreshMdmCustomer(RefreshMdmEntity obj) {

    }

    @Override
    public void refreshMdmSupplier(RefreshMdmEntity obj) {

    }
}
