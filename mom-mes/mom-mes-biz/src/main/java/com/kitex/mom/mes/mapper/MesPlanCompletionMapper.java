package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesApsBatchAllocationEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 实际组织的完整事实；不让软删或人员范围隐藏仍占用来源的工单。
 */
@Mapper
public interface MesPlanCompletionMapper {
    String TASK_COLUMNS =
            MesProductionOrderMapper.CAPACITY_TASK_COLUMNS
                    + ",t.plan_no,t.completion_version,t.aps_task_id,t.aps_batch_id,t.aps_batch_no,t.aps_batch_fingerprint";

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT "
                    + TASK_COLUMNS
                    + " FROM mom_mes.mes_task t WHERE t.tenant_id=#{tenant} AND"
                    + " t.factory_code=#{factory} AND t.id=#{id}")
    MesTaskEntity task(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT "
                    + TASK_COLUMNS
                    + """
                     FROM mom_mes.mes_task t WHERE t.tenant_id=#{tenant} AND t.factory_code=#{factory}
                     AND ((NULLIF(t.aps_batch_no,'') IS NULL AND t.plan_no=#{issueNo} AND t.mat_code=#{product})
                        OR EXISTS(SELECT 1 FROM mom_mes.mes_aps_batch_allocation a
                           WHERE a.tenant_id=#{tenant} AND a.factory_code=#{factory} AND a.mes_task_id=t.id
                             AND a.issue_id=#{issueId})) ORDER BY t.id
                    """)
    List<MesTaskEntity> sourceTasks(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("issueId") Long issueId,
            @Param("issueNo") String issueNo,
            @Param("product") String product);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            """
                    SELECT id,tenant_id,factory_code,del_flag,mes_task_id,mes_task_code,aps_task_id,aps_batch_id,
                        aps_batch_no,aps_batch_fingerprint,allocation_seq,issue_id,issue_no,issue_quantity_snapshot,
                        allocated_quantity,completed_quantity
                    FROM mom_mes.mes_aps_batch_allocation WHERE tenant_id=#{tenant} AND factory_code=#{factory}
                        AND mes_task_id=#{taskId} ORDER BY allocation_seq,id
                    """)
    List<MesApsBatchAllocationEntity> allocations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update(
            """
                    UPDATE mom_mes.mes_aps_batch_allocation SET completed_quantity=#{quantity}
                    WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND mes_task_id=#{taskId}
                    """)
    int completed(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("taskId") Long taskId,
            @Param("id") Long id,
            @Param("quantity") BigDecimal quantity);

    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockEnqueue(@Param("key") String key);
}
