package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.MesPlanEngineeringDecisionEntity;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesInFlightSopChangeMapper;
import com.kitex.mom.mes.mapper.MesPlanEngineeringDecisionMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.support.MesPlanEngineeringDecisionCodec;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO.Certificate;
import com.kitex.mom.plan.feign.RemotePlanEngineeringPreparationService;
import com.kitex.mom.plan.support.PlnEngineeringPreparationProofCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 普通PLAN工单未来节点标准工时的异人采用与永久撤销。两动作按原PEP共用本地决策锁，
 * APPLIED批准层与回执同事务保存，CANCELLED先到则阻止迟到采用。原工程和APS投影不覆盖。
 */
@Service
@RequiredArgsConstructor
public class MesPlanEngineeringDecisionService {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");
	private final MesActualFactoryAccessService access;
	private final PermissionService permissions;
	private final MesPlanEngineeringDecisionMapper decisions;
	private final MesInFlightSopChangeMapper facts;
	private final MesPlanLaborStandardService labor;
	private final MesNodeLaborChangePolicy policy;
	private final MesEngineeringPackageService packages;
	private final MesIdentifierBindingGroupService locks;
	private final MesTaskService tasks;
	private final RemotePlanEngineeringPreparationService plans;
	private final PlatformTransactionManager transactions;

	/**
	 * 仅精确回读原永久决策，不重新要求原目标仍具有当前未执行资格。
	 */
	public MesPlanEngineeringDecisionDTO.Receipt request(String requestId) {
		Scope scope = scope(false, false);
		requestId(requestId);
		ready();
		var row = decisions.request(scope.tenant(), scope.factory(), requestId);
		return row == null ? null : receipt(scope, row);
	}

	/**
	 * 先在事务外读取PLAN凭据和正式目标，再用MES本地只读快照展示完整节点对照。
	 * 预核不会批准或解除PLAN保护，当前数量变化也可能使原mapping摘要不再匹配。
	 */
	public MesPlanEngineeringDecisionDTO.Preview preview(String requestId) {
		noOuterTransaction();
		Scope scope = scope(false, false);
		requestId(requestId);
		ready();
		var certificate = certificate(scope, requestId, null);
		require("PREPARED".equals(certificate.preparation().status()) && certificate.preparation().resolution() == null,
				"原准备已经终结，请先查询原号实际决策");
		var target = target(certificate);
		return transaction(true, () -> {
			require(decisions.request(scope.tenant(), scope.factory(), requestId) == null,
					"原号已有永久决策，请查询实际终态");
			var selection = selection(scope, certificate, target);
			String snapshot = labor.contextSnapshot(selection.task(), selection.process());
			return new MesPlanEngineeringDecisionDTO.Preview("MES_PLAN_ENGINEERING_PREVIEW_V1", requestId,
					certificate.authorityFingerprint(), MesPlanEngineeringDecisionCodec.hash(snapshot),
					selection.process().getId().toString(), selection.process().getSourceRouteNodeCode(),
					selection.process().getRouteCode(), policy.comparison(selection.task(), selection.process(),
					target.source().getBundleSnapshot(), target.source().getContentHash()));
		});
	}

