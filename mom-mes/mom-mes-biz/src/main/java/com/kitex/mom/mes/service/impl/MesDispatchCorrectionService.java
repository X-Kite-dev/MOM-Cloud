package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 精确目标异人审批；关闭、释放资源、真实补派、永久回执共享同一事务。
 */
@Service
@RequiredArgsConstructor
public class MesDispatchCorrectionService {

    private static final String KIND = "DISPATCH_CORRECTION";

    private static final Set<String> STATUSES = Set.of("SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN");

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance).addSerializer(BigDecimal.class, ToStringSerializer.instance)).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final MesActualFactoryAccessService access;

    private final MesDispatchCorrectionMapper mapper;

    private final MesDispatchReturnService returns;

    private final MesTaskArrangeService arrangements;

    private final MesIdentifierBindingGroupService taskLocks;

    private final MesExecutionCommandCancellationService cancellations;

    private final RemoteClassesService shifts;

    @Transactional(readOnly = true)
    public MesDispatchCorrectionDTO.Preview preview(String arrangeId, String shiftCode, String worksiteCodes) {
        String factory = access.require("read");
        return observe(id(arrangeId), shiftCode, worksiteCodes == null ? null : csv(worksiteCodes), factory, null).preview();
    }

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
            var owner = mapper.arrange(tenant, factory, id(command.getArrangeId()));
            require(owner != null, "当前实际组织不存在原派工");
            taskLocks.lockDispatchTask(owner.getTaskCode());
            var observation = observe(owner.getId(), command.getShiftCode(), command.getWorksiteCodes(), factory, null);
            require(observation.preview().isEligible(), String.join("；", observation.preview().getBlockers()));
            require(Objects.equals(owner.getTaskCode(), observation.preview().getArrange().getTaskCode()), "派工归属已变化，请原号核对");
            require(command.getExpectedFingerprint().equals(observation.preview().getEvidenceFingerprint()), "派工或目标依据已变化，请重新预览");
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
                require(Objects.equals(row.getApplicantId(), actor) && Objects.equals(row.getApplicantCode(), user.getUsername()), "只有原申请人可撤回");
                row.setStatus("WITHDRAWN");
            } else {
                require(!Objects.equals(row.getApplicantId(), actor) && !Objects.equals(row.getApplicantCode(), user.getUsername()), "修正申请人与审批人必须分离");
                require(row.getEvidenceFingerprint().equals(command.getExpectedFingerprint()), "审批必须保留原申请所见的具体目标指纹");
                row.setReviewerId(actor);
                row.setReviewerCode(user.getUsername());
                row.setReviewerName(user.getName());
                row.setReviewedTime(now());
                row.setReviewReason(command.getReason());
                if ("REJECT".equals(command.getAction())) {
                    row.setStatus("REJECTED");
                } else {
                    var current = observe(row.getArrangeId(), row.getTargetShiftCode(), csv(row.getTargetWorksiteCodes()), factory, row.getId());
                    require(current.preview().isEligible(), String.join("；", current.preview().getBlockers()));
                    require(row.getEvidenceFingerprint().equals(current.preview().getEvidenceFingerprint()), "来源、资源、班次或执行模板已变化，须重新申请");
                    returns.retireForCorrection(row.getId(), row.getSourceFingerprint());
                    var dto = replacementCommand(row, current.preview().getArrange());
                    arrangements.arrangeCorrection(dto, row.getId());
                    var replacement = mapper.replacement(tenant, factory, row.getId());
                    require(replacement != null && Objects.equals(replacement.getTaskCode(), row.getTaskCode()) && Objects.equals(replacement.getProduceTaskCode(), row.getProduceTaskCode()), "替代派工未唯一归属原申请");
                    require(samePolicy(row.getOriginalPolicySnapshot(), replacement.getExecutionPolicySnapshot()), "真实派工冻结策略与原申请所见不一致，整笔修正回滚");
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

    @Transactional(readOnly = true)
    public MesDispatchCorrectionDTO.Detail detail(String correctionId) {
        String factory = access.require("read");
        var row = mapper.request(TenantContextHolder.getTenantId(), factory, id(correctionId));
        require(row != null, "当前实际组织不存在该修正申请");
        return detail(row, true);
    }

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
        result.setRecords(mapper.page(tenant, factory, status, arrange, size, (current - 1) * size).stream().map(this::view).toList());
        return result;
    }

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

    private Observation observe(Long arrangeId, String shiftCode, List<String> worksiteCodes, String factory, Long ownId) {
        Long tenant = TenantContextHolder.getTenantId();
        var evidence = returns.preview(arrangeId);
        var arrange = evidence.getArrange();
        require(arrange != null && Objects.equals(arrange.getTenantId(), tenant) && factory.equals(arrange.getFactoryCode()), "原派工返回范围不匹配");
        var task = mapper.task(tenant, factory, arrange.getTaskCode());
        var produce = mapper.produce(tenant, factory, arrange.getTaskCode(), arrange.getProduceTaskCode());
        require(task != null && produce != null, "缺少原真实工单或工序任务");
        var rules = activeShifts(tenant, factory);
        String targetShift = shiftCode == null ? arrange.getShiftCode() : code(shiftCode);
        List<String> selected = worksiteCodes == null ? csv(arrange.getWorksiteCodes()) : codes(worksiteCodes);
        var targetRule = rules.stream().filter(s -> Objects.equals(s.getShiftCode(), targetShift)).findFirst().orElse(null);
        var blockers = new ArrayList<>(evidence.getBlockers());
        if (targetRule == null) {
            blockers.add("目标班次不存在、已停用或缺少正式依据");
        }
        List<String> allowed = csv(produce.getWorksiteCodes());
        if (selected.isEmpty() || !allowed.containsAll(selected)) {
            blockers.add("目标工位不属于原工序授权候选");
        }
        if (Objects.equals(targetShift, arrange.getShiftCode()) && new HashSet<>(selected).equals(new HashSet<>(csv(arrange.getWorksiteCodes())))) {
            blockers.add("班次及工位完全没有变化");
        }
        if (produce.getPlannedWorksiteCode() != null && !produce.getPlannedWorksiteCode().isBlank() && !selected.equals(List.of(produce.getPlannedWorksiteCode()))) {
            blockers.add("APS已锁定工位，须返回计划重排");
        }
        if (produce.getPlannedWorkerCode() != null && !produce.getPlannedWorkerCode().isBlank() && !new HashSet<>(csv(produce.getPlannedWorkerCode())).equals(new HashSet<>(csv(arrange.getWorkerCode())))) {
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
        var matched = templates.stream().filter(t -> MesExecutionTemplateMatcher.reasons(t, task, hypothetical, LocalDateTime.now()).isEmpty()).max(MesExecutionTemplateMatcher.order()).orElse(null);
        if (!Objects.equals(arrange.getExecutionTemplateId(), matched == null ? null : matched.getId()) || !Objects.equals(arrange.getExecutionTemplateVersion(), matched == null ? null : matched.getVersionNo())) {
            blockers.add("当前匹配执行模板已变化，不能由班次/工位修正更换冻结策略");
        }
        var preview = new MesDispatchCorrectionDTO.Preview();
        preview.setTenantId(tenant.toString());
        preview.setFactoryCode(factory);
        preview.setObservedAt(now());
        preview.setArrange(arrange);
        preview.setTargetShiftCode(targetShift);
        preview.setTargetShiftName(targetRule == null ? null : targetRule.getShiftName());
        preview.setTargetWorksiteCodes(selected);
        preview.setCandidateShifts(rules.stream().map(s -> new MesDispatchCorrectionDTO.Choice(s.getShiftCode(), s.getShiftName())).toList());
        List<String> names = csv(produce.getWorksiteNames());
        var choices = new ArrayList<MesDispatchCorrectionDTO.Choice>();
        for (int i = 0; i < allowed.size(); i++) {
            choices.add(new MesDispatchCorrectionDTO.Choice(allowed.get(i), i < names.size() ? names.get(i) : allowed.get(i)));
        }
        preview.setCandidateWorksites(choices);
        var facts = new LinkedHashMap<String, Object>();
        facts.put("contractVersion", MesDispatchCorrectionDTO.VERSION);
        facts.put("arrange", arrange);
        facts.put("task", task);
        facts.put("produce", produce);
        facts.put("returnEvidence", evidence.getEvidenceFingerprint());
        facts.put("targetShift", targetRule);
        facts.put("targetWorksites", selected);
        facts.put("matchingTemplate", matched);
        String snapshot = json(facts);
        preview.setEvidenceFingerprint(hash(snapshot));
        preview.setBlockers(List.copyOf(blockers));
        preview.setEligible(blockers.isEmpty());
        return new Observation(preview, evidence.getEvidenceFingerprint(), snapshot);
    }

    private MesDispatchCorrectionRequestEntity request(Observation observation, MesDispatchCorrectionDTO.Command command, Long tenant, String factory) {
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

    private MesTaskArrangeDTO replacementCommand(MesDispatchCorrectionRequestEntity row, MesTaskArrangeEntity source) {
        var dto = new MesTaskArrangeDTO();
        dto.setCode(row.getTaskCode());
        dto.setShiftCode(row.getTargetShiftCode());
        dto.setShiftName(row.getTargetShiftName());
        dto.setTaskArrangeList(List.of(new MesTaskArrangeEntity().setProduceTaskCode(row.getProduceTaskCode()).setWorkerCode(source.getWorkerCode()).setWorkerName(source.getWorkerName()).setWorksiteCodes(row.getTargetWorksiteCodes())));
        return dto;
    }

    private MesDispatchCorrectionDTO.Resolution saveReceipt(MesDispatchCorrectionRequestEntity row, MesDispatchCorrectionDTO.Command command, String hash, String factory) {
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
        require(persisted != null && Objects.equals(persisted.getResultJson(), operation.getResultJson()) && Objects.equals(persisted.getRequestHash(), hash), "修正原回执回读不一致，整笔操作回滚");
        return resolution(persisted.getResultJson());
    }

    private MesDispatchCorrectionDTO.Detail detail(MesDispatchCorrectionRequestEntity row, boolean current) {
        var result = new MesDispatchCorrectionDTO.Detail();
        result.setTenantId(row.getTenantId().toString());
        result.setFactoryCode(row.getFactoryCode());
        result.setObservedAt(now());
        result.setCorrection(view(row));
        result.setOperations(mapper.operations(row.getTenantId(), row.getFactoryCode(), row.getId()).stream().map(this::operationView).toList());
        if (current && "SUBMITTED".equals(row.getStatus())) {
            result.setCurrentPreview(observe(row.getArrangeId(), row.getTargetShiftCode(), csv(row.getTargetWorksiteCodes()), row.getFactoryCode(), row.getId()).preview());
        }
        return result;
    }

    private MesDispatchCorrectionDTO.Correction view(MesDispatchCorrectionRequestEntity row) {
        var result = BeanUtil.copyProperties(row, MesDispatchCorrectionDTO.Correction.class, "targetWorksiteCodes");
        result.setTargetWorksiteCodes(csv(row.getTargetWorksiteCodes()));
        return result;
    }

    private MesDispatchCorrectionDTO.Operation operationView(MesDispatchCorrectionOperationEntity row) {
        return BeanUtil.copyProperties(row, MesDispatchCorrectionDTO.Operation.class);
    }

    private List<MdmClassesEntity> activeShifts(Long tenant, String factory) {
        var response = shifts.listActive();
        require(response != null && response.isOk() && response.getData() != null, "正式班次来源不可用");
        var rules = response.getData().stream().filter(s -> Objects.equals(s.getTenantId(), tenant) && Objects.equals(s.getFactoryCode(), factory) && Boolean.TRUE.equals(s.getStatus())).sorted(Comparator.comparing(MdmClassesEntity::getShiftCode)).toList();
        require(rules.size() <= 200 && rules.stream().allMatch(s -> s.getId() != null && s.getShiftCode() != null && s.getShiftName() != null && s.getStartTime() != null && s.getEndTime() != null && !s.getStartTime().equals(s.getEndTime())) && rules.stream().map(MdmClassesEntity::getShiftCode).distinct().count() == rules.size(), "正式班次数据不完整或重复");
        return rules;
    }

    private MesDispatchCorrectionDTO.Resolution replay(MesDispatchCorrectionOperationEntity row, Long actor, String hash) {
        require(Objects.equals(actor, row.getActorId()) && Objects.equals(hash, row.getRequestHash()), "修正原号已用于不同正文或操作人");
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
        require(Set.of("SUBMIT", "APPROVE", "REJECT", "WITHDRAW").contains(Objects.toString(c.getAction(), "")), "修正动作无效");
        require(c.getReason() != null && !c.getReason().isBlank() && c.getReason().length() <= 500 && c.getReason().equals(c.getReason().trim()), "请提供1至500字精确办理依据");
        if ("SUBMIT".equals(c.getAction())) {
            id(c.getArrangeId());
            require(c.getCorrectionId() == null && c.getShiftCode() != null && c.getWorksiteCodes() != null, "提交须指定原派工、正式班次和工位，不能夹带申请ID");
            code(c.getShiftCode());
            codes(c.getWorksiteCodes());
            fingerprint(c.getExpectedFingerprint());
        } else {
            id(c.getCorrectionId());
            require(c.getArrangeId() == null && c.getShiftCode() == null && c.getWorksiteCodes() == null, "办理原申请不能夹带派工或目标变更");
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
        require(value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 100 && value.indexOf(',') < 0 && value.chars().noneMatch(Character::isISOControl), "业务编码缺失或格式无效");
        return value;
    }

    private List<String> codes(List<String> values) {
        require(values != null && !values.isEmpty() && values.size() <= 200 && values.stream().distinct().count() == values.size(), "工位须为1至200个不重复精确编码");
        return values.stream().map(this::code).sorted().toList();
    }

    private List<String> csv(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",", -1)).map(String::trim).toList();
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
