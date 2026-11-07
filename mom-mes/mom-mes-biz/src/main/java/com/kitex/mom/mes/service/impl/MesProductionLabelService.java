package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesProductionLabelCodec.*;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.*;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.utils.PrintTemplateFingerprint;
import com.kitex.mom.mes.dto.MesProductionLabelDTO.*;
import com.kitex.mom.mes.entity.MesProductionLabelEntity;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 生产标签业务编排：核真实来源、冻结版式与原件并登记意图，不生成第二制造身份或调用硬件。
 */
@Service
@RequiredArgsConstructor
public class MesProductionLabelService {
	private final MesActualFactoryAccessService access;
	private final MesProductionLabelMapper rows;
	private final MesExecutionTaskLock locks;
	private final MesEngineeringPackageService engineering;
	private final MesManufacturingPieceMapper births;
	private final RemoteBarcodeTemplateService templates;
	private final RemotePrintService prints;

	/**
	 * 按原派工、当前本人及实际接收工位读取真实标签来源；归属或可信制造出生不符时拒绝。
	 */
	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Context context(Query query) {
		Scope scope = scope("read");
		return source(scope, query);
	}

	/**
	 * 读取当前实际工厂、指定业务类型的启用模板；跨厂、停用或类型不符时拒绝。
	 */
	@Transactional(readOnly = true)
	public Template template(Query query, String templateId) {
		Scope scope = scope("read");
		source(scope, query);
		return meta(layout(scope, query.kind(), templateId));
	}

	/**
	 * 只读核对完整来源并返回预览；来源结构、原单位或模板指纹不符时拒绝，不产生成功保存事实。
	 */
	@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
	public Preview preview(IssueCommand command) {
		Scope scope = scope("read");
		require(command != null, "缺少标签预览命令");
		Context source = source(scope, command.source());
		hash(command.expectedSourceFingerprint());
		hash(command.expectedTemplateFingerprint());
		require(source.fingerprint().equals(command.expectedSourceFingerprint()), "标签来源已变化，请重新核对");
		var layout = layout(scope, command.source().kind(), command.templateId());
		Template template = meta(layout);
		require(template.fingerprint().equals(command.expectedTemplateFingerprint()), "标签模板已变化，请重新核对");
		return new Preview(source, template, render(source.source(), template, "PREVIEW_NOT_ISSUED"));
	}

