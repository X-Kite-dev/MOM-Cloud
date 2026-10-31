package com.kitex.mom.wms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.wms.entity.WmsTransferReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDateTime;

@Mapper
public interface WmsTransferReceiptMapper extends MomBaseMapper<WmsTransferReceiptEntity> {
	/** 原回执包含删除历史，不能把看不到的已执行记录解释为未执行。 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	@Select("SELECT id,tenant_id AS tenantId,request_id AS requestId,allocation_bill_id AS allocationBillId,"
			+ "allocation_bill_no AS allocationBillNo,factory_code AS factoryCode,receiver_code AS receiverCode,"
			+ "receipt_no AS receiptNo,receipt_status AS receiptStatus,request_snapshot AS requestSnapshot,"
			+ "receipt_time AS receiptTime,variance_case_id AS varianceCaseId,final_receipt AS finalReceipt,line_count AS lineCount "
			+ "FROM mom_wms.wms_transfer_receipt WHERE tenant_id=#{tenant} AND request_id=#{requestId}")
	WmsTransferReceiptEntity findOriginal(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	@Select("SELECT tenant_id AS tenantId,request_id AS requestId,factory_code AS factoryCode,operator_code AS operatorCode,"
			+ "warehouse_code AS warehouseCode,allocation_bill_id AS allocationBillId,request_snapshot AS requestSnapshot,"
			+ "cancellation_reason AS reason,completed_time AS completedTime FROM mom_wms.wms_mobile_transfer_cancel_receipt "
			+ "WHERE tenant_id=#{tenant} AND request_id=#{requestId}")
	Cancellation cancelled(@Param("tenant") Long tenant, @Param("requestId") String requestId);

	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Insert("INSERT INTO mom_wms.wms_mobile_transfer_cancel_receipt (tenant_id,request_id,factory_code,operator_code,"
			+ "warehouse_code,allocation_bill_id,request_snapshot,cancellation_reason,completed_time) "
			+ "VALUES(#{row.tenantId},#{row.requestId},#{row.factoryCode},#{row.operatorCode},#{row.warehouseCode},"
			+ "#{row.allocationBillId},#{row.requestSnapshot},#{row.reason},#{row.completedTime})")
	int insertCancellation(@Param("row") Cancellation row);

	record Cancellation(Long tenantId, String requestId, String factoryCode, String operatorCode, String warehouseCode,
			Long allocationBillId, String requestSnapshot, String reason, LocalDateTime completedTime) {
	}
}
