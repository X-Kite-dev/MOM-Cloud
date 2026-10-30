package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnApsResourceSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 智能 APS 不可变资源快照持久化入口。
 */
@Mapper
public interface PlnApsResourceSnapshotMapper extends MomBaseMapper<PlnApsResourceSnapshotEntity> {

    /**
     * 同一租户工厂请求号的事务级幂等锁。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lock(@Param("lockKey") String lockKey);
}
