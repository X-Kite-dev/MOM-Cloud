package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsSalesReturnEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsSalesReturnEventMapper extends MomBaseMapper<OmsSalesReturnEventEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('OMS_SALES_RETURN_EVENT:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