	/**
	 * 锁定原派工并按原请求号发放不可变标签原件；来源/模板变化或同证据换号时拒绝。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Original<Issue> issue(IssueCommand command) {
		Scope scope = scope("write");
		require(command != null && command.source() != null, "缺少标签发放命令或来源");
		request(command.requestId());
		ready();
		locks.arrange(id(command.source().arrangeId()));
		Context source = source(scope, command.source());
		hash(command.expectedSourceFingerprint());
		hash(command.expectedTemplateFingerprint());
		require(source.fingerprint().equals(command.expectedSourceFingerprint()), "标签原件来源已变化，请重新核对");
		var layout = layout(scope, command.source().kind(), command.templateId());
		Template template = meta(layout);
		require(template.fingerprint().equals(command.expectedTemplateFingerprint()), "标签原模板已变化，请重新预览");
		var old = rows.issueRequest(scope.tenant, scope.factory, command.requestId());
		if (old != null) {
			var receipt = readIssue(old, scope);
			require(receipt.original().command().equals(command), "标签原请求已属于另一份来源或模板");
			return receipt;
		}
		var prior = rows.issueEvidence(scope.tenant, scope.factory, source.fingerprint(), template.fingerprint());
		require(prior == null, "同一来源和原模板已有标签原件，禁止换请求号绕过重打；请核对已有原件");
		long sequence = rows.nextSequence();
		require(sequence > 0, "标签发放序列无效");
		var issue = new Issue("MES_PRODUCTION_LABEL_ISSUE@1", Long.toString(sequence), scope.tenant.toString(),
				scope.factory,
				scope.actor.toString(), Instant.now().toString(), command, source.source(), source.fingerprint(),
				template,
				render(source.source(), template, Long.toString(sequence)));
		var row = row(scope, sequence, command.requestId(), id(command.source().arrangeId()), issue);
		row.setSourceFingerprint(source.fingerprint());
		row.setTemplateFingerprint(template.fingerprint());
		int inserted = rows.insertIssue(row);
		require(inserted == 0 || inserted == 1, "标签原件必要保存结果无效");
		var stored = rows.issueRequest(scope.tenant, scope.factory, command.requestId());
		require(stored != null, "标签原件未确认保存，请查询原请求号");
		var result = readIssue(stored, scope);
		require(result.original().command().equals(command), "标签原号冲突，禁止更换编号冒认成功");
		return result;
	}

	/**
	 * 锁定标签原件后登记首次或重打意图；须本人原工位、原来源、原模板及完整重打原因/前件，不调用硬件。
	 */
	@Transactional(rollbackFor = Exception.class)
	public Original<Intent> intent(IntentCommand command) {
		Scope scope = scope("write");
		require(command != null, "缺少打印意图");
		request(command.requestId());
		hash(command.expectedIssueFingerprint());
		ready();
		var row = rows.lockIssue(scope.tenant, scope.factory, id(command.issueId()));
		require(row != null, "当前工厂不存在标签原件");
		var issue = readIssue(row, scope);
		require(issue.fingerprint().equals(command.expectedIssueFingerprint()), "标签原件指纹已变化");
		Source original = issue.original().source();
		var current = source(scope, new Query(original.kind(), original.arrangeId(), command.worksiteCode(),
				"PIECE".equals(original.kind()) ? original.identifier() : null));
		require(current.fingerprint().equals(issue.original().sourceFingerprint()),
				"实际人员、工位或标签来源已变化，不能沿旧预览打印");
		require(meta(layout(scope, original.kind(), issue.original().template().id())).equals(
				issue.original().template()), "原标签布局已变化，须重新发放并核对");
		var old = rows.intentRequest(scope.tenant, scope.factory, command.requestId());
		if (old != null) {
			var receipt = readIntent(old, scope);
			require(receipt.original().command().equals(command), "打印原请求属于另一笔意图");
			return receipt;
		}
		var history = rows.intents(scope.tenant, scope.factory, row.getId());
		require(history != null && history.size() <= 1000, "打印意图来源不可读或超过1000条，禁止截断审计");
		long ordinal = 1;
		for (var h : history) {
			var checked = readIntent(h, scope);
			require(checked.original().issueId().equals(command.issueId()), "重打原件归属不一致");
		}
		if (history.isEmpty()) {
			require(command.previousIntentId() == null && command.reason() == null, "首次打印不能伪带重打来源或原因");
		} else {
			var last = readIntent(history.get(0), scope).original();
			ordinal = Long.parseLong(last.ordinal()) + 1;
			require(Objects.equals(command.previousIntentId(), last.intentId()) && command.reason() != null &&
							!command.reason().isBlank() && command.reason().length() <= 500,
					"重打须携带最近一次原打印意图及500字内原因；未知结果须先核对原号");
		}
		long sequence = rows.nextSequence();
		require(sequence > 0, "打印意图序列无效");
		var intent = new Intent("MES_PRODUCTION_LABEL_INTENT@1", Long.toString(sequence), command.issueId(),
				Long.toString(ordinal), scope.tenant.toString(), scope.factory,
				scope.actor.toString(), Instant.now().toString(), history.isEmpty() ? "PRINT_INTENT" : "REPRINT_INTENT",
				command, issue.fingerprint());
		var saved = row(scope, sequence, command.requestId(), row.getArrangeId(), intent);
		saved.setIssueId(row.getId());
		saved.setOrdinalNo(ordinal);
		int inserted = rows.insertIntent(saved);
		require(inserted == 0 || inserted == 1, "打印意图必要保存结果无效");
		var stored = rows.intentRequest(scope.tenant, scope.factory, command.requestId());
		require(stored != null, "打印意图尚未确认，请查询原请求号而非再次打印");
		var receipt = readIntent(stored, scope);
		require(receipt.original().command().equals(command), "打印意图原号冲突");
		return receipt;
	}

	/**
	 * 核对当前来源/模板后读取已发放原件；未找到为ABSENT，不重新生成编号。
	 */
	@Transactional(readOnly = true)
	public Resolution<Issue> findIssue(Query query, String templateId) {
		Scope scope = scope("read");
		ready();
		var context = source(scope, query);
		var template = meta(layout(scope, query.kind(), templateId));
		var row = rows.issueEvidence(scope.tenant, scope.factory, context.fingerprint(), template.fingerprint());
		return new Resolution<>(row == null ? "ABSENT" : "APPLIED", row == null ? null : readIssue(row, scope));
	}

