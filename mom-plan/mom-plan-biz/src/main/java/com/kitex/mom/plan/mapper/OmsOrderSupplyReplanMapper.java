package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * OMS订单供应级联主账数据访问及跨实例事务咨询锁。
 */
@Mapper
public interface OmsOrderSupplyReplanMapper extends MomBaseMapper<OmsOrderSupplyReplanEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_SUPPLY_REPLAN:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
