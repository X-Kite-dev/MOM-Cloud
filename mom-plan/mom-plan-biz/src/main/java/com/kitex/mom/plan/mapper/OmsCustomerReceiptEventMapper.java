package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsCustomerReceiptEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsCustomerReceiptEventMapper extends MomBaseMapper<OmsCustomerReceiptEventEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Long lockKey(String key);
}
