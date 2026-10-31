package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 永久原请求只允许预占、读取和一次终结，实际租户由服务层显式传入。 */
@Mapper
public interface WmsReturnFinalizeMapper {
	String COLUMNS = "tenant_id AS tenantId, factory_code AS factoryCode, operator_code AS operatorCode, "
			+ "request_id AS requestId, notice_id AS noticeId, warehouse_code AS warehouseCode, "
			+ "request_payload AS requestPayload, request_fingerprint AS requestFingerprint, "
			+ "status, result_payload AS resultPayload, completed_time AS completedTime, "
			+ "cancellation_reason AS cancellationReason";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_return_finalize_receipt "
			+ "(tenant_id,factory_code,operator_code,request_id,notice_id,warehouse_code,"
			+ "request_payload,request_fingerprint,status) VALUES "
			+ "(#{row.tenantId},#{row.factoryCode},#{row.operatorCode},#{row.requestId},#{row.noticeId},"
			+ "#{row.warehouseCode},#{row.requestPayload},#{row.requestFingerprint},'PENDING') "
			+ "ON CONFLICT DO NOTHING")
	int reserve(@Param("row") Receipt row);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_return_finalize_receipt "
			+ "WHERE tenant_id=#{tenant} AND request_id=#{requestId}")
	Receipt find(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_return_finalize_receipt "
			+ "WHERE tenant_id=#{tenant} AND request_id=#{requestId} FOR UPDATE")
	Receipt findForUpdate(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("UPDATE mom_wms.wms_return_finalize_receipt SET status=#{status},result_payload=#{result},"
			+ "completed_time=#{time},cancellation_reason=#{reason} WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND operator_code=#{operator} AND request_id=#{requestId} "
			+ "AND request_fingerprint=#{fingerprint} AND status='PENDING'")
	int complete(@Param("tenant") Long tenant, @Param("factory") String factory,
			@Param("operator") String operator, @Param("requestId") String requestId,
			@Param("fingerprint") String fingerprint, @Param("status") String status,
			@Param("result") String result, @Param("time") LocalDateTime time, @Param("reason") String reason);

	record Receipt(Long tenantId, String factoryCode, String operatorCode, String requestId,
			Long noticeId, String warehouseCode, String requestPayload, String requestFingerprint,
			String status, String resultPayload, LocalDateTime completedTime, String cancellationReason) {
	}
}