	/**
	 * 异人批准前核当前原件与节点范围，提交同一永久APPLIED事实。网络重放先读原终态，
	 * 不因旧版本退役或节点已接收而重新执行；同号异批准正文/人员明确拒绝。
	 */
	public MesPlanEngineeringDecisionDTO.Receipt apply(MesPlanEngineeringDecisionDTO.Apply command) {
		noOuterTransaction();
		Scope scope = scope(true, true);
		require(command != null, "请保留原采用命令");
		requestId(command.requestId());
		hashValue(command.expectedAuthorityFingerprint());
		hashValue(command.expectedMesFingerprint());
		text(command.reason(), 1000);
		ready();
		String payload = command("APPLY", command.requestId(), command.expectedAuthorityFingerprint(),
				command.expectedMesFingerprint(), scope.actor(), command.reason());
		var replay = transaction(false, () -> {
			lock(scope, command.requestId());
			return replayApply(scope, decisions.request(scope.tenant(), scope.factory(), command.requestId()), command,
					payload);
		});
		if (replay != null) {
			return replay;
		}
		var certificate = certificate(scope, command.requestId(), command.expectedAuthorityFingerprint());
		require("PREPARED".equals(certificate.preparation().status()) && certificate.preparation().resolution() == null,
				"PLAN原准备已终结，请查询实际MES决策");
		require(!scope.actor().toString().equals(certificate.preparation().applicantId()),
				"工程准备申请人与采用复核人必须不同");
		var target = target(certificate);
		return transaction(false, () -> {
			lock(scope, command.requestId());
			var existing = replayApply(scope, decisions.request(scope.tenant(), scope.factory(), command.requestId()),
					command, payload);
			if (existing != null) {
				return existing;
			}
			locks.lockDispatchTask(certificate.preparation().original().taskCode());
			var selection = selection(scope, certificate, target);
			String snapshot = labor.contextSnapshot(selection.task(), selection.process());
			require(MesPlanEngineeringDecisionCodec.hash(snapshot).equals(command.expectedMesFingerprint()),
					"待审MES节点、派工或SOP范围已经变化，请重新预核原号");
			var row = base(scope, certificate, "APPLIED", command.reason(), payload);
			row.setProcessId(selection.process().getId());
			row.setRouteNodeCode(selection.process().getSourceRouteNodeCode());
			row.setProcessCode(selection.process().getRouteCode());
			row.setSourceSnapshot(snapshot);
			row.setSourceFingerprint(MesPlanEngineeringDecisionCodec.hash(snapshot));
			row.setBundleSnapshot(target.source().getBundleSnapshot());
			row.setLaborSnapshot(policy.labor(row.getBundleSnapshot(), selection.process()));
			require(row.getLaborSnapshot() != null &&
							row.getLaborSnapshot().getBytes(StandardCharsets.UTF_8).length <= 65536,
					"本节点完整标准工时原件超过64KiB，不能截断采用");
			return save(scope, row);
		});
	}

	/**
	 * 已准备撤销先竞争MES永久CANCELLED；APPLIED先赢只能返回原采用回执。
	 * 不重新取目标生产包或要求原节点仍活跃，避免合法历史撤销被当前资料变化阻断。
	 */
	public MesPlanEngineeringDecisionDTO.Receipt cancel(MesPlanEngineeringDecisionDTO.Cancel command) {
		noOuterTransaction();
		Scope scope = scope(true, false);
		require(command != null, "请保留原永久撤销命令");
		requestId(command.requestId());
		hashValue(command.expectedAuthorityFingerprint());
		text(command.reason(), 1000);
		ready();
		var certificate = certificate(scope, command.requestId(), command.expectedAuthorityFingerprint());
		require(scope.actor().toString().equals(certificate.preparation().applicantId())
						|| permissions.hasPermission("mesInFlightSopChange:review"),
				"仅原申请人或工程审核岗位可以撤销已准备原号");
		String payload = command("CANCEL", command.requestId(), command.expectedAuthorityFingerprint(), null,
				scope.actor(), command.reason());
		return transaction(false, () -> {
			lock(scope, command.requestId());
			var existing = decisions.request(scope.tenant(), scope.factory(), command.requestId());
			if (existing != null) {
				require(command.expectedAuthorityFingerprint().equals(existing.getAuthorityFingerprint()),
						"原号不是同一准备权威凭据");
				if ("CANCELLED".equals(existing.getDecision())) {
					require(scope.actor().equals(existing.getActorId())
									&& MesPlanEngineeringDecisionCodec.hash(payload).equals(existing.getCommandFingerprint()),
							"原号已按原撤销人员和依据结束，请查询原回执");
				}
				return receipt(scope, existing);
			}
			require("PREPARED".equals(certificate.preparation().status()) &&
							certificate.preparation().resolution() == null,
					"PLAN原号已终结但MES回执缺失，请保留原号核查，不能补造撤销");
			return save(scope, base(scope, certificate, "CANCELLED", command.reason(), payload));
		});
	}

