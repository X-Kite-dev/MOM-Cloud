package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 智能 APS 案件持久化入口。
 *
 * <p>状态推进必须经过案件领域服务，本 Mapper 不提供绕过状态机的批量状态修改。</p>
 */
@Mapper
public interface PlnApsCaseMapper extends MomBaseMapper<PlnApsCaseEntity> {

    /**
     * 在当前事务内获取案件业务键的 PostgreSQL 咨询锁。
     *
     * @param lockKey 租户、工厂与业务键组成的稳定锁键
     * @return 固定值 1
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lock(@Param("lockKey") String lockKey);
}
