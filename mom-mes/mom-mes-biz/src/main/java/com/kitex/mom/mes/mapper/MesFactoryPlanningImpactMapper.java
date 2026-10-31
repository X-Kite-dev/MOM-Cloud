package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 本域聚合证据显式限定租户/工厂；正式引用及未完成投递不因软删除而消失。
 */
@Mapper
public interface MesFactoryPlanningImpactMapper {

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT
                (SELECT count(*) FROM mom_mes.mes_task
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' AND (status IS NULL OR status NOT IN ('FINISH','CLOSE'))) AS unfinished_task_count,
                (SELECT count(*) FROM mom_mes.mes_erp_posting_outbox
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND (status IS NULL OR status<>'TRANSFERRED')) AS pending_erp_event_count,
                (SELECT count(*) FROM mom_mes.mes_task
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
                    AND (planning_policy_id IS NOT NULL OR formal_plan_contract_id IS NOT NULL OR formal_plan_contract_no IS NOT NULL
                        OR formal_plan_contract_version IS NOT NULL OR formal_plan_contract_fingerprint IS NOT NULL
                        OR planning_policy_version IS NOT NULL OR planning_policy_fingerprint IS NOT NULL)) +
                (SELECT count(*) FROM mom_mes.mes_production_order
                    WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
                    AND (planning_policy_id IS NOT NULL OR formal_plan_contract_id IS NOT NULL OR formal_plan_contract_no IS NOT NULL
                        OR formal_plan_contract_version IS NOT NULL OR formal_plan_contract_fingerprint IS NOT NULL
                        OR planning_policy_version IS NOT NULL OR planning_policy_fingerprint IS NOT NULL)) AS formal_source_count
            """)
    MdmFactoryPlanningImpactDTO.ModuleImpact current(
            @Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode);

}
