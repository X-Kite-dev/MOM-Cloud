package com.kitex.mom.wms.service.impl.externalinventory;

import com.kitex.mom.wms.dto.WmsExternalInventoryLossDTO.*;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * 租户原号互斥及永久账本，APPLIED只引用数据库源触发器产生的同事务事件。
 */
@Repository
@RequiredArgsConstructor
public class ExternalLossStore {
	final JdbcTemplate jdbc;
	private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
			.appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true).toFormatter();

	static String str(Map<String, Object> r, String k) {
		Object v = r.get(k);
		if (v instanceof java.sql.Timestamp ts) {
			return TIME.format(ts.toLocalDateTime());
		}
		if (v instanceof LocalDateTime ts) {
			return TIME.format(ts);
		}
		return Objects.toString(v, null);
	}

	Map<String, Object> one(String query, Object... args) {
		var rows = jdbc.queryForList(query, args);
		require(rows.size() == 1, "业务原件缺失或不唯一");
		return rows.get(0);
	}

	/**
	 * 仅首次业务或结束前检查403及其封签依赖，查历史不依赖今日业务准入。
	 */
	void ready() {
		require(Boolean.TRUE.equals(
						jdbc.queryForObject("select mom_wms.inventory_external_loss_ready_403()", Boolean.class)),
				"请安装并核对V4第403节外部盘亏保护");
	}

	/**
	 * 取租户加原号事务锁，以串行化同号执行与永久结束。
	 */
	void commandLock(ExternalLossCodec.Parsed p) {
		jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",
				"WMS_EXTERNAL_LOSS|" + p.scope().tenantId() + "|" + p.envelope().requestId());
	}

	/**
	 * 复用359所有实物增删改共同持有的仓级事务锁，锁后另发SQL刷新RC快照。
	 */
	void warehouseLock(DetailScope s) {
		require(Boolean.TRUE.equals(
						jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class,
								"WMS_INVENTORY_FREEZE|" + s.tenantId() + "|" + s.factoryCode() + "|" + s.warehouseCode())),
				"当前仓业务忙，请按原号重试");
	}

	/**
	 * 设置本事务三个独立意向键，真实成功仍须实际源AFTER事件。
	 */
	void intent(String request, String action, String actor) {
		one("""
				select set_config('mom_wms.external_loss_request',?,true),
				       set_config('mom_wms.external_loss_action',?,true),
				       set_config('mom_wms.external_loss_actor',?,true)
				""", request, action, actor);
	}

	/**
	 * 逐字核同号命令与实际组织人员，返回原时点终态；查询失败不得冒充ABSENT。
	 */
	Resolution<Object> find(ExternalLossCodec.Parsed p) {
		var s = p.scope();
		var rows = jdbc.queryForList("""
				select r.*,e.source_id,e.result_status,e.action_time from mom_wms.wms_external_loss_request r
				left join mom_wms.wms_external_loss_effect e on e.id=r.effect_id and e.tenant_id=r.tenant_id and e.request_id=r.request_id
				where r.tenant_id=? and r.request_id=?
				""", Long.valueOf(s.tenantId()), p.envelope().requestId());
		if (rows.isEmpty()) {
			return new Resolution<>(1, p.domain(), "ABSENT", p.wireScope(), p.envelope().requestId(), p.action(), null,
					null, null, null, null, null);
		}
		require(rows.size() == 1, "永久原号账不唯一");
		var r = rows.get(0);
		require(s.actorId().equals(str(r, "actor_id")) && s.factoryCode().equals(str(r, "factory_code"))
						&& s.warehouseCode().equals(str(r, "warehouse_code")) && Objects.equals(s.planId(), str(r, "plan_id"))
						&& Objects.equals(s.planDetailId(), str(r, "plan_detail_id")) && p.domain().equals(str(r, "domain"))
						&& p.action().equals(str(r, "action")) && p.envelope().commandText().equals(str(r, "command_text")),
				"原号已绑定不同内容或范围");
		String status = str(r, "status");
		require(Set.of("APPLIED", "ENDED").contains(status) && r.get("actor_code") != null &&
				r.get("recorded_at") != null, "永久回执损坏");
		for (String k : List.of("effect_id", "source_id", "result_status", "action_time")) {
			require((r.get(k) != null) == "APPLIED".equals(status), "永久回执与实际事件不匹配");
		}
		return new Resolution<>(1, p.domain(), status, p.wireScope(), p.envelope().requestId(), p.action(),
				str(r, "effect_id"), str(r, "source_id"),
				str(r, "result_status"), str(r, "actor_code"), str(r, "action_time"), str(r, "recorded_at"));
	}

	/**
	 * 按当前顶层事务、源主键和实际操作者回读事件后写APPLIED；END仅落永久号码墓碑。
	 */
	void save(ExternalLossCodec.Parsed p, String actorCode, Long sourceId) {
		var s = p.scope();
		Object effectId = null;
		if (sourceId != null) {
			var effect = one("""
							select id from mom_wms.wms_external_loss_effect where tenant_id=? and factory_code=? and warehouse_code=?
							and plan_id is not distinct from ?::bigint and plan_detail_id is not distinct from ?::bigint
							and request_id=? and domain=? and action=? and actor_id=? and actor_code=? and source_id=?
							and transaction_id=pg_current_xact_id()
							""", Long.valueOf(s.tenantId()), s.factoryCode(), s.warehouseCode(), s.planId(), s.planDetailId(),
					p.envelope().requestId(), p.domain(), p.action(), Long.valueOf(s.actorId()), actorCode, sourceId);
			effectId = effect.get("id");
		}
		require(jdbc.update("""
								insert into mom_wms.wms_external_loss_request
								(tenant_id,factory_code,warehouse_code,plan_id,plan_detail_id,request_id,domain,action,command_text,actor_id,actor_code,status,effect_id)
								values (?,?,?,?::bigint,?::bigint,?,?,?,?,?,?,?,?)
								""", Long.valueOf(s.tenantId()), s.factoryCode(), s.warehouseCode(), s.planId(), s.planDetailId(),
						p.envelope().requestId(),
						p.domain(), p.action(), p.envelope().commandText(), Long.valueOf(s.actorId()), actorCode,
						sourceId == null ? "ENDED" : "APPLIED", effectId) == 1,
				"永久回执保存失败，业务整体回滚");
	}
}
