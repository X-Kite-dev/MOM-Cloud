package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPlanCompletionOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MesPlanCompletionOutboxMapper extends BaseMapper<MesPlanCompletionOutboxEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_plan_completion_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_id=#{taskId} AND completion_version=#{version}"
                    + " ORDER BY id LIMIT 2")
    List<MesPlanCompletionOutboxEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId,
            @Param("version") Integer version);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_plan_completion_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id}")
    MesPlanCompletionOutboxEntity scoped(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    /**
     * 后台调度入口，按实际行租户重新进入；不是面向用户的跨租户查询。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code FROM mom_mes.mes_plan_completion_outbox WHERE status"
                    + " IN('PENDING','RETRY_WAIT') AND next_attempt_time<=CURRENT_TIMESTAMP ORDER BY"
                    + " next_attempt_time,id LIMIT #{limit}")
    List<MesPlanCompletionOutboxEntity> due(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_mes.mes_plan_completion_outbox WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} FOR UPDATE")
    MesPlanCompletionOutboxEntity locked(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            "UPDATE mom_mes.mes_plan_completion_outbox SET"
                    + " status='DELIVERED',attempt_count=#{count},delivered_time=#{now},next_attempt_time=NULL,last_error=NULL,plan_receipt_ids_json=#{receipts}"
                    + " WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND status"
                    + " IN('PENDING','RETRY_WAIT')")
    int delivered(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("count") int count,
            @Param("now") LocalDateTime now,
            @Param("receipts") String receipts);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            "UPDATE mom_mes.mes_plan_completion_outbox SET"
                    + " status='RETRY_WAIT',attempt_count=#{count},next_attempt_time=#{due},last_error=#{error}"
                    + " WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND status"
                    + " IN('PENDING','RETRY_WAIT')")
    int retry(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("count") int count,
            @Param("due") LocalDateTime due,
            @Param("error") String error);
}
