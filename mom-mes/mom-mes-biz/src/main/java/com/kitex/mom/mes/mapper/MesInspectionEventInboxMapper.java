package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesInspectionEventInboxEntity;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MesInspectionEventInboxMapper extends BaseMapper<MesInspectionEventInboxEntity> {
    @Select("SELECT mom_mes.mom_procedure_disposition_ready_v1()")
    Integer dispositionReady();

    @Select("SELECT mom_mes.mom_inspection_event_inbox_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_gen_record WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND inspection_id=#{taskId} AND del_flag='0' ORDER BY"
                    + " id LIMIT 2 FOR UPDATE")
    List<MesInspectionGenRecordEntity> bindings(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId);

    @Select(
            "SELECT COUNT(*) AS \"event_count\", COALESCE(SUM(OCTET_LENGTH(payload)),0) AS"
                    + " \"payload_bytes\" FROM mom_mes.mes_inspection_event_inbox WHERE"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND inspection_type='PROCEDURE'"
                    + " AND task_id=#{taskId}")
    Map<String, Object> footprint(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_inbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND inspection_type='PROCEDURE' AND task_id=#{taskId}"
                    + " ORDER BY sequence_no LIMIT 5001")
    List<MesInspectionEventInboxEntity> chain(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId);
}