	private MesPlanEngineeringDecisionDTO.Receipt replayApply(Scope scope, MesPlanEngineeringDecisionEntity row,
	                                                          MesPlanEngineeringDecisionDTO.Apply command,
	                                                          String payload) {
		if (row == null) {
			return null;
		}
		require(command.expectedAuthorityFingerprint().equals(row.getAuthorityFingerprint()), "原号已绑定不同准备凭据");
		if ("APPLIED".equals(row.getDecision())) {
			require(scope.actor().equals(row.getActorId()) &&
							MesPlanEngineeringDecisionCodec.hash(payload).equals(row.getCommandFingerprint()),
					"原采用已由既有复核人和正文完成，请查询原回执");
		}
		return receipt(scope, row);
	}

	private Certificate certificate(Scope scope, String requestId, String expected) {
		var response = plans.certificate(requestId);
		require(response != null && response.isOk() && response.getData() != null,
				"PLAN原准备凭据未确认，不能作永久决策");
		var value = response.getData();
		PlnEngineeringPreparationProofCodec.validate(value);
		require(scope.tenant().toString().equals(value.preparation().tenantId())
						&& scope.factory().equals(value.preparation().factoryCode())
						&& requestId.equals(value.preparation().original().requestId())
						&& (expected == null || expected.equals(value.authorityFingerprint())),
				"PLAN原号身份、组织或权威摘要不一致");
		return value;
	}

	/**
	 * 正式目标取包在MES写事务前完成；只有持久原正式包参与比较，不接客户端正文。
	 */
	private MesEngineeringPackageService.FrozenEngineering target(Certificate certificate) {
		var query = new MesTaskReceiveDTO();
		query.setEngineeringReleaseId(Long.valueOf(certificate.preparation().original().targetReleaseId()));
		query.setEngineeringContentHash(certificate.preparation().original().targetContentHash());
		query.setMatCode(certificate.productCode());
		query.setFactoryCode(certificate.preparation().factoryCode());
		var result = packages.resolve(query);
		require(certificate.preparation().tenantId().equals(result.source().getTenantId()), "正式目标工程所属租户不同");
		require(result.source().getBundleSnapshot().getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
				"本期节点工时采用的完整目标包超过8MiB，不能截断保存");
		return result;
	}

