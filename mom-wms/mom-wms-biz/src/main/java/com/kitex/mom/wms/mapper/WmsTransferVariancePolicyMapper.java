package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsTransferVariancePolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsTransferVariancePolicyMapper extends MomBaseMapper<WmsTransferVariancePolicyEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_TRANSFER_VARIANCE_POLICY:', #{key}), 0))")
    void lockPolicyDimension(String key);
}
