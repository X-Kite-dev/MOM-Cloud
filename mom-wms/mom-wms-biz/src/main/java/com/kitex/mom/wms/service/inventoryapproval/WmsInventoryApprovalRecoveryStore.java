package com.kitex.mom.wms.service.inventoryapproval;

import com.kitex.mom.wms.dto.WmsInventoryApprovalRecoveryDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 仅写永久请求终态；真实effect由原审批表AFTER迁移产生，不允许Java伪造。
 */
@Repository
@RequiredArgsConstructor
public class WmsInventoryApprovalRecoveryStore {
	private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true).toFormatter();
	private final JdbcTemplate jdbc;

	/**
	 * 缺章或漂移只拒绝新增终态，历史原件查询不降级为ABSENT。
	 */
	public void ready() {
		require(Boolean.TRUE.equals(
						jdbc.queryForObject("select mom_wms.inventory_approval_recovery_ready_402()", Boolean.class)),
				"盘点审批原号保护未就绪，请安装并核对V4第402节");
	}

	/**
	 * 请求锁必须先于原access.detail内部的计划、明细与审批锁。
	 */
	public void lock(Scope s, String request) {
		jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",
				"WMS_INVENTORY_APPROVAL:" + s.tenantId() + ":" + request);
	}

	/**
	 * 租户唯一原号，不用工厂过滤隐藏跨厂冲突。
	 */
	public Map<String, Object> find(Scope s, String request) {
		var rows = jdbc.queryForList(
				"select * from mom_wms.wms_inventory_approval_request where tenant_id=? and request_id=?",
				Long.valueOf(s.tenantId()), request);
		require(rows.size() <= 1, "原号账重复");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 事务局部意向只允许一次原业务调用；空值用于调用后清理，不能替代effect证据。
	 */
	public void intent(String request, String action, String actor) {
		String[] keys = {"request", "action", "actor"};
		String[] values = {request, action, actor};
		for (int index = 0; index < keys.length; index++) {
			String key = "mom_wms.inventory_approval_" + keys[index];
			require(values[index].equals(
							jdbc.queryForObject("select set_config(?,?,true)", String.class, key, values[index])),
					"审批事务意向设置失败");
		}
	}

	/**
	 * 原事件必须DB回读，以微秒值冻结，避免Java纳秒和PG落地差异。
	 */
	public Map<String, Object> approval(Scope s, Long id) {
		var rows = jdbc.queryForList(
				"select * from mom_wms.wms_inventory_adjustment_approval where tenant_id=? and factory_code=? and id=? and warehouse_code=? and plan_id=? and plan_detail_id=?",
				Long.valueOf(s.tenantId()), s.factoryCode(), id, s.warehouseCode(), Long.valueOf(s.planId()),
				Long.valueOf(s.planDetailId()));
		require(rows.size() == 1, "真实审批事件与原号范围不一致");
		return rows.get(0);
	}

	/**
	 * 精确绑定逐字原文及全部组织对象，绝不把同号异载荷显示为成功。
	 */
	public Resolution resolution(Map<String, Object> row, Envelope e, Command c) {
		if (row == null) {
			return new Resolution(1, "ABSENT", c.scope(), e.requestId(), c.action(), null, null, null, null, null,
					null);
		}
		Scope s = c.scope();
		require(Objects.equals(str(row, "command_text"), e.commandText()) &&
						Objects.equals(str(row, "action"), c.action())
						&& Objects.equals(str(row, "actor_id"), s.actorId()) &&
						Objects.equals(str(row, "tenant_id"), s.tenantId())
						&& Objects.equals(str(row, "factory_code"), s.factoryCode()) &&
						Objects.equals(str(row, "warehouse_code"), s.warehouseCode())
						&& Objects.equals(str(row, "plan_id"), s.planId()) &&
						Objects.equals(str(row, "plan_detail_id"), s.planDetailId())
						&& Objects.equals(str(row, "request_id"), e.requestId()) && "1".equals(str(row, "contract_version")),
				"原号已绑定其他人员或内容");
		String status = str(row, "status");
		require(List.of("APPLIED", "ENDED").contains(status) && row.get("recorded_at") != null
				&& row.get("actor_code") != null, "原永久回执损坏");
		boolean applied = "APPLIED".equals(status);
		require(applied ?
						row.get("approval_id") != null && row.get("result_status") != null && row.get("action_time") != null
						: row.get("approval_id") == null && row.get("result_status") == null && row.get("action_time") == null,
				"原永久回执字段互斥无效");
		if (applied) {
			require(result(c.action()).equals(str(row, "result_status")), "原永久事件状态映射无效");
		}
		return new Resolution(1, status, s, e.requestId(), c.action(), str(row, "approval_id"),
				str(row, "result_status"),
				str(row, "actor_id"), str(row, "actor_code"), time(row.get("action_time")),
				time(row.get("recorded_at")));
	}

	/**
	 * 同事务一次插入APPLIED或ENDED；DB双向约束保证真实effect与APPLIED闭合。
	 */
	public void save(Envelope e, Command c, String actorCode, Map<String, Object> event) {
		Scope s = c.scope();
		String actorKey = "SUBMIT".equals(c.action()) ? "applicant_code" : "REVOKE".equals(
				c.action()) ? "revoked_by" : "reviewer_code";
		String timeKey = "SUBMIT".equals(c.action()) ? "submitted_time" : "REVOKE".equals(
				c.action()) ? "revoked_time" : "reviewed_time";
		if (event != null) {
			require(actorCode.equals(str(event, actorKey)) && result(c.action()).equals(str(event, "status")) &&
					event.get(timeKey) != null, "原正式服务事件回读不一致");
		}
		require(jdbc.update(
						"insert into mom_wms.wms_inventory_approval_request(tenant_id,request_id,contract_version,actor_id,actor_code,factory_code,warehouse_code,plan_id,plan_detail_id,action,command_text,status,approval_id,result_status,action_time) values(?,?,1,?,?,?,?,?,?,?,?,?,?,?,?)",
						Long.valueOf(s.tenantId()), e.requestId(), Long.valueOf(s.actorId()), actorCode, s.factoryCode(),
						s.warehouseCode(), Long.valueOf(s.planId()),
						Long.valueOf(s.planDetailId()), c.action(), e.commandText(), event == null ? "ENDED" : "APPLIED",
						event == null ? null : event.get("id"),
						event == null ? null : result(c.action()), event == null ? null : event.get(timeKey)) == 1,
				"审批永久回执保存失败");
	}

	/**
	 * 动作对应原时点状态，不读取今天最新状态推断旧成功。
	 */
	public static String result(String action) {
		return switch (action) {
			case "SUBMIT" -> "PENDING";
			case "APPROVE" -> "APPROVED";
			case "REJECT" -> "REJECTED";
			case "REVOKE" -> "REVOKED";
			default -> throw new IllegalArgumentException("无效审批动作");
		};
	}

	/**
	 * 数据库文本投影，null保持null。
	 */
	public static String str(Map<String, Object> row, String key) {
		return row.get(key) == null ? null : row.get(key).toString();
	}

	private String time(Object value) {
		if (value == null) {
			return null;
		}
		LocalDateTime t = value instanceof Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) value;
		return TIME.format(t);
	}
}
