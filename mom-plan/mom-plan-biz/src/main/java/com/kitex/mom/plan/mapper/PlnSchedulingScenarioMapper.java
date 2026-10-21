package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnSchedulingScenarioMapper extends MomBaseMapper<PlnSchedulingScenarioEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lock(@Param("lockKey") String lockKey);
}
