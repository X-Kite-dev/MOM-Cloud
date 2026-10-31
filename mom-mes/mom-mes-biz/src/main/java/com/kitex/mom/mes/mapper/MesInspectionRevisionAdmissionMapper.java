package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesInspectionRevisionAdmissionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface MesInspectionRevisionAdmissionMapper
        extends BaseMapper<MesInspectionRevisionAdmissionEntity> {
    @Select(
            "SELECT CASE WHEN mom_mes.mom_inspection_revision_admission_ready_v1()=1 AND"
                    + " mom_mes.mom_inspection_revision_execution_ready_v1()=1 THEN 1 ELSE 0 END")
    Integer ready();

    @Select("SELECT mom_mes.mom_workpiece_time_ready()")
    Boolean timingReady();

    @Select("SELECT current_setting('transaction_isolation')")
    String isolation();

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_inspection_revision_admission a WHERE"
                    + " a.tenant_id=#{tenant} AND a.factory_code=#{factory} AND a.task_code=#{root} AND"
                    + " NOT EXISTS(SELECT 1 FROM mom_mes.mes_inspection_revision_cancel c WHERE"
                    + " c.id=a.id AND c.tenant_id=a.tenant_id AND c.factory_code=a.factory_code AND"
                    + " c.task_code=a.task_code AND c.produce_task_code=a.produce_task_code AND"
                    + " c.inspection_id=a.inspection_id AND c.intent_fingerprint=a.intent_fingerprint"
                    + " AND c.intent=a.intent) AND NOT EXISTS(SELECT 1 FROM"
                    + " mom_mes.mes_inspection_revision_resolution r WHERE r.id=a.id AND"
                    + " r.tenant_id=a.tenant_id AND r.factory_code=a.factory_code AND"
                    + " r.task_code=a.task_code AND r.produce_task_code=a.produce_task_code AND"
                    + " r.inspection_id=a.inspection_id AND r.intent_fingerprint=a.intent_fingerprint"
                    + " AND r.fingerprint=encode(sha256(convert_to(r.payload,'UTF8')),'hex') AND"
                    + " r.receipt_fingerprint=encode(sha256(convert_to(r.receipt,'UTF8')),'hex'))")
    long held(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_gen_record WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND inspection_id=#{inspection} AND del_flag='0' ORDER"
                    + " BY id LIMIT 2")
    List<MesInspectionGenRecordEntity> bindings(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("inspection") Long inspection);

    @Select(
            "SELECT id AS \"id\",status AS \"status\",resource_type AS"
                    + " \"resourceType\",complete_num AS \"complete\" FROM mom_mes.mes_task WHERE"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{root} AND"
                    + " del_flag='0'")
    Map<String, Object> rootFacts(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_task_report x WHERE x.tenant_id=#{tenant} AND"
                    + " x.factory_code=#{factory} AND (x.task_code=#{root} OR EXISTS(SELECT 1 FROM"
                    + " mom_mes.mes_produce_task n WHERE n.tenant_id=x.tenant_id AND"
                    + " n.factory_code=x.factory_code AND n.code=x.produce_task_code AND"
                    + " n.task_code=#{root}))")
    long reports(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_quality_stock_proof p WHERE p.tenant_id=#{tenant} AND"
                    + " p.factory_code=#{factory} AND EXISTS(SELECT 1 FROM mom_mes.mes_produce_task n"
                    + " WHERE n.tenant_id=p.tenant_id AND n.factory_code=p.factory_code AND"
                    + " n.task_code=#{root} AND n.code=p.produce_task_code)")
    long proofs(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT COUNT(*) AS \"total\",COALESCE(SUM(CASE WHEN complete_num IS NULL OR"
                    + " complete_num<>0 OR quality_released_num IS NULL OR quality_released_num<>0 OR"
                    + " un_pass_num IS NULL OR un_pass_num<>0 THEN 1 ELSE 0 END),0) AS \"invalid\" FROM"
                    + " mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND task_code=#{root}")
    Map<String, Object> processFacts(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT COUNT(*) AS \"total\",COALESCE(SUM(CASE WHEN complete_num IS NULL OR"
                    + " complete_num<>0 OR quality_released_num IS NULL OR quality_released_num<>0 OR"
                    + " status IS NULL OR status NOT IN"
                    + " ('WAIT','UN_RECEIVE','RECEIVE','PAUSE','FINISH','CLOSE') THEN 1 ELSE 0 END),0)"
                    + " AS \"invalid\" FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_code=#{root}")
    Map<String, Object> arrangementFacts(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT (SELECT COUNT(*) FROM mom_mes.mes_workpiece_time_session x WHERE"
                    + " x.tenant_id=#{tenant} AND x.factory_code=#{factory} AND (x.task_code=#{root} OR"
                    + " EXISTS(SELECT 1 FROM mom_mes.mes_task_arrange a WHERE a.id=x.arrange_id AND"
                    + " a.tenant_id=x.tenant_id AND a.factory_code=x.factory_code AND"
                    + " a.task_code=#{root}) OR EXISTS(SELECT 1 FROM mom_mes.mes_produce_task p WHERE"
                    + " p.tenant_id=x.tenant_id AND p.factory_code=x.factory_code AND"
                    + " p.code=x.produce_task_code AND p.task_code=#{root})) AND (x.status IS NULL OR"
                    + " x.status NOT IN ('PAUSED','FINISHED'))) + (SELECT COUNT(*) FROM"
                    + " mom_mes.mes_workpiece_time_interval x WHERE x.tenant_id=#{tenant} AND"
                    + " x.factory_code=#{factory} AND (x.task_code=#{root} OR EXISTS(SELECT 1 FROM"
                    + " mom_mes.mes_task_arrange a WHERE a.id=x.arrange_id AND a.tenant_id=x.tenant_id"
                    + " AND a.factory_code=x.factory_code AND a.task_code=#{root}) OR EXISTS(SELECT 1"
                    + " FROM mom_mes.mes_produce_task p WHERE p.tenant_id=x.tenant_id AND"
                    + " p.factory_code=x.factory_code AND p.code=x.produce_task_code AND"
                    + " p.task_code=#{root})) AND (x.start_time IS NULL OR x.kind IS NULL OR x.kind NOT"
                    + " IN ('RUN','PAUSE') OR x.status IS NULL OR x.status NOT IN ('OPEN','CLOSED') OR"
                    + " (x.status='OPEN' AND x.end_time IS NOT NULL) OR (x.status='CLOSED' AND"
                    + " x.end_time IS NULL) OR (x.kind='RUN' AND x.end_time IS NULL)))")
    long activeTiming(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("root") String root);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_inspection_revision_seal s WHERE s.id=#{id} AND"
                    + " s.tenant_id=#{tenant} AND s.factory_code=#{factory} AND NOT EXISTS(SELECT 1"
                    + " FROM mom_mes.mes_inspection_revision_resolution r WHERE r.id=s.id AND"
                    + " r.tenant_id=s.tenant_id AND r.factory_code=s.factory_code AND"
                    + " r.operation='ABORT' AND"
                    + " r.fingerprint=encode(sha256(convert_to(r.payload,'UTF8')),'hex'))")
    long sealed(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_inspection_revision_resolution r WHERE r.id=#{id} AND"
                    + " r.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.operation='APPLY' AND"
                    + " r.fingerprint=encode(sha256(convert_to(r.payload,'UTF8')),'hex') AND"
                    + " r.receipt_fingerprint=encode(sha256(convert_to(r.receipt,'UTF8')),'hex')")
    long executed(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);
}
