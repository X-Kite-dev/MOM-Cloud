package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 固定仓库依赖目录；各分支显式限定租户/工厂，保留全部计划状态和软删历史。
 */
@Mapper
public interface PlnWarehouseLifecycleMapper {

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT 'PLAN产品需求' WHERE EXISTS (
                SELECT 1 FROM pln_product_demand
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND demand_warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN主生产计划明细' WHERE EXISTS (
                SELECT 1 FROM pln_mps_line
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN物料需求计划' WHERE EXISTS (
                SELECT 1 FROM plan_mat_mrp
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND demand_warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN排程批次分配' WHERE EXISTS (
                SELECT 1 FROM pln_scheduling_batch_allocation
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND demand_warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN产品需求变更' WHERE EXISTS (
                SELECT 1 FROM pln_product_demand_change
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND target_warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN质量补产需求' WHERE EXISTS (
                SELECT 1 FROM pln_quality_supplement_demand
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND demand_warehouse_code = #{code})
            UNION ALL
            SELECT 'PLAN库存需求挂接台账' WHERE EXISTS (
                SELECT 1 FROM plan_mrp_inventory_pegging
                WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code})
            """)
    List<String> findReferences(@Param("tenant") Long tenant, @Param("factory") String factory,
                                @Param("code") String code);
}
