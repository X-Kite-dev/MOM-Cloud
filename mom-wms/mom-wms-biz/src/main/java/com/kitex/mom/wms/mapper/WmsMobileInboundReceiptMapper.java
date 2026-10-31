package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 回执不提供通用增删接口，租户范围由实际认证上下文显式传入。
 */
@Mapper
public interface WmsMobileInboundReceiptMapper {
	String COLUMNS = "tenant_id AS tenantId, factory_code AS factoryCode, operator_code AS operatorCode, "
			+ "request_id AS requestId, notice_id AS noticeId, notice_detail_id AS noticeDetailId, "
			+ "warehouse_code AS warehouseCode, bill_type AS billType, request_payload AS requestPayload, "
			+ "request_fingerprint AS requestFingerprint, status, bill_no AS billNo, "
			+ "requested_quantity AS requestedQuantity, completed_time AS completedTime, "
			+ "cancellation_reason AS cancellationReason";

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_mobile_inbound_receipt "
			+ "(tenant_id,factory_code,operator_code,request_id,notice_id,notice_detail_id,warehouse_code,"
			+ "bill_type,request_payload,request_fingerprint,requested_quantity,status) VALUES "
			+ "(#{row.tenantId},#{row.factoryCode},#{row.operatorCode},#{row.requestId},#{row.noticeId},"
			+ "#{row.noticeDetailId},#{row.warehouseCode},#{row.billType},#{row.requestPayload},"
			+ "#{row.requestFingerprint},#{row.requestedQuantity},'PENDING') ON CONFLICT DO NOTHING")
	int reserve(@Param("row") Receipt row);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_mobile_inbound_receipt "
			+ "WHERE tenant_id=#{tenant} AND request_id=#{requestId}")
	Receipt find(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("SELECT " + COLUMNS + " FROM mom_wms.wms_mobile_inbound_receipt "
			+ "WHERE tenant_id=#{tenant} AND request_id=#{requestId} FOR UPDATE")
	Receipt findForUpdate(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("UPDATE mom_wms.wms_mobile_inbound_receipt SET status=#{status}, bill_no=#{billNo}, "
			+ "completed_time=#{time}, cancellation_reason=#{reason} WHERE tenant_id=#{tenant} "
			+ "AND factory_code=#{factory} AND operator_code=#{operator} AND request_id=#{requestId} "
			+ "AND request_fingerprint=#{fingerprint} AND status='PENDING'")
	int complete(@Param("tenant") Long tenant, @Param("factory") String factory,
	             @Param("operator") String operator, @Param("requestId") String requestId,
	             @Param("fingerprint") String fingerprint, @Param("status") String status,
	             @Param("billNo") String billNo, @Param("time") LocalDateTime time, @Param("reason") String reason);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("<script>SELECT " + COLUMNS + " FROM mom_wms.wms_mobile_inbound_receipt "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND operator_code=#{operator} "
			+ "AND warehouse_code=#{warehouse} AND bill_type=#{billType} "
			+ "AND status IN ('CONFIRMED','CANCELLED') AND completed_time BETWEEN #{from} AND #{through} "
			+ "<if test='reference.length() > 0'>"
			+ "AND (request_id=#{reference} OR bill_no=#{reference}) </if>"
			+ "<if test='status.length() > 0'>AND status=#{status} </if>"
			+ "ORDER BY completed_time DESC, request_id DESC LIMIT 21 OFFSET #{offset}</script>")
	List<Receipt> history(@Param("tenant") Long tenant, @Param("factory") String factory,
	                      @Param("operator") String operator, @Param("warehouse") String warehouse,
	                      @Param("billType") String billType, @Param("reference") String reference,
	                      @Param("status") String status, @Param("from") LocalDateTime from,
	                      @Param("through") LocalDateTime through, @Param("offset") int offset);

	public record Receipt(Long tenantId, String factoryCode, String operatorCode, String requestId,
	                      Long noticeId, Long noticeDetailId, String warehouseCode, String billType,
	                      String requestPayload, String requestFingerprint, String status, String billNo,
	                      BigDecimal requestedQuantity, LocalDateTime completedTime, String cancellationReason) {
	}
}
