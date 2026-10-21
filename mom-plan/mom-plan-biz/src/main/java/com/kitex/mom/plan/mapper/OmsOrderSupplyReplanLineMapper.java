package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanLineEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * OMS订单供应级联逐MRP证据和阶段回执数据访问。
 */
@Mapper
public interface OmsOrderSupplyReplanLineMapper extends MomBaseMapper<OmsOrderSupplyReplanLineEntity> {
}
