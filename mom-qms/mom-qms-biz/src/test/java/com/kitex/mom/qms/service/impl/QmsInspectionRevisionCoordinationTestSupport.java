package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionCoordinationMapper;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class QmsInspectionRevisionCoordinationTestSupport {
    static void install(SqlSessionTemplate session, JdbcTemplate db) {
        for (var type :
                List.of(
                        QmsInspectionRevisionExecutionEntity.class,
                        QmsInspectionRevisionDeliveryEntity.class,
                        QmsInspectionRevisionCancelEntity.class,
                        QmsInspectionRevisionReceiptEntity.class))
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(session.getConfiguration(), "revision-coordinate"),
                    type);
        session.getConfiguration().addMapper(QmsInspectionRevisionCoordinationMapper.class);
        db.execute(
                "CREATE ALIAS IF NOT EXISTS SHA256 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationTestSupport.sha'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS ENCODE FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationTestSupport.encode'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS CONVERT_TO FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationTestSupport.convert'");
        for (var type :
                List.of(
                        QmsInspectionRevisionRunEntity.class,
                        QmsInspectionRevisionOutcomeEntity.class,
                        QmsInspectionRevisionRunReceiptEntity.class))
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(session.getConfiguration(), "formal-revision"),
                    type);
        session.getConfiguration()
                .addMapper(com.kitex.mom.qms.mapper.QmsInspectionRevisionRunMapper.class);
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_revision_run_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationTestSupport.ready'");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_run(id bigint PRIMARY KEY,tenant_id"
                    + " bigint,factory_code varchar,task_id bigint,created_by bigint,created_at"
                    + " timestamp,phase varchar,status varchar,attempt_count int,next_attempt_at"
                    + " timestamp,updated_at timestamp,last_error varchar,version bigint)");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_outcome(id bigint PRIMARY"
                        + " KEY,tenant_id bigint,factory_code varchar,task_id bigint,decision"
                        + " varchar,payload text,fingerprint varchar,created_at timestamp)");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_run_receipt(id bigint PRIMARY"
                        + " KEY,request_id bigint,tenant_id bigint,factory_code varchar,task_id"
                        + " bigint,operation varchar,payload text,fingerprint varchar,received_at"
                        + " timestamp,UNIQUE(request_id,operation))");
        db.execute("CREATE TABLE qms_coordination_installation(version int)");
        db.update("INSERT INTO qms_coordination_installation VALUES(1)");
        db.execute(
                "CREATE ALIAS mom_qms.mom_inspection_revision_coordination_ready_v1 FOR"
                    + " 'com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationTestSupport.ready'");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_execution(id bigint PRIMARY"
                    + " KEY,tenant_id bigint,factory_code varchar,task_id bigint,intent"
                    + " text,intent_fingerprint varchar,executor_id bigint,created_at timestamp)");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_delivery(id bigint PRIMARY"
                        + " KEY,tenant_id bigint,factory_code varchar,task_id bigint,operation"
                        + " varchar,status varchar,attempt_count int,next_attempt_at"
                        + " timestamp,updated_at timestamp,last_error varchar,version bigint)");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_cancel(id bigint PRIMARY"
                        + " KEY,tenant_id bigint,factory_code varchar,task_id bigint,canceled_by"
                        + " bigint,canceled_at timestamp,reason varchar)");
        db.execute(
                "CREATE TABLE mom_qms.qms_inspection_revision_receipt(id bigint PRIMARY"
                        + " KEY,request_id bigint,tenant_id bigint,factory_code varchar,task_id"
                        + " bigint,operation varchar,payload text,fingerprint varchar,recorded_at"
                        + " timestamp,UNIQUE(request_id,operation))");
    }

    public static byte[] sha(byte[] v) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(v);
    }

    public static String encode(byte[] v, String encoding) {
        return java.util.HexFormat.of().formatHex(v);
    }

    public static byte[] convert(String v, String encoding) {
        return v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Integer ready(java.sql.Connection c) throws Exception {
        try (var s = c.createStatement();
                var rs = s.executeQuery("SELECT version FROM qms_coordination_installation")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }
}
