package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.*;

/** Fixed rework-completion channel. Quality V1 table and guard remain separate. */
@Mapper
public interface MesReworkSourcePlanOutboxMapper {
    String COLUMNS =
            "id,tenant_id,factory_code,request_id,request_fingerprint,task_id,task_code,CASE WHEN"
                + " OCTET_LENGTH(payload)<=1048576 THEN payload ELSE NULL END AS"
                + " payload,payload_fingerprint,status,attempt_count,next_attempt_at,created_at,received_at,last_error";

    @Select("SELECT mom_mes.mom_rework_source_plan_ready_v1()")
    Integer schemaVersion();

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_rework_source_plan_outbox(id,tenant_id,factory_code,request_id,request_fingerprint,task_id,task_code,payload,payload_fingerprint,status,attempt_count,next_attempt_at,created_at,received_at,last_error)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{requestId},#{requestFingerprint},#{taskId},#{taskCode},#{payload},#{payloadFingerprint},#{status},#{attemptCount},#{nextAttemptAt},#{createdAt},#{receivedAt},#{lastError})")
    int insert(MesSourceQuantityPlanOutboxEntity row);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM mom_mes.mes_rework_source_plan_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request} ORDER BY id LIMIT 2")
    @Results(
            id = "reworkPlan",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "request_id", property = "requestId"),
                @Result(column = "request_fingerprint", property = "requestFingerprint"),
                @Result(column = "task_id", property = "taskId"),
                @Result(column = "task_code", property = "taskCode"),
                @Result(column = "payload", property = "payload"),
                @Result(column = "payload_fingerprint", property = "payloadFingerprint"),
                @Result(column = "status", property = "status"),
                @Result(column = "attempt_count", property = "attemptCount"),
                @Result(column = "next_attempt_at", property = "nextAttemptAt"),
                @Result(column = "created_at", property = "createdAt"),
                @Result(column = "received_at", property = "receivedAt"),
                @Result(column = "last_error", property = "lastError")
            })
    List<MesSourceQuantityPlanOutboxEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM mom_mes.mes_rework_source_plan_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} FOR UPDATE")
    @ResultMap("reworkPlan")
    MesSourceQuantityPlanOutboxEntity locked(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Update(
            "UPDATE mom_mes.mes_rework_source_plan_outbox SET"
                + " status=#{row.status},attempt_count=#{row.attemptCount},next_attempt_at=#{row.nextAttemptAt},received_at=#{row.receivedAt},last_error=#{row.lastError}"
                + " WHERE id=#{row.id} AND tenant_id=#{row.tenantId} AND"
                + " factory_code=#{row.factoryCode} AND attempt_count=#{previous} AND status IN"
                + " ('PENDING','RETRY_WAIT')")
    int progress(
            @Param("row") MesSourceQuantityPlanOutboxEntity row, @Param("previous") long previous);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code FROM mom_mes.mes_rework_source_plan_outbox WHERE"
                    + " status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=#{now} ORDER BY"
                    + " next_attempt_at,id LIMIT 50")
    @ResultMap("reworkPlan")
    List<MesSourceQuantityPlanOutboxEntity> due(@Param("now") Date now);

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,request_id,request_fingerprint,event_kind,mes_task_id,mes_task_code,source_issue_id,source_issue_no,quantity_delta,source_reference"
                + " FROM mom_mes.mes_aps_source_quantity_event WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND request_id=#{request} AND del_flag='0' ORDER BY id"
                + " LIMIT 5001")
    @Results(
            id = "reworkFact",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "request_id", property = "requestId"),
                @Result(column = "request_fingerprint", property = "requestFingerprint"),
                @Result(column = "event_kind", property = "eventKind"),
                @Result(column = "mes_task_id", property = "mesTaskId"),
                @Result(column = "mes_task_code", property = "mesTaskCode"),
                @Result(column = "source_issue_id", property = "sourceIssueId"),
                @Result(column = "source_issue_no", property = "sourceIssueNo"),
                @Result(column = "quantity_delta", property = "quantityDelta"),
                @Result(column = "source_reference", property = "sourceReference")
            })
    List<MesApsSourceQuantityEventEntity> events(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    String TASK =
            "id,tenant_id,factory_code,code,mat_code,resource_type,source_task_code,rework_request_id,status,complete_num";

    @Select(
            "SELECT "
                    + TASK
                    + " FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND id=#{id} AND del_flag='0'")
    @Results(
            id = "reworkTask",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "code", property = "code"),
                @Result(column = "mat_code", property = "matCode"),
                @Result(column = "resource_type", property = "resourceType"),
                @Result(column = "source_task_code", property = "sourceTaskCode"),
                @Result(column = "rework_request_id", property = "reworkRequestId"),
                @Result(column = "status", property = "status"),
                @Result(column = "complete_num", property = "completeNum")
            })
    MesTaskEntity source(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT "
                    + TASK
                    + " FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND code=#{code} AND del_flag='0' LIMIT 2")
    @ResultMap("reworkTask")
    List<MesTaskEntity> rework(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);
}
