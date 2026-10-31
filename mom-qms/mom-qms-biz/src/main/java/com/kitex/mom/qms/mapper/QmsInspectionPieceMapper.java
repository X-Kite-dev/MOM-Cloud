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
        private Long id, tenantId, taskId, actorId, occurredAt, pieceId, submissionId, detailId, recordId;
        private String factoryCode, identifier, requestId, payload, fingerprint, state, basisFingerprint, valueText;
        private Boolean qualified;
    }
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_schema)='mom_qms' AND lower(table_name) IN ('qms_inspection_piece','qms_inspection_piece_submission','qms_inspection_piece_sample')")
    int installedTables();
    @Select("SELECT mom_qms.mom_inspection_piece_ready_v1()")
    Integer schemaVersion();
    @Select("SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory} FOR UPDATE")
    QmsInspectionProcedureTaskEntity lockParent(@Param("id") Long id,@Param("tenant") Long tenant,@Param("factory") String factory);
    @Select("SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionProcedureTaskEntity parent(@Param("id") Long id,@Param("tenant") Long tenant,@Param("factory") String factory);
    @Select("SELECT * FROM mom_qms.qms_inspection_piece_submission WHERE task_id=#{task} AND request_id=#{request} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    Row request(@Param("task") Long task,@Param("request") String request,@Param("tenant") Long tenant,@Param("factory") String factory);
    @Insert("INSERT INTO mom_qms.qms_inspection_piece_submission(id,tenant_id,factory_code,task_id,request_id,actor_id,occurred_at,payload,fingerprint,state,piece_id) VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{requestId},#{actorId},#{occurredAt},#{payload},#{fingerprint},#{state},#{pieceId})")
    int insertSubmission(Row row);
    @Insert("INSERT INTO mom_qms.qms_inspection_piece(id,tenant_id,factory_code,task_id,identifier,actor_id,occurred_at,basis_fingerprint) VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{identifier},#{actorId},#{occurredAt},#{basisFingerprint})")
    int insertPiece(Row row);
    @Insert("INSERT INTO mom_qms.qms_inspection_piece_sample(id,tenant_id,factory_code,task_id,piece_id,submission_id,detail_id,record_id,value_text,qualified) VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{pieceId},#{submissionId},#{detailId},#{recordId},#{valueText},#{qualified})")
    int insertSample(Row row);
    @Select("SELECT COUNT(*) FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND tenant_id=#{tenant} AND factory_code=#{factory}")
    long count(@Param("task") Long task,@Param("tenant") Long tenant,@Param("factory") String factory);
    @Select("SELECT COUNT(*) FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND tenant_id=#{tenant} AND factory_code=#{factory} AND identifier=#{identifier}")
    long identifierCount(@Param("task") Long task,@Param("tenant") Long tenant,@Param("factory") String factory,@Param("identifier") String identifier);
    @Select("SELECT COUNT(*) FROM mom_qms.qms_inspection_procedure_item_result_record r JOIN mom_qms.qms_inspection_procedure_task_detail d ON d.id=r.detail_id AND d.task_id=r.task_id WHERE r.task_id=#{task} AND d.tenant_id=#{tenant} AND d.factory_code=#{factory} AND NOT EXISTS(SELECT 1 FROM mom_qms.qms_inspection_piece_sample s WHERE s.record_id=r.id AND s.task_id=r.task_id AND s.tenant_id=#{tenant} AND s.factory_code=#{factory})")
    long anonymousCount(@Param("task") Long task,@Param("tenant") Long tenant,@Param("factory") String factory);
    @Select("SELECT * FROM mom_qms.qms_inspection_piece WHERE task_id=#{task} AND tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY id DESC LIMIT 20 OFFSET #{offset}")
    List<Row> pieces(@Param("task") Long task,@Param("tenant") Long tenant,@Param("factory") String factory,@Param("offset") int offset);
    @Select("SELECT * FROM mom_qms.qms_inspection_piece_sample WHERE task_id=#{task} AND piece_id=#{piece} AND tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY detail_id LIMIT 501")
    List<Row> samples(@Param("task") Long task,@Param("piece") Long piece,@Param("tenant") Long tenant,@Param("factory") String factory);
}
