package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsFulfillmentRiskCaseMapper extends MomBaseMapper<OmsFulfillmentRiskCaseEntity> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{key}))")
    Integer lockKey(@Param("key") String key);
}
