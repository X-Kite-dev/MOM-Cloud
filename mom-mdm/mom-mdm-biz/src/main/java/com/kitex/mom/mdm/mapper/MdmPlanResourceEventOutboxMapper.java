package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmPlanResourceEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmPlanResourceEventOutboxMapper extends BaseMapper<MdmPlanResourceEventOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MDM_PLAN_RESOURCE:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM mdm_plan_resource_event_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    MdmPlanResourceEventOutboxEntity lockById(@Param("id") Long id);
}
