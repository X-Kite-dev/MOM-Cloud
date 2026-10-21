package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnMpsRemainingReplanLinkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MPS重排来源账，永久请求按真实租户/工厂串行化。 */
@Mapper
public interface PlnMpsRemainingReplanLinkMapper extends MomBaseMapper<PlnMpsRemainingReplanLinkEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockRequest(@Param("key") String key);
}
