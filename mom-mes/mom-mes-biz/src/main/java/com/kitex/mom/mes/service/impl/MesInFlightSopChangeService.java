package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesFormalSopNodeDTO;
import com.kitex.mom.mes.dto.MesInFlightSopChangeDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesInFlightSopChangeMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 在制普通工单的首轮正式SOP变更。仅在目标稳定节点首次接收前批准生效；
 * 原工程包、用料、参数、工时与实绩永不由本服务覆盖。
 */
@Service
@RequiredArgsConstructor
public class MesInFlightSopChangeService {
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private final MesActualFactoryAccessService access;
	private final MesInFlightSopChangeMapper mapper;
	private final MesIdentifierBindingGroupService locks;
	private final MesEngineeringPackageService packages;
	private final MesSopFreezeService sops;

	/** 校验真实工厂写权限并锁定原号/工单，原号同载荷返回原回执；仅允许未接收、零实绩节点及SOP-only目标包。 */
	@Transactional(rollbackFor = Exception.class)
	public MesInFlightSopChangeEntity propose(MesInFlightSopChangeDTO.Proposal command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(command != null && text(command.getRequestId(), 100)
				&& command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}")
				&& text(command.getReason(), 1000), "请提供稳定原号及1至1000字变更依据");
		mapper.lockRequest(tenant, factory, command.getRequestId());
		Long processId = id(command.getProcessId());
		Long actor = SecurityUtils.getUser().getId();
		var process = process(tenant, factory, processId);
		locks.lockDispatchTask(process.getTaskCode());
		String commandHash = sha(JSONUtil.toJsonStr(Arrays.asList(tenant, factory, actor, command)));
		var replay = mapper.request(tenant, factory, command.getRequestId());
		if (replay != null) {
			require(Objects.equals(replay.getApplicantId(), actor)
					&& Objects.equals(commandHash, replay.getCommandFingerprint()), "原号已用于不同正文或申请人");
			return replay;
		}
		require(mapper.cancelled(tenant, factory, command.getRequestId()) == null, "原申请号已作废，禁止迟到提交，请使用新原号");
		process = process(tenant, factory, processId);
		var task = task(process);
		String source = source(task, process);
		var target = resolve(task, id(command.getReleaseId()), command.getContentHash());
		String sop = freeze(task, process, target);
		var row = new MesInFlightSopChangeEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setRequestId(command.getRequestId());
		row.setCommandFingerprint(commandHash);
		row.setTaskCode(task.getCode());
		row.setProcessId(process.getId());
		row.setRouteNodeCode(process.getSourceRouteNodeCode());
		row.setApplicantId(actor);
		row.setStatus("PENDING");
		row.setSourceSnapshot(source);
		row.setSourceFingerprint(sha(source));
		row.setReleaseId(target.source().getReleaseId());
		row.setContentHash(target.source().getContentHash());
		row.setBundleSnapshot(target.source().getBundleSnapshot());
		row.setSopSnapshot(sop);
		row.setReason(command.getReason());
		row.setProposedAt(LocalDateTime.now());
		require(mapper.insert(row) == 1, "变更申请保存失败");
		return row;
	}

	/** 异人复核原范围指纹；批准须重新精确取包和核对未执行资格，终态仅允许同人同决定同依据重放。 */
	@Transactional(rollbackFor = Exception.class)
	public MesInFlightSopChangeEntity review(Long id, MesInFlightSopChangeDTO.Review command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(command != null && text(command.getReason(), 1000)
				&& Set.of("APPROVE", "REJECT").contains(Objects.toString(command.getDecision(), "")),
				"请明确审批决定及1至1000字复核依据");
		var row = mapper.change(tenant, factory, id);
		require(row != null, "当前组织不存在该SOP变更申请");
		mapper.lockRequest(tenant, factory, row.getRequestId());
		locks.lockDispatchTask(row.getTaskCode());
		row = mapper.change(tenant, factory, id);
		Long actor = SecurityUtils.getUser().getId();
		require(!Objects.equals(actor, row.getApplicantId()), "申请人与复核人须不同，不能自行批准或驳回");
		require(Objects.equals(command.getExpectedFingerprint(), row.getSourceFingerprint()), "待审范围指纹不同，请重新查看申请");
		String result = "APPROVE".equals(command.getDecision()) ? "APPROVED" : "REJECTED";
		if (!"PENDING".equals(row.getStatus())) {
			require(Objects.equals(actor, row.getReviewerId()) && result.equals(row.getStatus())
					&& Objects.equals(command.getReason(), row.getReviewReason()), "原审批已完成，不能改写决定或依据");
			return row;
		}
		if ("APPROVED".equals(result)) {
			var process = process(tenant, factory, row.getProcessId());
			var task = task(process);
			require(Objects.equals(row.getSourceSnapshot(), source(task, process))
					&& Objects.equals(row.getSourceFingerprint(), sha(row.getSourceSnapshot())),
					"目标节点或派工范围已变化，请驳回后重新提交");
			var target = resolve(task, row.getReleaseId(), row.getContentHash());
			require(Objects.equals(target.source().getBundleSnapshot(), row.getBundleSnapshot())
					&& Objects.equals(freeze(task, process, target), row.getSopSnapshot()),
					"批准工程包或正式SOP来源已变化，请重新申请");
		}
		row.setStatus(result);
		row.setReviewerId(actor);
		row.setReviewReason(command.getReason());
		row.setReviewedAt(LocalDateTime.now());
		require(mapper.review(row) == 1, "审批发生并发变化，请核原申请回执");
		return row;
	}

	/** 永久封存尚未受理原号及原载荷；已受理只返回原记录，任何迟到申请不得复活墓碑。 */
	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> cancelOriginal(MesInFlightSopChangeDTO.Proposal command) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(command != null && text(command.getRequestId(), 100)
				&& command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}"), "请提供原申请正文及稳定原号");
		mapper.lockRequest(tenant, factory, command.getRequestId());
		Long actor = SecurityUtils.getUser().getId();
		String hash = sha(JSONUtil.toJsonStr(Arrays.asList(tenant, factory, actor, command)));
		var accepted = mapper.request(tenant, factory, command.getRequestId());
		if (accepted != null) {
			require(Objects.equals(actor, accepted.getApplicantId()) && Objects.equals(hash, accepted.getCommandFingerprint()),
					"该原号已用于不同申请内容或申请人");
			return Map.of("status", "ACCEPTED", "change", accepted);
		}
		var cancelled = mapper.cancelled(tenant, factory, command.getRequestId());
		if (cancelled != null) {
			require(Objects.equals(actor, cancelled.getApplicantId()) && Objects.equals(hash, cancelled.getCommandFingerprint()),
					"原号作废记录属于不同内容或申请人");
			return Map.of("status", "CANCELLED");
		}
		var process = process(tenant, factory, id(command.getProcessId()));
		locks.lockDispatchTask(process.getTaskCode());
		var row = new MesInFlightSopChangeCancellationEntity();
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setRequestId(command.getRequestId());
		row.setCommandFingerprint(hash);
		row.setApplicantId(actor);
		row.setCancelledAt(LocalDateTime.now());
		require(mapper.cancel(row) == 1, "原申请号作废未保存，不可新建替代申请");
		return Map.of("status", "CANCELLED");
	}

	/** 指定执行人的原SOP入口统一取本节点批准层；下游仍核安全撤回和本人签收。 */
	public Effective effective(MesProduceTaskEntity process, MesFormalSopNodeDTO original) {
		var row = mapper.approved(process.getTenantId(), process.getFactoryCode(), process.getId());
		if (row == null) {
			return new Effective(original, null);
		}
		require(Objects.equals(row.getTaskCode(), process.getTaskCode())
				&& Objects.equals(row.getRouteNodeCode(), process.getSourceRouteNodeCode())
				&& Objects.equals(sha(row.getBundleSnapshot()), row.getContentHash())
				&& Objects.equals(sha(row.getSourceSnapshot()), row.getSourceFingerprint()), "批准SOP变更证据或节点归属损坏");
		var frozen = JSONUtil.parseObj(row.getSourceSnapshot());
		var before = frozen.getJSONObject("process");
		require(Objects.equals(before.getStr("processRouteSnapshot"), process.getProcessRouteSnapshot()),
				"原工序路线快照已变化，不能继续消费批准SOP层");
		var task = mapper.task(process.getTenantId(), process.getFactoryCode(), process.getTaskCode());
		require(task != null && Objects.equals(frozen.getStr("originalEngineeringHash"), task.getEngineeringContentHash())
				&& Objects.equals(sha(task.getEngineeringBundleSnapshot()), task.getEngineeringContentHash()),
				"原工单工程包与批准范围不一致");
		var node = JSONUtil.toBean(row.getSopSnapshot(), MesFormalSopNodeDTO.class);
		sops.validateFrozen(node, process.getTenantId().toString(), process.getFactoryCode(),
				process.getProcessRouteCode(), process.getSourceRouteNodeCode(), process.getRouteCode());
		return new Effective(node, Map.of("id", row.getId().toString(), "releaseId", row.getReleaseId().toString(),
				"contentHash", row.getContentHash(), "reason", row.getReason(), "approvedAt", row.getReviewedAt().toString(),
				"effectivePoint", "BEFORE_FIRST_RECEIVE"));
	}

	/** 返回真实组织工单的节点和申请记录；超限拒绝，不能截断审批证据。 */
	public Map<String, Object> context(String taskCode) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(text(taskCode, 100) && mapper.task(tenant, factory, taskCode) != null, "请选择当前组织实际工单");
		var changes = mapper.changes(tenant, factory, taskCode);
		require(changes.size() <= 100, "变更记录超过100项，请按原号查询，不截断审批证据");
		return Map.of("processes", mapper.processes(tenant, factory, taskCode), "changes", changes);
	}

	/** 候选查询先核当前真实工厂读取权，再精确取得本组织工单。 */
	public MesTaskEntity selectionTask(String taskCode) {
		String factory = access.require("read");
		var task = mapper.task(TenantContextHolder.getTenantId(), factory, taskCode);
		require(task != null, "当前组织未找到工单");
		return task;
	}

	/** 原号恢复仅限原申请人；无原回执时返回null，不能推断迟到请求不会受理。 */
	public MesInFlightSopChangeEntity receipt(String requestId) {
		String factory = access.require("read");
		require(text(requestId, 100), "请提供原申请号");
		var row = mapper.request(TenantContextHolder.getTenantId(), factory, requestId);
		require(row == null || Objects.equals(row.getApplicantId(), SecurityUtils.getUser().getId()), "仅原申请人可恢复该原号");
		return row;
	}

	/** 按认证租户、实际工厂及精确ID取工序，拒绝跨组织关联。 */
	private MesProduceTaskEntity process(Long tenant, String factory, Long id) {
		var process = mapper.process(tenant, factory, id);
		require(process != null && Objects.equals(process.getTenantId(), tenant)
				&& factory.equals(process.getFactoryCode()), "当前组织未找到目标工序");
		return process;
	}

	/** 仅普通已下达在制工单允许首次SOP批准层，不承接返工覆盖合同。 */
	private MesTaskEntity task(MesProduceTaskEntity process) {
		var task = mapper.task(process.getTenantId(), process.getFactoryCode(), process.getTaskCode());
		require(task != null && Set.of("ISSUE", "DISPATCH", "PRODUCT", "PAUSE").contains(Objects.toString(task.getStatus(), ""))
				&& !"REWORK".equals(task.getResourceType()), "仅普通已下达在制工单可变更未执行节点SOP");
		return task;
	}

	/** 工单锁内核未接收、零数量/时间实绩及无SOP/报工/实物占用，保存完整原范围和原工程hash。 */
	private String source(MesTaskEntity task, MesProduceTaskEntity process) {
		require(process.getSourceRouteNodeCode() != null && process.getSourceRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
				&& Set.of("WAIT", "UN_RECEIVE").contains(Objects.toString(process.getStatus(), ""))
				&& untouched(process.getCompleteNum(), process.getUnPassNum(), process.getStartTime(), process.getFinishTime()),
				"仅首次接收前、零实绩的稳定节点可申请SOP变更");
		require(mapper.approved(task.getTenantId(), task.getFactoryCode(), process.getId()) == null,
				"本节点已有批准SOP变更，后续修订须另走受控工程合同");
		require(mapper.heldEngineering(task.getTenantId(), task.getFactoryCode(), task.getCode(), process.getCode()) == 0,
				"原工单工程或完整工序图正被实物来源占用，不能通过SOP批准层绕过冻结");
		var arranges = mapper.arranges(task.getTenantId(), task.getFactoryCode(), process.getCode());
		require(arranges.size() <= 200 && arranges.stream().allMatch(row -> task.getCode().equals(row.getTaskCode())
				&& "UN_RECEIVE".equals(row.getStatus())
				&& untouched(row.getCompleteNum(), row.getUnPassNum(), row.getStartTime(), row.getFinishTime())),
				"目标派工已接收或有执行事实，不能替换SOP");
		require(mapper.sopFacts(task.getTenantId(), task.getFactoryCode(), process.getId()) == 0
				&& mapper.reports(task.getTenantId(), task.getFactoryCode(), process.getCode()) == 0,
				"目标节点已有SOP或报工事实，不能追溯替换");
		packages.readIssuedOriginal(task);
		String result = JSONUtil.createObj().set("version", "MES_INFLIGHT_SOP_CHANGE_V1")
				.set("originalEngineeringHash", task.getEngineeringContentHash())
				.set("process", process).set("arranges", arranges).toString();
		require(result.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024, "节点变更范围超过2MiB，禁止截断");
		return result;
	}

	/** 实名精确取目标正式版本；移除唯一顶层SOP清单后全部工程内容必须与原包相同。 */
	private MesEngineeringPackageService.FrozenEngineering resolve(MesTaskEntity task, Long releaseId, String hash) {
		var query = new MesTaskReceiveDTO();
		query.setEngineeringReleaseId(releaseId);
		query.setEngineeringContentHash(hash);
		query.setMatCode(task.getMatCode());
		query.setFactoryCode(task.getFactoryCode());
		var target = packages.resolve(query);
		require(task.getTenantId().toString().equals(target.source().getTenantId()), "批准工程包租户与工单不一致");
		require(target.source().getBundleSnapshot().getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
				"批准工程包超过8MiB，禁止截断");
		try {
			ObjectNode old = (ObjectNode) JSON.readTree(task.getEngineeringBundleSnapshot());
			ObjectNode next = (ObjectNode) JSON.readTree(target.source().getBundleSnapshot());
			old.remove("sopManifest");
			next.remove("sopManifest");
			require(packages.sameProjection(old.toString(), next.toString()),
					"本入口仅支持正式SOP变更；路线、用料、参数、工时或其他工程内容变化须另走受控合同");
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原工程包无法完整核对，禁止变更", failure);
		}
		return target;
	}

	/** 仅从目标原件提取本稳定节点正式SOP，拒绝撤空、撤回、无实际变化或原节点缺失。 */
	private String freeze(MesTaskEntity task, MesProduceTaskEntity process, MesEngineeringPackageService.FrozenEngineering target) {
		var routes = JSONUtil.toList(target.routeSnapshot(), MdmProcessRouteWorkingDTO.class);
		sops.freezeFromEngineeringSnapshot(task, routes, target.sopManifestSnapshot());
		var selected = routes.stream().filter(row -> Objects.equals(process.getSourceRouteNodeCode(), row.getRouteNodeCode())
				&& Objects.equals(process.getRouteCode(), row.getProcessCode())).toList();
		require(selected.size() == 1 && selected.get(0).getFormalSops() != null
				&& !selected.get(0).getFormalSops().getSources().isEmpty(), "批准包缺少目标节点明确正式SOP，不允许撤空或降级");
		var node = selected.get(0).getFormalSops();
		require(Boolean.TRUE.equals(sops.currentState(node).get("allowed")), "目标正式SOP已撤回或当前原件不可核验");
		var originalRoutes = JSONUtil.toList(packages.readIssuedOriginal(task).routeSnapshot(), MdmProcessRouteWorkingDTO.class);
		sops.freezeFromEngineeringSnapshot(task, originalRoutes, packages.readIssuedOriginal(task).sopManifestSnapshot());
		var originalNode = originalRoutes.stream().filter(row -> process.getSourceRouteNodeCode().equals(row.getRouteNodeCode()))
				.findFirst().orElseThrow(() -> new CheckedException("原工程包没有目标稳定节点"));
		require(!Objects.equals(JSONUtil.toJsonStr(originalNode.getFormalSops().getSources()), JSONUtil.toJsonStr(node.getSources())),
				"所选节点正式SOP没有实际版本变化");
		return JSONUtil.toJsonStr(node);
	}

	/** 零实绩必须是明确零数量且无实际开始/结束；null数量不当作默认零。 */
	private boolean untouched(BigDecimal completed, BigDecimal ng, LocalDateTime start, LocalDateTime finish) {
		return completed != null && completed.signum() == 0 && ng != null && ng.signum() == 0 && start == null && finish == null;
	}

	/** 解析精确正整数字符串；缺失、无效或超Long范围均拒绝。 */
	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "记录ID须为精确正整数字符串");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException failure) {
			throw new CheckedException("记录ID超出长整型范围");
		}
	}

	/** 检验非空业务文本及最大长度，不改写原申请正文。 */
	private static boolean text(String value, int max) {
		return value != null && !value.isBlank() && value.length() <= max;
	}

	/** 对永久原件UTF-8字节计算SHA256，原件缺失时拒绝生成虚假指纹。 */
	private static String sha(String value) {
		require(value != null, "永久工程证据正文缺失");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("工程证据指纹计算失败", failure);
		}
	}

	/** 业务条件不成立时抛出明确拒绝，交由原事务合同回滚。 */
	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/** 原冻结节点或本节点批准层；change为空表示未发生批准覆盖。 */
	public record Effective(MesFormalSopNodeDTO node, Map<String, String> change) {
	}
}
