package com.kitex.mom.wms.repository;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsCorrectionTerminationDTO.Ended;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 成品纠错申请与办理的原号结束状态；组织范围显式限定，不改写业务申请。
 */
@Repository
@RequiredArgsConstructor
public class WmsCorrectionTerminationRepository {
	private final JdbcTemplate db;

	/**
	 * 原申请锁内核对办理原号，结束先提交时阻止迟到命令再次执行。
	 */
	public void requireDecisionOpen(Long tenant, String factory, Long correctionId, String requestId) {
		if (decisionEnded(tenant, factory, correctionId, requestId)) {
			throw new CheckedException("原办理请求已永久结束，请查询原结束结果");
		}
	}

	/**
	 * 仅返回指定申请原号是否结束，供执行守卫和原申请详情展示；不泄露结束原文。
	 */
	public boolean decisionEnded(Long tenant, String factory, Long correctionId, String requestId) {
		if (!Boolean.TRUE.equals(db.queryForObject(
				"SELECT mom_wms.fg_correction_decision_end_ready_v1()", Boolean.class))) {
			throw new CheckedException("成品纠错办理原号保护未就绪，请保留原文并联系管理员");
		}
		return Boolean.TRUE.equals(db.queryForObject(
				"SELECT EXISTS(SELECT 1 FROM mom_wms.wms_fg_correction_decision_end "
						+ "WHERE tenant_id=? AND factory_code=? AND correction_id=? AND request_id=?)",
				Boolean.class, tenant, factory, correctionId, requestId));
	}

	public void lock(Long tenant, String factory, String request) {
		db.queryForObject(
				"SELECT 1 FROM pg_advisory_xact_lock(hashtextextended("
						+ "CONCAT('WMS_FG_CORRECTION_REQUEST:',?,':',?,':',?),0))",
				Integer.class, tenant, factory, request);
	}

	public Ended find(Long tenant, String factory, String request) {
		var rows = db.query(
				"SELECT id,tenant_id,factory_code,operator_code,warehouse_code,notice_id,request_id,"
						+ "command_fingerprint,request_payload,reason,ended_time "
						+ "FROM mom_wms.wms_fg_correction_request_state "
						+ "WHERE tenant_id=? AND factory_code=? AND request_id=? AND state='ENDED' LIMIT 2",
				(r, n) -> new Ended(r.getString("id"),
						r.getString("tenant_id"), r.getString("factory_code"), r.getString("operator_code"),
						r.getString("warehouse_code"),
						r.getString("notice_id"), r.getString("request_id"), r.getString("command_fingerprint"),
						r.getString("request_payload"),
						r.getString("reason"),
						r.getObject("ended_time", LocalDateTime.class)
								.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)),
				tenant,
				factory, request);
		if (rows.size() > 1) {
			throw new CheckedException("原请求结束凭据不唯一，请保留原文核对");
		}
		return rows.isEmpty() ? null : rows.get(0);
	}

	public String sourceWarehouse(Long tenant, String factory, Long notice) {
		var rows = db.query(
				"SELECT warehouse_code FROM mom_wms.wms_in_notice WHERE tenant_id=? AND factory_code=? AND id=?",
				(r, n) -> r.getString("warehouse_code"), tenant, factory, notice);
		if (rows.size() != 1) {
			throw new CheckedException("原通知范围无法核实，请保留原请求核对");
		}
		return rows.get(0);
	}

	public int insert(Ended row) {
		return db.update(
				"INSERT INTO mom_wms.wms_fg_correction_request_state(id,tenant_id,factory_code,operator_code,"
						+
						"warehouse_code,notice_id,request_id,command_fingerprint,"
						+ "request_payload,reason,ended_time,state) "
						+ "VALUES(?,?,?,?,?,?,?,?,?,?,?,'ENDED')",
				Long.valueOf(row.terminationId()), Long.valueOf(row.tenantId()), row.factoryCode(),
				row.operatorCode(),
				row.warehouseCode(),
				Long.valueOf(row.noticeId()), row.requestId(), row.commandFingerprint(), row.requestPayload(),
				row.reason(), LocalDateTime.parse(row.endedTime()));
	}
}
