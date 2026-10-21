package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnAutomationEventMapper extends BaseMapper<PlnAutomationEventEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_AUTOMATION_EVENT:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pln_automation_event WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PlnAutomationEventEntity lockById(@Param("id") Long id);
}
