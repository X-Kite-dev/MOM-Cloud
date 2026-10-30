package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnMpsExecutionObservationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnMpsExecutionObservationMapper extends MomBaseMapper<PlnMpsExecutionObservationEntity> {
    /**
     * 当前租户/工厂/请求串行化；采集主事务使用READ_COMMITTED，锁后能读取前一提交回执。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);
}
