package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsOmsDeliveryIssueEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsOmsDeliveryIssueMapper extends MomBaseMapper<WmsOmsDeliveryIssueEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_OMS_DELIVERY_ISSUE:', #{key}), 0))")
    int lockKey(@Param("key") String key);
}
