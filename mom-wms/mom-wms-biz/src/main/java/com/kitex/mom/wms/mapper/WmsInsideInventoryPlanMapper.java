package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.dto.WmsInsideInventoryPlanQueryDTO;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WmsInsideInventoryPlanMapper extends MomBaseMapper<WmsInsideInventoryPlanEntity> {

	@Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
	int lockCountScope(@Param("key") String key);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT count(*) FROM mom_wms.wms_inside_inventory_plan
			WHERE tenant_id = #{tenant} AND factory_code = #{factory}
			    AND plan_no = #{planNo} AND del_flag = '0'
			""")
	int countActivePlanNo(@Param("tenant") Long tenant, @Param("factory") String factory,
	                      @Param("planNo") String planNo);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT CASE WHEN EXISTS (
			    SELECT 1 FROM pg_catalog.pg_attribute a
			    WHERE a.attrelid = pg_catalog.to_regclass('mom_wms.wms_inside_inventory_plan')
			      AND a.attname = 'stock_freeze_mode' AND NOT a.attisdropped
			      AND a.attnotnull AND pg_catalog.format_type(a.atttypid, a.atttypmod) = 'character varying(16)'
			) AND (
			    SELECT count(*) FROM pg_catalog.pg_trigger t
			    WHERE ((t.tgrelid = pg_catalog.to_regclass('mom_wms.wms_live_stock')
			        AND t.tgfoid = pg_catalog.to_regprocedure('mom_wms.guard_count_stock_359()')
			        AND ((t.tgname = 'trg_wms_count_stock_row_359' AND t.tgtype = 31)
			          OR (t.tgname = 'trg_wms_count_stock_truncate_359' AND t.tgtype = 34)))
			       OR (t.tgrelid = pg_catalog.to_regclass('mom_wms.wms_inside_inventory_plan')
			        AND t.tgfoid = pg_catalog.to_regprocedure('mom_wms.guard_count_plan_359()')
			        AND t.tgname = 'trg_wms_count_plan_row_359' AND t.tgtype = 31)
			       OR (t.tgrelid = pg_catalog.to_regclass('mom_wms.wms_inside_inventory_plan_detail')
			        AND t.tgfoid = pg_catalog.to_regprocedure('mom_wms.guard_count_detail_359()')
			        AND ((t.tgname = 'trg_wms_count_detail_row_359' AND t.tgtype = 31)
			          OR (t.tgname = 'trg_wms_count_detail_truncate_359' AND t.tgtype = 34))))
			      AND t.tgenabled = 'A'
			) = 5 THEN 1 ELSE 0 END
			""")
	int countFreezeReady();

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT d.* FROM wms_inside_inventory_plan_detail d
			JOIN wms_inside_inventory_plan p ON p.id = d.plan_id
			    AND p.tenant_id = d.tenant_id AND p.factory_code = d.factory_code
			    AND p.warehouse_code = d.warehouse_code
			WHERE p.tenant_id = #{tenant} AND p.factory_code = #{factory}
			    AND p.warehouse_code = #{warehouse}
			    AND p.status IN ('0', '1') AND p.handle_state = '0'
			    AND p.del_flag = '0' AND d.del_flag = '0'
			""")
	List<WmsInsideInventoryPlanDetailEntity> findUnfinishedDetails(
			@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM mom_wms.wms_inventory_replan_record WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND (source_plan_id=#{id} OR target_plan_id=#{id})")
	int countReplanLinks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

	/**
	 * 分页查询
	 *
	 * @param page                   分页对象
	 * @param wmsInsideInventoryPlan 仓库盘点计划
	 * @return
	 */
	Page<WmsInsideInventoryPlanEntity> appPage(Page page,
	                                           @Param("params") WmsInsideInventoryPlanQueryDTO wmsInsideInventoryPlan);
}
