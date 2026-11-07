package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 永久请求号索引：原复盘触发器登记成功，新结束操作登记未执行的终态。
 */
@Mapper
public interface WmsInventoryRecountRequestMapper {
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT tenant_id,factory_code,request_id,plan_id,plan_detail_id,status,record_id,warehouse_code, "
			+ "command_json,operator_code,reason,operated_time FROM mom_wms.wms_inventory_recount_request "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
	Row find(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_inventory_recount_request "
			+ "(tenant_id,factory_code,request_id,plan_id,plan_detail_id,status,record_id,warehouse_code,"
			+ "command_json,operator_code,reason,operated_time) VALUES "
			+ "(#{tenantId},#{factoryCode},#{requestId},#{planId},#{planDetailId},'TERMINATED',NULL,#{warehouseCode},"
			+ "#{commandJson},#{operatorCode},#{reason},#{operatedTime})")
	int insertTermination(Row row);

	record Row(Long tenantId, String factoryCode, String requestId, Long planId, Long planDetailId, String status,
	           Long recordId, String warehouseCode, String commandJson, String operatorCode, String reason,
	           LocalDateTime operatedTime) {
	}
}
