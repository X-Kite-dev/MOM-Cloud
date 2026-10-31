package com.kitex.mom.wms.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 原登记事实独立于今日库存；每次SQL显式限定实际租户和工厂。
 */
@Repository
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionSubmissionRepository {

	private final JdbcTemplate db;

	public List<Application> find(Long tenant, String factory, String requestId) {
		return db.query("SELECT id,notice_id,request_fingerprint,correction_no,status,reason,total_quantity,"
				+ "stock_line_count,applicant_code,applied_time,qms_decision_reversal_id,"
				+ "qms_decision_authorization_code,del_flag FROM mom_wms.wms_finished_goods_receipt_correction "
				+ "WHERE tenant_id=? AND factory_code=? AND request_id=? LIMIT 2", (row, index) ->
				new Application(row.getLong("id"), row.getLong("notice_id"), row.getString("request_fingerprint"),
						row.getString("correction_no"), row.getString("status"), row.getString("reason"),
						row.getBigDecimal("total_quantity"), row.getObject("stock_line_count", Integer.class),
						row.getString("applicant_code"), row.getObject("applied_time", LocalDateTime.class),
						row.getObject("qms_decision_reversal_id", Long.class),
						row.getString("qms_decision_authorization_code"),
						row.getString("del_flag")), tenant, factory, requestId);
	}

	public List<Line> lines(Long tenant, String factory, Long applicationId) {
		return db.query("SELECT stock_id,quantity,warehouse_code,del_flag "
						+ "FROM mom_wms.wms_finished_goods_receipt_correction_detail "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? ORDER BY id LIMIT 1001",
				(row, index) -> new Line(row.getLong("stock_id"), row.getBigDecimal("quantity"),
						row.getString("warehouse_code"), row.getString("del_flag")), tenant, factory, applicationId);
	}

	public List<Operation> operations(Long tenant, String factory, Long applicationId, String requestId) {
		return db.query("SELECT command_fingerprint,operator_code,operation_time,after_status,del_flag "
				+ "FROM mom_wms.wms_finished_goods_receipt_correction_operation "
				+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? AND command_id=? "
				+ "AND operation_type='SUBMIT' LIMIT 2", (row, index) ->
				new Operation(row.getString("command_fingerprint"), row.getString("operator_code"),
						row.getObject("operation_time", LocalDateTime.class), row.getString("after_status"),
						row.getString("del_flag")), tenant, factory, applicationId, requestId);
	}

	public record Application(Long id, Long noticeId, String fingerprint, String number, String status,
	                          String reason, BigDecimal quantity, Integer lineCount, String applicant,
	                          LocalDateTime time,
	                          Long authorizationId, String authorizationCode, String deleted) {
	}

	public record Line(Long stockId, BigDecimal quantity, String warehouse, String deleted) {
	}

	public record Operation(String fingerprint, String operator, LocalDateTime time, String afterStatus,
	                        String deleted) {
	}
}
