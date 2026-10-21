package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsDeliveryIssueEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsDeliveryIssueMapper extends MomBaseMapper<OmsDeliveryIssueEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_DELIVERY_ISSUE:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
