package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesWipTransferEntity;

import lombok.Data;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipDeliverySimulationMapper {
    @Data
    class TaskRow {
        private Long tenantId, actorId, createdAt;
        private String factoryCode, taskId, transferId, snapshotJson, snapshotHash, reason;
    }

    @Data
    class EventRow {
        private Long tenantId, actorId, createdAt;
        private Integer sequenceNo;
        private String factoryCode,
                taskId,
                requestId,
                kind,
                commandJson,
                fingerprint,
                beforeJson,
                afterJson;
    }

    @Data
    public class CancelRow {
        private Long tenantId, actorId, canceledAt;
        private String factoryCode, requestId, commandJson, fingerprint, reason;
    }

    @Select(
            "SELECT"
                + " tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,reason,canceled_at"
                + " FROM mom_mes.mes_wip_delivery_sim_cancel WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND request_id=#{request} LIMIT 2")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<CancelRow> cancellations(
            @Param("tenant") Long t, @Param("factory") String f, @Param("request") String request);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_delivery_sim_cancel(tenant_id,factory_code,request_id,actor_id,command_json,fingerprint,reason,canceled_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{requestId},#{actorId},#{commandJson},#{fingerprint},#{reason},#{canceledAt})")
    int insertCancel(CancelRow row);

    @Select(
            "SELECT (CASE WHEN to_regclass('mom_mes.mes_wip_delivery_sim_task') IS NULL THEN 0 ELSE"
                + " 1 END)+(CASE WHEN to_regclass('mom_mes.mes_wip_delivery_sim_event') IS NULL"
                + " THEN 0 ELSE 1 END)+(CASE WHEN"
                + " to_regclass('mom_mes.mes_wip_delivery_sim_cancel') IS NULL THEN 0 ELSE 1 END)")
    int installed();

    @Select("SELECT mom_mes.mom_wip_delivery_sim_ready_v1()")
    Integer ready();

    @Select("SELECT current_setting('transaction_isolation')")
    String isolation();

    String TRANSFER =
            "SELECT"
                + " id,tenant_id,factory_code,source_stock_id,source_report_id,arrange_code,produce_task_code,mat_code,mat_name,mat_batch,mat_barcode,mat_unit,from_location_code,to_location_code,quantity,remaining_quantity,received_quantity,returned_quantity,quarantined_quantity,lost_quantity,status,revision,del_flag,dispatch_user_id,due_time,source_snapshot,location_snapshot"
                + " FROM mom_mes.mes_wip_transfer WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0'";

    @Select(TRANSFER)
    MesWipTransferEntity transfer(
            @Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    @Select(TRANSFER + " FOR UPDATE")
    MesWipTransferEntity lockTransfer(
            @Param("tenant") Long t, @Param("factory") String f, @Param("id") Long id);

    String TASK =
            "SELECT"
                + " tenant_id,factory_code,task_id,actor_id,transfer_id,snapshot_json,snapshot_hash,reason,created_at"
                + " FROM mom_mes.mes_wip_delivery_sim_task WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND actor_id=#{actor} AND task_id=#{task}";

    @Select(TASK)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    TaskRow task(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("actor") Long a,
            @Param("task") String task);

    @Select(TASK + " FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    TaskRow lockTask(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("actor") Long a,
            @Param("task") String task);

    @Select(
            "SELECT"
                + " tenant_id,factory_code,task_id,actor_id,transfer_id,snapshot_json,snapshot_hash,reason,created_at"
                + " FROM mom_mes.mes_wip_delivery_sim_task WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND actor_id=#{actor} AND transfer_id=#{transfer} ORDER"
                + " BY created_at DESC,task_id DESC LIMIT 20")
    List<TaskRow> tasks(
            @Param("tenant") Long t,
            @Param("factory") String f,
            @Param("actor") Long a,
            @Param("transfer") String transfer);

    String EVENTS =
            "SELECT"
                + " tenant_id,factory_code,task_id,request_id,sequence_no,actor_id,kind,command_json,fingerprint,before_json,after_json,created_at"
                + " FROM mom_mes.mes_wip_delivery_sim_event WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory}";

    @Select(EVENTS + " AND task_id=#{task} ORDER BY sequence_no LIMIT 101")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<EventRow> events(
            @Param("tenant") Long t, @Param("factory") String f, @Param("task") String task);

    @Select(EVENTS + " AND request_id=#{request} LIMIT 2")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<EventRow> request(
            @Param("tenant") Long t, @Param("factory") String f, @Param("request") String request);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_delivery_sim_task(tenant_id,factory_code,task_id,actor_id,transfer_id,snapshot_json,snapshot_hash,reason,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{taskId},#{actorId},#{transferId},#{snapshotJson},#{snapshotHash},#{reason},#{createdAt})")
    int insertTask(TaskRow row);

    @Insert(
            "INSERT INTO"
                + " mom_mes.mes_wip_delivery_sim_event(tenant_id,factory_code,task_id,request_id,sequence_no,actor_id,kind,command_json,fingerprint,before_json,after_json,created_at)"
                + " VALUES(#{tenantId},#{factoryCode},#{taskId},#{requestId},#{sequenceNo},#{actorId},#{kind},#{commandJson},#{fingerprint},#{beforeJson},#{afterJson},#{createdAt})")
    int insertEvent(EventRow row);
}
