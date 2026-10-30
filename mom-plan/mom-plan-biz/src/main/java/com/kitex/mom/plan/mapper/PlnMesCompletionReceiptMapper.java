package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMesCompletionReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface PlnMesCompletionReceiptMapper extends BaseMapper<PlnMesCompletionReceiptEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockRequest(@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_plan.pln_mes_completion_receipt WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND outbox_id=#{id} ORDER BY issue_id")
    List<PlnMesCompletionReceiptEntity> originals(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            """
                    <script>SELECT id,tenant_id,factory_code,del_flag,issue_no,product_code,num,status,finish_time
                    FROM mom_plan.pln_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory}
                        AND issue_no=#{issueNo} AND product_code=#{product}
                        <if test="id!=null">AND id=#{id}</if> ORDER BY id LIMIT 2</script>
                    """)
    List<PlnIssueEntity> source(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("issueNo") String issueNo,
            @Param("product") String product);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,del_flag,issue_no,product_code,num,status,finish_time"
                    + " FROM mom_plan.pln_issue WHERE tenant_id=#{tenant} AND factory_code=#{factory}"
                    + " AND id=#{id} FOR UPDATE")
    PlnIssueEntity lockedIssue(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            "UPDATE mom_plan.pln_issue SET status=#{after},finish_time=#{finished} WHERE"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND"
                    + " status=#{before} AND status IN('1','2','3')")
    int decision(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("before") String before,
            @Param("after") String after,
            @Param("finished") LocalDate finished);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT * FROM mom_plan.pln_mes_completion_receipt WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND mes_task_id=#{taskId} AND mes_task_code=#{taskCode}"
                    + " AND completion_version>=#{version} AND issue_id=#{issueId} ORDER BY"
                    + " completion_version DESC,id LIMIT 2")
    List<PlnMesCompletionReceiptEntity> generation(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId,
            @Param("taskCode") String taskCode,
            @Param("version") Integer version,
            @Param("issueId") Long issueId);
}
