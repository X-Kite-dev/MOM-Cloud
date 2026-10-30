package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 智能 APS 向 MES 下达的事务 Outbox 持久化入口。
 */
@Mapper
public interface PlnApsMesDispatchOutboxMapper extends MomBaseMapper<PlnApsMesDispatchOutboxEntity> {

    /**
     * 以事务级咨询锁串行化同一案件发布指纹的入账。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lock(@Param("key") String key);
}
