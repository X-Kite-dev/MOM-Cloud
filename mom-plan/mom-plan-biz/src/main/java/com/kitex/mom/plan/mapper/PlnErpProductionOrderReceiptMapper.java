package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnErpProductionOrderReceiptEntity;
import org.apache.ibatis.annotations.Mapper;

/** ERP生产订单映射回执持久化入口。 */
@Mapper
public interface PlnErpProductionOrderReceiptMapper
        extends MomBaseMapper<PlnErpProductionOrderReceiptEntity> {
}
