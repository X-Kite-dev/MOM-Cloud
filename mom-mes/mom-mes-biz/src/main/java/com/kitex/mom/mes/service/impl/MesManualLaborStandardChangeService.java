package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO;
import com.kitex.mom.mes.dto.MesManualLaborChangeDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesInFlightSopChangeMapper;
import com.kitex.mom.mes.mapper.MesManualLaborChangeMapper;
import com.kitex.mom.mes.mapper.MesManualTaskMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 独立MOM手工工单首次接收前的标准工时批准层。原工程、路线、计划与实绩从不覆盖；
 * 不承接PLAN关联工单，也不以本地无投料证明WMS无占用。
 */
@Service
@RequiredArgsConstructor
public class MesManualLaborStandardChangeService {
	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.registerModule(new JavaTimeModule())
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private final MesActualFactoryAccessService access;
	private final MesManualLaborChangeMapper mapper;
	private final MesInFlightSopChangeMapper facts;
	private final MesManualTaskMapper manual;
	private final MesIdentifierBindingGroupService locks;
	private final MesEngineeringPackageService packages;
	private final MesManualLaborIssuedSource issuedSource;
	private final MesNodeLaborChangePolicy laborPolicy;

	/**
	 * 原号锁先于真实工单锁；同号同人同正文返回永久回执，已取消原号不能迟到复活。
	 */
	@Transactional(rollbackFor = Exception.class)
	public MesManualLaborChangeEntity propose(MesManualLaborChangeDTO.Proposal command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		validate(command);
		ready();
		mapper.lockRequest(tenant, factory, command.requestId());
		String payload = payload(command);
		String fingerprint = sha(payload);
		var replay = mapper.request(tenant, factory, command.requestId());
		if (replay != null) {
			locks.lockDispatchTask(replay.getTaskCode());
			replay = mapper.request(tenant, factory, command.requestId());
			replay(replay, fingerprint);
			require(!"CANCELLED".equals(replay.getStatus()), "原号已永久作废，不能重新提交");
			return replay;
		}
		var process = process(tenant, factory, id(command.processId()));
		locks.lockDispatchTask(process.getTaskCode());
		process = process(tenant, factory, process.getId());
		var task = task(tenant, factory, process.getTaskCode());
		String source = source(task, process);
		var target = resolve(task, process, id(command.releaseId()), command.contentHash());
		var row = commandRow(task, process, command, payload, fingerprint, "PENDING");
		row.setSourceSnapshot(source);
		row.setSourceFingerprint(sha(source));
		row.setOriginalEngineeringHash(task.getEngineeringContentHash());
		row.setBundleSnapshot(target.source().getBundleSnapshot());
		row.setLaborSnapshot(labor(target.source().getBundleSnapshot(), process));
		require(mapper.insert(row) == 1, "标准工时申请未保存，事务已回滚");
		return row;
	}

	/**
	 * 异人复核原指纹；批准前锁内重核完整范围并重新实名精确取正式目标包。
	 */
	@Transactional(rollbackFor = Exception.class)
	public MesManualLaborChangeEntity review(Long changeId, MesManualLaborChangeDTO.Review command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(command != null && text(command.reason(), 1000)
						&& Set.of("APPROVE", "REJECT").contains(Objects.toString(command.decision(), "")),
				"请填写明确审批决定与复核依据");
		ready();
		var row = mapper.change(tenant, factory, changeId);
		require(row != null && !"CANCELLED".equals(row.getStatus()), "当前组织未找到可复核原申请");
		mapper.lockRequest(tenant, factory, row.getRequestId());
		locks.lockDispatchTask(row.getTaskCode());
		row = mapper.change(tenant, factory, changeId);
		require(!Objects.equals(actor(), row.getApplicantId()), "申请人与复核人必须不同");
		require(Objects.equals(command.expectedFingerprint(), row.getSourceFingerprint()),
				"待审原范围指纹不一致，请重新读取");
		String decision = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
		if (!"PENDING".equals(row.getStatus())) {
			require(Objects.equals(actor(), row.getReviewerId()) && decision.equals(row.getStatus())
					&& command.reason().equals(row.getReviewReason()), "原审批已完成，不允许更改复核人、决定或依据");
			return row;
		}
		if ("APPROVED".equals(decision)) {
			var process = process(tenant, factory, row.getProcessId());
			var task = task(tenant, factory, row.getTaskCode());
			require(Objects.equals(source(task, process), row.getSourceSnapshot())
							&& Objects.equals(sha(row.getSourceSnapshot()), row.getSourceFingerprint()),
					"原节点、派工或SOP批准范围已变化，请驳回后重新申请");
			var target = resolve(task, process, row.getReleaseId(), row.getContentHash());
			require(Objects.equals(target.source().getBundleSnapshot(), row.getBundleSnapshot())
							&& Objects.equals(labor(row.getBundleSnapshot(), process), row.getLaborSnapshot()),
					"正式目标原件已变化，请重新申请");
		}
		row.setStatus(decision);
		row.setReviewerId(actor());
		row.setReviewReason(command.reason());
		row.setReviewedAt(LocalDateTime.now());
		require(mapper.review(row) == 1, "审批已并发变化，请核对原回执");
		return row;
	}

