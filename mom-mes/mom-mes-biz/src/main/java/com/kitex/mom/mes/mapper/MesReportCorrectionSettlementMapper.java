package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesReportCorrectionSettlementEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesReportCorrectionSettlementMapper extends MomBaseMapper<MesReportCorrectionSettlementEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_REPORT_SETTLEMENT:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