	/**
	 * 本地工单锁或只读RR快照内核完整映射，并只接受一个真实稳定节点的标准来源差异。
	 */
	private Selection selection(Scope scope, Certificate certificate,
	                            MesEngineeringPackageService.FrozenEngineering target) {
		var task = facts.task(scope.tenant(), scope.factory(), certificate.preparation().original().taskCode());
		require(task != null && certificate.taskId().equals(task.getId().toString()) &&
						scope.tenant().equals(task.getTenantId())
						&& scope.factory().equals(task.getFactoryCode()) && certificate.productCode().equals(task.getMatCode())
						&& certificate.preparation().sourceReleaseId().equals(String.valueOf(task.getEngineeringReleaseId()))
						&& certificate.preparation().sourceContentHash().equals(task.getEngineeringContentHash())
						&& labor.supports(task) &&
						Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE").contains(Objects.toString(task.getStatus(), "")),
				"原MES工单、正式工程或可采用范围已变化");
		var mapping = tasks.planSourceMapping(task.getCode(), null, scope.factory());
		require(mapping != null && Boolean.TRUE.equals(mapping.getMappingComplete()) &&
						"APS_ALLOCATION".equals(mapping.getMappingStatus())
						&& task.getId().equals(mapping.getMesTaskId()) && task.getCode().equals(mapping.getMesTaskCode())
						&& scope.factory().equals(mapping.getFactoryCode()) &&
						task.getMatCode().equals(mapping.getProductCode())
						&& certificate.preparation().sourceReleaseId().equals(mapping.getEngineeringReleaseId())
						&& certificate.preparation().sourceContentHash().equals(mapping.getEngineeringContentHash())
						&& certificate.mappingFingerprint().equals(mapping.getMappingFingerprint()),
				"原准备的MES完整分配或数量已变化，请沿原号撤销后重新准备");
		var processes = facts.processes(scope.tenant(), scope.factory(), task.getCode());
		require(processes != null && !processes.isEmpty() && processes.size() <= 200, "实际稳定节点范围缺失或超限");
		var original = nodes(task.getEngineeringBundleSnapshot());
		var next = nodes(target.source().getBundleSnapshot());
		require(original.size() == processes.size() && original.keySet().equals(next.keySet()),
				"完整稳定节点范围缺失、重复或变化");
		var changed = new ArrayList<MesProduceTaskEntity>();
		var seen = new java.util.HashSet<String>();
		for (var process : processes) {
			require(process != null && process.getId() != null && task.getCode().equals(process.getTaskCode()),
					"实际工序身份不完整或归属不同");
			String node = process.getSourceRouteNodeCode();
			require(node != null && seen.add(node) && original.containsKey(node)
							&& Objects.equals(process.getRouteCode(), original.get(node).path("processCode").asText(null)),
					"实际稳定节点与原工程不唯一对应");
			JsonNode before = original.get(node).get("laborStandard");
			JsonNode after = next.get(node).get("laborStandard");
			String beforeRaw = before == null ? "null" : before.toString();
			String afterRaw = after == null ? "null" : after.toString();
			if (!packages.sameProjection(beforeRaw, afterRaw)) {
				changed.add(process);
			}
		}
		require(changed.size() == 1, "只允许一个首次接收前稳定节点的标准工时来源变化");
		var process = changed.get(0);
		policy.compare(task, process, target.source().getBundleSnapshot(), target.source().getContentHash());
		return new Selection(task, process);
	}

	private HashMap<String, JsonNode> nodes(String raw) {
		try {
			var values = JSON.readTree(raw).path("nodes");
			require(values.isArray() && !values.isEmpty() && values.size() <= 200, "正式包节点范围缺失或超限");
			var result = new HashMap<String, JsonNode>();
			for (var value : values) {
				String code = value.path("routeNodeCode").asText();
				require(code.matches("RN-[1-9][0-9]{0,18}") && result.putIfAbsent(code, value) == null,
						"正式节点身份重复或缺失");
			}
			return result;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原正式节点范围无法解析", failure);
		}
	}

	private MesPlanEngineeringDecisionEntity base(Scope scope, Certificate certificate, String decision, String reason,
	                                              String payload) {
		var row = new MesPlanEngineeringDecisionEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(scope.tenant());
		row.setFactoryCode(scope.factory());
		row.setPreparationId(Long.valueOf(certificate.preparation().id()));
		row.setRequestId(certificate.preparation().original().requestId());
		row.setAuthorityFingerprint(certificate.authorityFingerprint());
		row.setAuthoritySnapshot(PlnEngineeringPreparationProofCodec.canonical(certificate));
		row.setTaskId(Long.valueOf(certificate.taskId()));
		row.setTaskCode(certificate.preparation().original().taskCode());
		row.setProductCode(certificate.productCode());
		row.setSourceReleaseId(Long.valueOf(certificate.preparation().sourceReleaseId()));
		row.setSourceContentHash(certificate.preparation().sourceContentHash());
		row.setTargetReleaseId(Long.valueOf(certificate.preparation().original().targetReleaseId()));
		row.setTargetContentHash(certificate.preparation().original().targetContentHash());
		row.setDecision(decision);
		row.setActorId(scope.actor());
		LocalDateTime now = LocalDateTime.now();
		row.setDecidedAt(now.withNano(now.getNano() / 1000 * 1000));
		row.setReason(reason);
		row.setCommandSnapshot(payload);
		row.setCommandFingerprint(MesPlanEngineeringDecisionCodec.hash(payload));
		row.setCreateBy(scope.actorName());
		row.setUpdateBy(scope.actorName());
		row.setCreateTime(row.getDecidedAt());
		row.setUpdateTime(row.getDecidedAt());
		row.setDelFlag("0");
		return row;
	}

