package com.kitex.mom.mdm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mdm.entity.MdmEmployeeCalendarExceptionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MdmEmployeeCalendarExceptionMapper extends MomBaseMapper<MdmEmployeeCalendarExceptionEntity> {
    @Select("""
            SELECT source.*
              FROM mdm_employee_calendar_exception source
             WHERE source.del_flag='0' AND source.status=true AND source.exception_type<>'OVERTIME'
               AND (source.calendar_date + COALESCE(source.end_time,TIME '00:00')
                    + CASE WHEN source.end_time IS NULL
                                OR (source.start_time IS NOT NULL AND source.end_time<=source.start_time)
                           THEN INTERVAL '1 day' ELSE INTERVAL '0' END)>CURRENT_TIMESTAMP
               AND EXISTS (
                    SELECT 1 FROM mdm_employee master
                     WHERE master.del_flag='0' AND master.status='1'
                       AND master.employee_code=source.employee_code
                       AND COALESCE(master.factory_code,'')=COALESCE(source.factory_code,'')
                       AND COALESCE(master.tenant_id,-1)=COALESCE(source.tenant_id,-1))
               AND NOT EXISTS (
                    SELECT 1 FROM mdm_plan_resource_event_outbox event
                     WHERE event.del_flag='0'
                       AND event.source_type='MDM_EMPLOYEE_CALENDAR_EXCEPTION'
                       AND event.source_id=source.id
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.calendar_date,source.start_time NULLS FIRST,source.id
             LIMIT #{limit}
            """)
    List<MdmEmployeeCalendarExceptionEntity> selectMissingPlanEvents(@Param("limit") int limit);
}
