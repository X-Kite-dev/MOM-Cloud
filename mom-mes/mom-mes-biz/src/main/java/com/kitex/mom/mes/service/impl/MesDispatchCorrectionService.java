package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmShiftOccurrence;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.dto.MesDispatchCorrectionDTO;
import com.kitex.mom.mes.dto.MesTaskArrangeDTO;
import com.kitex.mom.mes.entity.MesDispatchCorrectionOperationEntity;
import com.kitex.mom.mes.entity.MesDispatchCorrectionRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.mapper.MesDispatchCorrectionMapper;
import com.kitex.mom.mes.service.MesDispatchReturnService;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 明确日期班次和授权工位的异人审批；关闭、释放资源、真实补派、永久回执共享同一事务。
 * 新目标冻结完整日期实例，历史原号和终止动作沿原V1正文，不从实例编码或今天补造历史日期。
 */
@Service
@RequiredArgsConstructor
public class MesDispatchCorrectionService {

	private static final String KIND = "DISPATCH_CORRECTION";

	private static final Set<String> STATUSES = Set.of("SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN");

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().registerModule(
					new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance)
							.addSerializer(BigDecimal.class, ToStringSerializer.instance))
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

	private final MesActualFactoryAccessService access;

	private final MesDispatchCorrectionMapper mapper;

	private final MesDispatchReturnService returns;

	private final MesTaskArrangeService arrangements;

	private final MesIdentifierBindingGroupService taskLocks;

	private final MesExecutionCommandCancellationService cancellations;

	private final RemoteClassesService shifts;

	/**
	 * 兼容历史只读调用，缺少明确日期时只返回阻断，不能产生新修正事实。
	 */
	@Transactional(readOnly = true)
	public MesDispatchCorrectionDTO.Preview preview(String arrangeId, String shiftCode, String worksiteCodes) {
		return preview(arrangeId, shiftCode, null, worksiteCodes);
	}

	/**
	 * 读取原派工与明确目标日期的正式实例候选，所有执行及组织限制沿原合同。
	 *
	 * @param arrangeId     原派工精确身份
	 * @param shiftCode     目标日期实例编码；可空以先取得该日期候选
	 * @param shiftDate     用户明确选择的所属日；空值不推断日期并返回阻断
	 * @param worksiteCodes 逗号分隔的原工序授权候选工位，可空沿原派工
	 */
	@Transactional(readOnly = true)
	public MesDispatchCorrectionDTO.Preview preview(String arrangeId, String shiftCode, LocalDate shiftDate,
	                                                String worksiteCodes) {
		String factory = access.require("read");
		return observe(id(arrangeId), shiftCode, shiftDate,
				worksiteCodes == null ? null : csv(worksiteCodes), factory, null).preview();
	}

	/**
	 * 按永久原号办理原申请；新提交必须明确日期，旧已接受原文先回放，异人批准重采冻结日期依据。
	 * 原退派、资源和执行模板门禁均通过后才同事务关闭旧派工并生成替代派工。
	 */
	@Transactional(rollbackFor = Exception.class)
	public MesDispatchCorrectionDTO.Resolution command(MesDispatchCorrectionDTO.Command command) {
		String factory = access.require("write");
		validate(command);
		Long tenant = TenantContextHolder.getTenantId();
		Long actor = SecurityUtils.getUser().getId();
		String fingerprint = commandHash(command, tenant, factory, actor);
		mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
		var replay = mapper.original(tenant, factory, command.getRequestId());
		if (replay != null) {
			return replay(replay, actor, fingerprint);
		}
		cancellations.assertActive(KIND, command.getRequestId(), fingerprint);
		MesDispatchCorrectionRequestEntity row;
		if ("SUBMIT".equals(command.getAction())) {
			// 日期要求只约束新事实；置于原号回执查验后，旧成功命令仍按旧正文回放。
			require(command.getShiftDate() != null, "新修正申请须明确选择目标班次所属日期，请重新预览");
			var owner = mapper.arrange(tenant, factory, id(command.getArrangeId()));
			require(owner != null, "当前实际组织不存在原派工");
			taskLocks.lockDispatchTask(owner.getTaskCode());
			var observation = observe(owner.getId(), command.getShiftCode(), command.getShiftDate(),
					command.getWorksiteCodes(), factory, null);
			require(observation.preview().isEligible(), String.join("；", observation.preview().getBlockers()));
			require(Objects.equals(owner.getTaskCode(), observation.preview().getArrange().getTaskCode()),
					"派工归属已变化，请原号核对");
			require(command.getExpectedFingerprint().equals(observation.preview().getEvidenceFingerprint()),
					"派工或目标依据已变化，请重新预览");
			row = request(observation, command, tenant, factory);
			require(mapper.insertRequest(row) == 1, "修正申请未保存");
		} else {
			var owner = mapper.request(tenant, factory, id(command.getCorrectionId()));
			require(owner != null, "当前实际组织不存在该修正申请");
			taskLocks.lockDispatchTask(owner.getTaskCode());
			row = mapper.lockedRequest(tenant, factory, owner.getId());
			require(row != null && Objects.equals(owner.getTaskCode(), row.getTaskCode()), "修正申请归属已变化");
			require("SUBMITTED".equals(row.getStatus()), "只有待审核修正申请可以办理");
			var user = SecurityUtils.getUser();
			if ("WITHDRAW".equals(command.getAction())) {
				require(Objects.equals(row.getApplicantId(), actor) &&
						Objects.equals(row.getApplicantCode(), user.getUsername()), "只有原申请人可撤回");
				row.setStatus("WITHDRAWN");
			} else {
				require(!Objects.equals(row.getApplicantId(), actor) &&
						!Objects.equals(row.getApplicantCode(), user.getUsername()), "修正申请人与审批人必须分离");
				require(row.getEvidenceFingerprint().equals(command.getExpectedFingerprint()),
						"审批必须保留原申请所见的具体目标指纹");
				row.setReviewerId(actor);
				row.setReviewerCode(user.getUsername());
				row.setReviewerName(user.getName());
				row.setReviewedTime(now());
				row.setReviewReason(command.getReason());
				if ("REJECT".equals(command.getAction())) {
					row.setStatus("REJECTED");
				} else {
					// 先验证原快照SHA和完整日期实例，再按同一日期重采；不能以今日或拆码补齐历史。
					var target = frozenTarget(row);
					var current = observe(row.getArrangeId(), row.getTargetShiftCode(), target.shiftDate(),
							csv(row.getTargetWorksiteCodes()), factory, row.getId());
					require(current.preview().isEligible(), String.join("；", current.preview().getBlockers()));
					require(row.getEvidenceFingerprint().equals(current.preview().getEvidenceFingerprint()),
							"来源、资源、班次或执行模板已变化，须重新申请");
					returns.retireForCorrection(row.getId(), row.getSourceFingerprint());
					var dto = replacementCommand(row, current.preview().getArrange());
					arrangements.arrangeCorrection(dto, row.getId());
					var replacement = mapper.replacement(tenant, factory, row.getId());
					require(replacement != null && Objects.equals(replacement.getTaskCode(), row.getTaskCode()) &&
									Objects.equals(replacement.getProduceTaskCode(), row.getProduceTaskCode()),
							"替代派工未唯一归属原申请");
					require(samePolicy(row.getOriginalPolicySnapshot(), replacement.getExecutionPolicySnapshot()),
							"真实派工冻结策略与原申请所见不一致，整笔修正回滚");
					row.setReplacementArrangeId(replacement.getId());
					row.setReplacementArrangeCode(replacement.getCode());
					row.setAfterSnapshot(json(replacement));
					row.setStatus("APPROVED");
				}
			}
			require(mapper.finish(row) == 1, "修正结果未保存，领域变化必须回滚");
		}
		return saveReceipt(row, command, fingerprint, factory);
	}

	/**
	 * 读取原申请及当前资格；旧待办缺日期仍保留查询、驳回和撤回入口。
	 */
	@Transactional(readOnly = true)
	public MesDispatchCorrectionDTO.Detail detail(String correctionId) {
		String factory = access.require("read");
		var row = mapper.request(TenantContextHolder.getTenantId(), factory, id(correctionId));
		require(row != null, "当前实际组织不存在该修正申请");
		return detail(row, true);
	}

	/**
	 * 在实际组织内分页返回原申请；新日期元数据只从原快照读取，不补历史。
	 */
	@Transactional(readOnly = true)
	public MesDispatchCorrectionDTO.Page page(long current, long size, String status, String arrangeId) {
		String factory = access.require("read");
		require(current > 0 && current <= 100000 && size > 0 && size <= 100, "分页范围无效");
		require(status == null || STATUSES.contains(status), "修正状态无效");
		Long tenant = TenantContextHolder.getTenantId();
		Long arrange = arrangeId == null ? null : id(arrangeId);
		var result = new MesDispatchCorrectionDTO.Page();
		result.setTenantId(tenant.toString());
		result.setFactoryCode(factory);
		result.setObservedAt(now());
		result.setCurrent(current);
		result.setSize(size);
		result.setTotal(mapper.count(tenant, factory, status, arrange));
		result.setRecords(
				mapper.page(tenant, factory, status, arrange, size, (current - 1) * size).stream().map(this::view)
						.toList());
		return result;
	}

	/**
	 * 仅原操作者按永久原号读取冻结回执，不重做申请或根据当前状态推断结果。
	 */
	@Transactional(readOnly = true)
	public MesDispatchCorrectionDTO.Resolution receipt(String requestId) {
		String factory = access.require("read");
		requestId(requestId);
		var operation = mapper.original(TenantContextHolder.getTenantId(), factory, requestId);
		if (operation != null) {
			require(Objects.equals(operation.getActorId(), SecurityUtils.getUser().getId()), "原请求属于其他操作人");
			return resolution(operation.getResultJson());
		}
		return resolution(requestId, factory, cancellations.cancelled(KIND, requestId) ? "CANCELLED" : "ABSENT");
	}

	/**
	 * 封存未受理原文；旧SUBMIT无日期仍可取消，已受理原号优先回放。
	 */
	@Transactional(rollbackFor = Exception.class)
	public MesDispatchCorrectionDTO.Resolution cancel(MesDispatchCorrectionDTO.Command command) {
		String factory = access.require("write");
		validate(command);
		Long tenant = TenantContextHolder.getTenantId();
		String hash = commandHash(command, tenant, factory, SecurityUtils.getUser().getId());
		mapper.lock(tenant, factory, "REQUEST:" + command.getRequestId());
		var operation = mapper.original(tenant, factory, command.getRequestId());
		if (operation != null) {
			return replay(operation, SecurityUtils.getUser().getId(), hash);
		}
		cancellations.cancel(KIND, command.getRequestId(), hash);
		return resolution(command.getRequestId(), factory, "CANCELLED");
	}

	/**
	 * 观察原执行事实和明确日期的正式目标，过期目标与缺日期只返回阻断，不放宽原退派或APS限制。
	 * 快照同时冻结原规则与完整实例；审批须逐字节重现该SHA才可执行。
	 */
	private Observation observe(Long arrangeId, String shiftCode, LocalDate shiftDate, List<String> worksiteCodes,
	                            String factory, Long ownId) {
		Long tenant = TenantContextHolder.getTenantId();
		var evidence = returns.previewForCorrection(arrangeId);
		var arrange = evidence.getArrange();
		require(arrange != null && Objects.equals(arrange.getTenantId(), tenant) &&
				factory.equals(arrange.getFactoryCode()), "原派工返回范围不匹配");
		var task = mapper.task(tenant, factory, arrange.getTaskCode());
		var produce = mapper.produce(tenant, factory, arrange.getTaskCode(), arrange.getProduceTaskCode());
		require(task != null && produce != null, "缺少原真实工单或工序任务");
		var rules = activeShifts(tenant, factory);
		String targetShift = shiftCode == null ? (shiftDate == null ? arrange.getShiftCode() : null) : code(shiftCode);
		List<String> selected = worksiteCodes == null ? csv(arrange.getWorksiteCodes()) : codes(worksiteCodes);
		var occurrences = shiftDate == null ? List.<MdmShiftOccurrence>of()
				: rules.stream().map(rule -> occurrence(rule, shiftDate)).toList();
		var target = occurrences.stream().filter(item -> Objects.equals(item.shiftCode(), targetShift))
				.findFirst().orElse(null);
		var targetRule = target == null ? null : rules.stream()
				.filter(rule -> Objects.equals(rule.getId().toString(), target.ruleId())).findFirst().orElse(null);
		var blockers = new ArrayList<>(evidence.getBlockers());
		if (shiftDate == null) {
			blockers.add("缺少可核验的目标班次所属日期，不能批准；请重新选择日期并申请");
		} else if (targetShift == null) {
			blockers.add("请选择所选日期的正式目标班次，并重新核对依据");
		} else if (target == null) {
			blockers.add("目标班次实例不属于所选日期的正式启用规则，请重新选择");
		} else if (!target.windowEnd().isAfter(now())) {
			blockers.add("目标班次已结束，不能新增修正申请或批准；请重新选择未结束班次");
		}
		List<String> allowed = csv(produce.getWorksiteCodes());
		if (selected.isEmpty() || !allowed.containsAll(selected)) {
			blockers.add("目标工位不属于原工序授权候选");
		}
		if (Objects.equals(targetShift, arrange.getShiftCode()) &&
				new HashSet<>(selected).equals(new HashSet<>(csv(arrange.getWorksiteCodes())))) {
			blockers.add("班次及工位完全没有变化");
		}
		if (produce.getPlannedWorksiteCode() != null && !produce.getPlannedWorksiteCode().isBlank() &&
				!selected.equals(List.of(produce.getPlannedWorksiteCode()))) {
			blockers.add("APS已锁定工位，须返回计划重排");
		}
		if (produce.getPlannedWorkerCode() != null && !produce.getPlannedWorkerCode().isBlank() &&
				!new HashSet<>(csv(produce.getPlannedWorkerCode())).equals(
						new HashSet<>(csv(arrange.getWorkerCode())))) {
			blockers.add("原员工与APS锁定员工不一致，不能借修正换人");
		}
		long pending = mapper.pending(tenant, factory, arrangeId);
		if (pending > (ownId == null ? 0 : 1)) {
			blockers.add("派工已有待审修正申请");
		}
		if (mapper.workpieceFacts(tenant, factory, arrangeId) > 0) {
			blockers.add("派工已有工件计时事实，不能冒充未执行");
		}
		if (mapper.otherActive(tenant, factory, arrange.getTaskCode(), arrange.getProduceTaskCode(), arrangeId) > 0) {
			blockers.add("同一工序已有其他活动派工，须先核定完整分派范围");
		}
		if (arrange.getWorkerCode() == null || arrange.getWorkerCode().isBlank()) {
			blockers.add("原执行员工缺失，不能补造");
		}
		if (arrange.getExecutionPolicySnapshot() == null || arrange.getExecutionPolicySnapshot().isBlank()) {
			blockers.add("原执行策略缺失，不能按当前模板补造历史");
		} else if (!noneDynamic(arrange.getExecutionPolicySnapshot())) {
			blockers.add("动态标识区段须整组调整，当前不允许单派工修正");
		}
		var hypothetical = BeanUtil.copyProperties(arrange, MesTaskArrangeEntity.class);
		hypothetical.setWorksiteCodes(String.join(",", selected));
		var templates = mapper.templates(tenant, factory);
		var matched = templates.stream()
				.filter(t -> MesExecutionTemplateMatcher.reasons(t, task, hypothetical, LocalDateTime.now()).isEmpty())
				.max(MesExecutionTemplateMatcher.order()).orElse(null);
		if (!Objects.equals(arrange.getExecutionTemplateId(), matched == null ? null : matched.getId()) ||
				!Objects.equals(arrange.getExecutionTemplateVersion(),
						matched == null ? null : matched.getVersionNo())) {
			blockers.add("当前匹配执行模板已变化，不能由班次/工位修正更换冻结策略");
		}
		var preview = new MesDispatchCorrectionDTO.Preview();
		preview.setTenantId(tenant.toString());
		preview.setFactoryCode(factory);
		preview.setObservedAt(now());
		preview.setArrange(arrange);
		preview.setTargetShiftCode(targetShift);
		preview.setTargetShiftName(target == null ? null : target.shiftName());
		preview.setTargetShiftDate(shiftDate);
		preview.setTargetShiftOccurrence(target);
		preview.setTargetWorksiteCodes(selected);
		preview.setCandidateShifts(occurrences.stream().map(item -> {
			var choice = new MesDispatchCorrectionDTO.Choice(item.shiftCode(), item.shiftName());
			choice.setTargetShiftDate(item.shiftDate());
			choice.setTargetShiftOccurrence(item);
			return choice;
		}).toList());
		List<String> names = csv(produce.getWorksiteNames());
		var choices = new ArrayList<MesDispatchCorrectionDTO.Choice>();
		for (int i = 0; i < allowed.size(); i++) {
			choices.add(new MesDispatchCorrectionDTO.Choice(allowed.get(i),
					i < names.size() ? names.get(i) : allowed.get(i)));
		}
		preview.setCandidateWorksites(choices);
		var facts = new LinkedHashMap<String, Object>();
		facts.put("contractVersion", MesDispatchCorrectionDTO.VERSION);
		facts.put("arrange", arrange);
		facts.put("task", task);
		facts.put("produce", produce);
		facts.put("returnEvidence", evidence.getEvidenceFingerprint());
		facts.put("targetShift", targetRule);
		facts.put("shiftTargetContract", MdmShiftOccurrence.CONTRACT);
		facts.put("targetShiftOccurrence", target);
		facts.put("targetWorksites", selected);
		facts.put("matchingTemplate", matched);
		String snapshot = json(facts);
		preview.setEvidenceFingerprint(hash(snapshot));
		preview.setBlockers(List.copyOf(blockers));
		preview.setEligible(blockers.isEmpty());
		return new Observation(preview, evidence.getEvidenceFingerprint(), snapshot);
	}

	/**
	 * 永久冻结原派工、具体日期目标及策略；不覆盖原接收、SOP或生产事实。
	 */
	private MesDispatchCorrectionRequestEntity request(Observation observation,
	                                                   MesDispatchCorrectionDTO.Command command, Long tenant,
	                                                   String factory) {
		var p = observation.preview();
		var arrange = p.getArrange();
		var user = SecurityUtils.getUser();
		var row = new MesDispatchCorrectionRequestEntity();
		row.setId(IdWorker.getId());
		row.setTenantId(tenant);
		row.setFactoryCode(factory);
		row.setArrangeId(arrange.getId());
		row.setArrangeCode(arrange.getCode());
		row.setTaskCode(arrange.getTaskCode());
		row.setProduceTaskCode(arrange.getProduceTaskCode());
		row.setStatus("SUBMITTED");
		row.setSourceFingerprint(observation.sourceFingerprint());
		row.setEvidenceFingerprint(p.getEvidenceFingerprint());
		row.setTargetShiftCode(p.getTargetShiftCode());
		row.setTargetShiftName(p.getTargetShiftName());
		row.setTargetWorksiteCodes(String.join(",", p.getTargetWorksiteCodes()));
		row.setReason(command.getReason());
		row.setApplicantId(user.getId());
		row.setApplicantCode(user.getUsername());
		row.setApplicantName(user.getName());
		row.setSubmittedTime(now());
		row.setBeforeSnapshot(json(arrange));
		row.setProposalSnapshot(observation.snapshot());
		row.setOriginalPolicySnapshot(arrange.getExecutionPolicySnapshot());
		return row;
	}

	/**
	 * 只给原工序和原员工补派已批准的实例班次，不能借修正换人或扩展工序范围。
	 */
	private MesTaskArrangeDTO replacementCommand(MesDispatchCorrectionRequestEntity row, MesTaskArrangeEntity source) {
		var dto = new MesTaskArrangeDTO();
		dto.setCode(row.getTaskCode());
		dto.setShiftCode(row.getTargetShiftCode());
		dto.setShiftName(row.getTargetShiftName());
		dto.setTaskArrangeList(List.of(new MesTaskArrangeEntity().setProduceTaskCode(row.getProduceTaskCode())
				.setWorkerCode(source.getWorkerCode()).setWorkerName(source.getWorkerName())
				.setWorksiteCodes(row.getTargetWorksiteCodes())));
		return dto;
	}

	/**
	 * 在同一业务事务永久保存并回读原号回执，任何写入失败须回滚领域变化。
	 */
	private MesDispatchCorrectionDTO.Resolution saveReceipt(MesDispatchCorrectionRequestEntity row,
	                                                        MesDispatchCorrectionDTO.Command command, String hash,
	                                                        String factory) {
		var operation = new MesDispatchCorrectionOperationEntity();
		var user = SecurityUtils.getUser();
		operation.setId(IdWorker.getId());
		operation.setTenantId(row.getTenantId());
		operation.setFactoryCode(factory);
		operation.setCorrectionId(row.getId());
		operation.setRequestId(command.getRequestId());
		operation.setRequestHash(hash);
		operation.setAction(command.getAction());
		operation.setActorId(user.getId());
		operation.setActorCode(user.getUsername());
		operation.setReason(command.getReason());
		operation.setCreatedTime(now());
		var detail = detail(row, false);
		var history = new ArrayList<>(detail.getOperations());
		history.add(operationView(operation));
		detail.setOperations(history);
		var result = resolution(command.getRequestId(), factory, "APPLIED");
		result.setResult(detail);
		operation.setResultJson(json(result));
		require(mapper.insertOperation(operation) == 1, "修正永久回执未保存，整笔操作回滚");
		var persisted = mapper.original(row.getTenantId(), factory, command.getRequestId());
		require(persisted != null && Objects.equals(persisted.getResultJson(), operation.getResultJson()) &&
				Objects.equals(persisted.getRequestHash(), hash), "修正原回执回读不一致，整笔操作回滚");
		return resolution(persisted.getResultJson());
	}

	private MesDispatchCorrectionDTO.Detail detail(MesDispatchCorrectionRequestEntity row, boolean current) {
		var result = new MesDispatchCorrectionDTO.Detail();
		result.setTenantId(row.getTenantId().toString());
		result.setFactoryCode(row.getFactoryCode());
		result.setObservedAt(now());
		result.setCorrection(view(row));
		result.setOperations(mapper.operations(row.getTenantId(), row.getFactoryCode(), row.getId()).stream()
				.map(this::operationView).toList());
		if (current && "SUBMITTED".equals(row.getStatus())) {
			var target = result.getCorrection().getTargetShiftOccurrence();
			var preview = observe(row.getArrangeId(), row.getTargetShiftCode(),
					target == null ? null : target.shiftDate(), csv(row.getTargetWorksiteCodes()),
					row.getFactoryCode(), row.getId()).preview();
			if (result.getCorrection().getTargetShiftIssue() != null) {
				// 保留原件异常原因，不能把已知SHA损坏降成普通历史缺日期；拒绝/撤回仍沿原申请办理。
				var blockers = new ArrayList<>(preview.getBlockers());
				blockers.remove("缺少可核验的目标班次所属日期，不能批准；请重新选择日期并申请");
				blockers.add(result.getCorrection().getTargetShiftIssue());
				preview.setBlockers(List.copyOf(blockers));
				preview.setEligible(false);
			}
			result.setCurrentPreview(preview);
		}
		return result;
	}

	private MesDispatchCorrectionDTO.Correction view(MesDispatchCorrectionRequestEntity row) {
		var result = BeanUtil.copyProperties(row, MesDispatchCorrectionDTO.Correction.class, "targetWorksiteCodes");
		result.setTargetWorksiteCodes(csv(row.getTargetWorksiteCodes()));
		try {
			var target = frozenTarget(row);
			result.setTargetShiftDate(target.shiftDate());
			result.setTargetShiftOccurrence(target);
		} catch (CheckedException failure) {
			result.setTargetShiftIssue(failure.getMessage());
		}
		return result;
	}

	private MesDispatchCorrectionDTO.Operation operationView(MesDispatchCorrectionOperationEntity row) {
		return BeanUtil.copyProperties(row, MesDispatchCorrectionDTO.Operation.class);
	}

	/**
	 * 从原proposal恢复完整日期目标，先验原文SHA，再核实例与规则、行组织和目标的一致性。
	 * 旧未办申请缺完整日期只能终止或重申，不能从编码或当前日期迁移为新执行事实。
	 */
	private MdmShiftOccurrence frozenTarget(MesDispatchCorrectionRequestEntity row) {
		require(row.getProposalSnapshot() != null
						&& Objects.equals(hash(row.getProposalSnapshot()), row.getEvidenceFingerprint()),
				"原申请目标快照SHA不一致，不能批准产生新派工");
		try {
			JsonNode proposal = JSON.readTree(row.getProposalSnapshot());
			require(MdmShiftOccurrence.CONTRACT.equals(proposal.path("shiftTargetContract").asText()),
					"原申请缺少完整日期班次合同，请撤回后按明确所属日期重新申请");
			JsonNode node = proposal.path("targetShiftOccurrence");
			require(node.isObject(), "原申请缺少完整日期班次实例，请撤回后重新申请");
			var target = JSON.treeToValue(node, MdmShiftOccurrence.class);
			var rule = JSON.treeToValue(proposal.path("targetShift"), MdmClassesEntity.class);
			require(rule != null && target.equals(occurrence(rule, target.shiftDate()))
							&& Objects.equals(row.getTenantId().toString(), target.tenantId())
							&& Objects.equals(row.getFactoryCode(), target.factoryCode())
							&& Objects.equals(row.getTargetShiftCode(), target.shiftCode())
							&& Objects.equals(row.getTargetShiftName(), target.shiftName()),
					"原申请日期实例、原规则或实际组织不一致，不能批准");
			return target;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原申请完整日期班次依据无法核验，不能批准；可驳回或由本人撤回", failure);
		}
	}

	/**
	 * 仅作正式规则的纯日期计算，转换坏规则为明确业务拒绝，避免NPE或默默遗漏。
	 */
	private MdmShiftOccurrence occurrence(MdmClassesEntity rule, LocalDate shiftDate) {
		try {
			return MdmShiftOccurrence.from(rule, shiftDate);
		} catch (IllegalArgumentException | java.time.DateTimeException failure) {
			throw new CheckedException("正式班次规则不能生成完整日期实例：" + failure.getMessage(), failure);
		}
	}

	/**
	 * 先校验完整性和重号再排序，不截断、不按名称补来源，也不吞掉空行或坏规则。
	 */
	private List<MdmClassesEntity> activeShifts(Long tenant, String factory) {
		var response = shifts.listActive();
		require(response != null && response.isOk() && response.getData() != null, "正式班次来源不可用");
		require(response.getData().stream().noneMatch(Objects::isNull), "正式班次来源包含空记录，请先治理来源");
		var rules = response.getData().stream()
				.filter(rule -> Objects.equals(rule.getTenantId(), tenant)
						&& Objects.equals(rule.getFactoryCode(), factory) && Boolean.TRUE.equals(rule.getStatus()))
				.toList();
		require(rules.size() <= 200, "当前实际组织正式启用班次超过200条，不能截断候选");
		require(rules.stream().allMatch(rule -> rule.getId() != null && rule.getId() > 0
				&& validText(rule.getShiftCode()) && rule.getShiftCode().length() <= 94
				&& validName(rule.getShiftName()) && rule.getStartTime() != null && rule.getEndTime() != null
				&& !rule.getStartTime().equals(rule.getEndTime())), "正式班次规则身份、编码或时间不完整");
		require(rules.stream().map(MdmClassesEntity::getId).distinct().count() == rules.size()
						&& rules.stream().map(MdmClassesEntity::getShiftCode).distinct().count() == rules.size(),
				"当前实际组织正式班次规则身份或编码重复");
		return rules.stream().sorted(Comparator.comparing(MdmClassesEntity::getShiftCode)).toList();
	}

	/**
	 * 正式班次文本须可原文冻结并可作为编码候选，拒绝空白、分隔符和控制字符。
	 */
	private boolean validText(String value) {
		return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 100
				&& value.indexOf(',') < 0 && value.chars().noneMatch(Character::isISOControl);
	}

	/**
	 * 名称沿MDM原文保留，不把逗号等正常标点按候选编码分隔符拒绝。
	 */
	private boolean validName(String value) {
		return value != null && !value.isBlank() && value.length() <= 100;
	}

	private MesDispatchCorrectionDTO.Resolution replay(MesDispatchCorrectionOperationEntity row, Long actor,
	                                                   String hash) {
		require(Objects.equals(actor, row.getActorId()) && Objects.equals(hash, row.getRequestHash()),
				"修正原号已用于不同正文或操作人");
		return resolution(row.getResultJson());
	}

	private MesDispatchCorrectionDTO.Resolution resolution(String requestId, String factory, String state) {
		var result = new MesDispatchCorrectionDTO.Resolution();
		result.setRequestId(requestId);
		result.setTenantId(TenantContextHolder.getTenantId().toString());
		result.setFactoryCode(factory);
		result.setState(state);
		return result;
	}

	private MesDispatchCorrectionDTO.Resolution resolution(String json) {
		try {
			return JSON.readValue(json, MesDispatchCorrectionDTO.Resolution.class);
		} catch (Exception e) {
			throw new CheckedException("原修正回执损坏，不能以当前状态替代");
		}
	}

	private void validate(MesDispatchCorrectionDTO.Command c) {
		require(c != null, "修正命令不能为空");
		requestId(c.getRequestId());
		require(Set.of("SUBMIT", "APPROVE", "REJECT", "WITHDRAW").contains(Objects.toString(c.getAction(), "")),
				"修正动作无效");
		require(c.getReason() != null && !c.getReason().isBlank() && c.getReason().length() <= 500 &&
				c.getReason().equals(c.getReason().trim()), "请提供1至500字精确办理依据");
		if ("SUBMIT".equals(c.getAction())) {
			id(c.getArrangeId());
			require(c.getCorrectionId() == null && c.getShiftCode() != null && c.getWorksiteCodes() != null,
					"提交须指定原派工、正式班次和工位，不能夹带申请ID");
			code(c.getShiftCode());
			codes(c.getWorksiteCodes());
			fingerprint(c.getExpectedFingerprint());
		} else {
			id(c.getCorrectionId());
			require(c.getArrangeId() == null && c.getShiftCode() == null && c.getShiftDate() == null
					&& c.getWorksiteCodes() == null, "办理原申请不能夹带派工、日期或目标变更");
			if ("WITHDRAW".equals(c.getAction())) {
				require(c.getExpectedFingerprint() == null, "撤回不能夹带证据变更");
			} else {
				fingerprint(c.getExpectedFingerprint());
			}
		}
	}

	private String commandHash(MesDispatchCorrectionDTO.Command c, Long tenant, String factory, Long actor) {
		return hash(json(List.of(MesDispatchCorrectionDTO.VERSION, tenant, factory, actor, c)));
	}

	private void requestId(String value) {
		require(value != null && value.matches("[A-Za-z0-9_.:-]{8,100}"), "须提供8至100位永久原请求号");
	}

	private void fingerprint(String value) {
		require(value != null && value.matches("[a-f0-9]{64}"), "须保留预览或原申请的完整指纹");
	}

	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "须提供精确正整数身份");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			throw new CheckedException("身份超出Long范围");
		}
	}

	private String code(String value) {
		require(value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 100 &&
				value.indexOf(',') < 0 && value.chars().noneMatch(Character::isISOControl), "业务编码缺失或格式无效");
		return value;
	}

	private List<String> codes(List<String> values) {
		require(values != null && !values.isEmpty() && values.size() <= 200 &&
				values.stream().distinct().count() == values.size(), "工位须为1至200个不重复精确编码");
		return values.stream().map(this::code).sorted().toList();
	}

	private List<String> csv(String value) {
		return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::trim)
				.toList();
	}

	private boolean noneDynamic(String value) {
		try {
			var node = JSON.readTree(value).path("identifierNodePolicy").path("action");
			return node.isMissingNode() || node.isNull() || "NONE".equals(node.asText());
		} catch (Exception e) {
			return false;
		}
	}

	private boolean samePolicy(String a, String b) {
		try {
			return a != null && b != null && JSON.readTree(a).equals(JSON.readTree(b));
		} catch (Exception e) {
			return false;
		}
	}

	private String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			throw new CheckedException("修正完整证据不能序列化");
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private LocalDateTime now() {
		return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private record Observation(MesDispatchCorrectionDTO.Preview preview, String sourceFingerprint, String snapshot) {
	}
}
