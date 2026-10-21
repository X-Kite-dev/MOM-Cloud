package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmToolingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmToolingMapper extends BaseMapper<MdmToolingEntity> {
    @Select("""
            SELECT source.* FROM mdm_tooling source
             WHERE source.del_flag='0'
               AND (COALESCE(source.status,false)=false
                    OR COALESCE(source.tooling_state,'') NOT IN('AVAILABLE','IN_USE'))
               AND NOT EXISTS (
                    SELECT 1 FROM mdm_plan_resource_event_outbox event
                     WHERE event.del_flag='0' AND event.source_status='ACTIVE'
                       AND event.source_type='MDM_TOOLING_MASTER_STATUS' AND event.source_id=source.id
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.unavailable_since NULLS FIRST,source.id LIMIT #{limit}
            """)
    List<MdmToolingEntity> selectMissingMasterStatusEvents(@Param("limit") int limit);
}