	/**
	 * 按稳定原请求号读取本人原发放回执；不执行或补写历史。
	 */
	@Transactional(readOnly = true)
	public Resolution<Issue> inspectIssue(String requestId) {
		Scope scope = scope("read");
		request(requestId);
		ready();
		var row = rows.issueRequest(scope.tenant, scope.factory, requestId);
		return new Resolution<>(row == null ? "ABSENT" : "APPLIED", row == null ? null : readIssue(row, scope));
	}

	/**
	 * 读取本人首次打印意图原件；缺失仍为未知，不执行浏览器交付。
	 */
	@Transactional(readOnly = true)
	public Resolution<Intent> inspectIntent(String requestId) {
		Scope scope = scope("read");
		request(requestId);
		ready();
		var row = rows.intentRequest(scope.tenant, scope.factory, requestId);
		return new Resolution<>(row == null ? "ABSENT" : "APPLIED", row == null ? null : readIntent(row, scope));
	}

	/**
	 * 读取本人原件的完整连续意图链；超过1000条或原件关联损坏时拒绝截断展示。
	 */
	@Transactional(readOnly = true)
	public List<Original<Intent>> history(String issueId) {
		Scope scope = scope("read");
		ready();
		var issue = rows.issue(scope.tenant, scope.factory, id(issueId));
		require(issue != null, "当前工厂无标签原件");
		readIssue(issue, scope);
		var found = rows.intents(scope.tenant, scope.factory, id(issueId));
		require(found != null && found.size() <= 1000, "打印审计不可读取或超过1000条");
		return found.stream().map(r -> readIntent(r, scope)).toList();
	}

	private Context source(Scope scope, Query query) {
		require(query != null && Set.of("TASK", "PIECE").contains(Objects.toString(query.kind(), "")),
				"请选择工单或制造单件标签");
		Long arrangeId = id(query.arrangeId());
		text(query.worksiteCode(), "实际工位");
		var arrange = rows.arrange(scope.tenant, scope.factory, arrangeId);
		require(arrange != null && Objects.equals(arrange.getTenantId(), scope.tenant) &&
						Objects.equals(arrange.getFactoryCode(), scope.factory) && Objects.equals(arrange.getId(), arrangeId),
				"标签派工不属于实际工厂");
		require(query.worksiteCode().equals(arrange.getActualWorksiteCode()) && arrange.getWorkerCode() != null
						&& Arrays.stream(arrange.getWorkerCode().split(",")).map(String::trim).anyMatch(scope.worker::equals),
				"必须由原派工当前指定本人在实际接收工位办理标签");
		var tasks = rows.tasks(scope.tenant, scope.factory, arrange.getTaskCode());
		require(tasks != null && tasks.size() == 1, "标签原工单不唯一");
		var task = tasks.get(0);
		require(Objects.equals(task.getTenantId(), scope.tenant) &&
						Objects.equals(task.getFactoryCode(), scope.factory) &&
						Objects.equals(task.getCode(), arrange.getTaskCode()) && task.getId() != null,
				"标签工单实际归属不一致");
		text(task.getMatCode(), "原工单产品");
		text(arrange.getCode(), "原派工编号");
		text(arrange.getProduceTaskCode(), "原工序任务");
		String identifier, unit, quantity, birth = null, release, engineeringHash, node;
		if ("PIECE".equals(query.kind())) {
			text(query.identifier(), "原制造SN");
			var r = births.piece(scope.tenant, scope.factory, query.identifier());
			require(r != null, "该标识没有可信制造出生原件，不能把历史观察码升级为制造SN");
			var original = MesManufacturingPieceService.read(r);
			var b = original.birth();
			require(b.tenantId().equals(scope.tenant.toString()) && b.factoryCode().equals(scope.factory) &&
							b.actorId().equals(scope.actor.toString())
							&& b.orderId().equals(task.getId().toString()) && b.orderCode().equals(task.getCode()) &&
							b.operationCode().equals(arrange.getProduceTaskCode())
							&& b.command().arrangeId().equals(query.arrangeId()) &&
							Objects.equals(b.routeNodeCode(), arrange.getSourceRouteNodeCode()) &&
							Objects.equals(b.generation().context().material(), task.getMatCode()),
					"制造原SN、当前本人或原派工节点不一致");
			identifier = b.identifier();
			unit = b.quantityUnit();
			quantity = "1";
			birth = original.fingerprint();
			release = b.engineeringReleaseId();
			engineeringHash = b.engineeringHash();
			node = b.routeNodeCode();
		} else {
			require(query.identifier() == null, "工单标签不接受浏览器另指定条码");
			var frozen = engineering.readIssuedOriginal(task);
			var body = MesManufacturingPieceService.tree(frozen.source().getBundleSnapshot());
			require(task.getMatCode().equals(body.path("bom").path("productCode").asText()),
					"原工程BOM产品与工单不一致");
			unit = body.path("bom").path("unit").asText();
			text(unit, "工程原单位");
			require(task.getNoticeNum() != null && task.getNoticeNum().signum() > 0, "工单原下达数量未知或无效");
			identifier = task.getCode();
			quantity = task.getNoticeNum().stripTrailingZeros().toPlainString();
			release = task.getEngineeringReleaseId().toString();
			engineeringHash = task.getEngineeringContentHash();
			node = arrange.getSourceRouteNodeCode();
		}
		text(identifier, "标签规范标识");
		text(node, "原稳定工序节点");
		text(unit, "原数量单位");
		hash(engineeringHash);
		var source = new Source(query.kind(), scope.tenant.toString(), scope.factory, scope.actor.toString(),
				query.arrangeId(), arrange.getCode(), task.getId().toString(), task.getCode(),
				arrange.getProduceTaskCode(), node, query.worksiteCode(), task.getMatCode(), unit, quantity, identifier,
				birth, release, engineeringHash);
		return new Context(source, sha(write(source)));
	}

