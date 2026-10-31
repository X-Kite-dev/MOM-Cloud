package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsInspectionRevisionEntity;

import org.apache.ibatis.annotations.*;

@Mapper
public interface QmsInspectionRevisionMapper extends BaseMapper<QmsInspectionRevisionEntity> {
    String CONFIRMED_CANCELLATION =
            "SELECT 1 FROM mom_qms.qms_inspection_revision_receipt z JOIN"
                + " mom_qms.qms_inspection_revision_execution x ON x.id=z.request_id AND"
                + " x.tenant_id=z.tenant_id AND x.factory_code=z.factory_code AND"
                + " x.task_id=z.task_id JOIN mom_qms.qms_inspection_revision_cancel c ON c.id=x.id"
                + " AND c.tenant_id=x.tenant_id AND c.factory_code=x.factory_code AND"
                + " c.task_id=x.task_id JOIN mom_qms.qms_inspection_revision_delivery u ON"
                + " u.id=x.id AND u.tenant_id=x.tenant_id AND u.factory_code=x.factory_code AND"
                + " u.task_id=x.task_id AND u.operation='CANCEL' AND u.status='CANCELED' WHERE"
                + " z.request_id=r.id AND z.tenant_id=r.tenant_id AND z.factory_code=r.factory_code"
                + " AND z.task_id=r.task_id AND z.operation='CANCEL' AND"
                + " z.fingerprint=encode(sha256(convert_to(z.payload,'UTF8')),'hex') AND"
                + " x.intent_fingerprint=encode(sha256(convert_to(x.intent,'UTF8')),'hex')";

    String CONFIRMED_EXECUTION =
            "SELECT 1 FROM mom_qms.qms_inspection_revision_run u JOIN"
                + " mom_qms.qms_inspection_revision_outcome o ON o.id=u.id AND"
                + " o.tenant_id=u.tenant_id AND o.factory_code=u.factory_code AND"
                + " o.task_id=u.task_id JOIN mom_qms.qms_inspection_revision_run_receipt z ON"
                + " z.request_id=u.id AND z.tenant_id=u.tenant_id AND z.factory_code=u.factory_code"
                + " AND z.task_id=u.task_id AND z.operation='APPLY' WHERE u.id=r.id AND"
                + " u.tenant_id=r.tenant_id AND u.factory_code=r.factory_code AND"
                + " u.task_id=r.task_id AND u.phase='DONE' AND u.status='SUCCEEDED' AND"
                + " o.decision='COMMITTED' AND"
                + " o.fingerprint=encode(sha256(convert_to(o.payload,'UTF8')),'hex') AND"
                + " z.fingerprint=encode(sha256(convert_to(z.payload,'UTF8')),'hex')";

    @Select(
            "SELECT CASE WHEN mom_qms.mom_inspection_revision_ready_v1()=1 AND"
                    + " mom_qms.mom_inspection_revision_coordination_ready_v1()=1 AND"
                    + " mom_qms.mom_inspection_revision_run_ready_v1()=1 THEN 1 ELSE 0 END")
    Integer ready();

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_inspection_revision r WHERE r.tenant_id=#{tenant} AND"
                + " r.factory_code=#{factory} AND r.task_id=#{task} AND NOT EXISTS(SELECT 1 FROM"
                + " mom_qms.qms_inspection_revision_review d WHERE d.id=r.id AND"
                + " d.tenant_id=r.tenant_id AND d.factory_code=r.factory_code AND"
                + " d.task_id=r.task_id AND d.decision='REJECTED') AND NOT EXISTS("
                    + CONFIRMED_CANCELLATION
                    + ") AND NOT EXISTS("
                    + CONFIRMED_EXECUTION
                    + ")")
    long unresolved(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task);

    @Select(
            "SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM mom_qms.qms_inspection_revision_receipt z"
                + " WHERE z.request_id=r.id AND z.tenant_id=r.tenant_id AND"
                + " z.factory_code=r.factory_code AND z.task_id=r.task_id AND z.operation='CANCEL')"
                + " THEN 0 WHEN EXISTS("
                    + CONFIRMED_CANCELLATION
                    + ") THEN 1 ELSE -1 END FROM mom_qms.qms_inspection_revision r WHERE r.id=#{id}"
                    + " AND r.tenant_id=#{tenant} AND r.factory_code=#{factory} AND"
                    + " r.task_id=#{task}")
    Integer cancellationState(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task);

    @Select(
            "SELECT CASE WHEN u.phase='DONE' THEN CASE WHEN EXISTS("
                    + CONFIRMED_EXECUTION
                    + ") THEN 'EXECUTED' ELSE 'CORRUPT' END WHEN u.phase='STOPPED' THEN CASE WHEN"
                    + " EXISTS("
                    + CONFIRMED_CANCELLATION
                    + ") THEN 'ABORTED_EXECUTION' ELSE 'CORRUPT' END WHEN u.status='ATTENTION' THEN"
                    + " 'EXECUTION_ATTENTION' ELSE 'EXECUTING' END FROM"
                    + " mom_qms.qms_inspection_revision_run u JOIN mom_qms.qms_inspection_revision"
                    + " r ON r.id=u.id AND r.tenant_id=u.tenant_id AND"
                    + " r.factory_code=u.factory_code AND r.task_id=u.task_id WHERE r.id=#{id} AND"
                    + " r.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.task_id=#{task}")
    String formalState(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("task") Long task);
}
