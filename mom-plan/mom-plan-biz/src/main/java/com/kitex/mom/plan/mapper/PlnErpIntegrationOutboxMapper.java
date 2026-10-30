package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * ERP 可靠投递账持久化入口。
 */
@Mapper
public interface PlnErpIntegrationOutboxMapper extends MomBaseMapper<PlnErpIntegrationOutboxEntity> {
    /**
     * 串行化永久请求号的首次入账。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lock(@Param("key") String key);
}
