package com.kitex.mom.wms.repository;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.Ended;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionDecisionDTO.Query;
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

	/**
	 * 同原办理共用申请行锁；取得锁后才读取结果，结束和迟到办理不能同时成功。
	 */
	public void lockApplication(Long tenant, String factory, Long correctionId) {
		if (!Boolean.TRUE.equals(db.queryForObject(
				"SELECT mom_wms.fg_correction_decision_end_ready_v1()", Boolean.class))) {
			throw new CheckedException("成品纠错办理原号保护未就绪，请保留原文并联系管理员");
		}
		var rows = db.query("SELECT id FROM mom_wms.wms_finished_goods_receipt_correction "
						+ "WHERE tenant_id=? AND factory_code=? AND id=? FOR UPDATE",
				(row, index) -> row.getLong("id"), tenant, factory, correctionId);
		if (rows.size() != 1) {
			throw new CheckedException("原红冲申请不存在或范围不一致，请保留原操作核对");
		}
	}

	/**
	 * 仅用于核对未执行请求的岗位边界，不使用今日库存反推原申请人。
	 */
	public String applicant(Long tenant, String factory, Long correctionId) {
		return db.queryForObject("SELECT applicant_code FROM mom_wms.wms_finished_goods_receipt_correction "
				+ "WHERE tenant_id=? AND factory_code=? AND id=?", String.class, tenant, factory, correctionId);
	}

	/**
	 * 按原组织、申请及原号读取独立永久结束凭据，不能把它当作业务执行履历。
	 */
	public Ended ended(Long tenant, String factory, Long correctionId, String requestId) {
		var rows = db.query(
				"SELECT id,tenant_id,factory_code,operator_code,warehouse_code,notice_id,request_id,"
						+ "command_fingerprint,request_payload,reason,ended_time "
						+ "FROM mom_wms.wms_fg_correction_decision_end "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? AND request_id=? LIMIT 2",
				(row, index) -> new Ended(row.getString("id"), row.getString("tenant_id"),
						row.getString("factory_code"), row.getString("operator_code"),
						row.getString("warehouse_code"),
						row.getString("notice_id"), row.getString("request_id"),
						row.getString("command_fingerprint"),
						row.getString("request_payload"), row.getString("reason"),
						row.getObject("ended_time", LocalDateTime.class).toString()),
				tenant, factory, correctionId, requestId);
		if (rows.size() > 1) {
			throw new CheckedException("原办理结束凭据不唯一，请保留原文核对");
		}
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 申请锁内写入；数据库再次核对原范围、原文和唯一性，必须恰好新增一条。
	 */
	public int insertEnd(Query query, Ended end) {
		return db.update("INSERT INTO mom_wms.wms_fg_correction_decision_end "
						+ "(id,tenant_id,factory_code,correction_id,notice_id,warehouse_code,action,request_id,"
						+ "operator_code,command_fingerprint,request_payload,reason,ended_time) "
						+ "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
				Long.valueOf(end.terminationId()), Long.valueOf(end.tenantId()), end.factoryCode(),
				query.correctionId(), query.noticeId(), end.warehouseCode(), query.action(), query.requestId(),
				end.operatorCode(), end.commandFingerprint(), end.requestPayload(), end.reason(),
				LocalDateTime.parse(end.endedTime()));
	}

	public List<Application> applications(Long tenant, String factory, Long correctionId) {
		return db.query("SELECT id,notice_id,correction_no,status,del_flag,stock_line_count "
						+ "FROM mom_wms.wms_finished_goods_receipt_correction "
						+ "WHERE tenant_id=? AND factory_code=? AND id=? LIMIT 2", (row, index) ->
						new Application(row.getLong("id"), row.getLong("notice_id"), row.getString("correction_no"),
								row.getString("status"), row.getString("del_flag"),
								row.getObject("stock_line_count", Integer.class)),
				tenant, factory, correctionId);
	}

	public List<Line> lines(Long tenant, String factory, Long correctionId) {
		return db.query(
				"SELECT warehouse_code,del_flag FROM mom_wms.wms_finished_goods_receipt_correction_detail "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? LIMIT 10001",
				(row, index) ->
						new Line(row.getString("warehouse_code"), row.getString("del_flag")), tenant, factory,
				correctionId);
	}

	public List<Operation> operations(Long tenant, String factory, Long correctionId, String requestId) {
		return db.query("SELECT command_fingerprint,operation_type,before_status,after_status,operator_code,"
						+ "operation_time,del_flag FROM mom_wms.wms_finished_goods_receipt_correction_operation "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? AND command_id=? LIMIT 2",
				(row, index) ->
						new Operation(row.getString("command_fingerprint"), row.getString("operation_type"),
								row.getString("before_status"), row.getString("after_status"),
								row.getString("operator_code"),
								row.getObject("operation_time", LocalDateTime.class),
								row.getString("del_flag")),
				tenant, factory, correctionId, requestId);
	}

	public record Application(Long id, Long noticeId, String number, String status, String deleted,
	                          Integer lineCount) {
	}

	public record Line(String warehouse, String deleted) {
	}

	public record Operation(String fingerprint, String type, String before, String after, String operator,
	                        LocalDateTime time, String deleted) {
	}
}
