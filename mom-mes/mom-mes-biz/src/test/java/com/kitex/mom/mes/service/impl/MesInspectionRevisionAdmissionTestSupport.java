package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.List;

/** 向原真实应用夹具安装准入表；不把H2测试当PG触发器验收。 */
public class MesInspectionRevisionAdmissionTestSupport {
    static void install(SqlSessionTemplate session, JdbcTemplate db) {
        var c = session.getConfiguration();
        for (var e :
                List.of(
                        MesInspectionRevisionAdmissionEntity.class,
                        MesInspectionRevisionCancelEntity.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(c, "revision-admission"), e);
        c.addMapper(MesInspectionRevisionAdmissionMapper.class);
        c.addMapper(MesInspectionRevisionCancelMapper.class);
        db.execute(
                "CREATE ALIAS mom_mes.mom_workpiece_time_ready FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.timingReady'");
        for (var t :
                List.of(
                        com.kitex.mom.mes.entity.MesInspectionRevisionSealEntity.class,
                        com.kitex.mom.mes.entity.MesInspectionRevisionResolutionEntity.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(c, "revision-execution"), t);
        c.addMapper(com.kitex.mom.mes.mapper.MesInspectionRevisionExecutionMapper.class);
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_revision_execution_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.ready'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS SHA256 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.sha'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS ENCODE FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.encode'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS CONVERT_TO FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.convert'");
        db.execute(
                "CREATE TABLE mom_mes.mes_inspection_revision_seal(id bigint PRIMARY KEY,tenant_id"
                        + " bigint,factory_code varchar,task_code varchar,produce_task_code"
                        + " varchar,inspection_id bigint,gen_record_id bigint,intent_fingerprint"
                        + " varchar,admission_fingerprint varchar,payload text,fingerprint"
                        + " varchar,sealed_at timestamp)");
        db.execute(
                "CREATE TABLE mom_mes.mes_inspection_revision_resolution(id bigint PRIMARY"
                    + " KEY,tenant_id bigint,factory_code varchar,task_code"
                    + " varchar,produce_task_code varchar,inspection_id bigint,gen_record_id"
                    + " bigint,operation varchar,intent_fingerprint varchar,seal_fingerprint"
                    + " varchar,event_id bigint,event_fingerprint varchar,payload text,fingerprint"
                    + " varchar,receipt text,receipt_fingerprint varchar,resolved_at timestamp)");
        db.execute("CREATE TABLE revision_admission_installation(version int)");
        db.update("INSERT INTO revision_admission_installation VALUES(1)");
        db.execute(
                "CREATE ALIAS mom_mes.mom_inspection_revision_admission_ready_v1 FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.ready'");
        db.execute(
                "CREATE ALIAS IF NOT EXISTS CURRENT_SETTING FOR"
                    + " 'com.kitex.mom.mes.service.impl.MesInspectionRevisionAdmissionTestSupport.setting'");
        db.execute(
                "CREATE TABLE mom_mes.mes_inspection_revision_admission(id bigint PRIMARY"
                        + " KEY,tenant_id bigint,factory_code varchar,task_code"
                        + " varchar,produce_task_code varchar,gen_record_id bigint,inspection_id"
                        + " bigint,base_event_id bigint,base_sequence bigint,base_fingerprint"
                        + " varchar,base_state_version bigint,intent text,intent_fingerprint"
                        + " varchar,basis_snapshot text,basis_fingerprint varchar,reserved_at"
                        + " timestamp)");
        db.execute(
                "CREATE TABLE mom_mes.mes_inspection_revision_cancel(id bigint PRIMARY"
                        + " KEY,tenant_id bigint,factory_code varchar,task_code"
                        + " varchar,produce_task_code varchar,inspection_id bigint,intent"
                        + " text,intent_fingerprint varchar,canceled_by bigint,canceled_at"
                        + " timestamp,reason varchar)");
        db.execute(
                "CREATE TABLE mom_mes.mes_workpiece_time_session(id bigint PRIMARY KEY,tenant_id"
                    + " bigint,factory_code varchar,task_code varchar,arrange_id"
                    + " bigint,produce_task_code varchar,status varchar,del_flag varchar DEFAULT"
                    + " '0')");
        db.execute(
                "CREATE TABLE mom_mes.mes_workpiece_time_interval(id bigint PRIMARY KEY,tenant_id"
                        + " bigint,factory_code varchar,task_code varchar,arrange_id"
                        + " bigint,produce_task_code varchar,status varchar,kind varchar,start_time"
                        + " timestamp,end_time timestamp,del_flag varchar DEFAULT '0')");
    }

    public static Boolean timingReady() {
        return true;
    }

    public static byte[] sha(byte[] v) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(v);
    }

    public static String encode(byte[] v, String e) {
        return java.util.HexFormat.of().formatHex(v);
    }

    public static byte[] convert(String v, String e) {
        return v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Integer ready(Connection c) throws Exception {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT version FROM revision_admission_installation")) {
            return r.next() ? r.getInt(1) : null;
        }
    }

    public static String setting(Connection c, String name) throws Exception {
        return c.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED
                ? "read committed"
                : "repeatable read";
    }
}
