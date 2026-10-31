package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** 仅允许插入和按实际租户核对永久撤销证据，无修改/删除入口。 */
@Mapper
public interface WmsAllocationMobileCancelReceiptMapper {
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT tenant_id AS tenantId,request_id AS requestId,factory_code AS factoryCode,"
			+ "operator_code AS operatorCode,warehouse_code AS warehouseCode,bill_no AS billNo,"
			+ "request_snapshot AS requestSnapshot,cancellation_reason AS cancellationReason,"
			+ "completed_time AS completedTime "
			+ "FROM mom_wms.wms_mobile_allocation_cancel_receipt WHERE tenant_id=#{tenant} AND request_id=#{requestId}")
	Receipt find(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_mobile_allocation_cancel_receipt "
			+ "(tenant_id,request_id,factory_code,operator_code,warehouse_code,bill_no,request_snapshot,cancellation_reason,completed_time) "
			+ "VALUES(#{row.tenantId},#{row.requestId},#{row.factoryCode},#{row.operatorCode},#{row.warehouseCode},"
			+ "#{row.billNo},#{row.requestSnapshot},#{row.cancellationReason},#{row.completedTime})")
	int insert(@Param("row") Receipt row);

	record Receipt(Long tenantId, String requestId, String factoryCode, String operatorCode,
			String warehouseCode, String billNo, String requestSnapshot, String cancellationReason, LocalDateTime completedTime) {
	}
}