	/**
	 * 封存尚未受理原正文与原号；已接受时仅返回原申请，不声称已取消业务。
	 */
	@Transactional(rollbackFor = Exception.class)
	public MesManualLaborChangeEntity cancel(MesManualLaborChangeDTO.Proposal command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		validate(command);
		ready();
		mapper.lockRequest(tenant, factory, command.requestId());
		String payload = payload(command);
		String fingerprint = sha(payload);
		var old = mapper.request(tenant, factory, command.requestId());
		if (old != null) {
			locks.lockDispatchTask(old.getTaskCode());
			old = mapper.request(tenant, factory, command.requestId());
			replay(old, fingerprint);
			return old;
		}
		var process = mapper.processAny(tenant, factory, id(command.processId()));
		require(process != null, "当前组织未找到可永久作废原号的原工序");
		locks.lockDispatchTask(process.getTaskCode());
		process = mapper.processAny(tenant, factory, process.getId());
		require(process != null, "原工序身份已变化，请核对原号");
		var task = mapper.taskAny(tenant, factory, process.getTaskCode());
		require(task != null, "原工单身份缺失，请保留原号核对");
		assertManual(task);
		var row = commandRow(task, process, command, payload, fingerprint, "CANCELLED");
		require(mapper.insert(row) == 1, "原号作废未保存，请保留原号继续核对");
		return row;
	}

	/**
	 * 仅原申请人恢复原号；不存在不代表迟到请求不会接受。
	 */
	public MesManualLaborChangeEntity receipt(String requestId) {
		String factory = access.require("read");
		require(text(requestId, 100), "请提供原申请号");
		ready();
		var row = mapper.request(TenantContextHolder.getTenantId(), factory, requestId);
		require(row == null || Objects.equals(actor(), row.getApplicantId()), "仅原申请人可以恢复原号");
		return row;
	}

	/**
	 * 完整节点与申请记录；非独立手工来源明确拒绝，超限不截断证据。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Map<String, Object> context(String taskCode) {
		var task = selectionTask(taskCode);
		var processes = facts.processes(task.getTenantId(), task.getFactoryCode(), taskCode);
		require(mapper.changeCount(task.getTenantId(), task.getFactoryCode(), taskCode) <= 100
						&& mapper.bundleBytes(task.getTenantId(), task.getFactoryCode(), taskCode) <= 16 * 1024 * 1024,
				"申请历史超过100项或原件16MiB完整上限，请按原号查询，不截断");
		var changes = mapper.changes(task.getTenantId(), task.getFactoryCode(), taskCode);
		require(processes.size() <= 200 && changes.size() <= 100, "节点或申请超出完整读取上限，请按原号核对");
		long bytes = changes.stream().filter(row -> row.getBundleSnapshot() != null)
				.mapToLong(row -> row.getBundleSnapshot().getBytes(StandardCharsets.UTF_8).length).sum();
		require(bytes <= 16 * 1024 * 1024, "申请原件总量超过16MiB完整读取上限，请按原号核对，不截断历史");
		for (var row : changes) {
			if (!"CANCELLED".equals(row.getStatus())) {
				var process = mapper.processAny(task.getTenantId(), task.getFactoryCode(), row.getProcessId());
				require(process != null && Objects.equals(process.getTaskCode(), task.getCode())
								&& Objects.equals(process.getSourceRouteNodeCode(), row.getRouteNodeCode())
								&& Objects.equals(task.getEngineeringContentHash(), row.getOriginalEngineeringHash()),
						"原申请稳定节点或原工程归属无法核验");
				compare(task, process, row.getBundleSnapshot(), row.getContentHash());
				row.setComparison(comparison(task, process, row.getBundleSnapshot(), row.getContentHash()));
			}
		}
		return Map.of("processes", processes, "changes", changes);
	}

	/**
	 * 精确候选读取前核工单真实组织与独立手工回执。
	 */
	public MesTaskEntity selectionTask(String taskCode) {
		String factory = access.require("read");
		ready();
		return task(TenantContextHolder.getTenantId(), factory, taskCode);
	}

