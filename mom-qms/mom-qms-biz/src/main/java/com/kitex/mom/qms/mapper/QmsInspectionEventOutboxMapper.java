package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface QmsInspectionEventOutboxMapper extends BaseMapper<QmsInspectionEventOutboxEntity> {
    @Select("SELECT mom_qms.mom_inspection_event_outbox_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event WHERE id=#{id} AND tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND inspection_type='PROCEDURE'")
    QmsInspectionEventEntity original(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "<script>SELECT id FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' <if"
                    + " test='lock'>FOR UPDATE</if></script>")
    Long sourceTask(
            @Param("id") Long id,
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("lock") boolean lock);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event_outbox WHERE task_id=#{taskId} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} ORDER BY sequence_no LIMIT"
                    + " 5001")
    List<QmsInspectionEventOutboxEntity> stream(
            @Param("taskId") Long taskId,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event_outbox WHERE task_id=#{taskId} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND sequence_no=1 FOR UPDATE")
    QmsInspectionEventOutboxEntity anchor(
            @Param("taskId") Long taskId,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    /** 人工入口已持任务锁，不得等待正在发送的队列锚，防止与MES撤回形成锁环。 */
    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event_outbox WHERE task_id=#{taskId} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND sequence_no=1 FOR UPDATE"
                + " NOWAIT")
    QmsInspectionEventOutboxEntity anchorForRetry(
            @Param("taskId") Long taskId,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event_outbox WHERE task_id=#{taskId} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND status<>'RECEIVED' ORDER BY"
                + " sequence_no LIMIT 1 FOR UPDATE")
    QmsInspectionEventOutboxEntity head(
            @Param("taskId") Long taskId,
            @Param("tenant") Long tenant,
            @Param("factory") String factory);

    // 只读后台候选，不返回原文；实际发送恢复单租户工厂，禁止skip。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,task_id FROM mom_qms.qms_inspection_event_outbox q"
                    + " WHERE status IN('PENDING','RETRY_WAIT') AND"
                    + " next_attempt_time<=CURRENT_TIMESTAMP AND NOT EXISTS(SELECT 1 FROM"
                    + " mom_qms.qms_inspection_event_outbox p WHERE p.tenant_id=q.tenant_id AND"
                    + " p.factory_code=q.factory_code AND p.task_id=q.task_id AND"
                    + " p.sequence_no<q.sequence_no AND p.status<>'RECEIVED') ORDER BY"
                    + " next_attempt_time,id LIMIT 50")
    List<QmsInspectionEventOutboxEntity> due();
}
