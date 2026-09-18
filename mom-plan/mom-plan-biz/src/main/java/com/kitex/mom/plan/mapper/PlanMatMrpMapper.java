package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlanMatMrpMapper extends MomBaseMapper<PlanMatMrpEntity> {

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey},0))")
    Long lockInventoryAllocationPool(@Param("lockKey") String lockKey);
}
