package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnCompletionReopenReceiptEntity;
import com.kitex.mom.plan.entity.PlnIssueCallbackReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 原号永久回执及实际组织范围内的局部重新打开更新。
 */
@Mapper
public interface PlnCompletionReopenReceiptMapper
        extends MomBaseMapper<PlnCompletionReopenReceiptEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT"
                    + " id,tenant_id,factory_code,source_schema_version,inbox_id,mes_outbox_id,reverse_request_id,mes_task_id,mes_task_code,completion_version,issue_id,issue_no,status_before,status_after,finish_date_before,allocated_quantity,completed_quantity,apply_result,source_fingerprint,applied_time"
                    + " FROM mom_plan.pln_completion_reopen_receipt WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND mes_outbox_id=#{outbox} AND issue_id=#{issue} ORDER"
                    + " BY id LIMIT 2")
    List<PlnCompletionReopenReceiptEntity> original(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("outbox") Long outbox,
            @Param("issue") Long issue);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            "UPDATE mom_plan.pln_issue SET status='2',finish_time=NULL WHERE tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND id=#{id} AND status=#{before} AND status"
                    + " IN('2','3') AND del_flag='0'")
    int reopen(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("before") String before);

    /**
     * 原租户级回执是永久事实，不能按当前人员可见子集判断较新完成世代。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,request_id,issue_id,issue_no FROM"
                    + " mom_plan.pln_issue_callback_receipt WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request} ORDER BY id LIMIT 2")
    List<PlnIssueCallbackReceiptEntity> legacyCompletion(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);
}
