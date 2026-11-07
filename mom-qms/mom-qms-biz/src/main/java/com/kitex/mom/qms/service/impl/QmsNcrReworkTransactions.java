package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.IdUtil;
import com.kitex.mom.qms.dto.req.QmsNcrReworkDTO.*;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;
import com.kitex.mom.qms.service.QmsBadOperationService;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;

import static com.kitex.mom.qms.service.impl.QmsNcrReworkRepository.*;
import static com.kitex.mom.qms.service.impl.QmsNcrReworkSource.*;

/**
 * 只处理QMS本地事务；编排器经独立代理提交PREPARED后才能在事务外调用WMS。
 */
@Service
@RequiredArgsConstructor
public class QmsNcrReworkTransactions {
	private final QmsNcrReworkRepository ledger;
	private final QmsNcrReworkSource sources;
	private final JdbcTemplate jdbc;
	private final QmsBadOperationService operations;
	private final QmsNcrReworkPreparation preparation;

	/**
	 * 原号重放先回永久事实，避免远端已经放行后重验今日库存而拒绝原完成结果。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Completion complete(long detail, Complete input, Scope scope, Source inspected) {
		var c = sources.load(detail, scope, true, false);
		ledger.lockFacts(scope.tenant(), scope.factory(), detail);
		var old = ledger.completionRequest(scope.tenant(), scope.factory(), request(input.requestId()));
		require(input.quantity() != null && input.quantity().matches("[0-9]{1,16}(\\.[0-9]{1,8})?"),
				"完成量须为原基本单位定点数量文本");
		String quantity = quantity(new BigDecimal(input.quantity()));
		String measures = text(input.measures()), evidence = text(input.evidence());
		if (old != null) {
			require(old.detailId() == detail && old.proposalId() == id(input.proposalId())
							&& old.parentOperationId() == id(input.parentOperationId()) &&
							old.executor().userId().equals(scope.actor().userId())
							&& old.quantity().equals(quantity) && old.measures().equals(measures) &&
							old.evidence().equals(evidence),
					"完成原号已用于不同原件、人员或内容");
			return old;
		}
		c = sources.load(detail, scope, true, true);
		version(c, input.proposalId(), input.parentOperationId());
		ledger.noActive(scope.tenant(), scope.factory(), detail, c.detail().getWmsOperationId());
		sources.sourceMatches(c.key(), inspected);
		require(quantity.equals(inspected.key().quantity()), "一期须按原基本单位整量完成");
		require(valid(scope, detail, c.detail().getWmsOperationId()) == null,
				"本轮已有有效完成，请读取原完成事实或由原人有据作废");
		var row = new Completion(IdUtil.getSnowflakeNextId(), scope.tenant(), scope.factory(), input.requestId(),
				c.header().getId(), detail, c.proposal().getId(), c.detail().getWmsOperationId(),
				c.proposal().getExecutorId(),
				scope.actor(), quantity, inspected.key().unit(), measures, evidence, inspected, "VALID", now(), null,
				null, null, null);
		ledger.insert(row);
		return row;
	}

	/**
	 * 原完成者纠错，仅VALID→VOID；与prepare同锁，不可撤掉已待核/生效的完成依据。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Completion voidCompletion(long detail, VoidCompletion input, Scope scope) {
		var c = sources.load(detail, scope, true, false);
		ledger.lockFacts(scope.tenant(), scope.factory(), detail);
		request(input.requestId());
		String reason = text(input.reason());
		var rows = ledger.completions(scope.tenant(), scope.factory(), detail);
		var original = rows.stream().filter(r -> r.id() == id(input.completionId())).findFirst().orElse(null);
		require(original != null && original.proposalId() == id(input.proposalId())
				&& original.parentOperationId() == id(input.parentOperationId())
				&& original.executor().userId().equals(scope.actor().userId()), "只能由原完成者作废同版本的原记录");
		if ("VOID".equals(original.state())) {
			require(input.requestId().equals(original.voidRequestId()) && reason.equals(original.voidReason())
					&& Objects.equals(original.voidActorId(), id(scope.actor().userId())), "作废原号/理由或人员不匹配");
			return original;
		}
		c = sources.load(detail, scope, true, true);
		version(c, input.proposalId(), input.parentOperationId());
		ledger.noActive(scope.tenant(), scope.factory(), detail, original.parentOperationId());
		ledger.voidCompletion(original, input.requestId(), reason, id(scope.actor().userId()));
		return original;
	}

	/**
	 * 冻结完整Command并独立提交，未完工REJECT不制造执行人或完成事实。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public QmsNcrReworkPreparation.Resolution prepare(long detail, Prepare input, Scope scope, Source inspected,
	                                                  Intent observedSibling) {
		preparation.lock(scope, input.requestId());
		var c = sources.load(detail, scope, true, false);
		ledger.lockFacts(scope.tenant(), scope.factory(), detail);
		var replay = preparation.replay(detail, input, scope);
		if (replay != null) {
			return replay;
		}
		// 仅来自永久意图的授权快照用于拒绝新号，不借此跳过新准备所需的真实inspect。
		// 即使原意图在本次授权后刚取消，本新号仍明确拒绝；下一新号按CANCELLED规则重新预检。
		QmsNcrReworkPreparation.local(
				observedSibling == null && ledger.intents(scope.tenant(), scope.factory(), detail).stream()
						.noneMatch(i -> i.proposalId() == id(input.proposalId()) &&
								i.parentOperationId() == id(input.parentOperationId())
								&& !"CANCELLED".equals(i.state())), "OTHER_INTENT",
				"本轮已有原号决定，请先按既有原号核对后再按新版本办理");
		// 只将这些已受权原件的本地前准备规则归为可永久裁决拒绝，禁止捕获数据库/远端异常冒充。
		QmsNcrReworkPreparation.local("WAIT_VERIFY".equals(c.detail().getStatus())
				&& "REWORK".equals(c.detail().getHandleResult()), "SOURCE_CHANGED", "当前明细已不在待复核返工状态");
		QmsNcrReworkPreparation.local(c.proposal() != null && c.proposal().getId() == id(input.proposalId())
						&& c.detail().getWmsOperationId() != null &&
						c.detail().getWmsOperationId() == id(input.parentOperationId()),
				"VERSION_CHANGED", "当前MRB或原REWORK版本已变化");

		c = sources.load(detail, scope, true, true);
		version(c, input.proposalId(), input.parentOperationId());
		var completion = valid(scope, detail, c.detail().getWmsOperationId());
		QmsNcrReworkPreparation.local(
				completion == null ? input.completionId() == null && "REJECT".equals(input.decision())
						: Long.toString(completion.id()).equals(input.completionId()),
				"COMPLETION_MISMATCH", "PASS必须绑定有效整量完成；已有完成时驳回也必须绑定原记录");
		QmsNcrReworkPreparation.local(!scope.actor().userId().equals(c.proposal().getExecutorId().toString())
						&& (completion == null || !scope.actor().userId().equals(completion.executor().userId())),
				"REVIEWER_NOT_INDEPENDENT", "复核人必须独立于MRB发布人和实际完成者");
		Source source = completion == null ? inspected : completion.source();
		sources.sourceMatches(c.key(), source);
		// 有完成记录也须与本次实际预检全文一致，不能以旧快照掩盖实物漂移。
		QmsNcrReworkPreparation.local(inspected != null && inspected.equals(source), "SOURCE_CHANGED",
				"实际原件已变化，请核对原库存，禁止换片段验证");
		var command = new Command(1, input.requestId(), source,
				completion == null ? null : Long.toString(completion.id()),
				completion == null ? null : completion.requestId(), completion == null ? null : completion.executor(),
				scope.actor(), c.proposal().getExecutorId().toString(), input.decision(), input.reviewEvidence());
		var row = new Intent(IdUtil.getSnowflakeNextId(), scope.tenant(), scope.factory(), input.requestId(),
				c.header().getId(),
				detail, c.proposal().getId(), c.detail().getWmsOperationId(),
				completion == null ? null : completion.id(),
				id(scope.actor().userId()), input.decision(), command, "PREPARED", now(), null, null);
		ledger.insert(row);
		return new QmsNcrReworkPreparation.Resolution(row, null);
	}

	/**
	 * 完整权威收据核对后一次写意图、明细、主案重算和履历；取消只终本意图。
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
	public Intent finish(long detail, Intent expected, Receipt receipt, Scope scope) {
		var c = sources.load(detail, scope, true, false);
		ledger.lockFacts(scope.tenant(), scope.factory(), detail);
		var stored = ledger.intentRequest(scope.tenant(), scope.factory(), expected.requestId());
		require(stored != null && stored.detailId() == detail && stored.command().equals(expected.command()),
				"原验证意图不匹配");
		assertReceipt(stored.command(), receipt);
		if (!"PREPARED".equals(stored.state())) {
			require(receipt.equals(stored.receipt()), "远端终态与本地永久回执不一致");
			return stored;
		}
		c = sources.load(detail, scope, true, true);
		version(c, Long.toString(stored.proposalId()), Long.toString(stored.parentOperationId()));
		sources.sourceMatches(c.key(), stored.command().source());
		var completion = valid(scope, detail, stored.parentOperationId());
		require(Objects.equals(stored.completionId(), completion == null ? null : completion.id())
						&& c.proposal().getExecutorId().toString().equals(stored.command().deciderId()),
				"当前有效完成、原量单位或实名发布证据与冻结意图不一致");
		require("WAIT_VERIFY".equals(c.detail().getStatus()) && "REWORK".equals(c.detail().getHandleResult()),
				"当前明细已变更，禁止用旧回执落新案");
		ledger.finish(stored, receipt);
		if ("APPLIED".equals(receipt.status())) {
			boolean pass = "PASS".equals(stored.decision());
			var reviewer = stored.command().reviewer();
			// REJECT显式SET NULL，避免MyBatis默认忽略空值导致旧MRB显示/版本残留。
			String clear = pass ? "" : ",handle_result=NULL,handle_remark=NULL,handle_file=NULL,expected_completion_time=NULL,handle_person_id=NULL,handle_person_name=NULL,handle_time=NULL,execution_ref=NULL";
			require(jdbc.update(
					"UPDATE mom_qms.qms_bad_info_detail SET status=?,verification_result=?,verification_remark=?,verifier_code=?,verifier_name=?,verification_time=?"
							+ clear +
							" WHERE id=? AND tenant_id=? AND factory_code=? AND status='WAIT_VERIFY' AND wms_operation_id=? AND mrb_proposal_id=?",
					pass ? "CLOSED" : "WAIT_MRB", stored.decision(), stored.command().reviewEvidence(), reviewer.code(),
					reviewer.name(),
					stored.preparedAt(), detail, scope.tenant(), scope.factory(), stored.parentOperationId(),
					stored.proposalId()) ==
					1, "NCR明细终态更新失败");
			refreshHeader(c, scope);
		}
		var op = new QmsBadOperationEntity().setRequestId(stored.requestId()).setBadId(stored.ncrId())
				.setDetailId(detail)
				.setNcrNo(c.header().getNcrNo()).setOperationType("REWORK_" + receipt.status())
				.setBeforeStatus("WAIT_VERIFY").setAfterStatus("APPLIED".equals(receipt.status())
						? ("PASS".equals(stored.decision()) ? "CLOSED" : "WAIT_MRB") : "WAIT_VERIFY")
				.setContent("原验证=" + stored.requestId() + "，决定=" + stored.decision() + "，原复核人=" +
						stored.command().reviewer().code()
						+ "，WMS办理人=" + receipt.recordedBy().code() + "，本次回收人=" + scope.actor().code() +
						"，永久结果=" + receipt.resultId())
				.setOperatorCode(scope.actor().code()).setOperatorName(scope.actor().name()).setOperationTime(now())
				.setFactoryCode(scope.factory());
		op.setTenantId(scope.tenant());
		require(operations.save(op), "返工终态履历保存失败");
		return ledger.intentRequest(scope.tenant(), scope.factory(), stored.requestId());
	}

	private void refreshHeader(Case c, Scope scope) {
		var rows = jdbc.queryForList(
				"SELECT status,judge_num FROM mom_qms.qms_bad_info_detail WHERE bad_id=? AND tenant_id=? AND factory_code=? AND del_flag='0'",
				c.header().getId(), scope.tenant(), scope.factory());
		BigDecimal judged = BigDecimal.ZERO, disposed = BigDecimal.ZERO, closed = BigDecimal.ZERO;
		boolean mrb = false, wms = false, review = false;
		for (var row : rows) {
			var qty = (BigDecimal) row.get("judge_num");
			require(qty != null && qty.signum() > 0, "分类真实数量缺失，禁止虚增累计");
			String status = Objects.toString(row.get("status"), "");
			require(java.util.Set.of("WAIT_MRB", "WAIT_WMS", "WAIT_EXECUTION", "WAIT_VERIFY", "CLOSED")
							.contains(status),
					"有效分类存在未知状态，请治理原案，不能兜底关闭");
			judged = judged.add(qty);
			if (!"WAIT_MRB".equals(status)) {
				disposed = disposed.add(qty);
			}
			if ("CLOSED".equals(status)) {
				closed = closed.add(qty);
			}
			mrb |= "WAIT_MRB".equals(status);
			wms |= "WAIT_WMS".equals(status) || "WAIT_EXECUTION".equals(status);
			review |= "WAIT_VERIFY".equals(status);
		}
		BigDecimal total = c.header().getNum();
		require(total != null && judged.compareTo(total) <= 0 && closed.compareTo(disposed) <= 0 &&
						disposed.compareTo(judged) <= 0,
				"NCR真实明细数量不守恒");
		String status;
		if (!"SUCCESS".equals(c.header().getHoldSyncStatus())) {
			status = "WAIT_WMS_HOLD";
		} else if (judged.compareTo(total) < 0) {
			status = "WAIT_JUDGE";
		} else if (mrb) {
			status = "WAIT_MRB";
		} else if (wms) {
			status = "WAIT_WMS";
		} else if (review) {
			status = "WAIT_VERIFY";
		} else {
			require(closed.compareTo(total) == 0, "真实关闭量未达主案数量，禁止关闭");
			status = "CLOSED";
		}
		require(jdbc.update(
						"UPDATE mom_qms.qms_bad_info SET judged_num=?,un_judge_num=?,disposed_num=?,closed_num=?,status=? WHERE id=? AND tenant_id=? AND factory_code=? AND del_flag='0'",
						judged, total.subtract(judged), disposed, closed, status, c.header().getId(), scope.tenant(),
						scope.factory()) == 1,
				"NCR主案真实进度重算失败");
	}

	public static void assertReceipt(Command command, Receipt receipt) {
		require(receipt != null && command.equals(receipt.command()) && receipt.recordedBy() != null
						&& command.reviewer().userId().equals(receipt.recordedBy().userId()) && receipt.recordedAt() != null
						&& receipt.resultId() != null && receipt.resultId().matches("[1-9][0-9]{0,18}")
						&& ("APPLIED".equals(receipt.status()) || "CANCELLED".equals(receipt.status())) &&
						receipt.changes() != null,
				"远端未返回与完整原命令匹配的权威终态");
		id(receipt.resultId());
		require(java.time.LocalDateTime.parse(receipt.recordedAt()).getNano() % 1000 == 0, "WMS回执时间精度不符");
		if ("CANCELLED".equals(receipt.status())) {
			require(receipt.changes().isEmpty(), "取消墓碑不得包含库存变动");
			return;
		}
		require(receipt.changes().size() == command.source().pieces().size(), "WMS逐件回执集合不完整");
		var seen = new HashSet<String>();
		BigDecimal total = BigDecimal.ZERO;
		for (int n = 0; n < command.source().pieces().size(); n++) {
			var piece = command.source().pieces().get(n);
			var change = receipt.changes().get(n);
			boolean pass = "PASS".equals(command.decision());
			require(piece.stockId().equals(change.stockId()) && seen.add(change.stockId()) &&
							piece.quantity().equals(change.quantity())
							&& "1".equals(change.beforeStatus()) && "REWORK".equals(change.beforeQuality()) &&
							"UN_QUALIFIED".equals(change.beforeResult())
							&& (pass ? "0" : "1").equals(change.afterStatus()) &&
							(pass ? "QUALIFIED" : "HOLD").equals(change.afterQuality())
							&& (pass ? "RELEASE" : "UN_QUALIFIED").equals(change.afterResult()),
					"WMS逐件身份、原量或前后质量状态不符");
			total = total.add(new BigDecimal(change.quantity()));
		}
		require(total.compareTo(new BigDecimal(command.source().key().quantity())) == 0, "WMS终态数量不守恒");
	}

	private Completion valid(Scope scope, long detail, long parent) {
		return ledger.completions(scope.tenant(), scope.factory(), detail).stream()
				.filter(c -> c.parentOperationId() == parent && "VALID".equals(c.state())).findFirst().orElse(null);
	}

	private static void version(Case c, String proposal, String parent) {
		require(c.proposal() != null && c.proposal().getId() == id(proposal) && c.detail().getWmsOperationId() != null
				&& c.detail().getWmsOperationId() == id(parent), "当前MRB/原REWORK版本已变化，请重新读取原案");
	}
}
