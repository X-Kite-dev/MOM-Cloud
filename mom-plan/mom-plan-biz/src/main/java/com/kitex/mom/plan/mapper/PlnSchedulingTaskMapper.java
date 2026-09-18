package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnSchedulingTaskMapper extends MomBaseMapper<PlnSchedulingTaskEntity> {
    @Select("SELECT id FROM pln_scheduling_task WHERE id = #{taskId} FOR UPDATE")
    Long acquireTransactionLock(@Param("taskId") Long taskId);

    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{scenarioNo}, 0))")
    Integer acquireScenarioTransactionLock(@Param("scenarioNo") String scenarioNo);
}
