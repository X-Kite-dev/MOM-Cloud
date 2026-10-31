package com.kitex.mom.wms.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 原操作查询不依赖今日库存；每次显式限定真实租户与工厂。
 */
@Repository
@RequiredArgsConstructor
public class WmsFinishedGoodsCorrectionDecisionRepository {

	private final JdbcTemplate db;

	public List<Application> applications(Long tenant, String factory, Long correctionId) {
		return db.query("SELECT id,notice_id,correction_no,status,del_flag,stock_line_count "
						+ "FROM mom_wms.wms_finished_goods_receipt_correction "
						+ "WHERE tenant_id=? AND factory_code=? AND id=? LIMIT 2", (row, index) ->
						new Application(row.getLong("id"), row.getLong("notice_id"), row.getString("correction_no"),
								row.getString("status"), row.getString("del_flag"), row.getObject("stock_line_count", Integer.class)),
				tenant, factory, correctionId);
	}

	public List<Line> lines(Long tenant, String factory, Long correctionId) {
		return db.query("SELECT warehouse_code,del_flag FROM mom_wms.wms_finished_goods_receipt_correction_detail "
				+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? LIMIT 10001", (row, index) ->
				new Line(row.getString("warehouse_code"), row.getString("del_flag")), tenant, factory, correctionId);
	}

	public List<Operation> operations(Long tenant, String factory, Long correctionId, String requestId) {
		return db.query("SELECT command_fingerprint,operation_type,before_status,after_status,operator_code,"
						+ "operation_time,del_flag FROM mom_wms.wms_finished_goods_receipt_correction_operation "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? AND command_id=? LIMIT 2", (row, index) ->
						new Operation(row.getString("command_fingerprint"), row.getString("operation_type"),
								row.getString("before_status"), row.getString("after_status"), row.getString("operator_code"),
								row.getObject("operation_time", LocalDateTime.class), row.getString("del_flag")),
				tenant, factory, correctionId, requestId);
	}

	public record Application(Long id, Long noticeId, String number, String status, String deleted, Integer lineCount) {
	}

	public record Line(String warehouse, String deleted) {
	}

	public record Operation(String fingerprint, String type, String before, String after, String operator,
	                        LocalDateTime time, String deleted) {
	}
}
