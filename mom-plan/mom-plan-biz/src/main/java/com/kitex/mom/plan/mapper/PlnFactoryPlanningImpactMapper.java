package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 本域聚合证据显式限定租户/工厂；正式引用及未完成投递不因软删除而消失。
 */
@Mapper
public interface PlnFactoryPlanningImpactMapper {

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT
                (SELECT count(*) FROM mom_plan.plan_mat_mrp
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' AND (status=false OR status IS NULL)) AS active_mrp_count,
                (SELECT count(*) FROM mom_plan.pln_scheduling_task_detail
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND lock_status=true) +
                (SELECT count(*) FROM mom_plan.pln_scheduling_operation
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND lock_status=true) +
                (SELECT count(*) FROM mom_plan.pln_scheduling_batch
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND lock_status=true) +
                (SELECT count(*) FROM mom_plan.pln_issue
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (lock_status=true OR lock_task_id IS NOT NULL)) AS locked_aps_count,
                (SELECT count(*) FROM mom_plan.pln_erp_integration_outbox
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (status IS NULL OR status<>'SUCCEEDED')) AS pending_erp_event_count,
                (SELECT count(*) FROM mom_plan.pln_formal_plan_contract
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}) AS formal_source_count
            """)
    MdmFactoryPlanningImpactDTO.ModuleImpact current(
            @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

    /**
     * 两张可选表须同时存在或同时不存在；任何SQL错误均向上传递。
     */
    @Select("SELECT to_regclass('mom_plan.pln_aps_case') IS NOT NULL")
    boolean apsCaseInstalled();

    @Select("SELECT to_regclass('mom_plan.pln_aps_mes_dispatch_outbox') IS NOT NULL")
    boolean apsDispatchInstalled();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT (SELECT count(*) FROM mom_plan.pln_aps_case
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (case_status IS NULL OR case_status NOT IN ('DRAFT','COMPLETED','CANCELLED','SUPERSEDED'))) AS locked_aps_count,
                (SELECT count(*) FROM mom_plan.pln_aps_mes_dispatch_outbox
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (status IS NULL OR status NOT IN ('SUCCEEDED','CANCELLED'))) AS pending_erp_event_count,
                (SELECT count(*) FROM mom_plan.pln_aps_case
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
                    AND (planning_policy_id IS NOT NULL OR planning_policy_version IS NOT NULL
                        OR planning_policy_fingerprint IS NOT NULL OR formal_plan_contract_id IS NOT NULL
                        OR formal_plan_contract_no IS NOT NULL OR formal_plan_contract_version IS NOT NULL
                        OR formal_plan_contract_fingerprint IS NOT NULL)) AS formal_source_count
            """)
    MdmFactoryPlanningImpactDTO.ModuleImpact intelligentAps(
            @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

}
