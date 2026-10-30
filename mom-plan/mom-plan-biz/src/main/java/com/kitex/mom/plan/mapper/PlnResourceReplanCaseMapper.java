package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnResourceReplanCaseMapper extends BaseMapper<PlnResourceReplanCaseEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_RESOURCE_REPLAN_CASE:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);
}
