package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnInsertionReplanCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnInsertionReplanCaseMapper extends BaseMapper<PlnInsertionReplanCaseEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_INSERTION_REPLAN_CASE:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
