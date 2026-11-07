package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWorkpieceTimeSessionEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MesInspectionPieceEntryMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT s.* FROM mom_mes.mes_workpiece_entry_source e JOIN"
                    + " mom_mes.mes_workpiece_time_session s ON s.id=e.session_id AND"
                    + " s.tenant_id=e.tenant_id AND s.factory_code=e.factory_code AND"
                    + " s.arrange_id=e.arrange_id WHERE s.tenant_id=#{tenant} AND"
                    + " s.factory_code=#{factory} AND s.task_code=#{order} AND"
                    + " s.produce_task_code=#{operation} AND s.identifier_type='SN' AND"
                    + " s.identifier=#{identifier} AND s.del_flag='0' ORDER BY s.id LIMIT 2")
    List<MesWorkpieceTimeSessionEntity> candidates(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("order") String order,
            @Param("operation") String operation,
            @Param("identifier") String identifier);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT CAST(e.tenant_id AS varchar),e.factory_code,CAST(e.arrange_id AS"
                + " varchar),CAST(e.session_id AS varchar),CAST(e.operation_id AS"
                + " varchar),e.entry_raw,e.fingerprint,o.receipt_json,o.request_hash FROM"
                + " mom_mes.mes_workpiece_entry_source e LEFT JOIN"
                + " mom_mes.mes_workpiece_time_operation o ON o.id=e.operation_id AND"
                + " o.tenant_id=e.tenant_id AND o.factory_code=e.factory_code AND"
                + " o.arrange_id=e.arrange_id AND o.session_id=e.session_id AND o.action='START'"
                + " AND o.del_flag='0' WHERE e.tenant_id=#{tenant} AND e.factory_code=#{factory}"
                + " AND e.arrange_id=#{arrange} AND e.session_id=#{session}")
    MesWorkpieceTimeMapper.EntryRow original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("arrange") Long arrange,
            @Param("session") Long session);
}