	/**
	 * 规范回执与实际批准来源在同一次本地提交中保存，不用后续远程响应补造成功。
	 */
	private MesPlanEngineeringDecisionDTO.Receipt save(Scope scope, MesPlanEngineeringDecisionEntity row) {
		var value = new MesPlanEngineeringDecisionDTO.Receipt("MES_PLAN_ENGINEERING_DECISION_V1",
				row.getId().toString(),
				row.getTenantId().toString(), row.getFactoryCode(), row.getPreparationId().toString(),
				row.getRequestId(),
				row.getAuthorityFingerprint(), row.getTaskId().toString(), row.getTaskCode(), row.getProductCode(),
				row.getSourceReleaseId().toString(), row.getSourceContentHash(), row.getTargetReleaseId().toString(),
				row.getTargetContentHash(),
				row.getDecision(), row.getActorId().toString(), TIME.format(row.getDecidedAt()), row.getReason(),
				row.getProcessId() == null ? null : row.getProcessId().toString(), row.getRouteNodeCode(),
				row.getProcessCode(),
				row.getSourceFingerprint(),
				row.getLaborSnapshot() == null ? null : MesPlanEngineeringDecisionCodec.hash(row.getLaborSnapshot()),
				row.getCommandFingerprint(), null);
		row.setReceiptSnapshot(MesPlanEngineeringDecisionCodec.canonical(value));
		row.setReceiptFingerprint(MesPlanEngineeringDecisionCodec.hash(row.getReceiptSnapshot()));
		require(row.getReceiptSnapshot().getBytes(StandardCharsets.UTF_8).length <= 65536 && decisions.insert(row) == 1,
				"MES永久决策或采用来源未完整保存，事务已回滚");
		var saved = decisions.request(scope.tenant(), scope.factory(), row.getRequestId());
		require(saved != null, "MES提交前未回读到原决策");
		return receipt(scope, saved);
	}

	private MesPlanEngineeringDecisionDTO.Receipt receipt(Scope scope, MesPlanEngineeringDecisionEntity row) {
		var value = MesPlanEngineeringDecisionCodec.fromSnapshot(row.getReceiptSnapshot(), row.getReceiptFingerprint());
		require(scope.tenant().equals(row.getTenantId()) && scope.factory().equals(row.getFactoryCode())
						&& scope.tenant().toString().equals(value.tenantId()) && scope.factory().equals(value.factoryCode())
						&& row.getId().toString().equals(value.id()) && row.getRequestId().equals(value.requestId())
						&& row.getPreparationId().toString().equals(value.preparationId())
						&& row.getAuthorityFingerprint().equals(value.authorityFingerprint())
						&& MesPlanEngineeringDecisionCodec.hash(row.getAuthoritySnapshot()).equals(value.authorityFingerprint())
						&& MesPlanEngineeringDecisionCodec.hash(row.getCommandSnapshot()).equals(value.commandFingerprint())
						&& row.getTaskId().toString().equals(value.taskId()) && row.getTaskCode().equals(value.taskCode())
						&& row.getProductCode().equals(value.productCode()) &&
						row.getSourceReleaseId().toString().equals(value.sourceReleaseId())
						&& row.getSourceContentHash().equals(value.sourceContentHash()) &&
						row.getTargetReleaseId().toString().equals(value.targetReleaseId())
						&& row.getTargetContentHash().equals(value.targetContentHash()) &&
						Objects.equals(row.getSourceFingerprint(), value.sourceFingerprint())
						&& Objects.equals(row.getProcessId() == null ? null : row.getProcessId().toString(), value.processId())
						&& Objects.equals(row.getRouteNodeCode(), value.routeNodeCode()) &&
						Objects.equals(row.getProcessCode(), value.processCode())
						&& row.getDecision().equals(value.decision()) && row.getActorId().toString().equals(value.actorId())
						&& TIME.format(row.getDecidedAt()).equals(value.decidedAt()) && row.getReason().equals(value.reason()),
				"MES原永久回执与持久事实不一致");
		if ("APPLIED".equals(value.decision())) {
			require(MesPlanEngineeringDecisionCodec.hash(row.getBundleSnapshot()).equals(value.targetContentHash())
							&& MesPlanEngineeringDecisionCodec.hash(row.getSourceSnapshot()).equals(value.sourceFingerprint())
							&& MesPlanEngineeringDecisionCodec.hash(row.getLaborSnapshot()).equals(value.laborFingerprint()),
					"MES原采用完整包、节点范围或工时原件不一致");
		}
		return value;
	}

