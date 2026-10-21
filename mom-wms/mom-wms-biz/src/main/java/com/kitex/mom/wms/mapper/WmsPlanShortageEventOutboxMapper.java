package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsPlanShortageEventOutboxMapper extends BaseMapper<WmsPlanShortageEventOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('WMS_PLAN_SHORTAGE_OUTBOX:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM wms_plan_shortage_event_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    WmsPlanShortageEventOutboxEntity lockById(@Param("id") Long id);
}
