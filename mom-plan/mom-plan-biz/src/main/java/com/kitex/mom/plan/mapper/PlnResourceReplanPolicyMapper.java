package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnResourceReplanPolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnResourceReplanPolicyMapper extends BaseMapper<PlnResourceReplanPolicyEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_RESOURCE_REPLAN_POLICY:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);
}
