package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmToolingUnavailableWindowEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmToolingUnavailableWindowMapper extends BaseMapper<MdmToolingUnavailableWindowEntity> {
    @Select("""
            SELECT source.*
             FROM mdm_tooling_unavailable_window source
             WHERE source.del_flag='0' AND source.status=true AND source.end_time>CURRENT_TIMESTAMP
               AND EXISTS (
                    SELECT 1 FROM mdm_tooling master
                     WHERE master.del_flag='0' AND master.status=true
                       AND master.tooling_code=source.tooling_code
                       AND COALESCE(master.factory_code,'')=COALESCE(source.factory_code,'')
                       AND COALESCE(master.tenant_id,-1)=COALESCE(source.tenant_id,-1))
               AND NOT EXISTS (
                    SELECT 1 FROM mdm_plan_resource_event_outbox event
                     WHERE event.del_flag='0'
                       AND event.source_type='MDM_TOOLING_UNAVAILABLE_WINDOW'
                       AND event.source_id=source.id
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.start_time,source.id
             LIMIT #{limit}
            """)
    List<MdmToolingUnavailableWindowEntity> selectMissingPlanEvents(@Param("limit") int limit);
}
