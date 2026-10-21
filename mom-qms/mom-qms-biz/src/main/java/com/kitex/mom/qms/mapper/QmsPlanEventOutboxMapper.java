package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QmsPlanEventOutboxMapper extends BaseMapper<QmsPlanEventOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('QMS_PLAN_OUTBOX:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM qms_plan_event_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    QmsPlanEventOutboxEntity lockById(@Param("id") Long id);
}