	private PrintTemplateEntity layout(Scope scope, String kind, String templateId) {
		var response = templates.getTemplateById(id(templateId));
		require(response != null && response.isOk() && response.getData() != null, "请配置当前工厂的启用标签模板");
		var template = read(write(response.getData()), PrintTemplateEntity.class);
		require(Objects.equals(template.getId(), id(templateId)) &&
						Objects.equals(template.getTenantId(), scope.tenant) &&
						Objects.equals(template.getFactoryCode(), scope.factory)
						&& Boolean.TRUE.equals(template.getStatus()) && "0".equals(template.getDelFlag()) &&
						("PIECE".equals(kind) ? "MES_PIECE_LABEL" : "MES_TASK_LABEL").equals(template.getBusinessTypeCode())
						&& template.getContent() != null && !template.getContent().isBlank(),
				"模板不属于实际工厂、已停用或业务类型不符");
		text(template.getCode(), "模板编码");
		return template;
	}

	private Template meta(PrintTemplateEntity template) {
		return new Template(template.getId().toString(), template.getCode(), template.getName(),
				template.getBusinessTypeCode(), PrintTemplateFingerprint.of(template));
	}

	private String render(Source source, Template template, String labelIssueId) {
		var data = new LinkedHashMap<String, Object>();
		data.put("labelIssueId", labelIssueId);
		data.put("identifier", source.identifier());
		data.put("taskCode", source.taskCode());
		data.put("arrangeCode", source.arrangeCode());
		data.put("operationCode", source.operationCode());
		data.put("routeNodeCode", source.routeNodeCode());
		data.put("materialCode", source.materialCode());
		data.put("quantityUnit", source.quantityUnit());
		data.put("issuedQuantity", source.issuedQuantity());
		data.put("worksiteCode", source.worksiteCode());
		data.put("sourceFingerprint", sha(write(source)));
		data.put("source", source);
		var request = new PreviewObj();
		request.setTemplateCode(template.code());
		request.setExpectedTemplateId(template.id());
		request.setExpectedTemplateFingerprint(template.fingerprint());
		request.setExpectedBusinessTypeCode(template.businessTypeCode());
		request.setJsonData(write(data));
		var response = prints.preview(request);
		require(response != null && response.isOk() && response.getData() != null && !response.getData().isBlank() &&
						response.getData().getBytes(StandardCharsets.UTF_8).length <= 2_097_152,
				"标签渲染未确认或内容过大，未发放原件");
		return response.getData();
	}

	private void ready() {
		require(rows.installed() == 2 && Boolean.TRUE.equals(rows.ready()), "标签原件、审计及数据库保护尚未完整安装");
	}

