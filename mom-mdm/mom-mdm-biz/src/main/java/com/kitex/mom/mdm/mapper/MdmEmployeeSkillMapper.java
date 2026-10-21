package com.kitex.mom.mdm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mdm.entity.MdmEmployeeSkillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmEmployeeSkillMapper extends MomBaseMapper<MdmEmployeeSkillEntity> {
    @Select("""
            SELECT source.* FROM mdm_employee_skill source
             WHERE source.del_flag='0' AND (source.status=false OR source.valid_to IS NOT NULL)
               AND EXISTS (
                    SELECT 1 FROM mdm_employee master
                     WHERE master.del_flag='0' AND master.status='1'
                       AND master.employee_code=source.employee_code
                       AND COALESCE(master.factory_code,'')=COALESCE(source.factory_code,'')
                       AND COALESCE(master.tenant_id,-1)=COALESCE(source.tenant_id,-1))
               AND NOT EXISTS (
                    SELECT 1 FROM mdm_plan_resource_event_outbox event
                     WHERE event.del_flag='0' AND event.source_status='ACTIVE'
                       AND event.source_type='MDM_EMPLOYEE_QUALIFICATION_INVALID' AND event.source_id=source.id
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.invalid_time NULLS FIRST,source.valid_to NULLS FIRST,source.id LIMIT #{limit}
            """)
    List<MdmEmployeeSkillEntity> selectMissingQualificationEvents(@Param("limit") int limit);
}
