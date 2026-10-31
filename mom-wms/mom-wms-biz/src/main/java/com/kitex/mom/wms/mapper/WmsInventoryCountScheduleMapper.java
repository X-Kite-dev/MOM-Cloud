package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 盘点模板周期配置始终以租户、工厂和模板精确定位。 */
@Mapper
public interface WmsInventoryCountScheduleMapper {
	String COLUMNS = "s.version, s.enabled, s.interval_days AS intervalDays, "
			+ "s.time_of_day AS timeOfDay, s.zone_id AS zoneId, "
			+ "s.next_run_utc AS nextRunUtc";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_count_schedule s "
			+ "JOIN mom_wms.wms_inventory_count_template template "
			+ "ON template.tenant_id = s.tenant_id AND template.factory_code = s.factory_code "
			+ "AND template.template_code = s.template_code "
			+ "WHERE s.tenant_id = #{tenant} AND s.factory_code = #{factory} "
			+ "AND s.template_code = #{code} AND template.warehouse_code = #{warehouse}")
	ScheduleRow find(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("code") String code, @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_count_schedule s "
			+ "JOIN mom_wms.wms_inventory_count_template template "
			+ "ON template.tenant_id = s.tenant_id AND template.factory_code = s.factory_code "
			+ "AND template.template_code = s.template_code "
			+ "WHERE s.tenant_id = #{tenant} AND s.factory_code = #{factory} "
			+ "AND s.template_code = #{code} AND template.warehouse_code = #{warehouse} "
			+ "FOR UPDATE OF s")
	ScheduleRow findForUpdate(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("code") String code, @Param("warehouse") String warehouse);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT s.template_code AS templateCode, template.warehouse_code AS warehouseCode, "
			+ "s.next_run_utc AS nextRunUtc, s.version "
			+ "FROM mom_wms.wms_inventory_count_schedule s "
			+ "JOIN mom_wms.wms_inventory_count_template template "
			+ "ON template.tenant_id = s.tenant_id AND template.factory_code = s.factory_code "
			+ "AND template.template_code = s.template_code "
			+ "WHERE s.tenant_id = #{tenant} AND s.factory_code = #{factory} "
			+ "AND s.enabled = true AND s.next_run_utc <= #{nowUtc} "
			+ "ORDER BY s.next_run_utc, s.template_code LIMIT #{limit}")
	List<DueRow> due(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("nowUtc") LocalDateTime nowUtc, @Param("limit") int limit);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_count_schedule AS s "
			+ "(tenant_id, factory_code, template_code, enabled, interval_days, time_of_day, "
			+ "zone_id, next_run_utc, version, updated_by) "
			+ "SELECT template.tenant_id, template.factory_code, template.template_code, "
			+ "#{enabled}, #{intervalDays}, #{timeOfDay}, #{zoneId}, #{nextRunUtc}, 1, #{updatedBy} "
			+ "FROM mom_wms.wms_inventory_count_template template "
			+ "WHERE template.tenant_id = #{tenant} AND template.factory_code = #{factory} "
			+ "AND template.template_code = #{code} AND template.warehouse_code = #{warehouse} "
			+ "ON CONFLICT (tenant_id, factory_code, template_code) DO UPDATE SET "
			+ "enabled = EXCLUDED.enabled, interval_days = EXCLUDED.interval_days, "
			+ "time_of_day = EXCLUDED.time_of_day, zone_id = EXCLUDED.zone_id, "
			+ "next_run_utc = EXCLUDED.next_run_utc, version = s.version + 1, "
			+ "updated_at = now(), updated_by = EXCLUDED.updated_by "
			+ "WHERE s.version = #{expectedVersion}")
	int upsert(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("code") String code, @Param("warehouse") String warehouse,
			@Param("enabled") boolean enabled, @Param("intervalDays") int intervalDays,
			@Param("timeOfDay") String timeOfDay, @Param("zoneId") String zoneId,
			@Param("nextRunUtc") LocalDateTime nextRunUtc,
			@Param("expectedVersion") int expectedVersion,
			@Param("updatedBy") String updatedBy);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("UPDATE mom_wms.wms_inventory_count_schedule "
			+ "SET next_run_utc = #{nextRunUtc} "
			+ "WHERE tenant_id = #{tenant} AND factory_code = #{factory} AND template_code = #{code} "
			+ "AND version = #{version} AND enabled = true AND next_run_utc = #{dueUtc}")
	int advance(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("code") String code, @Param("version") int version,
			@Param("dueUtc") LocalDateTime dueUtc, @Param("nextRunUtc") LocalDateTime nextRunUtc);

	record ScheduleRow(int version, boolean enabled, int intervalDays, String timeOfDay,
			String zoneId, LocalDateTime nextRunUtc) {
	}

	record DueRow(String templateCode, String warehouseCode, LocalDateTime nextRunUtc, int version) {
	}
}
