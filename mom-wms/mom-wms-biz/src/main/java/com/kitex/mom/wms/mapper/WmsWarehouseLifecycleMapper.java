package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.wms.entity.WmsWarehouseMenuEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 固定依赖目录；显式限定租户/工厂，业务历史全部保留，配置/预览可显式排除。
 */
@Mapper
public interface WmsWarehouseLifecycleMapper {

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			<script>
			<if test="includeConfiguration">
			SELECT 'WMS业务配置' WHERE EXISTS (
			    SELECT 1 FROM wms_warehouse_biz
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND del_flag = '0' AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS看板风险阈值' WHERE EXISTS (
			    SELECT 1 FROM wms_warehouse_dashboard_policy
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS库位图配置' WHERE EXISTS (
			    SELECT 1 FROM wms_warehouse_location_map
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS盘点默认规则' WHERE EXISTS (
			    SELECT 1 FROM wms_inventory_scope_policy
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS盘点差异规则' WHERE EXISTS (
			    SELECT 1 FROM wms_inventory_variance_policy
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS盘点任务模板' WHERE EXISTS (
			    SELECT 1 FROM wms_inventory_count_template
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS仓库菜单预览' WHERE EXISTS (
			    SELECT 1 FROM wms_warehouse_menu
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND del_flag = '0'
			      AND (path = concat('/wms/warehouseManege/', #{factory}, '/', #{code})
			        OR position(concat('/wms/warehouseManege/', #{factory}, '/', #{code}, '/') in path) = 1))
			UNION ALL
			</if>
			SELECT 'WMS实时库存及其历史' WHERE EXISTS (
			    SELECT 1 FROM wms_live_stock
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS入库单' WHERE EXISTS (
			    SELECT 1 FROM wms_in_bill
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS入库单明细' WHERE EXISTS (
			    SELECT 1 FROM wms_in_bill_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS入库通知' WHERE EXISTS (
			    SELECT 1 FROM wms_in_notice
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS入库通知明细' WHERE EXISTS (
			    SELECT 1 FROM wms_in_notice_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS出库单' WHERE EXISTS (
			    SELECT 1 FROM wms_out_bill
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS出库单明细' WHERE EXISTS (
			    SELECT 1 FROM wms_out_bill_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS出库通知' WHERE EXISTS (
			    SELECT 1 FROM wms_out_notice
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS出库通知明细' WHERE EXISTS (
			    SELECT 1 FROM wms_out_notice_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS出入库流水' WHERE EXISTS (
			    SELECT 1 FROM wms_inout_flow
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS盘点计划' WHERE EXISTS (
			    SELECT 1 FROM wms_inside_inventory_plan
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS盘点明细' WHERE EXISTS (
			    SELECT 1 FROM wms_inside_inventory_plan_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS盘点模板触发历史' WHERE EXISTS (
			    SELECT 1 FROM wms_inventory_count_template_run run
			    JOIN wms_inventory_count_template template
			      ON template.tenant_id = run.tenant_id AND template.factory_code = run.factory_code
			     AND template.template_code = run.template_code
			    WHERE run.tenant_id = #{tenant} AND run.factory_code = #{factory}
			      AND template.warehouse_code = #{code})
			UNION ALL
			SELECT 'WMS物料预留' WHERE EXISTS (
			    SELECT 1 FROM wms_material_reservation
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS物料预留明细' WHERE EXISTS (
			    SELECT 1 FROM wms_material_reservation_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS销售库存预留' WHERE EXISTS (
			    SELECT 1 FROM wms_sales_stock_reservation_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS库内移动' WHERE EXISTS (
			    SELECT 1 FROM wms_material_move_site
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS调拨单' WHERE EXISTS (
			    SELECT 1 FROM wms_allocation_bill
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (old_warehouse_code = #{code} OR new_warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS容器及占用历史' WHERE EXISTS (
			    SELECT 1 FROM wms_container_barcode
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS库存预警配置' WHERE EXISTS (
			    SELECT 1 FROM wms_material_warning
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS拣货区域' WHERE EXISTS (
			    SELECT 1 FROM wms_picking_area
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS质量状态操作' WHERE EXISTS (
			    SELECT 1 FROM wms_quality_status_operation
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS发货单' WHERE EXISTS (
			    SELECT 1 FROM wms_deliver_goods_base
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS销售退货单' WHERE EXISTS (
			    SELECT 1 FROM wms_deliver_goods_return
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (return_warehouse = #{code}))
			UNION ALL
			SELECT 'WMS交付异常处置' WHERE EXISTS (
			    SELECT 1 FROM wms_shipment_exception_resolution
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (return_warehouse = #{code}))
			UNION ALL
			SELECT 'WMS成品实收纠正' WHERE EXISTS (
			    SELECT 1 FROM wms_finished_goods_receipt_correction_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS销售发货纠正' WHERE EXISTS (
			    SELECT 1 FROM wms_sales_shipment_correction_detail
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS调拨实收' WHERE EXISTS (
			    SELECT 1 FROM wms_transfer_receipt_line
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (target_warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS调拨路由策略' WHERE EXISTS (
			    SELECT 1 FROM wms_transfer_route_policy
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (source_warehouse_code = #{code} OR target_warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS调拨差异处置' WHERE EXISTS (
			    SELECT 1 FROM wms_transfer_variance_case
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (source_warehouse_code = #{code} OR target_warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS调拨差异策略' WHERE EXISTS (
			    SELECT 1 FROM wms_transfer_variance_policy
			    WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			      AND (source_warehouse_code = #{code} OR target_warehouse_code = #{code}))
			UNION ALL
			SELECT 'WMS组织调拨历史' WHERE EXISTS (
			    SELECT 1 FROM mom_wms.wms_org_transfer_bill
			    WHERE tenant_id = #{tenant}
			      AND ((source_factory_code = #{factory} AND source_warehouse_code = #{code})
			        OR (target_factory_code = #{factory} AND target_warehouse_code = #{code})))
			</script>
			""")
	List<String> findReferences(@Param("tenant") Long tenant, @Param("factory") String factory,
	                            @Param("code") String code,
	                            @Param("includeConfiguration") boolean includeConfiguration);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT count(*) FROM wms_warehouse_biz
			WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}
			""")
	Long countBusinessConfiguration(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                @Param("code") String code);

	/**
	 * 清理包含历史软删除配置，但永远只作用于此租户/工厂及仓库的配置表。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("""
			DELETE FROM wms_warehouse_biz
			WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}
			""")
	int deleteBusinessConfiguration(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM wms_warehouse_dashboard_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	Long countDashboardPolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                          @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("DELETE FROM wms_warehouse_dashboard_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	int deleteDashboardPolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                          @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM wms_warehouse_location_map "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	Long countLocationMap(@Param("tenant") Long tenant, @Param("factory") String factory,
	                      @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("DELETE FROM wms_warehouse_location_map "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	int deleteLocationMap(@Param("tenant") Long tenant, @Param("factory") String factory,
	                      @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM wms_inventory_scope_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	Long countInventoryScopePolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                               @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("DELETE FROM wms_inventory_scope_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	int deleteInventoryScopePolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                               @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM wms_inventory_variance_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	Long countInventoryVariancePolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                  @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("DELETE FROM wms_inventory_variance_policy "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	int deleteInventoryVariancePolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                  @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	Long countInventoryCountTemplate(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                 @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("DELETE FROM wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{code}")
	int deleteInventoryCountTemplate(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                 @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT * FROM wms_warehouse_menu WHERE tenant_id = #{tenant} AND factory_code = #{factory}")
	List<WmsWarehouseMenuEntity> findPreviewForCleanup(@Param("tenant") Long tenant, @Param("factory") String factory);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Delete("""
			<script>
			DELETE FROM wms_warehouse_menu WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND id IN
			<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
			</script>
			""")
	int deletePreview(@Param("tenant") Long tenant, @Param("factory") String factory,
	                  @Param("ids") Collection<Long> ids);

	/**
	 * 停用未使用仓库的发布状态，永久公司绑定仍保留。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@org.apache.ibatis.annotations.Update("""
			UPDATE mom_wms.wms_org_transfer_policy
			SET enabled = false, version = version + 1, updated_time = CURRENT_TIMESTAMP
			WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			  AND warehouse_code = #{code} AND enabled = true
			""")
	int disableOrganizationPolicy(@Param("tenant") Long tenant, @Param("factory") String factory,
	                              @Param("code") String code);

}
