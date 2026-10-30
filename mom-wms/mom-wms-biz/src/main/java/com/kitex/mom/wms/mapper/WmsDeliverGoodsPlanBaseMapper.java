package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsDeliverGoodsPlanBaseMapper extends MomBaseMapper<WmsDeliverGoodsPlanBaseEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_OMS_DELIVERY:', #{key}), 0))")
    int lockOmsSource(@Param("key") String key);
}
