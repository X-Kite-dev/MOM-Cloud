package com.kitex.mom.qms.mapper;

import com.kitex.mom.qms.entity.QmsReworkOrderEntity;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
public interface QmsInspectionPieceReinspectionMapper {
    @Data
    class Row {
        private Long id,
                tenantId,
                taskId,
                currentPieceId,
                sourceTaskId,
                sourcePieceId,
                coordinationId,
                boundBy,
                boundAt;
        private String factoryCode, payload, payloadFingerprint;
    }

    @Select(
            "SELECT CASE WHEN to_regclass('mom_qms.qms_inspection_piece_reinspection') IS NULL THEN"
                    + " 0 ELSE 1 END")
    int installed();

    @Select("SELECT mom_qms.mom_inspection_piece_reinspection_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece_reinspection WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND current_piece_id=#{piece} LIMIT 2")
    List<Row> byCurrent(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("piece") Long piece);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_piece_reinspection WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND source_piece_id=#{piece} LIMIT 2")
    List<Row> bySource(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("piece") Long piece);

    @Select(
            "SELECT * FROM mom_qms.qms_rework_order WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND inspection_type='PROCEDURE' AND"
                    + " reinspection_task_id=#{task} ORDER BY id LIMIT 2")
    List<QmsReworkOrderEntity> incoming(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_piece_reinspection(id,tenant_id,factory_code,task_id,current_piece_id,source_task_id,source_piece_id,coordination_id,bound_by,bound_at,payload,payload_fingerprint)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{currentPieceId},#{sourceTaskId},#{sourcePieceId},#{coordinationId},#{boundBy},#{boundAt},#{payload},#{payloadFingerprint})")
    int insert(Row row);

    @Select(
            "SELECT id FROM mom_qms.qms_inspection_piece WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id IN(#{current},#{source}) ORDER BY id FOR UPDATE")
    List<Long> lockPieces(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("current") Long current,
            @Param("source") Long source);
}
