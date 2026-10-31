package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 固定依赖目录；每一分支显式限定租户/工厂，保留所有状态及软删记录。
 */
@Mapper
public interface MesWarehouseLifecycleMapper {

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT 'MES用料清单默认仓库' WHERE EXISTS (
                SELECT 1 FROM mes_material_list_detail
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (default_warehouse = #{code} OR default_warehouse = #{warehouseId}))
            UNION ALL
            SELECT 'MES用料流转记录' WHERE EXISTS (
                SELECT 1 FROM mes_material_list_record
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES任务WMS实收' WHERE EXISTS (
                SELECT 1 FROM mes_task_in_wms_receipt
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES投料及其历史' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_feed
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES线边库出入库流水' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_inout
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES线边库盘点' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_inventory
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES线边库盘点履历' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_inventory_record
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES线边库库存及其历史' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_live_stock
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES线边库锁定明细' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_lock_detail
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES生产领料实收' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_material_in_bill
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES生产领料单' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_picking_bill
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES生产领料单明细' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_picking_bill_detail
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES库存退料审批' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_stock_feed_judge
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            UNION ALL
            SELECT 'MES不合格锁定' WHERE EXISTS (
                SELECT 1 FROM mes_warehouse_unqualified_lock
                WHERE tenant_id = #{tenant} AND factory_code = #{factory}
                  AND (warehouse_code = #{code}))
            """)
    List<String> findReferences(@Param("tenant") Long tenant, @Param("factory") String factory,
                                @Param("code") String code, @Param("warehouseId") String warehouseId);
}
