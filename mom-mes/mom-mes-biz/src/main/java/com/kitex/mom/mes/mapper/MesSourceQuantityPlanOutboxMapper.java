package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesSourceQuantityPlanOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface MesSourceQuantityPlanOutboxMapper
        extends BaseMapper<MesSourceQuantityPlanOutboxEntity> {
    @Select("SELECT mom_mes.mom_source_quantity_plan_outbox_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_mes.mes_source_quantity_plan_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request} LIMIT 2")
    List<MesSourceQuantityPlanOutboxEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Select(
            "SELECT * FROM mom_mes.mes_source_quantity_plan_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} FOR UPDATE")
    MesSourceQuantityPlanOutboxEntity locked(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code FROM mom_mes.mes_source_quantity_plan_outbox WHERE"
                    + " status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=#{now} ORDER BY"
                    + " next_attempt_at,id LIMIT 50")
    List<MesSourceQuantityPlanOutboxEntity> due(@Param("now") Date now);

    @Update(
            "UPDATE mom_mes.mes_source_quantity_plan_outbox SET"
                    + " status=#{row.status},attempt_count=#{row.attemptCount},next_attempt_at=#{row.nextAttemptAt},received_at=#{row.receivedAt},last_error=#{row.lastError}"
                    + " WHERE id=#{row.id} AND tenant_id=#{row.tenantId} AND"
                    + " factory_code=#{row.factoryCode} AND attempt_count=#{previous} AND status IN"
                    + " ('PENDING','RETRY_WAIT')")
    int progress(
            @Param("row") MesSourceQuantityPlanOutboxEntity row, @Param("previous") long previous);

    @Select(
            "SELECT"
                    + " id,tenant_id,factory_code,request_id,task_id,task_code,status,attempt_count,next_attempt_at,created_at,received_at,last_error"
                    + " FROM mom_mes.mes_source_quantity_plan_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_id=#{taskId} AND id>#{after} ORDER BY id LIMIT"
                    + " #{limit}")
    List<MesSourceQuantityPlanOutboxEntity> page(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId,
            @Param("after") Long after,
            @Param("limit") int limit);
}
