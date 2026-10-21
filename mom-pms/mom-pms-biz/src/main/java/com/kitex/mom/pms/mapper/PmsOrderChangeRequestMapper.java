package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsOrderChangeRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PmsOrderChangeRequestMapper extends MomBaseMapper<PmsOrderChangeRequestEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PMS_ORDER_CHANGE:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pms_order_change_request WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PmsOrderChangeRequestEntity lockById(@Param("id") Long id);
}
