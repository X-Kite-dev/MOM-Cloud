package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesInspectionEventApplicationEntity;
import com.kitex.mom.mes.entity.MesInspectionEventInboxEntity;
import com.kitex.mom.mes.entity.MesInspectionEventStateEntity;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface MesInspectionEventStateMapper extends BaseMapper<MesInspectionEventStateEntity> {
    @Select("SELECT mom_mes.mom_inspection_event_application_ready_v1()")
    Integer schemaVersion();

    @Select(
            "<script>SELECT * FROM mom_mes.mes_inspection_event_state WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} <if test='lock'>FOR"
                    + " UPDATE</if></script>")
    MesInspectionEventStateEntity state(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("lock") boolean lock);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_gen_record WHERE id=#{id} AND tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND del_flag='0'")
    MesInspectionGenRecordEntity generation(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_gen_record WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND produce_task_code=#{code} AND del_flag='0' ORDER BY"
                    + " id LIMIT 5001 FOR UPDATE")
    List<MesInspectionGenRecordEntity> lockGenerations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT id FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND code=#{code} AND del_flag='0' LIMIT 2")
    List<Long> rootIds(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT id FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND code=#{code} AND del_flag='0' LIMIT 2 FOR UPDATE")
    List<Long> lockRoot(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT id FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND code=#{code} AND del_flag='0' LIMIT 2 FOR UPDATE")
    List<Long> lockProcess(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT COUNT(*) FROM mom_mes.mes_inspection_gen_record WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND produce_task_code=#{code} AND del_flag='0' AND"
                    + " quality_event_managed=TRUE")
    long controlled(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_inbox WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    MesInspectionEventInboxEntity original(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_application WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    MesInspectionEventApplicationEntity application(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                    + " mom_mes.mes_inspection_event_application(id,tenant_id,factory_code,gen_record_id,inspection_id,sequence_no,event_fingerprint,previous_event_id,previous_fingerprint,decision_snapshot,decision_fingerprint,applied_at)"
                    + " VALUES(#{id},#{tenantId},#{factoryCode},#{genRecordId},#{inspectionId},#{sequenceNo},#{eventFingerprint},#{previousEventId},#{previousFingerprint},#{decisionSnapshot},#{decisionFingerprint},#{appliedAt})")
    int append(MesInspectionEventApplicationEntity receipt);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_application WHERE gen_record_id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY sequence_no LIMIT"
                    + " 5001")
    List<MesInspectionEventApplicationEntity> applications(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT s.id,s.tenant_id,s.factory_code FROM mom_mes.mes_inspection_event_state s JOIN"
                    + " mom_mes.mes_inspection_gen_record g ON g.id=s.id AND g.tenant_id=s.tenant_id"
                    + " AND g.factory_code=s.factory_code AND g.inspection_id=s.inspection_id AND"
                    + " g.produce_task_code=s.produce_task_code AND g.task_code=s.task_code AND"
                    + " g.del_flag='0' AND g.quality_event_managed=TRUE WHERE s.status='ACTIVE' AND"
                    + " s.id>#{after} AND EXISTS(SELECT 1 FROM mom_mes.mes_inspection_event_inbox i"
                    + " WHERE i.tenant_id=s.tenant_id AND i.factory_code=s.factory_code AND"
                    + " i.gen_record_id=s.id AND i.sequence_no>s.last_sequence) ORDER BY s.id LIMIT 50")
    List<MesInspectionEventStateEntity> dueAfter(@Param("after") long after);

    @Update(
            "UPDATE mom_mes.mes_inspection_gen_record SET quality_event_managed=TRUE WHERE id=#{id}"
                    + " AND tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    int markManaged(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT COALESCE(MAX(sequence_no),0) FROM mom_mes.mes_inspection_event_inbox WHERE"
                    + " gen_record_id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    Long latestSequence(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Update(
            "UPDATE mom_mes.mes_inspection_gen_record SET"
                    + " inspection_status=#{item.inspectionStatus},inspection_result=#{item.inspectionResult},qms_total_num=#{item.qmsTotalNum},qms_usable_num=#{item.qmsUsableNum},qms_handle_num=#{item.qmsHandleNum},qms_handle_result=#{item.qmsHandleResult},gate_status=#{item.gateStatus},sync_message=#{item.syncMessage},last_sync_time=#{item.lastSyncTime}"
                    + " WHERE id=#{item.recordId} AND tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND quality_event_managed=TRUE AND del_flag='0'")
    int project(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("item") com.kitex.mom.mes.dto.MesQualityGateItemDTO item);

    @Select(
            "SELECT COUNT(*) AS \"event_count\",COALESCE(SUM(OCTET_LENGTH(decision_snapshot)),0) AS"
                    + " \"snapshot_bytes\" FROM mom_mes.mes_inspection_event_application WHERE"
                    + " gen_record_id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    Map<String, Object> applicationFootprint(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_application WHERE gen_record_id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND sequence_no>#{after} ORDER"
                    + " BY sequence_no LIMIT #{limit}")
    List<MesInspectionEventApplicationEntity> applicationsPage(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT a.id AS"
                    + " event_id,a.sequence_no,i.event_type,a.event_fingerprint,a.previous_event_id,a.previous_fingerprint,a.applied_at,a.decision_fingerprint"
                    + " FROM mom_mes.mes_inspection_event_application a LEFT JOIN"
                    + " mom_mes.mes_inspection_event_inbox i ON i.id=a.id AND i.tenant_id=a.tenant_id"
                    + " AND i.factory_code=a.factory_code AND i.gen_record_id=a.gen_record_id WHERE"
                    + " a.gen_record_id=#{id} AND a.tenant_id=#{tenant} AND a.factory_code=#{factory}"
                    + " AND a.sequence_no>#{after} ORDER BY a.sequence_no LIMIT #{limit}")
    List<com.kitex.mom.mes.dto.MesInspectionEventSummaryDTO> applicationSummariesPage(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("after") long after,
            @Param("limit") int limit);

    @Select(
            "SELECT * FROM mom_mes.mes_inspection_event_application WHERE id=#{eventId} AND"
                    + " gen_record_id=#{recordId} AND tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND OCTET_LENGTH(decision_snapshot)<=1048576")
    MesInspectionEventApplicationEntity applicationBounded(
            @Param("eventId") Long eventId,
            @Param("recordId") Long recordId,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT a.intent AS \"intent\",s.fingerprint AS \"sealFingerprint\",r.payload AS"
                    + " \"outcome\",r.fingerprint AS \"outcomeFingerprint\" FROM"
                    + " mom_mes.mes_inspection_revision_resolution r JOIN"
                    + " mom_mes.mes_inspection_revision_seal s ON s.id=r.id AND s.tenant_id=r.tenant_id"
                    + " AND s.factory_code=r.factory_code JOIN"
                    + " mom_mes.mes_inspection_revision_admission a ON a.id=r.id AND"
                    + " a.tenant_id=r.tenant_id AND a.factory_code=r.factory_code AND"
                    + " a.gen_record_id=r.gen_record_id WHERE r.tenant_id=#{tenant} AND"
                    + " r.factory_code=#{factory} AND r.gen_record_id=#{record} AND r.event_id=#{event}"
                    + " AND r.operation='APPLY' AND r.intent_fingerprint=a.intent_fingerprint AND"
                    + " r.seal_fingerprint=s.fingerprint AND"
                    + " r.fingerprint=encode(sha256(convert_to(r.payload,'UTF8')),'hex')")
    java.util.Map<String, Object> revisionAuthorization(
            @Param("record") Long record,
            @Param("event") Long event,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);
}
