package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsFulfillmentSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsFulfillmentSnapshotMapper extends MomBaseMapper<OmsFulfillmentSnapshotEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_FULFILLMENT_SNAPSHOT:', #{key}), 0))")
    void lockRequest(@Param("key") String key);
}
