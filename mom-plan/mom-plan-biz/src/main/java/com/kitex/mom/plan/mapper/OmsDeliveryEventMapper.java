package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsDeliveryEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsDeliveryEventMapper extends MomBaseMapper<OmsDeliveryEventEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_DELIVERY_EVENT:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
