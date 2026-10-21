package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftCaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsSalesReservationDriftCaseMapper extends MomBaseMapper<WmsSalesReservationDriftCaseEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_SALES_DRIFT:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
