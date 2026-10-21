package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesErpPostingOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MES执行事实ERP转交账持久化入口。 */
@Mapper
public interface MesErpPostingOutboxMapper extends MomBaseMapper<MesErpPostingOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lock(@Param("key") String key);
}