	/**
	 * 申请准备阶段精确取正式目标包并返回小型对照；只读预览不授予未执行资格或产生申请。
	 */
	public MesManualLaborChangeDTO.Comparison preview(String taskCode, String processId, String releaseId,
	                                                  String contentHash) {
		var task = selectionTask(taskCode);
		var process = process(task.getTenantId(), task.getFactoryCode(), id(processId));
		require(Objects.equals(process.getTaskCode(), task.getCode()), "预览节点不属于原工单");
		var target = resolve(task, process, id(releaseId), contentHash);
		return comparison(task, process, target.source().getBundleSnapshot(), target.source().getContentHash());
	}

	private MesManualLaborChangeDTO.Comparison comparison(MesTaskEntity task, MesProduceTaskEntity process,
	                                                      String target, String hash) {
		return laborPolicy.comparison(task, process, target, hash);
	}

	/**
	 * 在原receive事务与工单锁内冻结实际采用来源；仅插入独立账，不修改原路线或工程。
	 * 同节点其他派工接收后必须采用第一次冻结的同一来源，不能追溯切换。
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public void freezeForReceive(MesTaskArrangeEntity arrange) {
		if (!MesManualTaskService.PREFIX.equals(
				prefix(arrange.getTaskCode(), arrange.getTenantId(), arrange.getFactoryCode()))) {
			return;
		}
		if (!available()) {
			return;
		}
		var task = task(arrange.getTenantId(), arrange.getFactoryCode(), arrange.getTaskCode());
		var matches = facts.processes(task.getTenantId(), task.getFactoryCode(), task.getCode()).stream()
				.filter(row -> Objects.equals(row.getCode(), arrange.getProduceTaskCode())).toList();
		require(matches.size() == 1, "接收派工未唯一对应原稳定节点");
		var process = matches.get(0);
		require(Objects.equals(process.getSourceRouteNodeCode(), arrange.getSourceRouteNodeCode()),
				"接收派工稳定节点归属不同");
		noPlanProjection(process);
		noPlanProjection(arrange);
		var effective = effective(task, process);
		var same = effective.adoptions().stream().filter(row -> Objects.equals(row.getArrangeId(), arrange.getId()))
				.toList();
		if (!same.isEmpty()) {
			return;
		}
		if (effective.change() != null && effective.adoptions().isEmpty()) {
			untouched(task, process, false);
		}
		var standard = effective.standard();
		var row = new MesManualLaborAdoptionEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(task.getTenantId());
		row.setFactoryCode(task.getFactoryCode());
		row.setTaskCode(task.getCode());
		row.setTaskId(task.getId());
		row.setProcessId(process.getId());
		row.setRouteNodeCode(process.getSourceRouteNodeCode());
		row.setArrangeId(arrange.getId());
		row.setChangeId(effective.change() == null ? null : effective.change().getId());
		row.setOriginalEngineeringHash(task.getEngineeringContentHash());
		row.setReleaseId(
				effective.change() == null ? task.getEngineeringReleaseId() : effective.change().getReleaseId());
		row.setContentHash(
				effective.change() == null ? task.getEngineeringContentHash() : effective.change().getContentHash());
		row.setLaborSnapshot(standard);
		row.setSourceFingerprint(standard == null ? null : decode(standard, task, process).getSourceFingerprint());
		row.setReceivedAt(LocalDateTime.now());
		require(mapper.adopt(row) == 1, "接收标准工时采用来源未保存，接收已回滚");
	}

	/**
	 * 读取原包、批准层与不可变接收采用账；审批后不查询今日MDM，不用新配置改写历史。
	 */
	public Effective effective(MesTaskEntity task, MesProduceTaskEntity process) {
		assertManual(task);
		var approved = mapper.approved(task.getTenantId(), task.getFactoryCode(), process.getId());
		String standard = labor(task.getEngineeringBundleSnapshot(), process);
		if (approved != null) {
			require(Objects.equals(approved.getTaskId(), task.getId()) &&
					Objects.equals(approved.getTaskCode(), task.getCode())
					&& Objects.equals(approved.getRouteNodeCode(), process.getSourceRouteNodeCode())
					&& Objects.equals(approved.getOriginalEngineeringHash(), task.getEngineeringContentHash())
					&& Objects.equals(sha(approved.getSourceSnapshot()), approved.getSourceFingerprint())
					&& Objects.equals(
					read(approved.getSourceSnapshot()).path("process").path("processRouteSnapshot").asText(null),
					process.getProcessRouteSnapshot()), "批准层原工单或来源范围损坏");
			compare(task, process, approved.getBundleSnapshot(), approved.getContentHash());
			require(Objects.equals(labor(approved.getBundleSnapshot(), process), approved.getLaborSnapshot()),
					"批准标准来源与完整目标原件不同");
			standard = approved.getLaborSnapshot();
		}
		compatibleSop(task, process);
		var received = mapper.adoptions(task.getTenantId(), task.getFactoryCode(), process.getId());
		require(received.size() <= 200, "接收采用记录超过完整上限，禁止截断");
		for (var row : received) {
			require(Objects.equals(row.getTaskId(), task.getId()) && Objects.equals(row.getTaskCode(), task.getCode())
							&& Objects.equals(row.getRouteNodeCode(), process.getSourceRouteNodeCode())
							&& Objects.equals(row.getOriginalEngineeringHash(), task.getEngineeringContentHash())
							&& Objects.equals(row.getChangeId(), approved == null ? null : approved.getId())
							&& Objects.equals(row.getReleaseId(),
							approved == null ? task.getEngineeringReleaseId() : approved.getReleaseId())
							&& Objects.equals(row.getContentHash(),
							approved == null ? task.getEngineeringContentHash() : approved.getContentHash())
							&& Objects.equals(row.getLaborSnapshot(), standard)
							&& Objects.equals(row.getSourceFingerprint(),
							standard == null ? null : decode(standard, task, process).getSourceFingerprint()),
					"接收时标准工时采用来源与批准层不一致，禁止改用其他版本");
		}
		return new Effective(standard, approved, received);
	}

