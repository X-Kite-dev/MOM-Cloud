package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesApsSourceQuantityEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesApsSourceQuantityEventMapper extends MomBaseMapper<MesApsSourceQuantityEventEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{requestId}, 0))")
    Integer lockRequest(@Param("requestId") String requestId);
}
