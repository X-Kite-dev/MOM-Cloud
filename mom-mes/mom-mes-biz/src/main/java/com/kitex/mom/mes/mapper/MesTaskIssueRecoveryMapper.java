package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesProductionOrderEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 回执恢复不让人员可见范围或软删隐藏曾接收事实；实际租户工厂由实名授权服务传入。
 */
@Mapper
public interface MesTaskIssueRecoveryMapper {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT " + MesProductionOrderMapper.CAPACITY_TASK_COLUMNS + """
            ,t.plan_no,t.create_time,t.aps_task_id,t.aps_batch_id,t.aps_batch_no,t.aps_batch_type,t.aps_batch_fingerprint,
             t.engineering_release_id,t.engineering_content_hash
            FROM mom_mes.mes_task t WHERE t.tenant_id=#{tenantId} AND t.factory_code=#{factoryCode}
            AND t.source_request_id=#{requestId} ORDER BY t.id LIMIT 2
            """)
    List<MesTaskEntity> findRequest(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                    @Param("requestId") String requestId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id,tenant_id,factory_code,del_flag,order_no,source_type,source_no,product_code,order_quantity,
                formal_plan_contract_no,formal_plan_contract_version,formal_plan_contract_fingerprint,
                source_system,source_plan_no,source_plan_version,planning_policy_id,planning_policy_version,
                planning_policy_fingerprint,mrp_authority,erp_production_order_no
            FROM mom_mes.mes_production_order WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode}
            AND order_no=#{orderNo} ORDER BY id LIMIT 2
            """)
    List<MesProductionOrderEntity> findOrder(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                             @Param("orderNo") String orderNo);
}
