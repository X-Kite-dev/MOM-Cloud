package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsOmsDeliveryWithdrawalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsOmsDeliveryWithdrawalMapper extends MomBaseMapper<WmsOmsDeliveryWithdrawalEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_OMS_WITHDRAW:', #{key}), 0))")
    int lockKey(@Param("key") String key);
}
