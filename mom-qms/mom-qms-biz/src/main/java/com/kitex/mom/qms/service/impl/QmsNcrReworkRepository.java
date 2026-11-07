package com.kitex.mom.qms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 三张永久账中的QMS侧持久化；显式列和组织条件，禁止BaseEntity填充或覆盖原事实。
 */
@Repository
@RequiredArgsConstructor
public class QmsNcrReworkRepository {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	/**
	 * 永久完成事实，原文冻结；仅原人有据作废。
	 *
	 * @param id                永久雪花编号
	 * @param tenantId          实际租户编号
	 * @param factoryCode       实际工厂编码
	 * @param requestId         永久幂等原号
	 * @param ncrId             原NCR主案编号
	 * @param detailId          原分类明细编号
	 * @param proposalId        原已发布MRB编号
	 * @param parentOperationId 原REWORK操作编号
	 * @param deciderId         原MRB实名发布人编号
	 * @param executor          真实完成人身份快照
	 * @param quantity          原基本单位实际完成量
	 * @param unit              由原件证明的冻结基本单位
	 * @param measures          实际返工措施
	 * @param evidence          完成凭证
	 * @param source            锁定原件及身份数量全文
	 * @param state             完成VALID/VOID
	 * @param completedAt       原完成登记时间，微秒精度
	 * @param voidRequestId     作废原号，仅VOID有值
	 * @param voidReason        作废纠错依据，仅VOID有值
	 * @param voidActorId       原完成者实名作废人编号，仅VOID有值
	 * @param voidedAt          作废时间，仅VOID有值
	 */
	public record Completion(long id, long tenantId, String factoryCode, String requestId, long ncrId, long detailId,
	                         long proposalId, long parentOperationId, long deciderId, Actor executor, String quantity,
	                         String unit,
	                         String measures, String evidence, Source source, String state, LocalDateTime completedAt,
	                         String voidRequestId, String voidReason, Long voidActorId, LocalDateTime voidedAt) {
	}

	/**
	 * 永久验证意图，命令冻结；终态只能单向确认。
	 *
	 * @param id                永久雪花编号
	 * @param tenantId          实际租户编号
	 * @param factoryCode       实际工厂编码
	 * @param requestId         永久幂等原号
	 * @param ncrId             原NCR主案编号
	 * @param detailId          原分类明细编号
	 * @param proposalId        原已发布MRB编号
	 * @param parentOperationId 原REWORK操作编号
	 * @param completionId      有效完成编号；未完成REJECT可空
	 * @param reviewerId        冻结独立复核人稳定编号
	 * @param decision          原复核决定PASS/REJECT
	 * @param command           永久冻结完整跨域命令
	 * @param state             PREPARED待核/APPLIED生效/CANCELLED取消
	 * @param preparedAt        意图提交时间，微秒精度
	 * @param receipt           权威终态收据，PREPARED可空
	 * @param finishedAt        WMS原终态事件时间，PREPARED可空
	 */
	public record Intent(long id, long tenantId, String factoryCode, String requestId, long ncrId, long detailId,
	                     long proposalId, long parentOperationId, Long completionId, long reviewerId, String decision,
	                     Command command,
	                     String state, LocalDateTime preparedAt, Receipt receipt, LocalDateTime finishedAt) {
	}

