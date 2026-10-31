package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QmsInspectionRevisionRunMapper extends BaseMapper<QmsInspectionRevisionRunEntity> {
    @Select("SELECT mom_qms.mom_inspection_revision_run_ready_v1()")
    Integer ready();

    @Select(
            "<script>SELECT * FROM mom_qms.qms_inspection_revision_run WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}<if test='lock'> FOR"
                    + " UPDATE</if></script>")
    QmsInspectionRevisionRunEntity run(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("lock") boolean lock);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_outcome WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionRevisionOutcomeEntity outcome(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_run_receipt WHERE request_id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY id LIMIT 4")
    List<QmsInspectionRevisionRunReceiptEntity> receipts(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_revision_outcome(id,tenant_id,factory_code,task_id,decision,payload,fingerprint,created_at)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{decision},#{payload},#{fingerprint},#{createdAt})")
    int recordOutcome(QmsInspectionRevisionOutcomeEntity e);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_revision_run_receipt(id,request_id,tenant_id,factory_code,task_id,operation,payload,fingerprint,received_at)"
                + " VALUES(#{id},#{requestId},#{tenantId},#{factoryCode},#{taskId},#{operation},#{payload},#{fingerprint},#{receivedAt})")
    int receipt(QmsInspectionRevisionRunReceiptEntity e);

    @Update(
            "UPDATE mom_qms.qms_inspection_revision_run SET"
                + " phase=#{phase},status=#{status},attempt_count=#{attemptCount},next_attempt_at=#{nextAttemptAt},updated_at=#{updatedAt},last_error=#{lastError},version=#{version}+1"
                + " WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND"
                + " task_id=#{taskId} AND version=#{version}")
    int progress(QmsInspectionRevisionRunEntity e);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,task_id FROM mom_qms.qms_inspection_revision_run"
                + " WHERE status IN('READY','RETRY_WAIT') AND next_attempt_at<=CURRENT_TIMESTAMP"
                + " ORDER BY next_attempt_at,id LIMIT 50")
    List<QmsInspectionRevisionRunEntity> due();
}
