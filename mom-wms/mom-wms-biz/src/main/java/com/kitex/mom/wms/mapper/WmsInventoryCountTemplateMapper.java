package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 盘点模板和手动触发记录均以明确的租户、工厂范围读写。 */
@Mapper
public interface WmsInventoryCountTemplateMapper {
	String COLUMNS = "template_code AS templateCode, template_name AS templateName, "
			+ "warehouse_code AS warehouseCode, rule_code AS ruleCode, "
			+ "threshold_days AS thresholdDays, site_codes_json AS siteCodesJson, "
			+ "stock_freeze_mode AS stockFreezeMode, enabled, version";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND template_code = #{code}")
	TemplateRow find(@Param("tenant") Long tenant, @Param("factory") String factory,
					 @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND template_code = #{code} FOR UPDATE")
	TemplateRow findForUpdate(@Param("tenant") Long tenant, @Param("factory") String factory,
							  @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} "
			+ "AND warehouse_code = #{warehouse} ORDER BY template_code")
	List<TemplateRow> list(@Param("tenant") Long tenant, @Param("factory") String factory,
						   @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT count(*) FROM mom_wms.wms_inventory_count_template "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND warehouse_code = #{warehouse}")
	Long countInWarehouse(@Param("tenant") Long tenant, @Param("factory") String factory,
						  @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_count_template AS template "
			+ "(tenant_id, factory_code, template_code, template_name, warehouse_code, "
			+ "rule_code, threshold_days, site_codes_json, stock_freeze_mode, enabled, version, updated_by) "
			+ "VALUES (#{tenant}, #{factory}, #{code}, #{name}, #{warehouse}, #{ruleCode}, "
			+ "#{thresholdDays}, #{siteCodesJson}, #{freezeMode}, #{enabled}, 1, #{updatedBy}) "
			+ "ON CONFLICT (tenant_id, factory_code, template_code) DO UPDATE SET "
			+ "template_name = EXCLUDED.template_name, rule_code = EXCLUDED.rule_code, "
			+ "threshold_days = EXCLUDED.threshold_days, site_codes_json = EXCLUDED.site_codes_json, "
			+ "stock_freeze_mode = EXCLUDED.stock_freeze_mode, enabled = EXCLUDED.enabled, "
			+ "version = template.version + 1, updated_at = now(), updated_by = EXCLUDED.updated_by "
			+ "WHERE template.version = #{expectedVersion} "
			+ "AND template.warehouse_code = EXCLUDED.warehouse_code")
	int upsert(@Param("tenant") Long tenant, @Param("factory") String factory,
			   @Param("code") String code, @Param("name") String name,
			   @Param("warehouse") String warehouse, @Param("ruleCode") String ruleCode,
			   @Param("thresholdDays") Integer thresholdDays, @Param("siteCodesJson") String siteCodesJson,
			   @Param("freezeMode") String freezeMode, @Param("enabled") boolean enabled,
			   @Param("expectedVersion") int expectedVersion, @Param("updatedBy") String updatedBy);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_count_template_run "
			+ "(tenant_id, factory_code, request_id, template_code, template_version, "
			+ "template_snapshot_json, triggered_by) "
			+ "VALUES (#{tenant}, #{factory}, #{requestId}, #{code}, #{version}, #{snapshot}, #{operator}) "
			+ "ON CONFLICT (tenant_id, factory_code, request_id) DO NOTHING")
	int reserveRun(@Param("tenant") Long tenant, @Param("factory") String factory,
				   @Param("requestId") String requestId, @Param("code") String code,
				   @Param("version") int version, @Param("snapshot") String snapshot,
				   @Param("operator") String operator);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT run.request_id AS requestId, run.template_version AS templateVersion, "
			+ "run.plan_id AS planId, run.plan_no AS planNo, run.triggered_by AS triggeredBy, "
			+ "run.created_at AS createdAt, run.template_snapshot_json AS templateSnapshotJson "
			+ "FROM mom_wms.wms_inventory_count_template_run run "
			+ "JOIN mom_wms.wms_inventory_count_template template "
			+ "ON template.tenant_id = run.tenant_id AND template.factory_code = run.factory_code "
			+ "AND template.template_code = run.template_code "
			+ "WHERE run.tenant_id = #{tenant} AND run.factory_code = #{factory} "
			+ "AND run.template_code = #{code} AND template.warehouse_code = #{warehouse} "
			+ "AND run.plan_id IS NOT NULL ORDER BY run.created_at DESC, run.request_id DESC LIMIT 20")
	List<HistoryRow> recentRuns(@Param("tenant") Long tenant, @Param("factory") String factory,
								@Param("warehouse") String warehouse, @Param("code") String code);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT template_code AS templateCode, template_version AS templateVersion, "
			+ "plan_id AS planId, plan_no AS planNo FROM mom_wms.wms_inventory_count_template_run "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} "
			+ "AND request_id = #{requestId} FOR UPDATE")
	RunRow findRunForUpdate(@Param("tenant") Long tenant, @Param("factory") String factory,
							@Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("UPDATE mom_wms.wms_inventory_count_template_run SET plan_id = #{planId}, plan_no = #{planNo} "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND request_id = #{requestId} "
			+ "AND plan_id IS NULL AND plan_no IS NULL")
	int completeRun(@Param("tenant") Long tenant, @Param("factory") String factory,
					@Param("requestId") String requestId, @Param("planId") Long planId,
					@Param("planNo") String planNo);

	record TemplateRow(String templateCode, String templateName, String warehouseCode,
					   String ruleCode, Integer thresholdDays, String siteCodesJson,
					   String stockFreezeMode, boolean enabled, int version) {
	}

	record RunRow(String templateCode, int templateVersion, Long planId, String planNo) {
	}

	record HistoryRow(String requestId, int templateVersion, Long planId, String planNo,
					  String triggeredBy, LocalDateTime createdAt, String templateSnapshotJson) {
	}
}
