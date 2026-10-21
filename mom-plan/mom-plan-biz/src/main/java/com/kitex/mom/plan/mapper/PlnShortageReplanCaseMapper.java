package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnShortageReplanCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnShortageReplanCaseMapper extends MomBaseMapper<PlnShortageReplanCaseEntity> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{requestId}))")
    Integer lockRequest(@Param("requestId") String requestId);
}
