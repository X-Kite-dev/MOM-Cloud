package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 实际组织的永久反向原件与投递账；完成原文不参与状态更新。
 */
@Mapper
public interface MesPlanCompletionReopenOutboxMapper
        extends MomBaseMapper<MesPlanCompletionReopenOutboxEntity> {
    String COLUMNS =
            "id,tenant_id,factory_code,event_id,reverse_request_id,task_id,task_code,plan_no,aps_batch_no,product_code,complete_quantity_before,complete_quantity_after,completion_version_before,completion_version_after,reopened_time,payload_json,payload_fingerprint,status,attempt_count,next_attempt_time,delivered_time,plan_inbox_id,plan_event_no,plan_event_status,last_error,retry_request_id,del_flag,create_by,create_time,update_by,update_time";

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM mom_mes.mes_plan_completion_reopen_outbox WHERE tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND id=#{id}")
    MesPlanCompletionReopenOutboxEntity scoped(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM mom_mes.mes_plan_completion_reopen_outbox WHERE tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND id=#{id} FOR UPDATE")
    MesPlanCompletionReopenOutboxEntity locked(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM mom_mes.mes_plan_completion_reopen_outbox WHERE tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND reverse_request_id=#{request} AND"
                    + " task_id=#{task} ORDER BY id LIMIT 2")
    List<MesPlanCompletionReopenOutboxEntity> originals(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") Long request,
            @Param("task") Long task);

    /**
     * 仅后台扫描，随后按每行实际租户/工厂进入独立事务。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code FROM mom_mes.mes_plan_completion_reopen_outbox WHERE"
                    + " del_flag='0' AND status IN('PENDING','RETRY_WAIT') AND"
                    + " next_attempt_time<=CURRENT_TIMESTAMP ORDER BY next_attempt_time,id LIMIT"
                    + " #{limit}")
    List<MesPlanCompletionReopenOutboxEntity> due(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,task_code,status,del_flag FROM"
                    + " mom_mes.mes_report_reverse_request WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id}")
    MesReportReverseRequestEntity request(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            "UPDATE mom_mes.mes_plan_completion_reopen_outbox SET"
                    + " status=#{status},attempt_count=#{count},next_attempt_time=#{next},delivered_time=#{delivered},plan_inbox_id=#{inbox},plan_event_no=#{number},plan_event_status=#{remoteStatus},last_error=#{error},retry_request_id=#{retry}"
                    + " WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND"
                    + " status=#{before}")
    int state(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("before") String before,
            @Param("status") String status,
            @Param("count") int count,
            @Param("next") LocalDateTime next,
            @Param("delivered") LocalDateTime delivered,
            @Param("inbox") Long inbox,
            @Param("number") String number,
            @Param("remoteStatus") String remoteStatus,
            @Param("error") String error,
            @Param("retry") String retry);
}
