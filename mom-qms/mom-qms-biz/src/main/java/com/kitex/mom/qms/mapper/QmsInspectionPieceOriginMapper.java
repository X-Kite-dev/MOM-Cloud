package com.kitex.mom.qms.mapper;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface QmsInspectionPieceOriginMapper {
    @Data
    class Row {
        private Long pieceId, taskId, tenantId, submissionId, createdBy, capturedAt;
        private String factoryCode, payload, fingerprint;
    }

    @Select(
            "SELECT CASE WHEN to_regclass('mom_qms.qms_inspection_piece_origin') IS NULL THEN 0"
                    + " ELSE 1 END")
    int installed();

    @Select("SELECT mom_qms.mom_inspection_piece_origin_ready_v1()")
    Integer ready();

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece_origin WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_id=#{task} AND piece_id=#{piece}")
    Row find(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task,
            @Param("piece") Long piece);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece_submission WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND task_id=#{task} AND piece_id=#{piece} ORDER BY id"
                + " LIMIT 2")
    List<QmsInspectionPieceMapper.Row> submissions(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task,
            @Param("piece") Long piece);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_piece_origin(piece_id,task_id,tenant_id,factory_code,submission_id,created_by,captured_at,payload,fingerprint)"
                + " VALUES(#{pieceId},#{taskId},#{tenantId},#{factoryCode},#{submissionId},#{createdBy},#{capturedAt},#{payload},#{fingerprint})")
    int insert(Row row);
}
