package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 重新盘点和未执行原号结束共用不可变履历。
 */
@Mapper
public interface WmsInventoryReplanMapper {
	String COLUMNS =
			"tenant_id,factory_code,request_id,source_plan_id,warehouse_code,source_plan_no,source_version,new_plan_no, "
					+
					"stock_freeze_mode,reason,status,target_plan_id,audit_json,operator_code,end_reason,operated_time";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_replan_record WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND request_id=#{request}")
	Row findRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_inventory_replan_record WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND source_plan_id=#{source} AND status='REPLANNED'")
	Row findSource(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("source") Long source);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_replan_record (" + COLUMNS + ") VALUES "
			+
			"(#{tenantId},#{factoryCode},#{requestId},#{sourcePlanId},#{warehouseCode},#{sourcePlanNo},#{sourceVersion},#{newPlanNo}, "
			+
			"#{stockFreezeMode},#{reason},#{status},#{targetPlanId},#{auditJson},#{operatorCode},#{endReason},#{operatedTime})")
	int insert(Row row);

	record Row(Long tenantId, String factoryCode, String requestId, Long sourcePlanId, String warehouseCode,
	           String sourcePlanNo,
	           String sourceVersion, String newPlanNo, String stockFreezeMode, String reason, String status,
	           Long targetPlanId, String auditJson, String operatorCode, String endReason, LocalDateTime operatedTime) {
	}
}
