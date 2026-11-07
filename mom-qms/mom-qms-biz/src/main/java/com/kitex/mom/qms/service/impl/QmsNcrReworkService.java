package com.kitex.mom.qms.service.impl;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.req.QmsNcrReworkDTO.*;
import com.kitex.mom.wms.api.feign.RemoteLiveStockService;
import com.kitex.mom.wms.dto.WmsNcrReworkDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.kitex.mom.qms.service.impl.QmsNcrReworkRepository.*;
import static com.kitex.mom.qms.service.impl.QmsNcrReworkSource.*;
import static com.kitex.mom.qms.service.impl.QmsNcrReworkTransactions.assertReceipt;

/**
 * 原位返工跨域协调器：事务外远程查原号；独立本地事务准备、提交及终态落账。
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class QmsNcrReworkService {
	private final QmsNcrReworkSource sources;
	private final QmsNcrReworkRepository ledger;
	private final QmsNcrReworkTransactions transactions;
	private final RemoteLiveStockService wms;
	private final QmsNcrReworkPreparation preparation;

	/**
	 * GET只读取受权原件与永久历史，不准备、不发送、不落账。
	 */
	public Progress context(long detail) {
		return progress(detail, sources.access("bad:show", false));
	}

	/**
	 * 登记前核实际原件；原号命中先授权冻结原仓，再比原文回永久事实。
	 */
	public Progress complete(long detail, Complete input) {
		require(input != null, "完成登记不能为空");
		var scope = sources.access("bad:rework:execute", true);
		var old = ledger.completionRequest(scope.tenant(), scope.factory(), request(input.requestId()));
		Source inspected;
		if (old != null) {
			authorize(old.source(), scope);
			inspected = old.source();
		} else {
			var c = sources.load(detail, scope, false, true);
			inspected = inspect(c);
		}
		transactions.complete(detail, input, scope, inspected);
		return progress(detail, scope);
	}

	/**
	 * 原人有据作废，原文永留；作废授权使用冻结原仓而非今日仓库选择。
	 */
	public Progress voidCompletion(long detail, VoidCompletion input) {
		require(input != null, "作废请求不能为空");
		var scope = sources.access("bad:rework:execute", true);
		var completion = ledger.completions(scope.tenant(), scope.factory(), detail).stream()
				.filter(c -> c.id() == id(input.completionId())).findFirst().orElse(null);
		require(completion != null, "原完成记录不存在");
		authorize(completion.source(), scope);
		transactions.voidCompletion(detail, input, scope);
		return progress(detail, scope);
	}

	/**
	 * prepare代理事务返回时PREPARED已提交；随后只在明确NOT_FOUND时发送同一完整命令。
	 */
	public PreparationOutcome prepare(long detail, Prepare raw) {
		var scope = sources.access("bad:verify", true);
		Prepare input = preparation.canonical(raw);
		var old = ledger.intentRequest(scope.tenant(), scope.factory(), input.requestId());
		var refused = preparation.refusal(scope, input.requestId());
		Source inspected;
		Intent observedSibling = null;
		if (old != null) {
			lookup(old.command());
			inspected = old.command().source();
		} else if (refused != null) {
			// 墓碑重放按原parent找永久来源授权；后续MRB/放行不应令原拒绝号复活。
			inspected = historicSource(detail, input, scope);
			authorize(inspected, scope);
		} else {
			observedSibling = ledger.intents(scope.tenant(), scope.factory(), detail).stream()
					.filter(i -> i.proposalId() == id(input.proposalId()) &&
							i.parentOperationId() == id(input.parentOperationId())
							&& !"CANCELLED".equals(i.state())).findFirst().orElse(null);
			if (observedSibling != null) {
				// 另一原号可能已放行或恢复HOLD；用其永久Source查原号授权，禁止再inspect今日库存卡住新号。
				lookup(observedSibling.command());
				inspected = observedSibling.command().source();
			} else {
				inspected = inspect(sources.load(detail, scope, false, true));
			}
		}
		QmsNcrReworkPreparation.Resolution result;
		try {
			result = transactions.prepare(detail, input, scope, inspected, observedSibling);
		} catch (QmsNcrReworkPreparation.LocalRejection rejected) {
			// 只捕获本地前准备业务拒绝。代理事务已回滚，裁决另行提交并重查并发已准备事实。
			result = preparation.adjudicate(detail, input, scope, inspected, rejected);
		}
		if (result.refusal() != null) {
			require(result.refusal().warehouseCode().equals(inspected.warehouseCode()),
					"永久拒绝原仓与已受权来源不一致");
			return new PreparationOutcome("NOT_PREPARED", result.refusal(), progress(detail, scope));
		}
		return new PreparationOutcome("INTENT", null, recover(detail, result.intent(), scope, "resume"));
	}

	/**
	 * 从同版本永久完成/意图授权旧拒绝号；没有永久来源时只允许当前真实parent预检。
	 */
	private Source historicSource(long detail, Prepare input, Scope scope) {
		for (var item : ledger.completions(scope.tenant(), scope.factory(), detail)) {
			if (item.parentOperationId() == id(input.parentOperationId()) &&
					item.proposalId() == id(input.proposalId())) {
				return item.source();
			}
		}
		for (var item : ledger.intents(scope.tenant(), scope.factory(), detail)) {
			if (item.parentOperationId() == id(input.parentOperationId()) &&
					item.proposalId() == id(input.proposalId())) {
				return item.command().source();
			}
		}
		var c = sources.load(detail, scope, false, true);
		require(c.key().parentOperationId().equals(input.parentOperationId()) &&
						c.key().proposalId().equals(input.proposalId()),
				"缺少该历史拒绝号的原仓证明，不能猜今日仓库授权");
		return inspect(c);
	}

	/**
	 * 查询并对账不会apply；显式续办/取消均保留原复核人，跨人只能回收已有终态。
	 */
	public Progress recover(long detail, Recover input, String action) {
		require(input != null, "须引用原验证号");
		var scope = sources.access("bad:verify", true);
		var intent = ledger.intentRequest(scope.tenant(), scope.factory(), request(input.requestId()));
		require(intent != null && intent.detailId() == detail && intent.proposalId() == id(input.proposalId())
				&& intent.parentOperationId() == id(input.parentOperationId()), "原组织、原案或原验证号不匹配");
		return recover(detail, intent, scope, action);
	}

	private Progress recover(long detail, Intent intent, Scope scope, String action) {
		var result = lookup(intent.command());
		Receipt receipt = result.receipt();
		require("PREPARED".equals(intent.state()) || receipt != null && receipt.equals(intent.receipt()),
				"原本地终态与WMS永久回执不一致，禁止重新发送原意图");
		if ("NOT_FOUND".equals(result.state()) && !"lookup".equals(action)) {
			require(intent.reviewerId() == id(scope.actor().userId()),
					"当前人员可查询已有终态；补发或取消须原复核人重新登录");
			// 未见原号不是取消；必须取得WMS永久墓碑。未知/异常直接失败，原PREPARED保持不变。
			receipt = value("cancel".equals(action) ? wms.cancelNcrRework(intent.command()) : wms.applyNcrRework(
							intent.command()),
					"WMS结果未知，请保留原验证号查询");
		}
		if (receipt != null) {
			transactions.finish(detail, intent, receipt, scope);
		}
		return progress(detail, scope);
	}

	private Source inspect(Case c) {
		var source = value(wms.inspectNcrRework(c.key()), "WMS原件预检失败，未登记新事实");
		sources.sourceMatches(c.key(), source);
		return source;
	}

	private Lookup lookup(Command command) {
		var result = value(wms.lookupNcrRework(command), "WMS查询失败，结果未知，不能换号");
		require(("FOUND".equals(result.state()) && result.receipt() != null)
						|| ("NOT_FOUND".equals(result.state()) && result.receipt() == null),
				"WMS未明确返回FOUND/NOT_FOUND，结果未知");
		if (result.receipt() != null) {
			assertReceipt(command, result.receipt());
		}
		return result;
	}

	/**
	 * 对没有验证意图的完成/VOID历史也经WMS按冻结原仓校show；查询不要求今日库存仍在。
	 */
	private void authorize(Source source, Scope scope) {
		lookup(new Command(1, UUID.randomUUID().toString(), source, null, null, null, scope.actor(),
				sources.decider(source), "REJECT", "只读核对永久完成来源"));
	}

	private Progress progress(long detail, Scope scope) {
		var c = sources.load(detail, scope, false, false);
		var completions = ledger.completions(scope.tenant(), scope.factory(), detail);
		var intents = ledger.intents(scope.tenant(), scope.factory(), detail);
		List<Attempt> history = new ArrayList<>();
		List<VoidFact> voidHistory = new ArrayList<>();
		List<CompletionFact> completionHistory = new ArrayList<>();
		Long parent = c.detail().getWmsOperationId();
		Completion valid = null;
		Intent current = null;
		Source source = null;
		String remoteState = "NOT_SENT";
		for (var item : completions) {
			authorize(item.source(), scope);
			completionHistory.add(new CompletionFact(Long.toString(item.id()), Long.toString(item.parentOperationId()),
					item.requestId(), item.state(), item.quantity(), item.unit(), item.measures(), item.evidence(),
					item.executor().code(), item.completedAt().toString()));
			if (Objects.equals(parent, item.parentOperationId())) {
				source = item.source();
				if ("VALID".equals(item.state())) {
					valid = item;
				}
			}
			if ("VOID".equals(item.state())) {
				voidHistory.add(
						new VoidFact(Long.toString(item.id()), Long.toString(item.parentOperationId()), item.quantity(),
								item.unit(),
								item.measures(), item.evidence(), item.executor().code(), item.completedAt().toString(),
								item.voidRequestId(),
								item.voidReason(), item.voidedAt().toString()));
			}
		}
		for (var item : intents) {
			// GET仅查询；远端已生效本地未知仍显示PREPARED，用户显式对账后才写本地终态。
			var remote = lookup(item.command());
			history.add(new Attempt(item.requestId(), Long.toString(item.parentOperationId()), item.state(),
					item.decision(),
					item.command().reviewer().code(), item.preparedAt().toString(),
					item.finishedAt() == null ? null : item.finishedAt().toString()));
			if (Objects.equals(parent, item.parentOperationId())) {
				current = item;
				source = item.command().source();
				remoteState = "FOUND".equals(remote.state()) ? remote.receipt().status() : "UNKNOWN";
			}
		}
		if (source == null && "WAIT_VERIFY".equals(c.detail().getStatus()) &&
				"REWORK".equals(c.detail().getHandleResult())) {
			source = inspect(sources.load(detail, scope, false, true));
		}
		String stage = !"WAIT_VERIFY".equals(c.detail().getStatus()) ? "HISTORY" :
				valid == null ? "WAIT_COMPLETION" : "WAIT_REVIEW";
		if (current != null) {
			stage = current.state();
		}
		return new Progress(Long.toString(detail),
				c.detail().getMrbProposalId() == null ? null : c.detail().getMrbProposalId().toString(),
				parent == null ? null : parent.toString(), stage, source == null ? null : source.warehouseCode(),
				valid == null ? null : Long.toString(valid.id()), valid == null ? null : valid.requestId(),
				valid == null ? null : valid.executor().code(), valid == null ? null : valid.quantity(),
				source == null ? quantity(c.detail().getJudgeNum()) : source.key().quantity(),
				source == null ? c.detail().getQuantityUnit() : source.key().unit(),
				valid == null ? null : valid.measures(), valid == null ? null : valid.evidence(),
				current == null ? null : current.requestId(), current == null ? null : current.decision(),
				current == null ? null : current.command().reviewEvidence(),
				current == null ? null : current.command().reviewer().code(),
				remoteState, history, voidHistory, completionHistory);
	}

	private static <T> T value(R<T> response, String message) {
		require(response != null && response.isOk() && response.getData() != null,
				response == null || response.getMsg() == null ? message : message + "：" + response.getMsg());
		return response.getData();
	}
}
