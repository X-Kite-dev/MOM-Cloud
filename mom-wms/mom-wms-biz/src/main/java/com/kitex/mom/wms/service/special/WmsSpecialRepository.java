package com.kitex.mom.wms.service.special;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.wms.dto.WmsSpecialMovementDTO.*;
import com.kitex.mom.wms.service.special.WmsSpecialAccess.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 特殊收发的冻结原件、规则版本和永久操作凭据持久化；实际库存仍由正式收发引擎处理。
 */
@Repository
@RequiredArgsConstructor
public class WmsSpecialRepository {
	private final JdbcTemplate db;
	private final ObjectMapper json = new ObjectMapper();
	private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern(
			"uuuu-MM-dd'T'HH:mm:ss.SSSSSS", Locale.ROOT);

	/**
	 * @param receipt      原JSON中的永久办理凭据，不追加派生字段影响回执相等性
	 * @param registeredAt 原操作表实际created_at，保留本地时间及微秒，不转换为终端时区
	 */
	public record RegisteredOperation(Receipt receipt, String registeredAt) {
	}

	private void lock(String key) {
		require(Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) c -> !c.getAutoCommit())) &&
						"read committed".equals(
								db.queryForObject("SELECT current_setting('transaction_isolation')", String.class)),
				"特殊收发必须在实际READ_COMMITTED事务内办理");
		db.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))", Integer.class, key);
	}

	public void lockRequest(Scope scope, String request) {
		lock("WMS_SPECIAL_REQUEST:" + scope.tenant() + ":" + request);
	}

	public void lockWarehouse(Scope scope) {
		lock("WMS_SPECIAL_WAREHOUSE:" + scope.tenant() + ":" + scope.factory() + ":" + scope.warehouse());
	}

	public String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("特殊收发原文无法编码", e);
		}
	}

	public <T> T decode(String value, Class<T> type) {
		try {
			return json.readValue(value, type);
		} catch (Exception e) {
			throw new IllegalStateException("特殊收发持久凭据不完整", e);
		}
	}

	public List<Rule> defaultRules() {
		return Arrays.stream(Kind.values()).map(k -> new Rule(k, false, true, true, true)).toList();
	}

	public PolicySnapshot policy(Scope scope) {
		var rows = db.query("SELECT version,rules_json::text FROM mom_wms.wms_special_policy WHERE " +
				"tenant_id=? AND factory_code=? AND warehouse_code=?", (r, n) -> {
			try {
				return new PolicySnapshot(1, scope.tenant().toString(), scope.factory(), scope.warehouse(),
						r.getString(1), json.readValue(r.getString(2), new TypeReference<List<Rule>>() {
				}));
			} catch (Exception e) {
				throw new IllegalStateException("特殊收发规则无法完整读取", e);
			}
		}, scope.tenant(), scope.factory(), scope.warehouse());
		require(rows.size() <= 1, "仓库特殊收发规则不唯一");
		return rows.isEmpty() ? new PolicySnapshot(1, scope.tenant().toString(), scope.factory(),
				scope.warehouse(),
				"0", defaultRules()) : rows.get(0);
	}

	public PolicySnapshot savePolicy(Scope scope, String version, List<Rule> rules) {
		int count;
		if ("0".equals(version)) {
			count = db.update("INSERT INTO mom_wms.wms_special_policy(tenant_id,factory_code," +
							"warehouse_code,version,rules_json,updated_by) VALUES(?,?,?,1,?::jsonb,?) ON " +
							"CONFLICT DO NOTHING", scope.tenant(), scope.factory(), scope.warehouse(), encode(rules),
					scope.operator());
		} else {
			count = db.update("UPDATE mom_wms.wms_special_policy SET version=version+1,rules_json=?::jsonb," +
							"updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND " +
							"factory_code=? AND warehouse_code=? AND version=?", encode(rules), scope.operator(),
					scope.tenant(), scope.factory(), scope.warehouse(), id(version));
		}
		require(count == 1, "规则已更新，请重新读取后核对修改");
		return policy(scope);
	}

	public OrderDocument order(Scope scope, String orderId) {
		id(orderId);
		var rows = db.query("SELECT * FROM mom_wms.wms_special_order WHERE id=? AND tenant_id=? AND " +
						"factory_code=? AND warehouse_code=?",
				(r, n) -> new OrderDocument(r.getString("id"), r.getString("tenant_id"),
						r.getString("factory_code"),
						r.getString("warehouse_code"), decode(r.getString("plan_json"), Plan.class),
						decode(r.getString("rule_json"), Rule.class), r.getString("policy_version"),
						r.getString("state"), r.getString("version"), r.getString("created_by"),
						r.getString("reviewed_by"), r.getTimestamp("created_at").toLocalDateTime().toString()),
				id(orderId), scope.tenant(), scope.factory(), scope.warehouse());
		require(rows.size() <= 1, "特殊收发申请不唯一");
		return rows.isEmpty() ? null : rows.get(0);
	}

	public OrderDocument insertOrder(Scope scope, Plan plan, Rule rule, String policyVersion) {
		String orderId = Long.toString(IdUtil.getSnowflakeNextId());
		require(db.update("INSERT INTO mom_wms.wms_special_order(id,tenant_id,factory_code," +
								"warehouse_code,kind,direction,plan_json,rule_json,policy_version,state," +
								"version,created_by) " +
								"VALUES(?,?,?,?,?,?,?::jsonb,?::jsonb,?,'PENDING',1,?)", id(orderId),
						scope.tenant(), scope.factory(), scope.warehouse(), plan.kind().name(), plan.direction().name(),
						encode(plan), encode(rule), id(policyVersion), scope.operator()) == 1,
				"特殊收发申请保存未确认");
		var actual = order(scope, orderId);
		require(actual != null && actual.plan().equals(plan) && actual.rule().equals(rule) &&
						actual.policyVersion().equals(policyVersion) && actual.createdBy().equals(scope.operator()) &&
						"PENDING".equals(actual.state()) && "1".equals(actual.version()),
				"申请实际保存内容不一致，已回滚");
		return actual;
	}

	public OrderDocument advance(Scope scope, String orderId, String version, String state, String reviewer) {
		require(db.update("UPDATE mom_wms.wms_special_order SET state=?,reviewed_by=?," +
						"version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? " +
						"AND factory_code=? AND warehouse_code=? AND version=?", state, reviewer, id(orderId),
				scope.tenant(),
				scope.factory(), scope.warehouse(), id(version)) == 1, "申请已变化，请刷新原单后办理");
		return order(scope, orderId);
	}

	public Receipt operation(Scope scope, String requestId) {
		var rows = db.query("SELECT result_json::text FROM mom_wms.wms_special_operation WHERE " +
						"tenant_id=? AND request_id=?", (r, n) -> decode(r.getString(1), Receipt.class), scope.tenant(),
				requestId);
		require(rows.size() <= 1, "原请求存在多份凭据");
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * @param scope   原实际组织与仓库
	 * @param orderId 原申请身份
	 * @return 已核原记录及登记时间的完整回执集合；不改变原JSON参与幂等回读
	 */
	public List<Receipt> operations(Scope scope, String orderId) {
		return registeredOperations(scope, orderId).stream().map(RegisteredOperation::receipt).toList();
	}

	/**
	 * 同一读取取得原凭据与实际登记时间，防止按版本、终端当前时间或首次入库时间补造履历。
	 *
	 * @param scope   已获准读取的实际租户、工厂与仓库
	 * @param orderId 原申请身份；完整记录超过10000条时明确拒绝，不截成看似完整的时间轴
	 * @return 按永久记录ID顺序排列的原凭据及登记时间
	 */
	public List<RegisteredOperation> registeredOperations(Scope scope, String orderId) {
		var rows = db.query("SELECT request_id,result_json::text,created_at " +
						"FROM mom_wms.wms_special_operation WHERE " +
						"tenant_id=? AND factory_code=? AND warehouse_code=? AND order_id=? ORDER BY " +
						"id LIMIT 10001", (r, n) -> {
					var receipt = decode(r.getString(2), Receipt.class);
					// timestamp without time zone直接读取本地时间，避免经JVM默认时区转换历史登记值。
					var registeredAt = r.getObject(3, LocalDateTime.class);
					require(receipt != null && Objects.equals(receipt.requestId(), r.getString(1)) &&
							registeredAt != null, "永久操作凭据或登记时间不完整，请核对原记录");
					return new RegisteredOperation(receipt, registeredAt.format(AUDIT_TIME));
				}, scope.tenant(),
				scope.factory(),
				scope.warehouse(), id(orderId));
		require(rows.size() <= 10000, "单据作业记录超过完整读取上限，请联系管理员核对");
		return rows;
	}

	public void insertOperation(Scope scope, Command command, Receipt receipt) {
		boolean physical = receipt.billId() != null;
		boolean inbound = physical && receipt.flows().get(0).direction() == Direction.IN;
		require(db.update("INSERT INTO mom_wms.wms_special_operation(id,tenant_id,request_id," +
						"factory_code,warehouse_code,actor,action,command_json,status,order_id," +
						"in_bill_id,out_bill_id,end_reason,result_json) VALUES(?,?,?,?,?,?,?," +
						"?::jsonb,?,?,?,?,?,?::jsonb)", IdUtil.getSnowflakeNextId(), scope.tenant(),
				command.requestId(),
				scope.factory(), scope.warehouse(), scope.operator(), command.action().name(), encode(command),
				receipt.status(), "REGISTERED".equals(receipt.status()) ? id(receipt.orderId()) : null,
				inbound ? id(receipt.billId()) : null, physical && !inbound ? id(receipt.billId()) : null,
				receipt.endReason(), encode(receipt)) == 1, "永久办理凭据保存未确认");
		require(receipt.equals(operation(scope, command.requestId())), "永久办理凭据回读不一致，整笔操作已回滚");
	}

	public List<String> list(Scope scope, String kind, String state, String reference, int page, int size) {
		return db.query("SELECT id FROM mom_wms.wms_special_order WHERE tenant_id=? AND " +
						"factory_code=? AND warehouse_code=? AND (?='' OR kind=?) AND (?='' OR " +
						"state=?) AND (?='' OR plan_json->>'reference' ILIKE ?) ORDER BY id DESC " +
						"LIMIT ? OFFSET ?", (r, n) -> r.getString(1), scope.tenant(), scope.factory(),
				scope.warehouse(), kind,
				kind, state, state, reference, "%" + reference + "%", size, (page - 1) * size);
	}

	public long count(Scope scope, String kind, String state, String reference) {
		return Objects.requireNonNull(
				db.queryForObject("SELECT count(*) FROM mom_wms.wms_special_order WHERE tenant_id=? AND " +
								"factory_code=? AND warehouse_code=? AND (?='' OR kind=?) AND (?='' OR " +
								"state=?) AND (?='' OR plan_json->>'reference' ILIKE ?)", Long.class, scope.tenant(),
						scope.factory(), scope.warehouse(), kind, kind, state, state, reference,
						"%" + reference + "%"));
	}
}
