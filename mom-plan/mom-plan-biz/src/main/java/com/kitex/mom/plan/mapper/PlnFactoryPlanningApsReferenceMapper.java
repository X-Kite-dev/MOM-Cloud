package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO.Reference;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * All facts retain soft-deleted/orphan sources; this mapper never releases resources.
 */
@Mapper
public interface PlnFactoryPlanningApsReferenceMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            """
                                SELECT (SELECT count(*) FROM mom_plan.pln_issue WHERE mom_plan.pln_issue.tenant_id=#{tenantId} AND mom_plan.pln_issue.factory_code=#{factoryCode} AND (lock_status=true OR lock_task_id IS NOT NULL)) +
                    (SELECT count(*) FROM mom_plan.pln_scheduling_task_detail WHERE mom_plan.pln_scheduling_task_detail.tenant_id=#{tenantId} AND mom_plan.pln_scheduling_task_detail.factory_code=#{factoryCode} AND lock_status=true) +
                    (SELECT count(*) FROM mom_plan.pln_scheduling_operation WHERE mom_plan.pln_scheduling_operation.tenant_id=#{tenantId} AND mom_plan.pln_scheduling_operation.factory_code=#{factoryCode} AND lock_status=true) +
                    (SELECT count(*) FROM mom_plan.pln_scheduling_batch WHERE mom_plan.pln_scheduling_batch.tenant_id=#{tenantId} AND mom_plan.pln_scheduling_batch.factory_code=#{factoryCode} AND lock_status=true)
                    """)
    long ordinaryCount(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            """
                                SELECT * FROM (
                    SELECT 'PLAN' AS source_type, CAST(s.id AS varchar) AS source_id, s.issue_no AS source_no,
                     CAST(s.id AS varchar) AS plan_id,s.issue_no AS plan_no,s.product_code,s.status AS plan_status,
                     CAST(s.lock_task_id AS varchar) AS task_id,t.task_no,t.task_status,s.status AS source_status,
                     s.del_flag='1' AS source_deleted,(t.id IS NULL) AS relation_incomplete
                     FROM mom_plan.pln_issue s LEFT JOIN mom_plan.pln_scheduling_task t ON t.id=s.lock_task_id AND t.tenant_id=s.tenant_id AND t.factory_code=s.factory_code
                     WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} AND (s.lock_status=true OR s.lock_task_id IS NOT NULL)
                    UNION ALL
                    SELECT 'TASK_DETAIL' AS source_type,CAST(s.id AS varchar) AS source_id,CAST(NULL AS varchar) AS source_no,
                     CAST(s.issue_id AS varchar) AS plan_id,COALESCE(i.issue_no,s.issue_no) AS plan_no,s.product_code,i.status AS plan_status,
                     CAST(s.task_id AS varchar) AS task_id,t.task_no,t.task_status,s.status AS source_status,
                     s.del_flag='1' AS source_deleted,(i.id IS NULL OR t.id IS NULL) AS relation_incomplete
                     FROM mom_plan.pln_scheduling_task_detail s LEFT JOIN mom_plan.pln_issue i ON i.id=s.issue_id AND i.tenant_id=s.tenant_id AND i.factory_code=s.factory_code LEFT JOIN mom_plan.pln_scheduling_task t ON t.id=s.task_id AND t.tenant_id=s.tenant_id AND t.factory_code=s.factory_code
                     WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} AND s.lock_status=true
                    UNION ALL
                    SELECT 'OPERATION' AS source_type,CAST(s.id AS varchar) AS source_id,CAST(NULL AS varchar) AS source_no,
                     CAST(s.issue_id AS varchar) AS plan_id,COALESCE(i.issue_no,s.issue_no) AS plan_no,s.product_code,i.status AS plan_status,
                     CAST(s.task_id AS varchar) AS task_id,t.task_no,t.task_status,s.schedule_status AS source_status,
                     s.del_flag='1' AS source_deleted,(i.id IS NULL OR t.id IS NULL) AS relation_incomplete
                     FROM mom_plan.pln_scheduling_operation s LEFT JOIN mom_plan.pln_issue i ON i.id=s.issue_id AND i.tenant_id=s.tenant_id AND i.factory_code=s.factory_code LEFT JOIN mom_plan.pln_scheduling_task t ON t.id=s.task_id AND t.tenant_id=s.tenant_id AND t.factory_code=s.factory_code
                     WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} AND s.lock_status=true
                    UNION ALL
                    SELECT 'BATCH' AS source_type,CAST(s.id AS varchar) AS source_id,s.batch_no AS source_no,
                     CAST(NULL AS varchar) AS plan_id,CAST(NULL AS varchar) AS plan_no,s.product_code,CAST(NULL AS varchar) AS plan_status,
                     CAST(s.task_id AS varchar) AS task_id,t.task_no,t.task_status,s.batch_status AS source_status,
                     s.del_flag='1' AS source_deleted,(t.id IS NULL) AS relation_incomplete
                     FROM mom_plan.pln_scheduling_batch s LEFT JOIN mom_plan.pln_scheduling_task t ON t.id=s.task_id AND t.tenant_id=s.tenant_id AND t.factory_code=s.factory_code
                     WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} AND s.lock_status=true
                                ) facts ORDER BY source_type,source_id LIMIT #{limit}
                    """)
    List<Reference> ordinary(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT count(*) FROM mom_plan.pln_aps_case WHERE tenant_id=#{tenantId} AND"
                    + " factory_code=#{factoryCode} AND (case_status IS NULL OR case_status NOT IN"
                    + " ('DRAFT','COMPLETED','CANCELLED','SUPERSEDED')) ")
    long caseCount(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            """
                    SELECT 'APS_CASE' AS source_type, CAST(id AS varchar) AS source_id, case_no AS source_no,
                        case_status AS source_status,del_flag='1' AS source_deleted,false AS relation_incomplete
                    FROM mom_plan.pln_aps_case WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (case_status IS NULL OR case_status NOT IN ('DRAFT','COMPLETED','CANCELLED','SUPERSEDED'))
                    ORDER BY source_type,source_id LIMIT #{limit}
                    """)
    List<Reference> cases(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("limit") int limit);
}