	/**
	 * 只对真实独立手工单读取批准层；其他来源沿原工时只读合同。
	 */
	public boolean supports(MesTaskEntity task) {
		return MesManualTaskService.owns(task);
	}

	/**
	 * 供原标准视图读取完整实际节点，不按主工序猜首条。
	 */
	public List<MesProduceTaskEntity> processes(MesTaskEntity task) {
		if (!available()) {
			return List.of();
		}
		assertManual(task);
		var rows = facts.processes(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(rows.size() <= 200, "实际节点超出完整上限");
		return rows;
	}

	/**
	 * 工单锁后核完整目标范围；已存在任何接收采用、报工或原SOP事实均不再接受批准。
	 */
	private String source(MesTaskEntity task, MesProduceTaskEntity process) {
		assertManual(task);
		var allProcesses = facts.processes(task.getTenantId(), task.getFactoryCode(), task.getCode());
		require(allProcesses.size() <= 200 && allProcesses.stream()
						.filter(row -> Objects.equals(row.getSourceRouteNodeCode(), process.getSourceRouteNodeCode()))
						.count() == 1,
				"完整工单范围内稳定节点不唯一或超限，不能选择同码首条变更");
		require(mapper.approved(task.getTenantId(), task.getFactoryCode(), process.getId()) == null,
				"本节点已有批准标准工时，后续换版须另走受控合同");
		require(mapper.adoptions(task.getTenantId(), task.getFactoryCode(), process.getId()).isEmpty(),
				"本节点已有首次接收采用来源，不能追溯换版");
		untouched(task, process, true);
		var sop = compatibleSop(task, process);
		String source = JSONUtil.createObj().set("version", "MES_MANUAL_LABOR_CHANGE_V1")
				.set("taskId", task.getId().toString()).set("originalEngineeringHash", task.getEngineeringContentHash())
				.set("manualRequestHash", task.getSourceRequestFingerprint()).set("process", process)
				.set("arranges", mapper.arranges(task.getTenantId(), task.getFactoryCode(), process.getCode()))
				.set("sopChangeId", sop == null ? null : sop.getId().toString())
				.set("sopContentHash", sop == null ? null : sop.getContentHash()).toString();
		require(source.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "完整目标范围超过2MiB，禁止截断");
		return source;
	}

	private void untouched(MesTaskEntity task, MesProduceTaskEntity process, boolean approving) {
		require(Objects.equals(task.getCode(), process.getTaskCode()) && process.getSourceRouteNodeCode() != null
						&& process.getSourceRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
						&& Set.of("WAIT", "UN_RECEIVE").contains(Objects.toString(process.getStatus(), ""))
						&& zero(process.getCompleteNum(), process.getUnPassNum()) && process.getStartTime() == null &&
						process.getFinishTime() == null,
				"仅首次接收前、零实绩的稳定节点可采用新标准工时");
		noPlanProjection(process);
		var arranges = mapper.arranges(task.getTenantId(), task.getFactoryCode(), process.getCode());
		require(arranges.size() <= 200, "派工超过完整读取上限");
		for (var row : arranges) {
			require(Objects.equals(row.getTaskCode(), task.getCode())
							&& Objects.equals(row.getSourceRouteNodeCode(), process.getSourceRouteNodeCode())
							&& "0".equals(row.getDelFlag()) && "UN_RECEIVE".equals(row.getStatus()) &&
							zero(row.getCompleteNum(), row.getUnPassNum())
							&& row.getStartTime() == null && row.getFinishTime() == null,
					"目标完整派工范围已接收或存在执行事实");
			noPlanProjection(row);
		}
		// 原包、图和BOM不变，早序对整单原件的引用保持；只阻断目标工序自身占用。
		require(facts.reports(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0
						&& (!approving || facts.sopFacts(task.getTenantId(), task.getFactoryCode(), process.getId()) == 0)
						&& (!approving || mapper.feeds(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0)
						&&
						mapper.heldNode(task.getTenantId(), task.getFactoryCode(), process.getCode()) ==
								0,
				"原节点已有报工、SOP、投料或自身实物来源冻结事实，不能替换标准工时");
	}

	/**
	 * SOP层和标准工时层分别与同一原工程核对允许维度，不互相冒充完整新工程。
	 */
	private MesInFlightSopChangeEntity compatibleSop(MesTaskEntity task, MesProduceTaskEntity process) {
		var sop = facts.approved(task.getTenantId(), task.getFactoryCode(), process.getId());
		if (sop == null) {
			return null;
		}
		require(Objects.equals(sop.getTaskCode(), task.getCode()) &&
				Objects.equals(sop.getRouteNodeCode(), process.getSourceRouteNodeCode())
				&& Objects.equals(sha(sop.getBundleSnapshot()), sop.getContentHash())
				&& Objects.equals(sha(sop.getSourceSnapshot()), sop.getSourceFingerprint()), "并存SOP批准来源损坏");
		JsonNode scope = read(sop.getSourceSnapshot());
		require(task.getEngineeringContentHash().equals(scope.path("originalEngineeringHash").asText())
						&& Objects.equals(scope.path("process").path("processRouteSnapshot").asText(null),
						process.getProcessRouteSnapshot()),
				"SOP与工时批准层并非基于同一原工程");
		ObjectNode original = read(task.getEngineeringBundleSnapshot());
		ObjectNode next = read(sop.getBundleSnapshot());
		original.remove("sopManifest");
		next.remove("sopManifest");
		require(packages.sameProjection(original.toString(), next.toString()),
				"并存SOP批准层包含非SOP变化，不能叠加工时来源");
		return sop;
	}

	/**
	 * 实名精确取包；本入口只接受本节点工时变更，schema版本保持相同，不隐式升级旧标准。
	 */
	private MesEngineeringPackageService.FrozenEngineering resolve(MesTaskEntity task, MesProduceTaskEntity process,
	                                                               Long release, String hash) {
		var query = new MesTaskReceiveDTO();
		query.setEngineeringReleaseId(release);
		query.setEngineeringContentHash(hash);
		query.setMatCode(task.getMatCode());
		query.setFactoryCode(task.getFactoryCode());
		var target = packages.resolve(query);
		require(task.getTenantId().toString().equals(target.source().getTenantId()), "正式目标版本租户不一致");
		compare(task, process, target.source().getBundleSnapshot(), target.source().getContentHash());
		return target;
	}

	private void compare(MesTaskEntity task, MesProduceTaskEntity process, String raw, String hash) {
		laborPolicy.compare(task, process, raw, hash);
	}

	private String labor(String raw, MesProduceTaskEntity process) {
		return laborPolicy.labor(raw, process);
	}

	private MdmProcedureLaborStandardDTO decode(String raw, MesTaskEntity task, MesProduceTaskEntity process) {
		return laborPolicy.decode(raw, task, process);
	}

	/**
	 * 原正式手工接受回执与工单精确关联，所有PLAN/ERP/APS来源必须真实为空。
	 */
	private void assertManual(MesTaskEntity task) {
		require(MesManualTaskService.owns(task) && "MOM_MRP".equals(task.getMrpAuthority())
						&& "MOM_ORDER_ONLY".equals(task.getProductionOrderSyncMode()) && "MOM".equals(task.getSourceSystem())
						&& Objects.equals(task.getProductionOrder(), task.getCode()) && task.getPlanningPolicyId() != null
						&& task.getPlanningPolicyVersion() != null && task.getPlanningPolicyFingerprint() != null,
				"仅原独立MOM手工建单回执支持节点标准工时批准层");
		noPlanProjection(task);
		require(mapper.apsLinks(task.getTenantId(), task.getFactoryCode(), task.getId()) == 0,
				"手工工单已有APS来源分配账，不能绕过PLAN变更合同");
		String requestId = task.getSourceRequestId().substring(MesManualTaskService.PREFIX.length());
		var receipt = manual.receipt(task.getTenantId(), task.getFactoryCode(), requestId);
		require(receipt != null && "ACCEPTED".equals(receipt.getState()) &&
						Objects.equals(receipt.getTaskId(), task.getId())
						&& Objects.equals(receipt.getTaskCode(), task.getCode()) && receipt.getActorId() != null
						&& Objects.equals(sha(receipt.getCommandPayload()), receipt.getCommandFingerprint())
						&& Objects.equals(receipt.getCommandFingerprint(), task.getSourceRequestFingerprint()),
				"原手工建单接受回执缺失或与工单不同");
		if ("CREATE".equals(task.getStatus())) {
			var original = packages.readIssuedOriginal(task);
			require(packages.sameProjection(task.getProcessRouteSnapshot(), original.routeSnapshot())
							&& packages.sameProjection(task.getBomSnapshot(), original.bomSnapshot()),
					"原手工草稿路线或BOM来源已漂移");
		} else {
			issuedSource.validate(task);
		}
		// 368实际原件的数量和日期都是文本；与组合共用纯解析，不改变本层批准/采用资格。
		MesIndependentManualTaskSource.validateOriginalCommand(task, requestId, receipt);
		packages.readIssuedOriginal(task);
	}

	/**
	 * 所有同类PLAN投影逐属性核空，不能漏掉继承的新增资源字段；计划数量不是APS来源字段。
	 */
	private void noPlanProjection(Object bean) {
		var view = new BeanWrapperImpl(bean);
		for (var descriptor : view.getPropertyDescriptors()) {
			String name = descriptor.getName();
			if (name.startsWith("aps") || name.startsWith("formalPlan") || name.startsWith("sourcePlan")
					|| name.startsWith("planned") && !"plannedNum".equals(name)
					|| Set.of("planNo", "materialReservationSnapshot", "toolingAllocationSnapshot",
							"toolingLifeUsageSnapshot",
							"erpProductionOrderNo", "resourceId", "reworkRequestId", "reworkMaterialPlanId", "sourceTaskCode",
							"sourceProduceTaskCode", "sourceInspectionType", "sourceInspectionTaskId", "sourceHandleRecordId")
					.contains(name)) {
				require(view.getPropertyValue(name) == null, "独立手工工单已有计划、资源或外部来源投影：" + name);
			}
		}
	}

	private MesTaskEntity task(Long tenant, String factory, String code) {
		require(text(code, 100), "请选择当前组织原工单");
		var task = facts.task(tenant, factory, code);
		require(task != null && Objects.equals(task.getTenantId(), tenant) &&
				Objects.equals(task.getFactoryCode(), factory), "当前组织不存在原工单");
		assertManual(task);
		require(Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE", "FINISH")
				.contains(Objects.toString(task.getStatus(), "")), "仅已下达独立手工工单支持标准来源批准与回读");
		return task;
	}

	private MesProduceTaskEntity process(Long tenant, String factory, Long id) {
		var process = facts.process(tenant, factory, id);
		require(process != null, "当前组织不存在目标实际工序");
		return process;
	}

	/**
	 * 判别接收所属工单；普通PLAN/返工接收不依赖新批准层表。
	 */
	private String prefix(String code, Long tenant, String factory) {
		var task = facts.task(tenant, factory, code);
		require(task != null, "派工原生产工单不存在");
		return MesManualTaskService.owns(task) ? MesManualTaskService.PREFIX : "";
	}

	private MesManualLaborChangeEntity commandRow(MesTaskEntity task, MesProduceTaskEntity process,
	                                              MesManualLaborChangeDTO.Proposal command, String payload,
	                                              String fingerprint, String status) {
		var row = new MesManualLaborChangeEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(task.getTenantId());
		row.setFactoryCode(task.getFactoryCode());
		row.setTaskId(task.getId());
		row.setTaskCode(task.getCode());
		row.setProcessId(process.getId());
		row.setRouteNodeCode(process.getSourceRouteNodeCode());
		row.setRequestId(command.requestId());
		row.setCommandSnapshot(payload);
		row.setCommandFingerprint(fingerprint);
		row.setApplicantId(actor());
		row.setStatus(status);
		row.setReleaseId(id(command.releaseId()));
		row.setContentHash(command.contentHash());
		row.setReason(command.reason());
		row.setProposedAt(LocalDateTime.now());
		return row;
	}

	private void replay(MesManualLaborChangeEntity row, String fingerprint) {
		require(Objects.equals(row.getApplicantId(), actor()) &&
				Objects.equals(row.getCommandFingerprint(), fingerprint), "原号已被不同申请人或正文使用");
	}

	private void validate(MesManualLaborChangeDTO.Proposal command) {
		require(command != null && command.requestId() != null && command.requestId().matches("[A-Za-z0-9_.:-]{8,100}")
				&& text(command.reason(), 1000) && command.contentHash() != null &&
				command.contentHash().matches("[0-9a-f]{64}"), "请提供稳定原号、正式版本指纹与1至1000字依据");
		id(command.processId());
		id(command.releaseId());
	}

	/**
	 * 仅未安装且完全没有新账时保留旧接收/原标准读取；存在任何新对象则必须完整就绪。
	 */
	private boolean available() {
		boolean function = Boolean.TRUE.equals(mapper.readyPresent());
		boolean objects = Boolean.TRUE.equals(mapper.installationObjectsPresent());
		if (!function && !objects) {
			return false;
		}
		require(function && objects && Integer.valueOf(1).equals(mapper.ready()),
				"标准工时批准或接收来源保护未完整安装，请核对统一升级入口");
		return true;
	}

	/**
	 * 当前真实手工工单是否可显示独立变更入口；尚未下达或未安装保持原标准只读。
	 */
	public boolean changeAvailable(MesTaskEntity task) {
		return supports(task) && available() && Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE", "FINISH")
				.contains(Objects.toString(task.getStatus(), ""));
	}

	private void ready() {
		require(available(), "标准工时批准层尚未安装，请先由实施人员核对统一升级入口");
	}

	private ObjectNode read(String raw) {
		require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
				"来源原件缺失或超过8MiB，禁止截断");
		try {
			var node = JSON.readTree(raw);
			require(node instanceof ObjectNode, "来源原件不是对象");
			return (ObjectNode) node;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("来源原件无法完整解析", failure);
		}
	}

	private String payload(MesManualLaborChangeDTO.Proposal command) {
		try {
			return JSON.writeValueAsString(command);
		} catch (Exception failure) {
			throw new CheckedException("原申请正文无法生成", failure);
		}
	}

	private Long actor() {
		return SecurityUtils.getUser().getId();
	}

	private boolean zero(BigDecimal... values) {
		return Arrays.stream(values).allMatch(value -> value != null && value.signum() == 0);
	}

	private boolean text(String value, int limit) {
		return value != null && !value.isBlank() && value.length() <= limit;
	}

	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "ID必须是完整正整数文本");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException failure) {
			throw new CheckedException("ID超出长整数范围");
		}
	}

	private String sha(String value) {
		require(value != null, "来源原件不能为空");
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new CheckedException("来源指纹无法计算", failure);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 受控读取结果，采用账中的标准原件必须与批准层/原包逐字相同。
	 */
	public record Effective(String standard, MesManualLaborChangeEntity change,
	                        List<MesManualLaborAdoptionEntity> adoptions) {
	}
}