	private Scope scope(boolean write, boolean adopting) {
		require(permissions.hasPermission("mesTask:show") && permissions.hasPermission("pln_plnIssue_view")
						&& permissions.hasPermission("mesInFlightSopChange:propose", "mesInFlightSopChange:review")
						&& (!write || permissions.hasPermission("pln_plnIssue_edit"))
						&& (!adopting || permissions.hasPermission("mesInFlightSopChange:review")
						&& permissions.hasPermission("mesPlanEngineeringChange:adopt")),
				"当前人员缺少原计划/工程岗位或节点工时采用权限");
		String factory = access.require(write ? "write" : "read");
		var user = SecurityUtils.getUser();
		var authentication = SecurityUtils.getAuthentication();
		Long tenant = TenantContextHolder.getTenantId();
		require(authentication != null && authentication.isAuthenticated() && user != null && user.getId() != null
						&& user.getId() > 0 && tenant != null && tenant > 0 && tenant.equals(user.getTenantId())
						&& !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
				"工程永久决策须实名且所属租户一致，不允许跳过组织范围");
		text(user.getUsername(), 100);
		return new Scope(tenant, factory, user.getId(), user.getUsername());
	}

	private void ready() {
		require(labor.available(), "PLAN工单工时采用协同尚未完整安装");
	}

	private void lock(Scope scope, String request) {
		require(Objects.equals(decisions.lockRequest(scope.tenant(), scope.factory(), request), 1),
				"未取得MES原决策事务锁");
	}

	/**
	 * 同一原PEP永久串行；远程取包/凭据在调用此方法之前，工作体中只有本域读取和写入。
	 */
	private <T> T transaction(boolean readOnly, Supplier<T> work) {
		var template = new TransactionTemplate(transactions);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(
				readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
		template.setReadOnly(readOnly);
		return template.execute(transaction -> work.get());
	}

	private String command(String action, String requestId, String authority, String mes, Long actor, String reason) {
		var values = new LinkedHashMap<String, String>();
		values.put("schemaVersion", "MES_PLAN_ENGINEERING_" + action + "_V1");
		values.put("requestId", requestId);
		values.put("authorityFingerprint", authority);
		values.put("mesFingerprint", mes);
		values.put("actorId", actor.toString());
		values.put("reason", reason);
		try {
			return JSON.writeValueAsString(values);
		} catch (Exception failure) {
			throw new CheckedException("原采用/撤销命令无法规范化", failure);
		}
	}

	private void noOuterTransaction() {
		require(!TransactionSynchronizationManager.isActualTransactionActive(), "MES采用编排不能嵌入已有事务");
	}

	private void requestId(String value) {
		require(value != null && value.length() <= 100 && value.matches("PEP:[1-9][0-9]{0,18}:[A-Za-z0-9_.:-]{8,60}"),
				"请保留有效原PEP号");
	}

	private void hashValue(String value) {
		require(value != null && value.matches("[0-9a-f]{64}"), "原观察或权威凭据摘要无效");
	}

	private void text(String value, int maximum) {
		require(value != null && !value.isBlank() && value.length() <= maximum, "原人员或业务依据不可为空或超长");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Scope(Long tenant, String factory, Long actor, String actorName) {
	}

	private record Selection(MesTaskEntity task, MesProduceTaskEntity process) {
	}
}
