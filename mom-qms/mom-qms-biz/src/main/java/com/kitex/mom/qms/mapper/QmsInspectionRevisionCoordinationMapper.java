package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QmsInspectionRevisionCoordinationMapper
        extends BaseMapper<QmsInspectionRevisionExecutionEntity> {
    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision WHERE id=#{id} AND tenant_id=#{tenant}"
                    + " AND factory_code=#{factory}")
    QmsInspectionRevisionEntity request(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_review WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionRevisionReviewEntity review(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select("SELECT mom_qms.mom_inspection_revision_coordination_ready_v1()")
    Integer ready();

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_execution WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionRevisionExecutionEntity execution(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "<script>SELECT * FROM mom_qms.qms_inspection_revision_delivery WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}<if test='lock'> FOR"
                    + " UPDATE</if></script>")
    QmsInspectionRevisionDeliveryEntity delivery(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("lock") boolean lock);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_cancel WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionRevisionCancelEntity cancel(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_revision_receipt WHERE request_id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY id LIMIT 3")
    List<QmsInspectionRevisionReceiptEntity> receipts(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_revision_delivery(id,tenant_id,factory_code,task_id,operation,status,attempt_count,next_attempt_at,updated_at,last_error,version)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{operation},#{status},#{attemptCount},#{nextAttemptAt},#{updatedAt},#{lastError},#{version})")
    int insertDelivery(QmsInspectionRevisionDeliveryEntity row);

    @Update(
            "UPDATE mom_qms.qms_inspection_revision_delivery SET"
                + " operation=#{operation},status=#{status},attempt_count=#{attemptCount},next_attempt_at=#{nextAttemptAt},updated_at=#{updatedAt},last_error=#{lastError},version=#{version}+1"
                + " WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND"
                + " task_id=#{taskId} AND version=#{version}")
    int progress(QmsInspectionRevisionDeliveryEntity row);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_revision_cancel(id,tenant_id,factory_code,task_id,canceled_by,canceled_at,reason)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{canceledBy},#{canceledAt},#{reason})")
    int insertCancel(QmsInspectionRevisionCancelEntity row);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_revision_receipt(id,request_id,tenant_id,factory_code,task_id,operation,payload,fingerprint,recorded_at)"
                + " VALUES(#{id},#{requestId},#{tenantId},#{factoryCode},#{taskId},#{operation},#{payload},#{fingerprint},#{recordedAt})")
    int insertReceipt(QmsInspectionRevisionReceiptEntity row);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,task_id FROM mom_qms.qms_inspection_revision_delivery"
                    + " WHERE NOT EXISTS(SELECT 1 FROM mom_qms.qms_inspection_revision_run r WHERE"
                    + " r.id=qms_inspection_revision_delivery.id AND"
                    + " r.tenant_id=qms_inspection_revision_delivery.tenant_id AND"
                    + " r.factory_code=qms_inspection_revision_delivery.factory_code AND"
                    + " r.phase<>'PREPARE') AND status IN('PENDING','RETRY_WAIT') AND"
                    + " next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT 50")
    List<QmsInspectionRevisionDeliveryEntity> due();

    @Select(
            "SELECT phase FROM mom_qms.qms_inspection_revision_run WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    String runPhase(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);
}
