package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnQualityReplanPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnQualityReplanPolicyMapper extends MomBaseMapper<PlnQualityReplanPolicyEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_QUALITY_POLICY:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);
}
