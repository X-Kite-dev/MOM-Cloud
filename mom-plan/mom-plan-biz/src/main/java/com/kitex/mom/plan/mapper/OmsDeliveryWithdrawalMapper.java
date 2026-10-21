package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsDeliveryWithdrawalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsDeliveryWithdrawalMapper extends MomBaseMapper<OmsDeliveryWithdrawalEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_DELIVERY_WITHDRAWAL:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
