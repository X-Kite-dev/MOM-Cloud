package com.kitex.mom.qms.mapper;

import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 显式实际组织条件；所有写入均由持有真实父任务锁的服务调用。 */
@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
public interface QmsInspectionPieceMapper {
    @Data
    class Row {
        private Long id,
                tenantId,
                taskId,
                actorId,
                occurredAt,
                pieceId,
                submissionId,
                detailId,
                recordId,
                originInsertTxid;
        private String factoryCode,
                identifier,
                requestId,
                payload,
                fingerprint,
                state,
                basisFingerprint,
                valueText;
        private Boolean qualified, actualQualified;
        private Long actualTask, actualDetail;
        private Double actualValue;
        private String actorName;
    }

    /** regclass 按真实关系存在性判断；不能把 information_schema 的权限不可见误作未安装。 */
    @Select(
            "SELECT (CASE WHEN to_regclass('mom_qms.qms_inspection_piece') IS NULL THEN 0 ELSE 1"
                + " END) + (CASE WHEN to_regclass('mom_qms.qms_inspection_piece_submission') IS"
                + " NULL THEN 0 ELSE 1 END) + (CASE WHEN"
                + " to_regclass('mom_qms.qms_inspection_piece_sample') IS NULL THEN 0 ELSE 1 END)")
    int installedTables();

    @Select("SELECT mom_qms.mom_inspection_piece_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} FOR UPDATE")
    QmsInspectionProcedureTaskEntity lockParent(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionProcedureTaskEntity parent(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece_submission WHERE task_id=#{task} AND"
                    + " request_id=#{request} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    Row request(
            @Param("task") Long task,
            @Param("request") String request,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_piece_submission(id,tenant_id,factory_code,task_id,request_id,actor_id,occurred_at,payload,fingerprint,state,piece_id)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{requestId},#{actorId},#{occurredAt},#{payload},#{fingerprint},#{state},#{pieceId})")
    int insertSubmission(Row row);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_piece(id,tenant_id,factory_code,task_id,identifier,actor_id,actor_name,occurred_at,basis_fingerprint)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{identifier},#{actorId},#{actorName},#{occurredAt},#{basisFingerprint})")
    int insertPiece(Row row);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_piece_sample(id,tenant_id,factory_code,task_id,piece_id,submission_id,detail_id,record_id,value_text,qualified)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{pieceId},#{submissionId},#{detailId},#{recordId},#{valueText},#{qualified})")
    int insertSample(Row row);

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    long count(
            @Param("task") Long task,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND identifier=#{identifier}")
    long identifierCount(
            @Param("task") Long task,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("identifier") String identifier);

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_item_result_record r JOIN"
                + " mom_qms.qms_inspection_procedure_task_detail d ON d.id=r.detail_id AND"
                + " d.task_id=r.task_id WHERE r.task_id=#{task} AND d.tenant_id=#{tenant} AND"
                + " d.factory_code=#{factory} AND NOT EXISTS(SELECT 1 FROM"
                + " mom_qms.qms_inspection_piece_sample s WHERE s.record_id=r.id AND"
                + " s.task_id=r.task_id AND s.tenant_id=#{tenant} AND s.factory_code=#{factory})")
    long anonymousCount(
            @Param("task") Long task,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY id DESC LIMIT 20"
                    + " OFFSET #{offset}")
    List<Row> pieces(
            @Param("task") Long task,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("offset") int offset);

    @Select(
            "SELECT s.*, r.task_id AS actual_task, r.detail_id AS actual_detail, r.double_value AS"
                + " actual_value, r.qualified AS actual_qualified FROM"
                + " mom_qms.qms_inspection_piece_sample s LEFT JOIN"
                + " mom_qms.qms_inspection_procedure_item_result_record r ON r.id=s.record_id WHERE"
                + " s.task_id=#{task} AND s.piece_id=#{piece} AND s.tenant_id=#{tenant} AND"
                + " s.factory_code=#{factory} ORDER BY s.detail_id LIMIT 501")
    List<Row> samples(
            @Param("task") Long task,
            @Param("piece") Long piece,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece WHERE id=#{piece} AND task_id=#{task} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory}")
    Row piece(
            @Param("task") Long task,
            @Param("piece") Long piece,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND"
                + " identifier=#{identifier} AND tenant_id=#{tenant} AND factory_code=#{factory}"
                + " ORDER BY id LIMIT 2")
    List<Row> byIdentifier(
            @Param("task") Long task,
            @Param("identifier") String identifier,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);
}
