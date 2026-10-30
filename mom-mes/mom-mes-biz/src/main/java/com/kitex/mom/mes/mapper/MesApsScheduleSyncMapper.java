package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesApsScheduleSyncEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesApsScheduleSyncMapper extends MomBaseMapper<MesApsScheduleSyncEntity> {
    /**
     * 应用、补偿及取证按真实案件串行化；须先锁案件，再锁永久请求。
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockCase(@Param("key") String key);

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_APS_SCHEDULE_SYNC:', #{requestId}), 0))")
    void lockRequest(@Param("requestId") String requestId);
}