	private Scope scope(String level) {
		String factory = access.require(level);
		Long tenant = TenantContextHolder.getTenantId();
		var user = SecurityUtils.getUser();
		require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip() && user != null &&
				user.getId() != null && user.getId() > 0 && Objects.equals(user.getTenantId(), tenant) &&
				user.getUsername() != null && !user.getUsername().isBlank(), "标签缺实际实名租户身份");
		return new Scope(tenant, factory, user.getId(), user.getUsername());
	}

	private MesProductionLabelEntity row(Scope scope, long id, String request, Long arrange, Object original) {
		String raw = write(original);
		require(raw.getBytes(StandardCharsets.UTF_8).length <= 4_194_304, "标签原件超过4MiB");
		var row = new MesProductionLabelEntity();
		row.setId(id);
		row.setTenantId(scope.tenant);
		row.setFactoryCode(scope.factory);
		row.setActorId(scope.actor);
		row.setRequestId(request);
		row.setArrangeId(arrange);
		row.setPayload(raw);
		row.setFingerprint(sha(raw));
		return row;
	}

	private void integrity(MesProductionLabelEntity row, Scope scope) {
		require(row != null && Objects.equals(row.getTenantId(), scope.tenant) &&
				Objects.equals(row.getFactoryCode(), scope.factory) && Objects.equals(row.getActorId(), scope.actor) &&
				row.getId() != null && row.getId() > 0 && row.getPayload() != null &&
				row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 4_194_304 &&
				Objects.equals(sha(row.getPayload()), row.getFingerprint()), "标签原文、当前本人或实际组织无法核验");
	}

	private Original<Issue> readIssue(MesProductionLabelEntity row, Scope scope) {
		integrity(row, scope);
		var value = read(row.getPayload(), Issue.class);
		require(write(value).equals(row.getPayload()) && "MES_PRODUCTION_LABEL_ISSUE@1".equals(value.schema()) &&
						value.issueId().equals(row.getId().toString()) &&
						value.command().requestId().equals(row.getRequestId()) &&
						value.source().arrangeId().equals(row.getArrangeId().toString()) &&
						value.tenantId().equals(scope.tenant.toString()) && value.factoryCode().equals(scope.factory) &&
						value.actorId().equals(scope.actor.toString()) &&
						value.sourceFingerprint().equals(sha(write(value.source()))) &&
						value.sourceFingerprint().equals(value.command().expectedSourceFingerprint()) &&
						value.template().fingerprint().equals(value.command().expectedTemplateFingerprint()) &&
						Objects.equals(row.getSourceFingerprint(), value.sourceFingerprint()) &&
						Objects.equals(row.getTemplateFingerprint(), value.template().fingerprint()),
				"标签原文与索引、来源或模板指纹不一致");
		return new Original<>(value, row.getFingerprint());
	}

	private Original<Intent> readIntent(MesProductionLabelEntity row, Scope scope) {
		integrity(row, scope);
		var value = read(row.getPayload(), Intent.class);
		require(write(value).equals(row.getPayload()) && "MES_PRODUCTION_LABEL_INTENT@1".equals(value.schema()) &&
				value.intentId().equals(row.getId().toString()) &&
				value.issueId().equals(row.getIssueId().toString()) &&
				value.ordinal().equals(row.getOrdinalNo().toString()) &&
				value.command().requestId().equals(row.getRequestId()) &&
				value.command().issueId().equals(value.issueId()) &&
				value.issueFingerprint().equals(value.command().expectedIssueFingerprint()) &&
				value.tenantId().equals(scope.tenant.toString()) && value.factoryCode().equals(scope.factory) &&
				value.actorId().equals(scope.actor.toString()), "打印意图原文与原件关联不一致");
		var linked = rows.issue(scope.tenant, scope.factory, row.getIssueId());
		require(linked != null, "打印意图缺少标签原件");
		var issue = readIssue(linked, scope);
		require(issue.fingerprint().equals(value.issueFingerprint()) &&
						Objects.equals(linked.getArrangeId(), row.getArrangeId()) &&
						Objects.equals(value.command().worksiteCode(), issue.original().source().worksiteCode()),
				"打印意图与实际标签原件或工位不一致");
		return new Original<>(value, row.getFingerprint());
	}

	private record Scope(Long tenant, String factory, Long actor, String worker) {
	}
}
