package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * OMS订单供应级联不可变操作履历数据访问。
 */
@Mapper
public interface OmsOrderSupplyReplanOperationMapper extends MomBaseMapper<OmsOrderSupplyReplanOperationEntity> {
}