	/**
	 * 新流程必须具备正式安装的约束和ALWAYS保护，不能在半安装库继续写入。
	 */
	public void ready() {
		require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT mom_qms.qms_ncr_rework_ready_v1()", Boolean.class)),
				"仓储返工V1数据库保护未就绪，请安装正式升级章节");
	}

	/**
	 * 按实际组织和明细读取所有版本完成事实；不回填或修改历史。
	 */
	public List<Completion> completions(long tenant, String factory, long detail) {
		return jdbc.query(
				"SELECT * FROM mom_qms.qms_ncr_rework_completion WHERE tenant_id=? AND factory_code=? AND detail_id=? ORDER BY id",
				this::completion, tenant, factory, detail);
	}

	/**
	 * 按实际组织和明细读取所有永久验证意图，包含已取消历史。
	 */
	public List<Intent> intents(long tenant, String factory, long detail) {
		return jdbc.query(
				"SELECT * FROM mom_qms.qms_ncr_rework_verify_intent WHERE tenant_id=? AND factory_code=? AND detail_id=? ORDER BY id",
				this::intent, tenant, factory, detail);
	}

	/**
	 * 按组织及永久请求号查完成事实，用于同号先回账。
	 */
	public Completion completionRequest(long tenant, String factory, String request) {
		var rows = jdbc.query(
				"SELECT * FROM mom_qms.qms_ncr_rework_completion WHERE tenant_id=? AND factory_code=? AND request_id=?",
				this::completion, tenant, factory, request);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 按组织及永久请求号查冻结意图，不能从今日库存推导原结果。
	 */
	public Intent intentRequest(long tenant, String factory, String request) {
		var rows = jdbc.query(
				"SELECT * FROM mom_qms.qms_ncr_rework_verify_intent WHERE tenant_id=? AND factory_code=? AND request_id=?",
				this::intent, tenant, factory, request);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 在detail/header/proposal之后锁永久事实，所有写事务保持相同顺序。
	 */
	public void lockFacts(long tenant, String factory, long detail) {
		jdbc.queryForList(
				"SELECT id FROM mom_qms.qms_ncr_rework_completion WHERE tenant_id=? AND factory_code=? AND detail_id=? ORDER BY id FOR UPDATE",
				tenant, factory, detail);
		jdbc.queryForList(
				"SELECT id FROM mom_qms.qms_ncr_rework_verify_intent WHERE tenant_id=? AND factory_code=? AND detail_id=? ORDER BY id FOR UPDATE",
				tenant, factory, detail);
	}

	/**
	 * 调用方已持有detail锁；所有新流程、MRB和旧重试共享此门禁。
	 */
	public void noActive(long tenant, String factory, long detail, Long parent) {
		ready();
		require(intents(tenant, factory, detail).stream()
				.noneMatch(i -> (parent == null || i.parentOperationId() == parent)
						&& !"CANCELLED".equals(i.state())), "已有待核或已生效返工意图，请按原号核对，不能换案或重登");
	}

	/**
	 * MRB或旧入口持有同detail锁后检查；APPLIED驳回可开新MRB，PREPARED绝不可换版本。
	 */
	public void noPending(long tenant, String factory, long detail) {
		ready();
		require(intents(tenant, factory, detail).stream().noneMatch(i -> "PREPARED".equals(i.state())),
				"存在待核返工验证意图，请先按原号取得权威终态，不能重发MRB或旧处置");
	}

	/**
	 * 显式写入整量实作永久事实；调用方已按统一锁序重核原件。
	 */
	public void insert(Completion c) {
		require(jdbc.update("""
						INSERT INTO mom_qms.qms_ncr_rework_completion
						(id,tenant_id,factory_code,request_id,ncr_id,detail_id,proposal_id,parent_operation_id,decider_id,
						executor_id,executor_code,executor_name,actual_quantity,unit,measures,evidence,source_json,policy_json,state,completed_at)
						VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::numeric,?,?,?,?,?,'VALID',?)
						""", c.id(), c.tenantId(), c.factoryCode(), c.requestId(), c.ncrId(), c.detailId(), c.proposalId(),
				c.parentOperationId(), c.deciderId(), Long.valueOf(c.executor().userId()), c.executor().code(),
				c.executor().name(), c.quantity(), c.unit(), c.measures(), c.evidence(), encode(c.source()),
				"{\"version\":1,\"mode\":\"IN_PLACE\",\"quantityMode\":\"FULL_QUANTITY\",\"verificationMode\":\"INDEPENDENT_REVIEW\"}",
				c.completedAt()) == 1, "完成永久事实写入失败");
	}

	/**
	 * 仅一次VALID→VOID追加原人纠错依据，禁止覆盖完成原文。
	 */
	public void voidCompletion(Completion c, String request, String reason, long actor) {
		require(jdbc.update("""
						UPDATE mom_qms.qms_ncr_rework_completion SET state='VOID',void_request_id=?,void_reason=?,void_actor_id=?,voided_at=?
						WHERE id=? AND tenant_id=? AND factory_code=? AND state='VALID'
						""", request, reason, actor, now(), c.id(), c.tenantId(), c.factoryCode()) == 1,
				"原完成记录已变化，请重新核对");
	}

	/**
	 * 提交不可变PREPARED命令，尚不调用远端或改变库存。
	 */
	public void insert(Intent i) {
		require(jdbc.update("""
								INSERT INTO mom_qms.qms_ncr_rework_verify_intent
								(id,tenant_id,factory_code,request_id,ncr_id,detail_id,proposal_id,parent_operation_id,completion_id,
								reviewer_id,decision,command_json,state,prepared_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?)
								""", i.id(), i.tenantId(), i.factoryCode(), i.requestId(), i.ncrId(), i.detailId(), i.proposalId(),
						i.parentOperationId(), i.completionId(), i.reviewerId(), i.decision(), encode(i.command()),
						i.preparedAt()) == 1,
				"验证永久意图写入失败");
	}

	/**
	 * 仅PREPARED单向确认匹配远端终态，终态事件时间取原收据。
	 */
	public void finish(Intent i, Receipt receipt) {
		require(jdbc.update("""
								UPDATE mom_qms.qms_ncr_rework_verify_intent SET state=?,receipt_json=?,finished_at=?
								WHERE id=? AND tenant_id=? AND factory_code=? AND state='PREPARED'
								""", receipt.status(), encode(receipt), LocalDateTime.parse(receipt.recordedAt()), i.id(), i.tenantId(),
						i.factoryCode()) == 1,
				"原验证意图已变化");
	}

	private Completion completion(ResultSet r, int row) throws SQLException {
		var value = new Completion(r.getLong("id"), r.getLong("tenant_id"), r.getString("factory_code"),
				r.getString("request_id"),
				r.getLong("ncr_id"), r.getLong("detail_id"), r.getLong("proposal_id"), r.getLong("parent_operation_id"),
				r.getLong("decider_id"),
				new Actor(r.getString("executor_id"), r.getString("executor_code"), r.getString("executor_name")),
				r.getBigDecimal("actual_quantity").stripTrailingZeros().toPlainString(), r.getString("unit"),
				r.getString("measures"),
				r.getString("evidence"), decode(r.getString("source_json"), Source.class), r.getString("state"),
				r.getObject("completed_at", LocalDateTime.class), r.getString("void_request_id"),
				r.getString("void_reason"),
				r.getObject("void_actor_id", Long.class), r.getObject("voided_at", LocalDateTime.class));
		keyMatches(value.source(), value.tenantId(), value.factoryCode(), value.ncrId(), value.detailId(),
				value.proposalId(), value.parentOperationId());
		require(value.quantity().equals(value.source().key().quantity()) &&
						value.unit().equals(value.source().key().unit())
						&& value.id() > 0 && value.deciderId() > 0 && value.completedAt() != null,
				"永久完成列与冻结原文不一致");
		return value;
	}

	private Intent intent(ResultSet r, int row) throws SQLException {
		var value = new Intent(r.getLong("id"), r.getLong("tenant_id"), r.getString("factory_code"),
				r.getString("request_id"),
				r.getLong("ncr_id"), r.getLong("detail_id"), r.getLong("proposal_id"), r.getLong("parent_operation_id"),
				r.getObject("completion_id", Long.class), r.getLong("reviewer_id"), r.getString("decision"),
				decode(r.getString("command_json"), Command.class), r.getString("state"),
				r.getObject("prepared_at", LocalDateTime.class),
				decode(r.getString("receipt_json"), Receipt.class), r.getObject("finished_at", LocalDateTime.class));
		var command = value.command();
		require(command != null && command.version() == 1, "永久意图协议未知");
		keyMatches(command.source(), value.tenantId(), value.factoryCode(), value.ncrId(), value.detailId(),
				value.proposalId(), value.parentOperationId());
		require(value.requestId().equals(command.requestId()) && command.reviewer() != null
				&& Long.toString(value.reviewerId()).equals(command.reviewer().userId()) &&
				value.decision().equals(command.decision())
				&& java.util.Objects.equals(value.completionId() == null ? null : value.completionId().toString(),
				command.completionId())
				&& (value.completionId() == null ?
				"REJECT".equals(value.decision()) && command.completionRequestId() == null && command.executor() == null
				: command.completionRequestId() != null && command.executor() != null), "永久意图列与完整命令不一致");
		return value;
	}

	/**
	 * 新账即使经DB保护仍在解析后复核列/JSON同组织同原件，不把JSON当任意可信载荷。
	 */
	private void keyMatches(Source source, long tenant, String factory, long ncr, long detail, long proposal,
	                        long parent) {
		require(source != null && source.version() == 1 && source.key() != null, "永久来源协议未知或缺失");
		var key = source.key();
		require(Long.toString(tenant).equals(key.tenantId()) && factory.equals(key.factoryCode())
						&& Long.toString(ncr).equals(key.ncrId()) && Long.toString(detail).equals(key.detailId())
						&& Long.toString(proposal).equals(key.proposalId()) &&
						Long.toString(parent).equals(key.parentOperationId()),
				"永久账组织/来源列与冻结原文不一致");
	}

	/**
	 * 使用当前服务JSON合同规范冻结永久原文，不保存令牌或权限。
	 */
	public String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception e) {
			throw new CheckedException("返工证据无法规范序列化");
		}
	}

	/**
	 * 按明确类型解析永久原文；未知或损坏证据拒绝继续。
	 */
	public <T> T decode(String value, Class<T> type) {
		try {
			return value == null ? null : json.readValue(value, type);
		} catch (Exception e) {
			throw new CheckedException("返工永久证据无法解析，禁止猜补");
		}
	}

	/**
	 * 生成服务端微秒精度本地业务事件时间。
	 */
	public static LocalDateTime now() {
		var value = LocalDateTime.now();
		return value.withNano(value.getNano() / 1000 * 1000);
	}

	/**
	 * 业务条件不满足时明确失败，不猜补来源、数量或成功结果。
	 */
	public static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
