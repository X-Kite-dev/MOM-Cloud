package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.oms.entity.OmsCustomerExceptionResolutionEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OmsCustomerExceptionResolutionEventMapper extends BaseMapper<OmsCustomerExceptionResolutionEventEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockKey(String key);
}
