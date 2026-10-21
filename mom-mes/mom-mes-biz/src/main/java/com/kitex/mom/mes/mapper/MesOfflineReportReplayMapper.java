package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesOfflineReportReplayEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 弱网报工重放账数据访问。 */
@Mapper
public interface MesOfflineReportReplayMapper extends MomBaseMapper<MesOfflineReportReplayEntity> {

    /** 同一租户、工厂和请求号的重放必须串行，避免并发网络恢复造成重复记产。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_OFFLINE_REPORT:', #{tenantId}, ':', #{factoryCode}, ':', #{requestId}), 0))")
    Integer lockRequest(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                        @Param("requestId") String requestId);
}
