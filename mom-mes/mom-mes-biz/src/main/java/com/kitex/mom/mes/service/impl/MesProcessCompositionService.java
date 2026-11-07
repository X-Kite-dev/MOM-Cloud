package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.dto.MesDispatchAllocationDTO;
import com.kitex.mom.mes.dto.MesTaskArrangeDTO;
import com.kitex.mom.mes.vo.MesDispatchAllocationVO;
import java.math.BigDecimal;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesProcessCompositionMapper;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 组合配置的事务边界；生产事实仍由现有工单、工序和派工服务持有。
 */
@Service
@RequiredArgsConstructor
public class MesProcessCompositionService {
	private static final String COMMAND_KIND = "PROCESS_COMPOSITION";

	private final MesActualFactoryAccessService factoryAccess;
	private final MesProcessCompositionMapper mapper;
	private final MesTaskService tasks;
	private final MesProduceTaskService operations;
	private final MesTaskArrangeService arrangements;
	private final MesIdentifierBindingGroupService taskLocks;
	private final MesExecutionCommandCancellationService cancellations;
	private final MesIndependentManualTaskSource independentManualSource;
	private final MesDispatchAllocationService allocationService;
	private final MesDispatchAllocationLedger allocationLedger;

	/**
	 * 返回当前代码适配的来源及层级，不核数据库ready，也不构成某工单的派工许可。
	 * 原能力版本及schema保持，旧客户端可以忽略新增taskSources。
	 */
	public Map<String, Object> capabilities() {
		String factory = factoryAccess.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0, "过程能力缺少实际租户");
		return Map.of("contractVersion", "PROCESS_COMPOSITION_CAPABILITIES_V1",
				"tenantId", tenant.toString(), "factoryCode", factory, "schema", MesProcessCompositionSchema.schema(),
				"dispatchAllocationContract", "MES_DISPATCH_ALLOCATION_V1",
				"taskSources", List.of(
						Map.of("code", "FORMAL_PLAN", "label", "计划工单", "levelCounts", List.of(2, 3, 4)),
						Map.of("code", "INDEPENDENT_MANUAL", "label", "手工工单", "levelCounts", List.of(2, 3))));
	}

	@Transactional(rollbackFor = Exception.class)
	public MesProcessCompositionDTO.Template saveDraft(MesProcessCompositionDTO.Draft draft) {
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(draft != null && draft.getCode() != null && draft.getCode().matches("[A-Za-z0-9_-]{1,60}")
						&& draft.getName() != null && !draft.getName().isBlank() && draft.getName().length() <= 100,
				"请填写有效过程编码与1至100字名称");
		MesProcessCompositionModel.validate(draft.getDefinition());
		mapper.lock(tenant, factory, "TEMPLATE:" + draft.getCode());
		var actor = SecurityUtils.getUser();
		MesProcessCompositionTemplateEntity row;
		if (draft.getId() == null) {
			row = new MesProcessCompositionTemplateEntity();
			row.setId(IdWorker.getId());
			row.setTenantId(tenant);
			row.setFactoryCode(factory);
			row.setCode(draft.getCode());
			row.setVersionNo(mapper.maxVersion(tenant, factory, draft.getCode()) + 1);
			row.setRevisionNo(0L);
			row.setStatus("DRAFT");
			row.setCreateBy(actor.getUsername());
			row.setCreateTime(LocalDateTime.now());
		} else {
			row = mapper.template(tenant, factory, id(draft.getId()));
			require(row != null && row.getCode().equals(draft.getCode()) && "DRAFT".equals(row.getStatus())
					&& Objects.equals(row.getRevisionNo(), draft.getRevisionNo()), "过程草稿已变化或版本已发布，请重新加载");
		}
		row.setName(draft.getName().trim());
		row.setDefinitionJson(JSONUtil.toJsonStr(draft.getDefinition()));
		row.setUpdateBy(actor.getUsername());
		row.setUpdateTime(LocalDateTime.now());
		require((draft.getId() == null ? mapper.insertTemplate(row) : mapper.updateDraft(row)) == 1,
				"过程草稿保存失败或并发修订，请重新加载");
		return templateView(mapper.template(tenant, factory, row.getId()));
	}

	@Transactional(rollbackFor = Exception.class)
	public MesProcessCompositionDTO.Template publish(String templateId, Long expectedRevisionNo) {
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		var row = mapper.template(tenant, factory, id(templateId));
		require(row != null, "当前组织不存在该过程版本");
		mapper.lock(tenant, factory, "TEMPLATE:" + row.getCode());
		row = mapper.template(tenant, factory, row.getId());
		require("DRAFT".equals(row.getStatus()) && Objects.equals(row.getRevisionNo(), expectedRevisionNo),
				"过程草稿已变化或已发布，请重新加载");
		MesProcessCompositionModel.validate(definition(row.getDefinitionJson()));
		row.setStatus("PUBLISHED");
		row.setUpdateBy(SecurityUtils.getUser().getUsername());
		row.setUpdateTime(LocalDateTime.now());
		require(mapper.updateDraft(row) == 1, "过程版本发布失败或发生并发修订");
		return templateView(mapper.template(tenant, factory, row.getId()));
	}

	public List<MesProcessCompositionDTO.Template> templates() {
		String factory = factoryAccess.require("read");
		return mapper.templates(TenantContextHolder.getTenantId(), factory).stream().map(this::templateView).toList();
	}

	private MesProcessCompositionDTO.Template templateView(MesProcessCompositionTemplateEntity row) {
		var result = new MesProcessCompositionDTO.Template();
		result.setId(row.getId().toString());
		result.setCode(row.getCode());
		result.setName(row.getName());
		result.setVersionNo(row.getVersionNo());
		result.setRevisionNo(row.getRevisionNo());
		result.setStatus(row.getStatus());
		result.setDefinition(definition(row.getDefinitionJson()));
		return result;
	}

	/**
	 * 精确已发布配置首次覆盖完整工序并调用原派工；原号重放始终先返回原实例。
	 * 新手工来源只允许两/三级，出生原件及原工程须有效，不能伪造四级订单或绕开PLAN来源。
	 *
	 * @param command 本次精确版本、工单及派工的稳定原命令
	 * @return 原运行及当前数量观察；必要派工或绑定失败时同一事务整体回滚
	 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesProcessCompositionDTO.View run(MesProcessCompositionDTO.Run command) {
		String factory = factoryAccess.require("write");
		String fingerprint = commandFingerprint(command, TenantContextHolder.getTenantId(), factory,
				SecurityUtils.getUser().getId());
		return runInternal(command, null, fingerprint);
	}

	/** 新一对多运行：原外号与确定逐工单子号同事务，旧Run原文及指纹不改变。 */
	@Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
	public MesProcessCompositionDTO.View runAllocated(MesProcessCompositionDTO.RunAllocated command) {
		String factory = factoryAccess.require("write");
		AllocatedPlan plan = allocatedPlan(command);
		String fingerprint = allocatedFingerprint(plan.snapshot(), TenantContextHolder.getTenantId(), factory,
				SecurityUtils.getUser().getId());
		return runInternal(plan.adapted(), plan.lines(), fingerprint);
	}

	/** 两代外协议共用原来源、根锁、版本及原运行持久化；只替换真实派工的有界构造方式。 */
	private MesProcessCompositionDTO.View runInternal(MesProcessCompositionDTO.Run command,
			Map<String, List<MesDispatchAllocationDTO.Line>> allocationLines, String fingerprint) {
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		var actor = SecurityUtils.getUser();
		require(command != null && command.getRequestId() != null
				&& command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}"), "请提供8至100位稳定过程运行原号");
		require(command.getDispatches() != null && !command.getDispatches().isEmpty()
				&& command.getDispatches().size() <= 20, "请选择1至20个已下达正式工单");
		// 原号先锁后查，避免并发重放在MyBatis一级缓存中继续看到不存在。
		mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
		var replay = mapper.request(tenant, factory, command.getRequestId());
		if (replay != null) {
			require(Objects.equals(replay.getActorId(), actor.getId())
					&& fingerprint.equals(replay.getRequestFingerprint()), "该过程原号已用于另一正文或操作人");
			return view(replay);
		}
		cancellations.assertActive(COMMAND_KIND, command.getRequestId(), fingerprint);
		var template = mapper.template(tenant, factory, id(command.getTemplateId()));
		require(template != null && "PUBLISHED".equals(template.getStatus())
						&& Objects.equals(template.getRevisionNo(), command.getExpectedRevisionNo()),
				"请选择当前组织精确已发布过程版本并刷新修订");
		var definition = definition(template.getDefinitionJson());
		MesProcessCompositionModel.validate(definition);
		var requestedCodes = new HashSet<String>();
		for (var dispatch : command.getDispatches()) {
			require(dispatch != null && dispatch.getCode() != null && !dispatch.getCode().isBlank()
					&& dispatch.getShiftCode() != null && !dispatch.getShiftCode().isBlank()
					&& requestedCodes.add(dispatch.getCode()), "工单或班次缺失，或同批重复工单");
		}
		var codes = requestedCodes.stream().sorted().toList();
		// 与既有派工、报工使用相同工单锁，多个工单始终按编码排序锁定。
		for (String code : codes) {
			taskLocks.lockDispatchTask(code);
		}
		var sourceTasks = sourceTasks(codes, tenant, factory);
		require(sourceTasks.size() == codes.size(), "部分工单不存在或不属于当前实际组织");
		var sourceOperations = sourceOperations(codes, tenant, factory);
		for (var task : sourceTasks) {
			boolean manualSource = independentManualSource.isManualCandidate(task);
			require("ISSUE".equals(task.getStatus()) && (manualSource || task.getFormalPlanContractId() != null)
					&& task.getEngineeringReleaseId() != null && task.getNoticeNum() != null
					&& task.getNoticeNum().signum() > 0, "过程运行仅接收有原工程依据的已下达未派工正式来源工单");
			var members = sourceOperations.stream().filter(row -> task.getCode().equals(row.getTaskCode())).toList();
			if (manualSource) {
				// 手工productionOrder是内部同号，不是四级模型所需的真实上游生产订单。
				require(!definition.getLevels().get(0).getBindings().contains("ORDER"),
						"独立手工工单不能采用含订单层的过程，内部同号不是上游生产订单");
			}
			com.kitex.mom.mes.util.MesFrozenExecutionGraph.of(members);
			var dispatch = command.getDispatches().stream().filter(row -> task.getCode().equals(row.getCode())).findFirst().orElseThrow();
			require(dispatch.getTaskArrangeList() != null && dispatch.getTaskArrangeList().size() == members.size(),
					"首次过程运行须完整派出原冻结工序，不自动补造任务");
			var selected = dispatch.getTaskArrangeList().stream().map(row -> {
				require(row != null && row.getWorkerCode() != null && !row.getWorkerCode().isBlank()
						&& row.getWorksiteCodes() != null && !row.getWorksiteCodes().isBlank(), "请为全部工序选择实际人员及工位");
				return row.getProduceTaskCode();
			}).collect(Collectors.toSet());
			require(selected.equals(members.stream().map(MesProduceTaskEntity::getCode).collect(Collectors.toSet())),
					"派工工序必须精确覆盖本工单冻结图，不能跨工单或重复");
			for (var member : members) {
				require(member.getNoticeNum() != null && member.getNoticeNum().compareTo(task.getNoticeNum()) == 0,
						"原工单与工序数量不一致，请先核定，不能由组合层改写");
				if (allocationLines != null) {
					BigDecimal allocated = allocationLines.get(task.getCode()).stream()
							.filter(line -> member.getCode().equals(line.getProduceTaskCode()))
							.map(MesDispatchAllocationDTO.Line::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
					require(allocated.compareTo(member.getNoticeNum()) == 0,
							"首次组合运行须完整分配工序【" + member.getRouteName() + "】的原需求，不能漏分或超派");
				}
			}
		}
		// 同批真实手工来源共用一次ready核验；PLAN保持原资格，不读取手工表作为额外前置。
		independentManualSource.validateAll(sourceTasks, sourceOperations);
		if (definition.getLevels().get(0).getBindings().contains("ORDER")) {
			String order = sourceTasks.get(0).getProductionOrder();
			require(order != null && !order.isBlank()
							&& sourceTasks.stream().allMatch(task -> order.equals(task.getProductionOrder())),
					"四级过程须来自同一真实生产订单，缺少或混合来源不能运行");
		}
		require(sourceArrangements(codes, tenant, factory).isEmpty(), "工单已有派工，请查看既有过程，不得再次启动");
		Map<Long, String> allocationKeys = new HashMap<>();
		for (String code : codes) {
			var dispatch = command.getDispatches().stream().filter(item -> code.equals(item.getCode())).findFirst().orElseThrow();
			List<MesDispatchAllocationDTO.Line> lines = allocationLines == null
					? legacyAllocationLines(dispatch, sourceOperations) : allocationLines.get(code);
			var accepted = allocationService.executeForComposition(command.getRequestId(), code, lines);
			require("ACCEPTED".equals(accepted.getStatus()), "原组合子派工命令已取消，不能另号重建该运行");
			for (var line : accepted.getLines()) {
				require(allocationKeys.put(Long.valueOf(line.getArrangeId()), line.getAllocationKey()) == null,
						"组合子命令重复引用同一真实派工");
			}
		}
		var actualArrangements = sourceArrangements(codes, tenant, factory);
		require(actualArrangements.stream().allMatch(row -> row.getExecutionPolicySnapshot() != null
				&& !row.getExecutionPolicySnapshot().isBlank()), "实际派工缺少执行策略冻结，组合事务不能提交");
		var nodes = MesProcessCompositionModel.bind(definition, sourceTasks, sourceOperations, actualArrangements, allocationKeys);
		var row = new MesProcessCompositionRunEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setRequestId(command.getRequestId());
		row.setRequestFingerprint(fingerprint);
		row.setActorId(actor.getId());
		row.setTemplateId(template.getId());
		row.setTemplateVersion(template.getVersionNo());
		row.setTemplateName(template.getName());
		row.setDefinitionSnapshot(template.getDefinitionJson());
		row.setNodesJson(JSONUtil.toJsonStr(nodes));
		row.setTaskCodesJson(JSONUtil.toJsonStr(codes));
		row.setCreateBy(actor.getUsername());
		row.setCreateTime(LocalDateTime.now());
		require(mapper.insertRun(row) == 1, "过程绑定保存失败，派工一起回滚");
		return view(row);
	}

	public MesProcessCompositionDTO.View runView(String runId) {
		String factory = factoryAccess.require("read");
		var row = mapper.run(TenantContextHolder.getTenantId(), factory, id(runId));
		require(row != null, "当前组织不存在该过程运行");
		return view(row);
	}

	public List<MesProcessCompositionDTO.View> runs() {
		String factory = factoryAccess.require("read");
		return mapper.runs(TenantContextHolder.getTenantId(), factory).stream().map(this::view).toList();
	}

	public MesProcessCompositionDTO.Receipt receipt(String requestId) {
		String factory = factoryAccess.require("read");
		require(requestId != null && requestId.matches("[A-Za-z0-9_.:-]{8,100}"), "过程原号无效");
		var row = mapper.request(TenantContextHolder.getTenantId(), factory, requestId);
		var result = new MesProcessCompositionDTO.Receipt();
		result.setStatus(row == null
				? cancellations.cancelled(COMMAND_KIND, requestId) ? "CANCELLED" : "ABSENT"
				: "APPLIED");
		if (row != null) {
			require(Objects.equals(row.getActorId(), SecurityUtils.getUser().getId()), "请回原操作人账号核对过程回执");
			result.setRun(view(row));
		}
		return result;
	}

	@Transactional(rollbackFor = Exception.class)
	public MesProcessCompositionDTO.Receipt cancelOriginal(MesProcessCompositionDTO.Run command) {
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		var actor = SecurityUtils.getUser();
		String fingerprint = commandFingerprint(command, tenant, factory, actor.getId());
		mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
		var original = mapper.request(tenant, factory, command.getRequestId());
		var receipt = new MesProcessCompositionDTO.Receipt();
		if (original != null) {
			require(Objects.equals(original.getActorId(), actor.getId())
					&& fingerprint.equals(original.getRequestFingerprint()), "该过程原号已用于另一正文或操作人");
			receipt.setStatus("APPLIED");
			receipt.setRun(view(original));
		} else {
			// 与执行共用原号锁；墓碑提交后，迟到的原执行不能再进入真实派工。
			cancellations.cancel(COMMAND_KIND, command.getRequestId(), fingerprint);
			receipt.setStatus("CANCELLED");
		}
		return receipt;
	}

	/** 新协议取消沿原外号墓碑；成功子派工和父运行原子提交，不单独取消子原号。 */
	@Transactional(rollbackFor = Exception.class)
	public MesProcessCompositionDTO.Receipt cancelAllocatedOriginal(MesProcessCompositionDTO.RunAllocated command) {
		String factory = factoryAccess.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		var actor = SecurityUtils.getUser();
		AllocatedPlan plan = allocatedPlan(command);
		String fingerprint = allocatedFingerprint(plan.snapshot(), tenant, factory, actor.getId());
		mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
		var original = mapper.request(tenant, factory, command.getRequestId());
		var receipt = new MesProcessCompositionDTO.Receipt();
		if (original != null) {
			require(Objects.equals(original.getActorId(), actor.getId())
					&& fingerprint.equals(original.getRequestFingerprint()), "该过程原号已用于另一正文或操作人");
			receipt.setStatus("APPLIED");
			receipt.setRun(view(original));
		} else {
			cancellations.cancel(COMMAND_KIND, command.getRequestId(), fingerprint);
			receipt.setStatus("CANCELLED");
		}
		return receipt;
	}

	/** V2规范原文只含确定输入；数量为精确文本，工单排序，行顺序及稳定行标识保留。 */
	private AllocatedPlan allocatedPlan(MesProcessCompositionDTO.RunAllocated input) {
		require(input != null && input.getRequestId() != null
						&& input.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}")
						&& input.getTemplateId() != null && input.getExpectedRevisionNo() != null
						&& input.getExpectedRevisionNo() >= 0 && input.getAllocationDispatches() != null
						&& !input.getAllocationDispatches().isEmpty() && input.getAllocationDispatches().size() <= 20,
				"请选择精确已发布模板、修订及1至20个工单，并保留稳定原号");
		String template = Long.toString(id(input.getTemplateId()));
		Map<String, List<MesDispatchAllocationDTO.Line>> lines = new TreeMap<>();
		List<Map<String, Object>> groups = new ArrayList<>();
		for (var group : input.getAllocationDispatches()) {
			require(group != null, "组合工单分配不能为空");
			var child = allocationService.compositionCommand(input.getRequestId(), group.getTaskCode(), group.getLines());
			require(lines.put(child.getTaskCode(), child.getLines()) == null, "组合不能重复同一原工单");
		}
		var adapted = new MesProcessCompositionDTO.Run();
		adapted.setRequestId(input.getRequestId());
		adapted.setTemplateId(template);
		adapted.setExpectedRevisionNo(input.getExpectedRevisionNo());
		List<MesTaskArrangeDTO> dispatches = new ArrayList<>();
		for (var entry : lines.entrySet()) {
			var child = allocationService.compositionCommand(input.getRequestId(), entry.getKey(), entry.getValue());
			Map<String, Object> group = new LinkedHashMap<>();
			group.put("taskCode", entry.getKey());
			group.put("lines", JSONUtil.parseObj(allocationLedger.commandJson(child)).getJSONArray("lines"));
			groups.add(group);
			MesTaskArrangeDTO dispatch = new MesTaskArrangeDTO();
			dispatch.setCode(entry.getKey());
			dispatch.setShiftCode(entry.getValue().get(0).getShiftCode());
			dispatch.setShiftName(entry.getValue().get(0).getShiftName());
			Map<String, MesTaskArrangeEntity> summary = new LinkedHashMap<>();
			for (var line : entry.getValue()) {
				summary.putIfAbsent(line.getProduceTaskCode(), new MesTaskArrangeEntity()
						.setProduceTaskCode(line.getProduceTaskCode()).setWorkerCode(line.getWorkerCode())
						.setWorkerName(line.getWorkerName()).setWorksiteCodes(line.getWorksiteCodes()));
			}
			dispatch.setTaskArrangeList(new ArrayList<>(summary.values()));
			dispatches.add(dispatch);
		}
		adapted.setDispatches(dispatches);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("requestId", input.getRequestId());
		snapshot.put("templateId", template);
		snapshot.put("expectedRevisionNo", input.getExpectedRevisionNo().toString());
		snapshot.put("allocationDispatches", groups);
		return new AllocatedPlan(adapted, lines, JSONUtil.toJsonStr(snapshot));
	}

	/** 旧未受理Run仍按原外文指纹恢复，首次派工按原图全量确定生成合法子配额。 */
	private List<MesDispatchAllocationDTO.Line> legacyAllocationLines(MesTaskArrangeDTO dispatch,
			List<MesProduceTaskEntity> sourceOperations) {
		Map<String, MesProduceTaskEntity> byCode = sourceOperations.stream()
				.filter(row -> Objects.equals(row.getTaskCode(), dispatch.getCode()))
				.collect(Collectors.toMap(MesProduceTaskEntity::getCode, row -> row));
		List<MesDispatchAllocationDTO.Line> result = new ArrayList<>();
		for (var old : dispatch.getTaskArrangeList()) {
			var source = byCode.get(old.getProduceTaskCode());
			require(source != null, "旧原文工序不属于原工单");
			var line = new MesDispatchAllocationDTO.Line();
			line.setAllocationKey(sha("LEGACY:" + source.getCode()));
			line.setProduceTaskCode(source.getCode());
			line.setShiftCode(dispatch.getShiftCode());
			line.setShiftName(dispatch.getShiftName());
			line.setWorkerCode(old.getWorkerCode());
			line.setWorkerName(old.getWorkerName());
			line.setWorksiteCodes(old.getWorksiteCodes());
			line.setQuantity(source.getNoticeNum());
			result.add(line);
		}
		return result;
	}

	private String allocatedFingerprint(String snapshot, Long tenant, String factory, Long actorId) {
		return sha(JSONUtil.toJsonStr(List.of("MES_PROCESS_COMPOSITION_ALLOCATION_V1", tenant.toString(),
				factory, actorId.toString(), snapshot)));
	}

	private record AllocatedPlan(MesProcessCompositionDTO.Run adapted,
			Map<String, List<MesDispatchAllocationDTO.Line>> lines, String snapshot) {
	}

	private String commandFingerprint(MesProcessCompositionDTO.Run command, Long tenant, String factory, Long actorId) {
		require(command != null && command.getRequestId() != null
				&& command.getRequestId().matches("[A-Za-z0-9_.:-]{8,100}"), "请提供8至100位稳定过程运行原号");
		return sha(JSONUtil.toJsonStr(List.of("MES_PROCESS_COMPOSITION_V1", tenant, factory, actorId.toString(), command)));
	}

	private MesProcessCompositionDTO.View view(MesProcessCompositionRunEntity row) {
		var result = new MesProcessCompositionDTO.View();
		result.setId(row.getId().toString());
		result.setRequestId(row.getRequestId());
		result.setTemplateId(row.getTemplateId().toString());
		result.setTemplateVersion(row.getTemplateVersion());
		result.setTemplateName(row.getTemplateName());
		result.setDefinition(definition(row.getDefinitionSnapshot()));
		result.setNodes(JSONUtil.toList(row.getNodesJson(), MesProcessCompositionDTO.Node.class));
		var codes = JSONUtil.toList(row.getTaskCodesJson(), String.class);
		result.setProgress(MesProcessCompositionModel.progress(sourceTasks(codes, row.getTenantId(), row.getFactoryCode()),
				sourceOperations(codes, row.getTenantId(), row.getFactoryCode())));
		result.setQuantityBasis("按原工单唯一末工序完成量统计；各工序及层级不重复相加，异物料不混加；未提供完成量表示依据缺失");
		boolean quotaRun = result.getNodes().stream().anyMatch(node -> node.getAllocatedQuantity() != null);
		if (quotaRun && Objects.equals(row.getActorId(), SecurityUtils.getUser().getId())) {
			List<MesDispatchAllocationVO.Receipt> receipts = new ArrayList<>();
			var original = new MesProcessCompositionDTO.RunAllocated();
			original.setRequestId(row.getRequestId());
			original.setTemplateId(row.getTemplateId().toString());
			var published = mapper.template(row.getTenantId(), row.getFactoryCode(), row.getTemplateId());
			require(published != null && "PUBLISHED".equals(published.getStatus())
					&& Objects.equals(published.getVersionNo(), row.getTemplateVersion())
					&& Objects.equals(published.getDefinitionJson(), row.getDefinitionSnapshot()),
					"原组合已发布版本与永久运行不一致，请保留原号核对");
			original.setExpectedRevisionNo(published.getRevisionNo());
			List<MesProcessCompositionDTO.AllocationDispatch> groups = new ArrayList<>();
			for (String code : codes) {
				var child = allocationService.compositionReceipt(row.getRequestId(), code);
				require("ACCEPTED".equals(child.getStatus()) && child.getCommandSnapshot() != null,
						"配额组合缺少原工单永久子回执，请保留原号核对");
				var group = new MesProcessCompositionDTO.AllocationDispatch();
				group.setTaskCode(code);
				group.setLines(child.getCommandSnapshot().getLines());
				groups.add(group);
				receipts.add(child);
			}
			original.setAllocationDispatches(groups);
			AllocatedPlan rebuilt = allocatedPlan(original);
			if (Objects.equals(row.getRequestFingerprint(), allocatedFingerprint(rebuilt.snapshot(),
					row.getTenantId(), row.getFactoryCode(), row.getActorId()))) {
				result.setAllocatedCommandSnapshot(rebuilt.snapshot());
				result.setAllocationReceipts(List.copyOf(receipts));
			}
		}
		return result;
	}

	private List<MesTaskEntity> sourceTasks(List<String> codes, Long tenant, String factory) {
		var rows = tasks.list(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTenantId, tenant)
				.eq(MesTaskEntity::getFactoryCode, factory).in(MesTaskEntity::getCode, codes));
		require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
				&& factory.equals(row.getFactoryCode()) && codes.contains(row.getCode()))
				&& rows.size() == codes.size()
				&& rows.stream().map(MesTaskEntity::getCode).distinct().count() == rows.size(), "工单来源范围或唯一性异常");
		return rows;
	}

	private List<MesProduceTaskEntity> sourceOperations(List<String> codes, Long tenant, String factory) {
		var rows = operations.list(new LambdaQueryWrapper<MesProduceTaskEntity>().eq(MesProduceTaskEntity::getTenantId, tenant)
				.eq(MesProduceTaskEntity::getFactoryCode, factory).in(MesProduceTaskEntity::getTaskCode, codes));
		require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
				&& factory.equals(row.getFactoryCode()) && codes.contains(row.getTaskCode())), "工序来源范围异常");
		return rows;
	}

	private List<MesTaskArrangeEntity> sourceArrangements(List<String> codes, Long tenant, String factory) {
		var rows = arrangements.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.in(MesTaskArrangeEntity::getTaskCode, codes));
		require(rows.stream().allMatch(row -> Objects.equals(tenant, row.getTenantId())
				&& factory.equals(row.getFactoryCode()) && codes.contains(row.getTaskCode())), "派工来源范围异常");
		return rows;
	}

	private MesProcessCompositionDTO.Definition definition(String json) {
		try {
			return JSONUtil.toBean(json, MesProcessCompositionDTO.Definition.class);
		} catch (RuntimeException failure) {
			throw new CheckedException("过程定义快照损坏，请核定原版本");
		}
	}

	private Long id(String text) {
		require(text != null && text.matches("[1-9][0-9]{0,18}"), "请选择精确过程记录");
		try {
			return Long.valueOf(text);
		} catch (NumberFormatException failure) {
			throw new CheckedException("过程记录ID超出范围");
		}
	}

	private String sha(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
