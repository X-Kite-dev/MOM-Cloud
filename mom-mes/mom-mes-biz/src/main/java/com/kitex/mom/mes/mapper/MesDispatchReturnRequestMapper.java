package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesDispatchReturnRequestMapper extends MomBaseMapper<MesDispatchReturnRequestEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_DISPATCH_RETURN:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
