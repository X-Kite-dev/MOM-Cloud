package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.feign.RemoteMdmDeviceRecoveryEventService;
import com.kitex.mom.mdm.feign.RemoteMdmToolingService;
import com.kitex.mom.mdm.feign.RemoteMdmWorkforceService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnMpsPublicationRecoveryDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnResourceLocalReplanService;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import com.kitex.mom.tpm.feign.RemoteTpmDeviceAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备、工装和人员异常共用的工序级局部重排闭环。异常事实来自TPM/MDM，
 * PLAN只冻结证据、调用确定性APS并组织A3发布，不代替源系统关闭异常。
 */
@Service
@RequiredArgsConstructor
public class PlnResourceLocalReplanServiceImpl implements PlnResourceLocalReplanService {
    private static final Set<String> RESOURCE_TYPES = Set.of("DEVICE", "TOOLING", "EMPLOYEE", "EXECUTION");
    private static final Set<String> POLICY_TYPES = Set.of("ALL", "DEVICE", "TOOLING", "EMPLOYEE", "EXECUTION");
    private static final Set<String> LOCAL_REPLAN_TRIGGER_TYPES =
            Set.of("RESOURCE_LOCAL_REPLAN", "EXECUTION_DEVIATION_LOCAL_REPLAN");
    private static final int PROPOSAL_VALID_HOURS = 24;

    private final PlnResourceReplanPolicyMapper policyMapper;
    private final PlnResourceReplanCaseMapper caseMapper;
    private final PlnResourceReplanLineMapper lineMapper;
    private final PlnResourceReplanOperationMapper operationMapper;
    private final PlnAutomationEventMapper automationEventMapper;
    private final PlnRepairResumeObservationMapper resumeObservationMapper;
    private final PlnQualityReplanCaseMapper qualityCaseMapper;
    private final PlnShortageReplanCaseMapper shortageCaseMapper;
    private final PlnInsertionReplanCaseMapper insertionCaseMapper;
    private final PlnInsertionReplanIssueMapper insertionIssueMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnSchedulingAgentService schedulingAgentService;
    private final PlnMpsRemainingEvidenceReader mpsRemainingEvidenceReader;
    private final PlnMpsPublicationEvidenceReader mpsPublicationEvidenceReader;
    private final PlnMpsLineMapper mpsLineMapper;
    private final RemoteTpmDeviceAvailabilityService deviceAvailabilityService;
    private final RemoteMdmToolingService toolingService;
    private final RemoteMdmWorkforceService workforceService;
    private final RemoteMdmDeviceRecoveryEventService deviceRecoveryService;
    private final RemoteMesTaskService mesTaskService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<PlnResourceReplanPolicyEntity> policyPage(
            SearchPage page, PlanResourceLocalReplanDTO.PolicyQuery query) {
        PlanResourceLocalReplanDTO.PolicyQuery q = query == null
                ? new PlanResourceLocalReplanDTO.PolicyQuery() : query;
        return policyMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                .like(StrUtil.isNotBlank(q.getPolicyCode()), PlnResourceReplanPolicyEntity::getPolicyCode, q.getPolicyCode())
                .eq(StrUtil.isNotBlank(q.getResourceType()), PlnResourceReplanPolicyEntity::getResourceType, q.getResourceType())
                .like(StrUtil.isNotBlank(q.getResourceCode()), PlnResourceReplanPolicyEntity::getResourceCode, q.getResourceCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnResourceReplanPolicyEntity::getStatus, q.getStatus())
                .orderByDesc(PlnResourceReplanPolicyEntity::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnResourceReplanPolicyEntity savePolicyDraft(
            PlanResourceLocalReplanDTO.PolicyDraftCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getPolicyCode())
                && StrUtil.isNotBlank(command.getPolicyName()), "策略编码和名称不能为空");
        String factoryCode = StrUtil.blankToDefault(command.getFactoryCode(), FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factoryCode), "当前工厂不能为空");
        requireFactory(factoryCode);
        String type = normalizePolicyType(command.getResourceType());
        String resourceCode = trimToNull(command.getResourceCode());
        require(!Set.of("ALL", "EXECUTION").contains(type) || resourceCode == null,
                "ALL通配策略和MES执行偏差策略不能指定资源编码");
        int freezeMinutes = bounded(command.getFreezeMinutes(), 120, 30, 7 * 24 * 60,
                "冻结区分钟数必须在30分钟到7天之间");
        int maxDelayMinutes = bounded(command.getMaxDelayMinutes(), 7 * 24 * 60, 1, 366 * 24 * 60,
                "最大允许延期必须在1分钟到366天之间");
        int priority = bounded(command.getPriority(), 100, 0, 9999, "策略优先级必须在0到9999之间");
        require(command.getEffectiveFrom() == null || command.getEffectiveTo() == null
                || command.getEffectiveTo().isAfter(command.getEffectiveFrom()), "策略失效时间必须晚于生效时间");
        String policyCode = command.getPolicyCode().trim();
        policyMapper.lockKey(factoryCode + ':' + policyCode);
        PlnResourceReplanPolicyEntity entity;
        MomUser user = currentUser();
        if (command.getId() == null) {
            Integer lastVersion = policyMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                            .eq(PlnResourceReplanPolicyEntity::getFactoryCode, factoryCode)
                            .eq(PlnResourceReplanPolicyEntity::getPolicyCode, policyCode)
                            .orderByDesc(PlnResourceReplanPolicyEntity::getPolicyVersion).last("LIMIT 1"))
                    .stream().findFirst().map(PlnResourceReplanPolicyEntity::getPolicyVersion).orElse(0);
            entity = new PlnResourceReplanPolicyEntity().setPolicyVersion(lastVersion + 1)
                    .setStatus("DRAFT").setFactoryCode(factoryCode)
                    .setAuthorCode(userCode(user)).setAuthorName(userName(user));
        } else {
            entity = requirePolicy(command.getId(), true);
            require("DRAFT".equals(entity.getStatus()), "仅草稿策略允许修改");
            require(Objects.equals(entity.getFactoryCode(), factoryCode), "策略不属于当前工厂");
        }
        entity.setPolicyCode(policyCode).setPolicyName(command.getPolicyName().trim())
                .setResourceType(type).setResourceCode(resourceCode).setFreezeMinutes(freezeMinutes)
                .setMaxDelayMinutes(maxDelayMinutes).setPriority(priority)
                .setEffectiveFrom(command.getEffectiveFrom()).setEffectiveTo(command.getEffectiveTo());
        entity.setPolicyFingerprint(policyFingerprint(entity));
        require(entity.getId() == null ? policyMapper.insert(entity) == 1 : policyMapper.updateById(entity) == 1,
                "资源异常重排策略草稿保存失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnResourceReplanPolicyEntity activatePolicy(PlanResourceLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId()),
                "策略启用参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("ACTIVATE:" + requestId);
        PlnResourceReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                        .eq(PlnResourceReplanPolicyEntity::getActivationRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId()), "策略启用幂等号已被不同请求使用");
            return duplicate;
        }
        PlnResourceReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("DRAFT".equals(entity.getStatus()), "仅草稿策略允许启用");
        policyMapper.lockKey("SCOPE:" + entity.getFactoryCode() + ':' + entity.getResourceType() + ':'
                + Objects.toString(entity.getResourceCode(), "*"));
        MomUser user = currentUser();
        require(!Objects.equals(entity.getAuthorCode(), userCode(user)), "策略编制人与启用复核人必须分离");
        LocalDateTime from = Optional.ofNullable(entity.getEffectiveFrom()).orElse(LocalDateTime.now());
        List<PlnResourceReplanPolicyEntity> active = policyMapper.selectList(
                new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                        .eq(PlnResourceReplanPolicyEntity::getFactoryCode, entity.getFactoryCode())
                        .eq(PlnResourceReplanPolicyEntity::getResourceType, entity.getResourceType())
                        .eq(PlnResourceReplanPolicyEntity::getStatus, "ACTIVE"));
        boolean overlap = active.stream().filter(item -> Objects.equals(trimToNull(item.getResourceCode()),
                        trimToNull(entity.getResourceCode())))
                .anyMatch(item -> intervalsOverlap(from, entity.getEffectiveTo(),
                        Optional.ofNullable(item.getEffectiveFrom()).orElse(LocalDateTime.MIN), item.getEffectiveTo()));
        require(!overlap, "同工厂、同资源范围存在生效期重叠的启用策略");
        entity.setEffectiveFrom(from).setPolicyFingerprint(policyFingerprint(entity)).setStatus("ACTIVE")
                .setActivationRequestId(requestId).setApproverCode(userCode(user)).setApproverName(userName(user))
                .setApprovedTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "资源异常重排策略启用失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnResourceReplanPolicyEntity retirePolicy(PlanResourceLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "策略退役参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("RETIRE:" + requestId);
        PlnResourceReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                        .eq(PlnResourceReplanPolicyEntity::getRetireRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId())
                            && Objects.equals(duplicate.getRetireReason(), command.getReason().trim()),
                    "策略退役幂等号已被不同请求使用");
            return duplicate;
        }
        PlnResourceReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("ACTIVE".equals(entity.getStatus()), "仅启用策略允许退役");
        MomUser user = currentUser();
        entity.setStatus("RETIRED").setRetireRequestId(requestId).setRetireReason(command.getReason().trim())
                .setRetiredBy(userCode(user)).setRetiredTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "资源异常重排策略退役失败");
        return entity;
    }

    @Override
    public Page<PlnResourceReplanCaseEntity> casePage(
            SearchPage page, PlanResourceLocalReplanDTO.CaseQuery query) {
        LocalDateTime now = LocalDateTime.now();
        caseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getStatus, "PROPOSED")
                .le(PlnResourceReplanCaseEntity::getExpiresTime, now)
                .set(PlnResourceReplanCaseEntity::getStatus, "EXPIRED"));
        scenarioMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnSchedulingScenarioEntity>()
                .in(PlnSchedulingScenarioEntity::getTriggerType, LOCAL_REPLAN_TRIGGER_TYPES)
                .eq(PlnSchedulingScenarioEntity::getScenarioStatus, "EVALUATED")
                .le(PlnSchedulingScenarioEntity::getExpiresTime, now)
                .set(PlnSchedulingScenarioEntity::getScenarioStatus, "EXPIRED"));
        PlanResourceLocalReplanDTO.CaseQuery q = query == null
                ? new PlanResourceLocalReplanDTO.CaseQuery() : query;
        Page<PlnResourceReplanCaseEntity> result = caseMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .like(StrUtil.isNotBlank(q.getCaseNo()), PlnResourceReplanCaseEntity::getCaseNo, q.getCaseNo())
                .like(StrUtil.isNotBlank(q.getIssueNo()), PlnResourceReplanCaseEntity::getIssueNo, q.getIssueNo())
                .eq(StrUtil.isNotBlank(q.getResourceType()), PlnResourceReplanCaseEntity::getResourceType, q.getResourceType())
                .like(StrUtil.isNotBlank(q.getResourceCode()), PlnResourceReplanCaseEntity::getResourceCode, q.getResourceCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnResourceReplanCaseEntity::getStatus, q.getStatus())
                .eq(StrUtil.isNotBlank(q.getRecoveryReviewStatus()), PlnResourceReplanCaseEntity::getRecoveryReviewStatus, q.getRecoveryReviewStatus())
                .orderByDesc(PlnResourceReplanCaseEntity::getProposedTime));
        result.getRecords().forEach(this::enrichCandidate);
        return result;
    }

    @Override
    public PlanResourceLocalReplanDTO.CaseDetail detail(Long caseId) {
        PlnResourceReplanCaseEntity entity = requireCase(caseId, false);
        requireFactory(entity.getFactoryCode());
        PlanResourceLocalReplanDTO.CaseDetail result = new PlanResourceLocalReplanDTO.CaseDetail();
        BeanUtils.copyProperties(entity, result);
        result.setLines(caseLines(caseId));
        result.setOperations(operationMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanOperationEntity>()
                .eq(PlnResourceReplanOperationEntity::getCaseId, caseId)
                .orderByAsc(PlnResourceReplanOperationEntity::getOperationTime)));
        enrichCandidate(result);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public PlanResourceLocalReplanDTO.RecoveryReviewPreview recoveryReviewPreview(Long caseId) {
        PlnResourceReplanCaseEntity candidate = requireCase(caseId, false);
        requireRecoveryReviewFactory(candidate.getFactoryCode());
        require(Objects.equals(candidate.getTenantId(), TenantContextHolder.getTenantId()), "恢复复核案件租户不一致");
        return recoveryReviewFacts(candidate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlnRepairResumeObservation> resumeObservations(Long caseId) {
        PlnResourceReplanCaseEntity candidate = requireCase(caseId, false);
        requireRecoveryReviewFactory(candidate.getFactoryCode());
        require("DEVICE".equals(candidate.getResourceType()) && candidate.getRecoveryReceiptId() != null,
                "该案件没有设备恢复来源，不能查询真实复工回写");
        return resumeObservationMapper.selectList(new LambdaQueryWrapper<PlnRepairResumeObservation>()
                .eq(PlnRepairResumeObservation::getTenantId, candidate.getTenantId())
                .eq(PlnRepairResumeObservation::getFactoryCode, candidate.getFactoryCode())
                .eq(PlnRepairResumeObservation::getPlanNo, candidate.getIssueNo())
                .eq(PlnRepairResumeObservation::getDeviceCode, candidate.getResourceCode())
                .eq(PlnRepairResumeObservation::getRecoveryReceiptId, candidate.getRecoveryReceiptId())
                .orderByDesc(PlnRepairResumeObservation::getResumedTime).last("LIMIT 100"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnResourceReplanOperationEntity decideRecoveryReview(PlanResourceLocalReplanDTO.RecoveryReviewCommand command) {
        require(command != null && command.getCaseId() != null && command.getRecoveryReviewEventId() != null
                        && StrUtil.isNotBlank(command.getRequestId()) && StrUtil.isNotBlank(command.getDecision())
                        && StrUtil.isNotBlank(command.getExpectedEvidenceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "设备恢复排程复核须指定案件、恢复事件、请求号、决定、事实指纹与依据");
        String requestId = command.getRequestId().trim();
        String reason = command.getReason().trim();
        String decision = command.getDecision().trim().toUpperCase(Locale.ROOT);
        require(requestId.length() <= 100 && reason.length() <= 700
                && Set.of("KEEP_PUBLISHED", "REQUIRE_REPLAN", "CONFIRM_REPLAN").contains(decision)
                && ("CONFIRM_REPLAN".equals(decision)
                || command.getReplacementTaskId() == null && command.getReplacementCaseId() == null)
                && command.getExpectedEvidenceFingerprint().matches("[a-f0-9]{64}"), "恢复复核命令格式无效");
        PlnResourceReplanCaseEntity candidate = requireCase(command.getCaseId(), false);
        requireRecoveryReviewFactory(candidate.getFactoryCode());
        require(Objects.equals(candidate.getTenantId(), TenantContextHolder.getTenantId()), "恢复复核案件租户不一致");
        MomUser reviewer = currentUser();
        require(reviewer != null && reviewer.getId() != null && StrUtil.isNotBlank(reviewer.getUsername()), "复核须实名登录");
        String fingerprint = hash(json(List.of(command.getCaseId(), command.getRecoveryReviewEventId(),
                decision, command.getExpectedEvidenceFingerprint(), reason,
                Objects.toString(command.getReplacementTaskId(), ""),
                Objects.toString(command.getReplacementCaseId(), ""), reviewer.getId())));
        PlnResourceReplanOperationEntity previous = operationMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanOperationEntity>()
                        .eq(PlnResourceReplanOperationEntity::getTenantId, candidate.getTenantId())
                        .eq(PlnResourceReplanOperationEntity::getRequestId, requestId));
        if (previous != null) {
            require(Objects.equals(previous.getCaseId(), candidate.getId())
                    && Objects.equals(previous.getCommandFingerprint(), fingerprint), "复核请求号已用于其他案件、内容或人员");
            return previous;
        }
        candidate = requireCase(command.getCaseId(), true);
        previous = operationMapper.selectOne(new LambdaQueryWrapper<PlnResourceReplanOperationEntity>()
                .eq(PlnResourceReplanOperationEntity::getTenantId, candidate.getTenantId())
                .eq(PlnResourceReplanOperationEntity::getRequestId, requestId));
        if (previous != null) {
            require(Objects.equals(previous.getCaseId(), candidate.getId())
                    && Objects.equals(previous.getCommandFingerprint(), fingerprint), "复核请求号已用于其他案件、内容或人员");
            return previous;
        }
        require(Objects.equals(candidate.getRecoveryReviewEventId(), command.getRecoveryReviewEventId())
                        && candidate.getRecoveryReceiptId() != null && candidate.getRecoveryReviewStatus() != null,
                "恢复复核事件已被新回执覆盖或案件没有待办");
        PlanResourceLocalReplanDTO.RecoveryReviewPreview facts = recoveryReviewFacts(candidate);
        require(Objects.equals(facts.getEvidenceFingerprint(), command.getExpectedEvidenceFingerprint()),
                "设备、TPM、正式排程或MES执行事实已变化，请重新打开复核预览");
        String before = candidate.getRecoveryReviewStatus();
        String after;
        String action;
        if ("KEEP_PUBLISHED".equals(decision)) {
            require("PENDING".equals(before) && facts.getGaps().isEmpty(),
                    "仅当前设备资格与TPM/MES/正式排程均可核验时允许保留已发布方案");
            require(!Objects.equals(candidate.getApplicantCode(), reviewer.getUsername())
                            && !Objects.equals(candidate.getPublisherCode(), reviewer.getUsername()),
                    "原资源重排申请人或发布人不能自行确认恢复后继续执行原排程");
            after = "KEEP_PUBLISHED";
            action = "RECOVERY_KEEP";
        } else if ("REQUIRE_REPLAN".equals(decision)) {
            require("PENDING".equals(before) && Set.of("PUBLISHED", "ROLLED_BACK").contains(candidate.getStatus()),
                    "仅待复核的已发布或其后受控回退案件可要求重新排产");
            after = "REPLAN_REQUIRED";
            action = "RECOVERY_REQUIRE_REPLAN";
        } else {
            require("REPLAN_REQUIRED".equals(before)
                            && (command.getReplacementTaskId() != null) != (command.getReplacementCaseId() != null)
                            && Boolean.TRUE.equals(facts.getTpmCurrentlyClear())
                            && Boolean.TRUE.equals(facts.getDeviceCurrentlyUsable())
                            && Boolean.TRUE.equals(facts.getCurrentTaskOfficial())
                            && facts.getCurrentScheduleFingerprint() != null,
                    "须在设备资格有效且TPM无阻断时，指定一个已生效的新正式任务或后继A3重排案件");
            if (command.getReplacementTaskId() != null) {
                require(Objects.equals(command.getReplacementTaskId(), facts.getCurrentTaskId())
                                && !Objects.equals(command.getReplacementTaskId(), candidate.getOriginalTaskId()),
                        "新正式排产任务必须是当前锁定任务，且不能与原发布任务相同");
                PlnSchedulingTaskEntity replacement = taskMapper.selectById(command.getReplacementTaskId());
                require(replacement != null && Objects.equals(replacement.getTenantId(), candidate.getTenantId())
                                && Objects.equals(replacement.getFactoryCode(), candidate.getFactoryCode())
                                && "official".equalsIgnoreCase(replacement.getMode())
                                && "SUCCESS".equals(replacement.getTaskStatus())
                                && replacement.getCreateTime() != null
                                && !replacement.getCreateTime().isBefore(candidate.getRecoveryReviewTime()),
                        "新任务不是恢复事件后的当前正式成功排产，不能核销复核待办");
            } else {
                require(!Objects.equals(command.getReplacementCaseId(), candidate.getId()), "不能用原案件核销原案件");
                PlnResourceReplanCaseEntity replacement = caseMapper.selectById(command.getReplacementCaseId());
                require(replacement != null && Objects.equals(replacement.getTenantId(), candidate.getTenantId())
                                && Objects.equals(replacement.getFactoryCode(), candidate.getFactoryCode())
                                && Objects.equals(replacement.getIssueId(), candidate.getIssueId())
                                && "PUBLISHED".equals(replacement.getStatus())
                                && replacement.getPublishedTime() != null
                                && replacement.getPublishedTime().isAfter(candidate.getRecoveryReviewTime())
                                && Objects.equals(replacement.getOriginalTaskId(), facts.getCurrentTaskId())
                                && Objects.equals(replacement.getPublishedScheduleFingerprint(), facts.getCurrentScheduleFingerprint()),
                        "后继A3案件未在恢复后真实发布到当前正式工序，不能核销复核待办");
            }
            after = "REPLAN_CONFIRMED";
            action = "RECOVERY_CONFIRM_REPLAN";
        }
        candidate.setRecoveryReviewStatus(after).setRecoveryReviewRequestId(requestId)
                .setRecoveryReviewReason(reason).setRecoveryReviewedBy(reviewer.getUsername())
                .setRecoveryReviewedTime(LocalDateTime.now())
                .setRecoveryReviewEvidenceFingerprint(facts.getEvidenceFingerprint())
                .setRecoveryReplacementTaskId("CONFIRM_REPLAN".equals(decision) ? command.getReplacementTaskId() : null)
                .setRecoveryReplacementCaseId("CONFIRM_REPLAN".equals(decision) ? command.getReplacementCaseId() : null);
        require(caseMapper.updateById(candidate) == 1, "设备恢复排程复核结论保存失败");
        PlnResourceReplanOperationEntity operation = new PlnResourceReplanOperationEntity()
                .setCaseId(candidate.getId()).setRequestId(requestId).setOperationType(action)
                .setBeforeStatus(candidate.getStatus()).setAfterStatus(candidate.getStatus())
                .setCommandFingerprint(fingerprint)
                .setContent("恢复事件" + candidate.getRecoveryReviewEventId() + "、回执" + candidate.getRecoveryReceiptId()
                        + "：" + before + "→" + after + "；" + reason
                        + (command.getReplacementTaskId() == null ? "" : "；新正式任务=" + command.getReplacementTaskId())
                        + (command.getReplacementCaseId() == null ? "" : "；后继A3案件=" + command.getReplacementCaseId()))
                .setOperatorCode(reviewer.getUsername()).setOperatorName(userName(reviewer))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(candidate.getFactoryCode());
        operation.setTenantId(candidate.getTenantId());
        require(operationMapper.insert(operation) == 1, "设备恢复排程复核永久履历保存失败");
        return operation;
    }

    private PlanResourceLocalReplanDTO.RecoveryReviewPreview recoveryReviewFacts(PlnResourceReplanCaseEntity candidate) {
        require("DEVICE".equals(candidate.getResourceType()) && candidate.getRecoveryReviewEventId() != null
                        && candidate.getRecoveryReceiptId() != null && candidate.getRecoveryReviewStatus() != null,
                "案件没有设备恢复复核待办");
        PlnAutomationEventEntity source = automationEventMapper.selectById(candidate.getRecoveryReviewEventId());
        require(source != null && Objects.equals(source.getTenantId(), candidate.getTenantId())
                        && Objects.equals(source.getFactoryCode(), candidate.getFactoryCode())
                        && "MDM".equals(source.getSourceSystem()) && "RESOURCE_AVAILABLE".equals(source.getEventType())
                        && "SUCCEEDED".equals(source.getStatus()) && StrUtil.isNotBlank(source.getPayloadJson()),
                "设备恢复来源事件尚未成功完成或与案件不一致");
        JsonNode payload;
        try {
            payload = objectMapper.readTree(source.getPayloadJson());
            require(Objects.equals(payload.path("recoveryReceiptId").asText(), String.valueOf(candidate.getRecoveryReceiptId()))
                            && "DEVICE".equals(payload.path("resourceType").asText())
                            && Objects.equals(payload.path("resourceCode").asText(), candidate.getResourceCode()),
                    "PLAN恢复事件载荷与待办来源不一致");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("PLAN恢复事件冻结载荷不可解析");
        }
        var response = deviceRecoveryService.evidence(candidate.getRecoveryReceiptId(), candidate.getFactoryCode());
        require(response != null && response.isOk() && response.getData() != null, "MDM设备恢复事实暂不可核验");
        var proof = response.getData();
        require(Objects.equals(proof.getTenantId(), candidate.getTenantId())
                        && Objects.equals(proof.getFactoryCode(), candidate.getFactoryCode())
                        && Objects.equals(proof.getReceiptId(), candidate.getRecoveryReceiptId())
                        && Objects.equals(proof.getDeviceCode(), candidate.getResourceCode())
                        && Objects.equals(String.valueOf(proof.getTaskId()), payload.path("taskId").asText())
                        && Objects.equals(proof.getTaskCode(), payload.path("taskCode").asText())
                        && Objects.equals(proof.getSourceFingerprint(), payload.path("sourceFingerprint").asText()),
                "MDM恢复来源与PLAN案件不一致");
        require(StrUtil.isNotBlank(candidate.getResourceEvidenceSnapshot()), "原资源重排冻结维修窗口证据缺失");
        try {
            JsonNode authority = objectMapper.readTree(candidate.getResourceEvidenceSnapshot()).path("authorityFacts");
            boolean matched = false;
            if (authority.isArray()) for (JsonNode fact : authority) {
                if ("REPAIR_TASK".equals(fact.path("sourceType").asText())
                        && Objects.equals(String.valueOf(proof.getTaskId()), fact.path("sourceId").asText())
                        && Objects.equals(proof.getTaskCode(), fact.path("sourceCode").asText())) {
                    matched = true;
                    break;
                }
            }
            require(matched, "原重排案件不含该MDM恢复回执对应的维修窗口");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("原资源重排冻结维修窗口证据不可解析");
        }
        boolean deviceUsable = Objects.equals(proof.getLatestRecoveryReceiptId(), proof.getReceiptId())
                && Boolean.TRUE.equals(proof.getDeviceStatus()) && "0".equals(proof.getDeviceState())
                && "1".equals(proof.getDeviceUsable());
        TpmDeviceAvailabilityQueryDTO query = new TpmDeviceAvailabilityQueryDTO();
        query.setDeviceCodes(List.of(candidate.getResourceCode()));
        LocalDateTime now = LocalDateTime.now();
        query.setStartTime(now.minusSeconds(1));
        query.setEndTime(now.plusSeconds(1));
        var windows = deviceAvailabilityService.unavailableWindows(query);
        require(windows != null && windows.isOk() && windows.getData() != null, "TPM实时设备阻断暂不可核验");
        List<String> blockers = windows.getData().stream()
                .filter(w -> Objects.equals(w.getDeviceCode(), candidate.getResourceCode())
                        && !"DEVICE_STATE".equals(w.getSourceType()))
                .map(w -> w.getSourceType() + ':' + w.getSourceId() + ':' + w.getSourceCode())
                .distinct().sorted().toList();
        PlnIssueEntity issue = issueMapper.selectById(candidate.getIssueId());
        require(issue != null && Objects.equals(issue.getTenantId(), candidate.getTenantId())
                && Objects.equals(issue.getFactoryCode(), candidate.getFactoryCode())
                && Objects.equals(issue.getIssueNo(), candidate.getIssueNo()), "生产计划来源已不可核验");
        Long currentTaskId = issue.getLockTaskId();
        PlnSchedulingTaskEntity currentTask = currentTaskId == null ? null : taskMapper.selectById(currentTaskId);
        List<PlnSchedulingOperationEntity> operations = currentTaskId == null ? List.of()
                : schedulingOperationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, currentTaskId)
                .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        MesApsLocalScheduleDTO.StateBundle mes = mesStates(issue);
        List<String> gaps = new ArrayList<>();
        if (!"PUBLISHED".equals(candidate.getStatus())) gaps.add("原资源重排案件已非已发布状态");
        if (!deviceUsable) gaps.add("MDM当前设备资格已变化");
        if (!blockers.isEmpty()) gaps.add("TPM当前仍有设备阻断：" + String.join(",", blockers));
        if (!Boolean.TRUE.equals(issue.getLockStatus())) gaps.add("PLAN生产计划未锁定正式排程");
        if ("3".equals(issue.getStatus()) || "4".equals(issue.getStatus()))
            gaps.add("生产计划已完成或关闭，不能以继续原排程作为复核结论");
        if (!Objects.equals(currentTaskId, candidate.getOriginalTaskId()))
            gaps.add("当前正式任务已非原资源重排发布任务");
        boolean currentTaskOfficial = currentTask != null && "official".equalsIgnoreCase(currentTask.getMode())
                && "SUCCESS".equals(currentTask.getTaskStatus()) && Boolean.TRUE.equals(issue.getLockStatus());
        if (!currentTaskOfficial) gaps.add("当前任务不是成功的正式排产");
        if (operations.isEmpty()) gaps.add("当前正式任务缺少锁定工序");
        String currentScheduleFingerprint = operations.isEmpty() ? null : scheduleFingerprint(operations);
        if (candidate.getPublishedScheduleFingerprint() == null
                || !Objects.equals(candidate.getPublishedScheduleFingerprint(), currentScheduleFingerprint))
            gaps.add("原案件发布后的正式工序基线缺失或当前排程已变化");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("caseId", candidate.getId());
        evidence.put("caseStatus", candidate.getStatus());
        evidence.put("eventId", source.getId());
        evidence.put("receiptId", proof.getReceiptId());
        evidence.put("latestReceiptId", proof.getLatestRecoveryReceiptId());
        evidence.put("deviceState", proof.getDeviceState());
        evidence.put("deviceUsable", proof.getDeviceUsable());
        evidence.put("deviceStatus", proof.getDeviceStatus());
        evidence.put("blockers", blockers);
        evidence.put("issueId", issue.getId());
        evidence.put("issueStatus", issue.getStatus());
        evidence.put("lockStatus", issue.getLockStatus());
        evidence.put("currentTaskId", currentTaskId);
        evidence.put("taskMode", currentTask == null ? null : currentTask.getMode());
        evidence.put("taskStatus", currentTask == null ? null : currentTask.getTaskStatus());
        evidence.put("operations", currentScheduleFingerprint);
        evidence.put("mes", mes.getExecutionFingerprint());
        return new PlanResourceLocalReplanDTO.RecoveryReviewPreview()
                .setCaseId(candidate.getId()).setRecoveryReviewEventId(source.getId())
                .setRecoveryReceiptId(proof.getReceiptId()).setReviewStatus(candidate.getRecoveryReviewStatus())
                .setIssueNo(issue.getIssueNo()).setDeviceCode(candidate.getResourceCode())
                .setCurrentTaskId(currentTaskId).setOriginalPublishedTaskId(candidate.getOriginalTaskId())
                .setCurrentScheduleFingerprint(currentScheduleFingerprint)
                .setCurrentTaskOfficial(currentTaskOfficial).setMesTaskPresent(mes.getMesTaskPresent())
                .setDeviceCurrentlyUsable(deviceUsable).setTpmCurrentlyClear(blockers.isEmpty())
                .setMesExecutionFingerprint(mes.getExecutionFingerprint())
                .setEvidenceFingerprint(hash(json(evidence))).setGaps(gaps);
    }

    @Override
    public PlanResourceLocalReplanDTO.Scenario scenario(Long scenarioId) {
        require(scenarioId != null, "资源异常重排情景不能为空");
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(scenarioId);
        require(header != null && LOCAL_REPLAN_TRIGGER_TYPES.contains(header.getTriggerType()),
                "资源或执行偏差局部重排情景不存在");
        requireFactory(header.getFactoryCode());
        List<PlanResourceLocalReplanDTO.CaseDetail> candidates = caseMapper.selectList(
                        new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                                .eq(PlnResourceReplanCaseEntity::getScenarioId, scenarioId)
                                .orderByAsc(PlnResourceReplanCaseEntity::getId)).stream()
                .map(item -> detail(item.getId())).sorted(Comparator.comparing(
                        PlanResourceLocalReplanDTO.CaseDetail::getCandidateRank,
                        Comparator.nullsLast(Integer::compareTo))).toList();
        return new PlanResourceLocalReplanDTO.Scenario().setScenario(header).setCandidates(candidates);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.Scenario assess(PlanResourceLocalReplanDTO.AssessCommand command) {
        validateAssess(command);
        require(command.getRequestId().trim().length() <= 48, "资源异常评估请求号不能超过48位");
        String requestId = command.getRequestId().trim();
        String requestFingerprint = hash(json(command));
        scenarioMapper.lock("RESOURCE_SCENARIO_ASSESS:" + requestId);
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("RESOURCE_LOCAL_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "资源异常评估幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }
        PlnIssueEntity issue = lockIssue(command.getIssueId());
        caseMapper.lockKey("ISSUE:" + issue.getId());
        ensureNoLiveProposal(issue.getId());
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        boolean openEnded = Boolean.TRUE.equals(command.getOpenEnded());
        String resourceType = normalizeResourceType(command.getResourceType());
        String resourceCode = command.getResourceCode().trim();
        PlnResourceReplanPolicyEntity policy = resolvePolicy(issue, resourceType, resourceCode, LocalDateTime.now());
        require(policy != null, "当前工厂和资源范围未配置生效的异常重排策略");
        LocalDateTime freezeUntil = LocalDateTime.now().plusMinutes(policy.getFreezeMinutes());
        require(openEnded || command.getExpectedRecoveryTime().isAfter(freezeUntil),
                "预计恢复时刻仍在冻结区内，应先由现场处置，不能通过移动计划掩盖异常");
        LocalDateTime impactEnd = openEnded ? impactEnd(originals, command.getEventStartTime())
                : command.getExpectedRecoveryTime();
        ResourceEvidence evidence = captureEvidence(resourceType, resourceCode, originals,
                command.getEventStartTime(), impactEnd, openEnded);
        require(!evidence.directCodes.isEmpty(), "权威资源事实未证明该资源异常影响当前正式排程");
        Set<String> affectedCodes = downstreamClosure(originals, evidence.directCodes);
        Map<String, PlnSchedulingOperationEntity> originalByCode = uniqueByProcess(originals);
        for (String code : affectedCodes) {
            PlnSchedulingOperationEntity operation = originalByCode.get(code);
            require(operation != null && operation.getScheduledStartTime() != null
                            && operation.getScheduledStartTime().isAfter(freezeUntil),
                    "工序[" + code + "]已进入冻结区，必须现场处置后再评估后续计划");
        }
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        validateMesMovable(mesBefore, affectedCodes);
        PlnSchedulingTaskEntity originalTask = taskMapper.selectById(issue.getLockTaskId());
        require(originalTask != null && "official".equalsIgnoreCase(originalTask.getMode()), "原正式排产任务不存在");
        LocalDateTime proposalStart = openEnded
                ? later(command.getEventStartTime(), freezeUntil.plusSeconds(1)) : command.getExpectedRecoveryTime();
        String originalFingerprint = scheduleFingerprint(originals);
        MomUser user = currentUser();
        LocalDateTime now = LocalDateTime.now();
        String sourceFingerprint = hash(evidence.fingerprint + '|' + policy.getPolicyFingerprint() + '|'
                + originalFingerprint + '|' + mesBefore.getExecutionFingerprint());
        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("RRS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(requestId);
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("RESOURCE_LOCAL_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(proposalStart.toLocalDate());
        header.setPlanNum(1);
        header.setSourceIssueIdsSnapshot(json(List.of(issue.getId())));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode(policy.getPolicyCode());
        header.setRuleVersion(policy.getPolicyVersion());
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(issue.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "资源异常多候选情景保存失败");

        List<ResourceCandidateProfile> profiles = List.of(
                new ResourceCandidateProfile("RECOVERY", "最早恢复", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new ResourceCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new ResourceCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<ResourceCandidateBuild> builds = new ArrayList<>();
        for (ResourceCandidateProfile profile : profiles) {
            builds.add(buildCandidate(header, profile, command, requestFingerprint, issue, originalTask, originals,
                    resourceType, resourceCode, evidence, policy, affectedCodes, originalByCode, mesBefore, freezeUntil, proposalStart,
                    impactEnd, originalFingerprint, user, now));
        }
        rankCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "资源异常多候选情景评估状态保存失败");
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.Scenario assessExecutionDeviation(
            PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command) {
        return assessExecutionDeviationInternal(command, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.Scenario assessMpsRemaining(
            PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command, int toleranceMinutes) {
        require(toleranceMinutes >= 0 && toleranceMinutes <= 1440, "工序开工偏差容差须为0至1440分钟");
        return assessExecutionDeviationInternal(command, toleranceMinutes);
    }

    /**
     * 旧Agent入口保留其既有指纹；新业务入口冻结明确公式，不改写历史永久请求算法。
     */
    private PlanResourceLocalReplanDTO.Scenario assessExecutionDeviationInternal(
            PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command, Integer mpsTolerance) {
        require(command != null && command.getSourceScenarioId() != null && command.getIssueId() != null
                        && StrUtil.isNotBlank(command.getRequestId())
                        && StrUtil.isNotBlank(command.getExpectedEffectFingerprint())
                        && StrUtil.isNotBlank(command.getReason()),
                "执行偏差重排必须指定来源情景、生产计划、请求号、效果指纹和原因");
        String requestId = command.getRequestId().trim();
        require(requestId.length() <= 48 && command.getReason().trim().length() <= 500,
                "执行偏差重排请求号不能超过48位且原因不能超过500字");
        require(command.getExpectedEffectFingerprint().matches("[a-f0-9]{64}"), "排程效果指纹格式无效");
        String requestFingerprint = hash(json(command));
        scenarioMapper.lock("EXECUTION_DEVIATION_SCENARIO_ASSESS:" + requestId);
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("EXECUTION_DEVIATION_LOCAL_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "执行偏差评估幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }

        PlnIssueEntity issue = lockIssue(command.getIssueId());
        PlanSchedulingAgentDTO.ScenarioQuery effectQuery = new PlanSchedulingAgentDTO.ScenarioQuery();
        effectQuery.setFactoryCode(issue.getFactoryCode());
        effectQuery.setScenarioId(command.getSourceScenarioId());
        PlnMpsRemainingEvidenceReader.Proof mpsProof = mpsTolerance == null ? null
                : mpsRemainingEvidenceReader.read(issue.getId(), command.getSourceScenarioId(), mpsTolerance);
        PlanSchedulingAgentDTO.EffectEvidence effect = mpsProof == null ? schedulingAgentService.effect(effectQuery) : mpsProof.effect();
        require(mpsProof == null || mpsProof.gaps().isEmpty(), "MPS剩余工序原始证据存在缺口，禁止生成候选");
        require(effect != null && Objects.equals(effect.getEffectFingerprint(),
                        command.getExpectedEffectFingerprint()),
                "排程效果已变化，请刷新偏差证据后重新评估");
        require(Objects.equals(effect.getScenarioId(), command.getSourceScenarioId())
                        && List.of("PUBLISHED", "PARTIAL").contains(effect.getScenarioStatus()),
                "来源排程情景未正式发布，不能执行偏差重排");

        Set<String> requestedCodes = Optional.ofNullable(command.getProcessCodes()).orElse(List.of()).stream()
                .filter(StrUtil::isNotBlank).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
        List<PlanSchedulingAgentDTO.DeviationDisposition> eligible = Optional.ofNullable(
                        effect.getDeviationDispositions()).orElse(List.of()).stream()
                .filter(item -> Objects.equals(item.getIssueId(), issue.getId()))
                .filter(item -> "LOCAL_REPLAN_CANDIDATE".equals(item.getDisposition()))
                .filter(item -> Boolean.TRUE.equals(item.getReplanEligible()))
                .filter(item -> requestedCodes.isEmpty() || requestedCodes.contains(item.getProcessCode()))
                .toList();
        require(!eligible.isEmpty(), "当前效果证据中没有属于该计划的可重排未开工偏差工序");
        Set<String> directCodes = eligible.stream().map(PlanSchedulingAgentDTO.DeviationDisposition::getProcessCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        require(requestedCodes.isEmpty() || directCodes.containsAll(requestedCodes),
                "指定工序包含已执行、现场阻断、数据缺口或非当前计划工序");

        caseMapper.lockKey("ISSUE:" + issue.getId());
        ensureNoLiveProposal(issue.getId());
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        Map<String, PlnSchedulingOperationEntity> originalByCode = uniqueByProcess(originals);
        if (mpsProof == null) {
            Set<String> eligibleKeys = eligibleEffectKeys(effect, issue.getId(), originals);
            boolean nodeProof = "FROZEN_NODE_V2".equals(effect.getIdentityContractVersion());
            require(nodeProof || command.getSourceRouteNodeCodes() == null,
                    "历史效果证据未冻结节点，不能指定节点或补造历史身份");
            List<String> selected = PlnFrozenRouteNodeIdentity.selection(originals,
                    command.getProcessCodes(), command.getSourceRouteNodeCodes());
            directCodes = selected.isEmpty() ? eligibleKeys : new LinkedHashSet<>(selected);
            require(!directCodes.isEmpty() && eligibleKeys.containsAll(directCodes),
                    "指定节点缺少未开工且可移动的完整效果证据");
        } else {
            Set<String> eligibleKeys = mpsProof.operations().stream().filter(row -> Boolean.TRUE.equals(row.getEligible()))
                    .map(row -> StrUtil.isNotBlank(row.getSourceRouteNodeCode()) ? row.getSourceRouteNodeCode() : row.getProcessCode())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> selected = PlnFrozenRouteNodeIdentity.selection(originals, command.getProcessCodes(), command.getSourceRouteNodeCodes());
            directCodes = selected.isEmpty() ? eligibleKeys : new LinkedHashSet<>(selected);
            require(eligibleKeys.containsAll(directCodes), "指定节点缺少完整剩余工序证明");
        }
        require(originalByCode.keySet().containsAll(directCodes), "正式排程已缺少效果证据中的偏差工序");
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        var currentStates = PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        if (mpsProof == null && "FROZEN_NODE_V2".equals(effect.getIdentityContractVersion())) {
            for (var disposition : eligible) {
                String key = effectNodeKey(disposition);
                if (!directCodes.contains(key)) continue;
                var currentState = currentStates.get(key);
                require(currentState != null && Objects.equals(disposition.getMesProduceTaskId(), String.valueOf(currentState.getProduceTaskId()))
                                && Objects.equals(disposition.getMesProduceTaskCode(), currentState.getProduceTaskCode()),
                        "MES真实工序任务在观察与评估间已变化，须重新采集效果");
            }
        }
        require(mpsProof == null || Objects.equals(mpsProof.mesFingerprint(), mesBefore.getExecutionFingerprint()),
                "MES事实在取证与评估之间变化，须重新预览，不能混用两次事实");
        Set<String> affectedCodes = downstreamClosure(originals, new ArrayList<>(directCodes));
        validateMesMovable(mesBefore, affectedCodes);

        PlnResourceReplanPolicyEntity policy = resolvePolicy(issue, "EXECUTION", null, LocalDateTime.now());
        require(policy != null, "当前工厂未配置生效的执行偏差局部重排策略");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime freezeUntil = now.plusMinutes(policy.getFreezeMinutes());
        LocalDateTime proposalStart = freezeUntil.plusSeconds(1);
        String originalFingerprint = scheduleFingerprint(originals);
        String sourceScenarioNo = StrUtil.blankToDefault(effect.getScenarioNo(),
                String.valueOf(command.getSourceScenarioId()));
        Map<String, Object> evidenceFacts = new LinkedHashMap<>();
        evidenceFacts.put("sourceScenarioId", command.getSourceScenarioId());
        evidenceFacts.put("sourceScenarioNo", sourceScenarioNo);
        evidenceFacts.put("effectFingerprint", effect.getEffectFingerprint());
        evidenceFacts.put("issueId", issue.getId());
        evidenceFacts.put("issueNo", issue.getIssueNo());
        evidenceFacts.put("directProcessCodes", directCodes.stream().map(code -> originalByCode.get(code).getProcessCode()).distinct().toList());
        if (PlnFrozenRouteNodeIdentity.nodeCodes(originals) != null)
            evidenceFacts.put("directSourceRouteNodeCodes", directCodes);
        evidenceFacts.put("dispositions", eligible);
        evidenceFacts.put("capturedTime", effect.getCapturedTime());
        if (mpsProof != null) {
            evidenceFacts.put("businessFormula", PlnMpsRemainingEvidenceReader.FORMULA);
            evidenceFacts.put("toleranceMinutes", mpsTolerance);
            evidenceFacts.put("fullExecutionSnapshot", mpsProof.snapshot());
        }
        String evidenceSnapshot = json(evidenceFacts);
        ResourceEvidence evidence = new ResourceEvidence("PLAN_MES_EFFECT", sourceScenarioNo,
                "MES执行偏差", evidenceSnapshot, effect.getEffectFingerprint(), new ArrayList<>(directCodes));
        String sourceFingerprint = hash(effect.getEffectFingerprint() + '|' + policy.getPolicyFingerprint() + '|'
                + originalFingerprint + '|' + mesBefore.getExecutionFingerprint());

        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("EDS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(requestId);
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("EXECUTION_DEVIATION_LOCAL_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(proposalStart.toLocalDate());
        header.setPlanNum(1);
        header.setSourceIssueIdsSnapshot(json(List.of(issue.getId())));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode(policy.getPolicyCode());
        header.setRuleVersion(policy.getPolicyVersion());
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(issue.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "执行偏差多候选情景保存失败");

        PlanResourceLocalReplanDTO.AssessCommand buildCommand = new PlanResourceLocalReplanDTO.AssessCommand();
        buildCommand.setIssueId(issue.getId());
        buildCommand.setRequestId(requestId);
        buildCommand.setResourceType("EXECUTION");
        buildCommand.setResourceCode(sourceScenarioNo);
        buildCommand.setEventStartTime(now);
        buildCommand.setExpectedRecoveryTime(proposalStart);
        buildCommand.setOpenEnded(false);
        buildCommand.setReason(command.getReason().trim());
        PlnSchedulingTaskEntity originalTask = taskMapper.selectById(issue.getLockTaskId());
        require(originalTask != null && "official".equalsIgnoreCase(originalTask.getMode()), "原正式排产任务不存在");
        List<ResourceCandidateProfile> profiles = List.of(
                new ResourceCandidateProfile("RECOVERY", "交期优先", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new ResourceCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new ResourceCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<ResourceCandidateBuild> builds = new ArrayList<>();
        for (ResourceCandidateProfile profile : profiles) {
            builds.add(buildCandidate(header, profile, buildCommand, requestFingerprint, issue, originalTask,
                    originals, "EXECUTION", sourceScenarioNo, evidence, policy, affectedCodes, originalByCode,
                    mesBefore, freezeUntil, proposalStart, proposalStart, originalFingerprint, currentUser(), now));
        }
        rankCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "执行偏差多候选情景评估状态保存失败");
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.Scenario assessRecovery(PlanResourceLocalReplanDTO.RecoveryOptimizeCommand command) {
        require(command != null && command.getRecoveryReviewCaseId() != null
                        && StrUtil.isNotBlank(command.getRequestId()) && StrUtil.isNotBlank(command.getReason()),
                "恢复优化须指定来源复核案件、幂等请求号和业务依据");
        String requestId = command.getRequestId().trim();
        require(requestId.length() <= 48 && command.getReason().trim().length() <= 700,
                "恢复优化请求号或业务依据过长");
        String requestFingerprint = hash(json(command));
        scenarioMapper.lock("RESOURCE_SCENARIO_ASSESS:" + requestId);
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("RESOURCE_LOCAL_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "恢复优化幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }
        PlnResourceReplanCaseEntity source = requireCase(command.getRecoveryReviewCaseId(), true);
        requireRecoveryReviewFactory(source.getFactoryCode());
        require("PUBLISHED".equals(source.getStatus()) && "REPLAN_REQUIRED".equals(source.getRecoveryReviewStatus())
                && "DEVICE".equals(source.getResourceType()), "仅已发布且要求重新排产的设备恢复案件可生成优化候选");
        PlnIssueEntity issue = lockIssue(source.getIssueId());
        caseMapper.lockKey("ISSUE:" + issue.getId());
        ensureNoLiveProposal(issue.getId());
        PlanResourceLocalReplanDTO.RecoveryReviewPreview facts = recoveryReviewFacts(source);
        require(facts.getGaps().isEmpty() && Objects.equals(facts.getCurrentTaskId(), source.getOriginalTaskId()),
                "恢复回执、设备资格、TPM、正式排程或MES事实不满足恢复优化条件");
        var proofResponse = deviceRecoveryService.evidence(source.getRecoveryReceiptId(), source.getFactoryCode());
        require(proofResponse != null && proofResponse.isOk() && proofResponse.getData() != null
                && proofResponse.getData().getRecoveryTime() != null, "MDM实际恢复时刻不可核验");
        LocalDateTime actualRecovery = proofResponse.getData().getRecoveryTime();
        require(actualRecovery.isAfter(source.getEventStartTime()), "恢复回执时刻不得早于原资源故障开始时刻");
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        PlnResourceReplanPolicyEntity policy = resolvePolicy(issue, "DEVICE", source.getResourceCode(), LocalDateTime.now());
        require(policy != null, "当前工厂和设备没有生效的恢复优化策略");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime freezeUntil = now.plusMinutes(policy.getFreezeMinutes());
        Map<String, PlnSchedulingOperationEntity> originalByCode = uniqueByProcess(originals);
        Set<String> affectedCodes = caseLines(source.getId()).stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(line -> PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), originalByCode))
                .filter(code -> originalByCode.get(code).getScheduledStartTime() != null
                        && originalByCode.get(code).getScheduledStartTime().isAfter(freezeUntil))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(!affectedCodes.isEmpty(), "恢复影响工序均已进入冻结区或不在当前正式排程中，不能生成虚假优化");
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        validateMesMovable(mesBefore, affectedCodes);
        List<String> directCodes = caseLines(source.getId()).stream()
                .filter(line -> Boolean.TRUE.equals(line.getDirectAffected()))
                .map(line -> PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), originalByCode)).filter(affectedCodes::contains)
                .distinct().toList();
        require(!directCodes.isEmpty(), "原直接影响工序均已冻结，不能猜测新的直接锚点");
        ResourceEvidence evidence = recoveryEvidence(source, facts, directCodes);
        PlnSchedulingTaskEntity originalTask = taskMapper.selectById(issue.getLockTaskId());
        require(originalTask != null && "official".equalsIgnoreCase(originalTask.getMode())
                && "SUCCESS".equals(originalTask.getTaskStatus()), "当前正式成功排产任务不存在");
        LocalDateTime proposalStart = freezeUntil.plusSeconds(1);
        String originalFingerprint = scheduleFingerprint(originals);
        String sourceFingerprint = hash(evidence.fingerprint + '|' + policy.getPolicyFingerprint() + '|'
                + originalFingerprint + '|' + mesBefore.getExecutionFingerprint());
        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("RRS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(requestId);
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("RESOURCE_LOCAL_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(proposalStart.toLocalDate());
        header.setPlanNum(1);
        header.setSourceIssueIdsSnapshot(json(List.of(issue.getId())));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode(policy.getPolicyCode());
        header.setRuleVersion(policy.getPolicyVersion());
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(issue.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "设备恢复优化多候选情景保存失败");
        PlanResourceLocalReplanDTO.AssessCommand candidateCommand = new PlanResourceLocalReplanDTO.AssessCommand();
        candidateCommand.setRequestId(requestId);
        candidateCommand.setReason(command.getReason());
        candidateCommand.setIssueId(issue.getId());
        candidateCommand.setResourceType("DEVICE");
        candidateCommand.setResourceCode(source.getResourceCode());
        candidateCommand.setEventStartTime(source.getEventStartTime());
        candidateCommand.setExpectedRecoveryTime(actualRecovery);
        candidateCommand.setOpenEnded(false);
        List<ResourceCandidateProfile> profiles = List.of(
                new ResourceCandidateProfile("RECOVERY", "最早完工", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new ResourceCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new ResourceCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<ResourceCandidateBuild> builds = new ArrayList<>();
        MomUser user = currentUser();
        for (ResourceCandidateProfile profile : profiles) {
            builds.add(buildCandidate(header, profile, candidateCommand, requestFingerprint, issue, originalTask,
                    originals, "DEVICE", source.getResourceCode(), evidence, policy, affectedCodes, originalByCode,
                    mesBefore, freezeUntil, proposalStart, actualRecovery, originalFingerprint, user, now,
                    source.getId(), source.getRecoveryReceiptId()));
        }
        rankCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "设备恢复优化多候选评估状态保存失败");
        return scenario(header.getId());
    }

    private ResourceEvidence recoveryEvidence(PlnResourceReplanCaseEntity source,
                                              PlanResourceLocalReplanDTO.RecoveryReviewPreview facts,
                                              List<String> directCodes) {
        require("REPLAN_REQUIRED".equals(source.getRecoveryReviewStatus())
                        && facts.getGaps().isEmpty() && Objects.equals(facts.getRecoveryReceiptId(), source.getRecoveryReceiptId()),
                "恢复优化来源复核已变化");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceCaseId", source.getId());
        snapshot.put("recoveryEventId", source.getRecoveryReviewEventId());
        snapshot.put("recoveryReceiptId", source.getRecoveryReceiptId());
        snapshot.put("reviewEvidenceFingerprint", facts.getEvidenceFingerprint());
        snapshot.put("sourceResourceEvidenceFingerprint", source.getResourceEvidenceFingerprint());
        snapshot.put("directCodes", directCodes);
        String serialized = json(snapshot);
        return new ResourceEvidence("MDM_RECOVERY", String.valueOf(source.getId()),
                source.getResourceName(), serialized, hash(serialized), directCodes);
    }

    private ResourceEvidence recaptureRecoveryEvidence(PlnResourceReplanCaseEntity candidate) {
        require("MDM_RECOVERY".equals(candidate.getEvidenceSourceType())
                        && candidate.getRecoverySourceCaseId() != null && candidate.getRecoverySourceReceiptId() != null,
                "候选缺少设备恢复优化来源案件和回执");
        PlnResourceReplanCaseEntity source = requireCase(candidate.getRecoverySourceCaseId(), false);
        require(Objects.equals(source.getTenantId(), candidate.getTenantId())
                        && Objects.equals(source.getFactoryCode(), candidate.getFactoryCode())
                        && Objects.equals(source.getIssueId(), candidate.getIssueId())
                        && Objects.equals(source.getResourceCode(), candidate.getResourceCode())
                        && Objects.equals(source.getRecoveryReceiptId(), candidate.getRecoverySourceReceiptId()),
                "设备恢复优化来源案件、计划、设备或回执已变化");
        List<String> directCodes;
        try {
            JsonNode codes = objectMapper.readTree(candidate.getResourceEvidenceSnapshot()).path("directCodes");
            require(codes.isArray(), "恢复优化直接锚定工序快照缺失");
            directCodes = new ArrayList<>();
            codes.forEach(code -> directCodes.add(code.asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("恢复优化来源快照不可解析");
        }
        require(!directCodes.isEmpty(), "恢复优化直接锚定工序不能为空");
        return recoveryEvidence(source, recoveryReviewFacts(source), directCodes);
    }

    /**
     * 重新读取来源排程情景的当前效果证据。效果指纹、计划归属和直接工序必须全部保持不变，
     * 否则旧候选进入STALE，避免把已经开工或已被现场阻断的工序继续下发到MES。
     */
    private ResourceEvidence recaptureExecutionEvidence(PlnResourceReplanCaseEntity candidate) {
        require("EXECUTION".equals(candidate.getResourceType())
                        && "PLAN_MES_EFFECT".equals(candidate.getEvidenceSourceType())
                        && StrUtil.isNotBlank(candidate.getResourceEvidenceSnapshot()),
                "执行偏差候选缺少来源效果证据");
        long sourceScenarioId;
        Integer mpsTolerance = null;
        List<String> frozenDirectCodes = new ArrayList<>();
        try {
            JsonNode snapshot = objectMapper.readTree(candidate.getResourceEvidenceSnapshot());
            if (PlnMpsRemainingEvidenceReader.FORMULA.equals(snapshot.path("businessFormula").asText())) {
                require(snapshot.has("toleranceMinutes") && snapshot.path("toleranceMinutes").canConvertToInt(),
                        "MPS剩余工序冻结容差缺失");
                mpsTolerance = snapshot.path("toleranceMinutes").asInt();
            }
            sourceScenarioId = snapshot.path("sourceScenarioId").asLong(0L);
            JsonNode codes = snapshot.has("directSourceRouteNodeCodes") ? snapshot.path("directSourceRouteNodeCodes") : snapshot.path("directProcessCodes");
            require(sourceScenarioId > 0 && codes.isArray(), "执行偏差来源情景或直接工序快照缺失");
            codes.forEach(code -> frozenDirectCodes.add(code.asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("执行偏差来源效果快照不可解析");
        }
        PlanSchedulingAgentDTO.ScenarioQuery query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(candidate.getFactoryCode());
        query.setScenarioId(sourceScenarioId);
        var remainingProof = mpsTolerance == null ? null : mpsRemainingEvidenceReader.read(candidate.getIssueId(), sourceScenarioId, mpsTolerance);
        PlanSchedulingAgentDTO.EffectEvidence effect = remainingProof == null ? schedulingAgentService.effect(query) : remainingProof.effect();
        List<String> currentDirectCodes;
        if (remainingProof != null) {
            currentDirectCodes = remainingProof.operations().stream().filter(row -> Boolean.TRUE.equals(row.getEligible()))
                    .map(row -> StrUtil.isNotBlank(row.getSourceRouteNodeCode()) ? row.getSourceRouteNodeCode() : row.getProcessCode())
                    .filter(frozenDirectCodes::contains).distinct().sorted().toList();
        } else {
            var originalFrozen = frozenOriginalOperations(candidate);
            currentDirectCodes = eligibleEffectKeys(effect, candidate.getIssueId(), originalFrozen).stream()
                    .filter(frozenDirectCodes::contains).sorted().toList();
        }
        if (currentDirectCodes.size() != new HashSet<>(frozenDirectCodes).size()) {
            return new ResourceEvidence("PLAN_MES_EFFECT", effect.getScenarioNo(), "MES执行偏差",
                    json(effect), effect.getEffectFingerprint(), List.of());
        }
        return new ResourceEvidence("PLAN_MES_EFFECT", effect.getScenarioNo(), "MES执行偏差",
                json(effect), effect.getEffectFingerprint(), currentDirectCodes);
    }

    /**
     * 效果清单属于观察时的冻结身份。新协议逐节点核对，旧协议只允许唯一主工序桥接；
     * Agent重点清单被截断或存在缺证时不能据其生成完整可执行方案。
     */
    private Set<String> eligibleEffectKeys(PlanSchedulingAgentDTO.EffectEvidence effect, Long issueId,
                                          List<PlnSchedulingOperationEntity> originals) {
        require(Boolean.TRUE.equals(effect.getAvailable()) && !Boolean.TRUE.equals(effect.getDeviationTruncated())
                        && (effect.getDataGaps() == null || effect.getDataGaps().isEmpty())
                        && effect.getDetail() != null && effect.getDetail().getReview() != null
                        && Set.of("WAITING_EXECUTION", "IN_PROGRESS", "COMPLETED")
                        .contains(effect.getDetail().getReview().getReviewStatus()),
                "效果证据缺失、存在缺口或重点清单已截断，须完整采集后再评估");
        var eligible = Optional.ofNullable(effect.getDeviationDispositions()).orElse(List.of()).stream()
                .filter(row -> Objects.equals(row.getIssueId(), issueId))
                .filter(row -> "LOCAL_REPLAN_CANDIDATE".equals(row.getDisposition()) && Boolean.TRUE.equals(row.getReplanEligible()))
                .toList();
        if (effect.getIdentityContractVersion() == null) {
            return new LinkedHashSet<>(PlnFrozenRouteNodeIdentity.selection(originals,
                    eligible.stream().map(PlanSchedulingAgentDTO.DeviationDisposition::getProcessCode).toList(), null));
        }
        require("FROZEN_NODE_V2".equals(effect.getIdentityContractVersion()), "效果身份协议不受支持");
        requireEffectBaseline(effect, issueId, originals);
        var originalIndex = PlnFrozenRouteNodeIdentity.index(originals);
        Set<String> result = new LinkedHashSet<>();
        for (var row : eligible) {
            String key = effectNodeKey(row);
            var original = originalIndex.get(key);
            require(original != null && Objects.equals(original.getIssueId(), row.getIssueId())
                            && Objects.equals(original.getIssueNo(), row.getIssueNo())
                            && Objects.equals(original.getSourceRouteNodeCode(), row.getSourceRouteNodeCode())
                            && Objects.equals(original.getProcessCode(), row.getProcessCode())
                            && Objects.equals(original.getOperationSeq(), row.getOperationSeq())
                            && row.getSchedulingOperationId() != null && row.getSchedulingOperationId().matches("[1-9][0-9]{0,18}")
                            && row.getMesProduceTaskId() != null && row.getMesProduceTaskId().matches("[1-9][0-9]{0,18}")
                            && StrUtil.isNotBlank(row.getMesProduceTaskCode()) && result.add(key),
                    "效果节点、原工序或MES任务身份缺失、重复或与当前正式范围不同");
        }
        return result;
    }

    /**
     * 当前正式任务必须属于原发布谱系，且整张根计划仍对应观察冻结的时刻、资源和数量。
     * 局部重排的proposal工序与原正式工序主键合法不同，使用原领域发布记录证明关系。
     */
    private void requireEffectBaseline(PlanSchedulingAgentDTO.EffectEvidence effect, Long issueId,
                                       List<PlnSchedulingOperationEntity> originals) {
        var review = effect.getDetail().getReview();
        Set<Long> formalTasks = originals.stream().map(PlnSchedulingOperationEntity::getTaskId).collect(Collectors.toSet());
        require(formalTasks.size() == 1 && !formalTasks.contains(null) && review.getSelectedTaskId() != null
                        && Objects.equals(review.getScenarioId(), effect.getScenarioId()),
                "效果来源与当前正式根计划任务不一致");
        Long formalTask = formalTasks.iterator().next();
        if (!Objects.equals(formalTask, review.getSelectedTaskId())) {
            require(publishedEffectSourceCount(effect, issueId, formalTask) == 1,
                    "效果来源未对应当前正式任务的原局部发布，不能用旧观察驱动新任务重排");
        }
        try {
            JsonNode snapshot = objectMapper.readTree(review.getPlannedScopeSnapshot());
            require("FROZEN_NODE_V2".equals(snapshot.path("identityContractVersion").asText())
                            && snapshot.path("operations").isArray(), "效果原冻结基线缺少节点协议");
            Map<String, JsonNode> frozen = new LinkedHashMap<>();
            for (JsonNode row : snapshot.path("operations")) {
                if (!Objects.equals(row.path("issueId").asText(), String.valueOf(issueId))) continue;
                String node = nullableText(row, "sourceRouteNodeCode");
                String key = StrUtil.isNotBlank(node) ? node : nullableText(row, "processCode");
                require(key != null && frozen.putIfAbsent(key, row) == null, "效果原冻结节点重复或缺失");
            }
            var current = PlnFrozenRouteNodeIdentity.index(originals);
            require(frozen.keySet().equals(current.keySet()), "效果原冻结范围与当前正式根计划不同");
            for (var operation : originals) {
                JsonNode row = frozen.get(PlnFrozenRouteNodeIdentity.key(operation));
                require(Objects.equals(operation.getIssueNo(), nullableText(row, "issueNo"))
                                && Objects.equals(operation.getSourceRouteNodeCode(), nullableText(row, "sourceRouteNodeCode"))
                                && Objects.equals(operation.getProcessCode(), nullableText(row, "processCode"))
                                && row.path("operationSeq").canConvertToInt()
                                && Objects.equals(operation.getOperationSeq(), row.path("operationSeq").asInt())
                                && Objects.equals(operation.getScheduledStartTime(), LocalDateTime.parse(row.path("scheduledStartTimeExact").asText()))
                                && Objects.equals(operation.getScheduledEndTime(), LocalDateTime.parse(row.path("scheduledEndTimeExact").asText()))
                                && Objects.equals(operation.getResourceType(), nullableText(row, "resourceType"))
                                && Objects.equals(operation.getResourceCode(), nullableText(row, "resourceCode"))
                                && Objects.equals(operation.getDeviceCode(), nullableText(row, "deviceCode"))
                                && Objects.equals(operation.getEmployeeCode(), nullableText(row, "employeeCode"))
                                && Objects.equals(operation.getToolingCodes(), nullableText(row, "toolingCodes"))
                                && operation.getPlannedQuantity() != null && row.path("plannedQuantity").isNumber()
                                && operation.getPlannedQuantity().compareTo(row.path("plannedQuantity").decimalValue()) == 0,
                        "效果冻结的节点、时刻、资源或数量与当前正式排程不同，须重新核对来源并采集");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.time.format.DateTimeParseException ex) {
            throw new CheckedException("效果原冻结基线无法完整读取，不能猜测当前正式范围");
        }
    }

    /** 只认可原领域PUBLISHED记录，不按名称、时间先后或候选ID相近推断来源。 */
    private long publishedEffectSourceCount(PlanSchedulingAgentDTO.EffectEvidence effect, Long issueId, Long taskId) {
        Long selectedTask = effect.getDetail().getReview().getSelectedTaskId();
        Long scenarioId = effect.getScenarioId();
        String factory = FactoryContextHolder.getFactoryCode();
        Long tenant = TenantContextHolder.getTenantId();
        long count = caseMapper.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getTenantId, tenant).eq(PlnResourceReplanCaseEntity::getFactoryCode, factory)
                .eq(PlnResourceReplanCaseEntity::getScenarioId, scenarioId).eq(PlnResourceReplanCaseEntity::getProposalTaskId, selectedTask)
                .eq(PlnResourceReplanCaseEntity::getOriginalTaskId, taskId).eq(PlnResourceReplanCaseEntity::getIssueId, issueId)
                .eq(PlnResourceReplanCaseEntity::getStatus, "PUBLISHED"));
        count += shortageCaseMapper.selectCount(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getTenantId, tenant).eq(PlnShortageReplanCaseEntity::getFactoryCode, factory)
                .eq(PlnShortageReplanCaseEntity::getScenarioId, scenarioId).eq(PlnShortageReplanCaseEntity::getProposalTaskId, selectedTask)
                .eq(PlnShortageReplanCaseEntity::getOriginalTaskId, taskId).eq(PlnShortageReplanCaseEntity::getIssueId, issueId)
                .eq(PlnShortageReplanCaseEntity::getStatus, "PUBLISHED"));
        count += qualityCaseMapper.selectCount(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getTenantId, tenant).eq(PlnQualityReplanCaseEntity::getFactoryCode, factory)
                .eq(PlnQualityReplanCaseEntity::getScenarioId, scenarioId).eq(PlnQualityReplanCaseEntity::getProposalTaskId, selectedTask)
                .eq(PlnQualityReplanCaseEntity::getOriginalTaskId, taskId).eq(PlnQualityReplanCaseEntity::getIssueId, issueId)
                .eq(PlnQualityReplanCaseEntity::getStatus, "PUBLISHED"));
        var insertionCases = insertionCaseMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getTenantId, tenant).eq(PlnInsertionReplanCaseEntity::getFactoryCode, factory)
                .eq(PlnInsertionReplanCaseEntity::getScenarioId, scenarioId).eq(PlnInsertionReplanCaseEntity::getProposalTaskId, selectedTask)
                .eq(PlnInsertionReplanCaseEntity::getStatus, "PUBLISHED"));
        for (var insertionCase : insertionCases) {
            var query = new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                    .eq(PlnInsertionReplanIssueEntity::getTenantId, tenant).eq(PlnInsertionReplanIssueEntity::getFactoryCode, factory)
                    .eq(PlnInsertionReplanIssueEntity::getCaseId, insertionCase.getId())
                    .eq(PlnInsertionReplanIssueEntity::getIssueId, issueId);
            if (Objects.equals(insertionCase.getInsertionIssueId(), issueId)) {
                // 新插入根计划没有原任务，发布时另建正式任务，不能按被挤占计划的谱系核验。
                if (!Objects.equals(insertionCase.getPublishedTaskId(), taskId)) continue;
                query.eq(PlnInsertionReplanIssueEntity::getInsertionIssue, true)
                        .isNull(PlnInsertionReplanIssueEntity::getOriginalTaskId);
            } else {
                query.eq(PlnInsertionReplanIssueEntity::getInsertionIssue, false)
                        .eq(PlnInsertionReplanIssueEntity::getOriginalTaskId, taskId);
            }
            count += insertionIssueMapper.selectCount(query);
        }
        return count;
    }

    private String nullableText(JsonNode value, String field) {
        return value.path(field).isNull() || value.path(field).isMissingNode() ? null : value.path(field).asText();
    }

    private String effectNodeKey(PlanSchedulingAgentDTO.DeviationDisposition disposition) {
        return StrUtil.isNotBlank(disposition.getSourceRouteNodeCode())
                ? disposition.getSourceRouteNodeCode() : disposition.getProcessCode();
    }

    private ResourceCandidateBuild buildCandidate(PlnSchedulingScenarioEntity header,
                                                  ResourceCandidateProfile profile,
                                                  PlanResourceLocalReplanDTO.AssessCommand command,
                                                  String requestFingerprint,
                                                  PlnIssueEntity issue,
                                                  PlnSchedulingTaskEntity originalTask,
                                                  List<PlnSchedulingOperationEntity> originals,
                                                  String resourceType,
                                                  String resourceCode,
                                                  ResourceEvidence evidence,
                                                  PlnResourceReplanPolicyEntity policy,
                                                  Set<String> affectedCodes,
                                                  Map<String, PlnSchedulingOperationEntity> originalByCode,
                                                  MesApsLocalScheduleDTO.StateBundle mesBefore,
                                                  LocalDateTime freezeUntil,
                                                  LocalDateTime proposalStart,
                                                  LocalDateTime impactEnd,
                                                  String originalFingerprint,
                                                  MomUser user,
                                                  LocalDateTime now) {
        return buildCandidate(header, profile, command, requestFingerprint, issue, originalTask, originals,
                resourceType, resourceCode, evidence, policy, affectedCodes, originalByCode, mesBefore, freezeUntil,
                proposalStart, impactEnd, originalFingerprint, user, now, null, null);
    }

    private ResourceCandidateBuild buildCandidate(PlnSchedulingScenarioEntity header,
                                                  ResourceCandidateProfile profile,
                                                  PlanResourceLocalReplanDTO.AssessCommand command,
                                                  String requestFingerprint,
                                                  PlnIssueEntity issue,
                                                  PlnSchedulingTaskEntity originalTask,
                                                  List<PlnSchedulingOperationEntity> originals,
                                                  String resourceType,
                                                  String resourceCode,
                                                  ResourceEvidence evidence,
                                                  PlnResourceReplanPolicyEntity policy,
                                                  Set<String> affectedCodes,
                                                  Map<String, PlnSchedulingOperationEntity> originalByCode,
                                                  MesApsLocalScheduleDTO.StateBundle mesBefore,
                                                  LocalDateTime freezeUntil,
                                                  LocalDateTime proposalStart,
                                                  LocalDateTime impactEnd,
                                                  String originalFingerprint,
                                                  MomUser user,
                                                  LocalDateTime now,
                                                  Long recoverySourceCaseId,
                                                  Long recoverySourceReceiptId) {
        PlnSchedulingTaskEntity proposalTask = new PlnSchedulingTaskEntity();
        BeanUtils.copyProperties(originalTask, proposalTask, "id", "createTime", "updateTime", "createBy", "updateBy");
        proposalTask.setId(null);
        boolean executionDeviation = "EXECUTION".equals(resourceType);
        proposalTask.setTaskNo((executionDeviation ? "EDR-" : "RRP-") + IdUtil.getSnowflakeNextIdStr());
        proposalTask.setTriggerType(executionDeviation
                ? "EXECUTION_DEVIATION_LOCAL_REPLAN" : "RESOURCE_LOCAL_REPLAN");
        proposalTask.setMode("simulate");
        proposalTask.setTaskStatus("EVALUATING");
        proposalTask.setScheduleDate(proposalStart.toLocalDate());
        proposalTask.setPlanCount(1);
        proposalTask.setScenarioId(header.getId());
        proposalTask.setCandidateCode(profile.code());
        proposalTask.setCandidateName(profile.name());
        proposalTask.setSequenceStrategy(profile.sequenceStrategy());
        proposalTask.setCrossPriorityWindowDays(0);
        proposalTask.setObjectiveWeightsSnapshot(json(Map.of("localReplanStrategy", profile.solverStrategy(),
                "objective", profile.name(), "hardConstraintsRelaxed", false)));
        proposalTask.setSelectedCandidate(false);
        proposalTask.setRemark((executionDeviation ? "MES执行偏差情景" : "资源异常情景")
                + header.getScenarioNo() + "候选[" + profile.name() + "]，计划="
                + issue.getIssueNo() + "，" + resourceType + '=' + resourceCode);
        proposalTask.setFactoryCode(issue.getFactoryCode());
        require(taskMapper.insert(proposalTask) == 1, "资源异常局部重排候选任务保存失败");

        PlnIssueEntity previewIssue = new PlnIssueEntity();
        BeanUtils.copyProperties(issue, previewIssue);
        operationSchedulingService.previewLocalReplan(proposalTask.getId(), issue.getLockTaskId(), previewIssue,
                proposalStart, profile.solverStrategy());
        List<PlnSchedulingOperationEntity> candidateRows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, proposalTask.getId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        if (executionDeviation && PlnMpsRemainingEvidenceReader.FORMULA.equals(
                JSONUtil.parseObj(evidence.snapshot).getStr("businessFormula"))) {
            // 新业务候选先统一到现有MES契约的秒级精度，再冻结指纹；不修改已批准的旧快照。
            candidateRows.forEach(this::normalizeMpsCandidateTime);
            candidateRows = schedulingOperationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                    .eq(PlnSchedulingOperationEntity::getTaskId, proposalTask.getId())
                    .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                    .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        }
        PlnFrozenRouteNodeIdentity.replacementSet(originals, candidateRows);
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidateRows);
        require(candidateByCode.keySet().containsAll(affectedCodes), "候选排程未覆盖全部受资源异常影响工序");
        List<PlnSchedulingOperationEntity> moved = affectedCodes.stream().map(candidateByCode::get)
                .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq)).toList();
        Map<String, PlnSchedulingOperationEntity> movedByCode = uniqueByProcess(moved);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, movedByCode),
                issue.getLockTaskId(), issue.getId());
        if (executionDeviation) {
            require(moved.stream().allMatch(item -> item.getScheduledStartTime() != null
                            && item.getScheduledStartTime().isAfter(freezeUntil)),
                    "执行偏差候选仍落在当前冻结区，不能形成可发布修复方案");
        } else {
            ResourceEvidence candidateEvidence = captureEvidence(resourceType, resourceCode, moved,
                    command.getEventStartTime(), Boolean.TRUE.equals(command.getOpenEnded())
                            ? impactEnd(moved, command.getEventStartTime()) : impactEnd,
                    Boolean.TRUE.equals(command.getOpenEnded()));
            require(candidateEvidence.directCodes.isEmpty(), "候选排程仍占用当前不可用资源，未形成可发布修复方案");
        }

        LocalDateTime originalCompletion = completion(originals);
        LocalDateTime proposedCompletion = completion(effectiveOperations(originals, movedByCode));
        long delay = Math.max(0, Duration.between(originalCompletion, proposedCompletion).toMinutes());
        long maxShift = maximumShift(originals, candidateByCode, affectedCodes);
        long totalShift = totalShift(originals, candidateByCode, affectedCodes);
        int resourceChanges = (int) affectedCodes.stream()
                .filter(code -> resourceChanged(originalByCode.get(code), candidateByCode.get(code))).count();
        long changeoverMinutes = moved.stream().map(PlnSchedulingOperationEntity::getChangeoverMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        boolean recoveryOptimization = recoverySourceCaseId != null;
        long savedMinutes = Math.max(0, Duration.between(proposedCompletion, originalCompletion).toMinutes());
        int restoredDeviceOperations = recoveryOptimization ? (int) moved.stream()
                .filter(item -> Objects.equals(item.getDeviceCode(), resourceCode)
                        && !Objects.equals(originalByCode.get(PlnFrozenRouteNodeIdentity.key(item)).getDeviceCode(), resourceCode))
                .count() : 0;
        boolean realImprovement = !recoveryOptimization || savedMinutes > 0
                || (restoredDeviceOperations > 0 && !proposedCompletion.isAfter(originalCompletion));
        boolean policyFeasible = delay <= policy.getMaxDelayMinutes() && realImprovement;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("completionDelayMinutes", delay);
        metrics.put("maximumShiftMinutes", maxShift);
        metrics.put("totalShiftMinutes", totalShift);
        metrics.put("resourceChangeCount", resourceChanges);
        metrics.put("changeoverMinutes", changeoverMinutes);
        metrics.put("deliveryRisk", deliveryRisk(issue, proposedCompletion));
        metrics.put("movedOperationCount", moved.size());
        metrics.put("maximumAllowedDelayMinutes", policy.getMaxDelayMinutes());
        metrics.put("policyFeasible", policyFeasible);
        metrics.put("resourceEvidenceCleared", true);
        metrics.put("executionDeviationReplan", executionDeviation);
        if (recoveryOptimization) {
            metrics.put("recoverySavedMinutes", savedMinutes);
            metrics.put("restoredDeviceOperationCount", restoredDeviceOperations);
            metrics.put("realImprovement", realImprovement);
            metrics.put("recoverySourceCaseId", recoverySourceCaseId);
            metrics.put("recoverySourceReceiptId", recoverySourceReceiptId);
        }
        proposalTask.setCandidateMetricsSnapshot(json(metrics));
        proposalTask.setCandidateFeasible(policyFeasible);
        BigDecimal baseScore = candidateBaseScore(delay, totalShift, resourceChanges, changeoverMinutes);
        BigDecimal recoveryBonus = recoveryOptimization
                ? BigDecimal.valueOf(Math.min(25.0, savedMinutes / 60.0 * 4.0
                + restoredDeviceOperations * 2.0)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        proposalTask.setCandidateScore(policyFeasible
                ? baseScore.add(recoveryBonus).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2));
        proposalTask.setCandidateRank(1);
        proposalTask.setEvaluatedTime(LocalDateTime.now());
        proposalTask.setTaskStatus("SUCCESS");
        require(taskMapper.updateById(proposalTask) == 1, "资源异常候选指标保存失败");

        String proposalFingerprint = hash(evidence.fingerprint + '|' + policy.getPolicyFingerprint() + '|'
                + originalFingerprint + '|' + mesBefore.getExecutionFingerprint() + '|' + scheduleFingerprint(moved));
        PlnResourceReplanCaseEntity entity = new PlnResourceReplanCaseEntity()
                .setCaseNo((executionDeviation ? "EDC-" : "RRC-") + IdUtil.getSnowflakeNextIdStr())
                .setAssessRequestId(command.getRequestId().trim() + ':' + profile.code())
                .setAssessRequestFingerprint(hash(requestFingerprint + '|' + profile.code()))
                .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                .setProductCode(issue.getProductCode()).setProductName(issue.getProductName())
                .setResourceType(resourceType).setResourceCode(resourceCode).setResourceName(evidence.resourceName)
                .setEvidenceSourceType(evidence.sourceType).setEvidenceSourceNo(evidence.sourceNo)
                .setRecoverySourceCaseId(recoverySourceCaseId)
                .setRecoverySourceReceiptId(recoverySourceReceiptId)
                .setEventStartTime(command.getEventStartTime()).setExpectedRecoveryTime(command.getExpectedRecoveryTime())
                .setOpenEnded(command.getOpenEnded()).setResourceEvidenceSnapshot(evidence.snapshot)
                .setResourceEvidenceFingerprint(evidence.fingerprint).setPolicyId(policy.getId())
                .setPolicyCode(policy.getPolicyCode()).setPolicyVersion(policy.getPolicyVersion())
                .setPolicyFingerprint(policy.getPolicyFingerprint()).setOriginalTaskId(issue.getLockTaskId())
                .setProposalTaskId(proposalTask.getId()).setScenarioId(header.getId())
                .setAffectedProcessCodes(affectedCodes.stream().map(code -> uniqueByProcess(originals).get(code).getProcessCode()).distinct().collect(Collectors.joining(","))).setFreezeUntil(freezeUntil)
                .setMaxDelayMinutes(policy.getMaxDelayMinutes()).setDirectAffectedCount(evidence.directCodes.size())
                .setMovedOperationCount(moved.size()).setReassignedOperationCount(resourceChanges)
                .setMaximumShiftMinutes(maxShift).setOriginalCompletionTime(originalCompletion)
                .setProposedCompletionTime(proposedCompletion).setDeliveryRisk(deliveryRisk(issue, proposedCompletion))
                .setMesExecutionFingerprint(mesBefore.getExecutionFingerprint())
                .setOriginalScheduleFingerprint(originalFingerprint).setOriginalScheduleSnapshot(json(originals))
                .setProposedScheduleSnapshot(json(moved)).setProposalFingerprint(proposalFingerprint)
                .setReason(command.getReason().trim()).setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setProposedTime(now).setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS))
                .setStatus("PROPOSED").setFactoryCode(issue.getFactoryCode());
        require(caseMapper.insert(entity) == 1, "资源异常局部重排案件保存失败");
        saveLines(entity, originals, candidateByCode, evidence.directCodes, affectedCodes, freezeUntil, mesBefore);
        saveProposalDetail(proposalTask, previewIssue, moved);
        audit(entity, entity.getAssessRequestId(), "ASSESS_PROPOSE", null, "PROPOSED",
                "候选[" + profile.name() + "]：" + evidence.sourceType + "证明" + resourceType + '[' + resourceCode
                        + "]来源，直接锚定" + evidence.directCodes.size() + "道工序，联动重排" + moved.size()
                        + "道，延期" + delay + "分钟，提前" + savedMinutes + "分钟，恢复设备工序"
                        + restoredDeviceOperations + "道" + (policyFeasible ? "" : "，不满足策略或无实际改善，不可选择"));
        return new ResourceCandidateBuild(entity, proposalTask, delay, totalShift, resourceChanges, changeoverMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.Scenario select(PlanResourceLocalReplanDTO.SelectCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getCandidateCaseId() != null
                        && StrUtil.isNotBlank(command.getExpectedSourceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "资源异常候选选择参数不完整");
        PlnResourceReplanCaseEntity selectedCase = requireCase(command.getCandidateCaseId(), false);
        requireFactory(selectedCase.getFactoryCode());
        require(selectedCase.getScenarioId() != null, "历史单方案不需要候选选择");
        scenarioMapper.lock("RESOURCE_SCENARIO_SELECT:" + selectedCase.getScenarioId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getId, selectedCase.getScenarioId()).last("FOR UPDATE"));
        require(header != null && LOCAL_REPLAN_TRIGGER_TYPES.contains(header.getTriggerType()),
                "资源或执行偏差多候选情景不存在");
        String selectionFingerprint = hash(command.getRequestId().trim() + '|' + command.getCandidateCaseId()
                + '|' + command.getExpectedSourceFingerprint() + '|' + command.getReason().trim());
        if (Set.of("SELECTED", "PUBLISHED").contains(header.getScenarioStatus())) {
            require(Objects.equals(header.getSelectionRequestId(), command.getRequestId().trim())
                            && Objects.equals(header.getSelectionFingerprint(), selectionFingerprint),
                    "该情景已完成候选选择，不允许覆盖审计结论");
            return scenario(header.getId());
        }
        require("EVALUATED".equals(header.getScenarioStatus()), "当前情景状态不允许选择候选");
        require(header.getExpiresTime() != null && header.getExpiresTime().isAfter(LocalDateTime.now()),
                "资源异常多候选情景已过期，必须重新评估");
        require(Objects.equals(header.getSourceFingerprint(), command.getExpectedSourceFingerprint()), "情景来源指纹不一致");
        require("PROPOSED".equals(selectedCase.getStatus()), "候选不属于当前可选择情景");
        List<PlnResourceReplanCaseEntity> cases = caseMapper.selectList(
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
        require(cases.size() == header.getCandidateCount(), "情景候选数量不完整");
        List<PlnSchedulingTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, header.getId()));
        PlnSchedulingTaskEntity selectedTask = tasks.stream()
                .filter(item -> Objects.equals(item.getId(), selectedCase.getProposalTaskId())).findFirst()
                .orElseThrow(() -> new CheckedException("候选任务不属于当前情景"));
        require(Boolean.TRUE.equals(selectedTask.getCandidateFeasible()), "存在硬阻断的候选不能进入发布链");
        cases.forEach(item -> {
            item.setStatus(Objects.equals(item.getId(), selectedCase.getId()) ? "SELECTED" : "SUPERSEDED");
            require(caseMapper.updateById(item) == 1, "资源异常候选选择状态保存失败");
        });
        tasks.forEach(item -> {
            boolean selected = Objects.equals(item.getId(), selectedTask.getId());
            item.setSelectedCandidate(selected);
            if (!selected) item.setTaskStatus("SUPERSEDED");
            require(taskMapper.updateById(item) == 1, "资源异常候选任务选择状态保存失败");
        });
        header.setSelectedTaskId(selectedTask.getId());
        header.setSelectionRequestId(command.getRequestId().trim());
        header.setSelectionFingerprint(selectionFingerprint);
        header.setSelectionReason(command.getReason().trim());
        header.setSelectedBy(userCode(currentUser()));
        header.setSelectedTime(LocalDateTime.now());
        header.setScenarioStatus("SELECTED");
        require(scenarioMapper.updateById(header) == 1, "资源异常候选选择审计保存失败");
        audit(selectedCase, command.getRequestId(), "SELECT", "PROPOSED", "SELECTED",
                "从三个有限产能候选中选择[" + selectedTask.getCandidateName() + "]：" + command.getReason().trim());
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.PublishReceipt publish(PlanResourceLocalReplanDTO.PublishCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedResourceEvidenceFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "资源异常局部重排发布参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("PUBLISH:" + requestId);
        PlnResourceReplanCaseEntity duplicate = caseMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getPublishRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                            && Objects.equals(duplicate.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                            && Objects.equals(duplicate.getResourceEvidenceFingerprint(),
                            command.getExpectedResourceEvidenceFingerprint())
                            && Objects.equals(duplicate.getMesExecutionFingerprint(),
                            command.getExpectedMesExecutionFingerprint()),
                    "发布幂等号已被不同请求使用");
            if (isMpsRemainingCase(duplicate)) {
                requireFactory(duplicate.getFactoryCode());
                mpsRemainingEvidenceReader.requireWriteAccess();
                require(Objects.equals(duplicate.getPublisherCode(), currentUser().getUsername()),
                        "MPS原发布号须由原实名发布人重放；其他岗位请只读核对正式回执");
            }
            return receipt(duplicate, duplicate.getMesPublishedFingerprint());
        }
        PlnResourceReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        String beforeStatus = entity.getStatus();
        if (entity.getScenarioId() == null) {
            require("PROPOSED".equals(entity.getStatus()), "仅待发布资源异常候选允许发布");
        } else {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "SELECTED".equals(header.getScenarioStatus())
                            && Objects.equals(header.getSelectedTaskId(), entity.getProposalTaskId())
                            && "SELECTED".equals(entity.getStatus()),
                    "资源异常多候选情景必须先选择且只能发布被选方案");
        }
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getResourceEvidenceFingerprint(), command.getExpectedResourceEvidenceFingerprint())
                        && Objects.equals(entity.getMesExecutionFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "审批冻结的方案、资源证据或MES指纹不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getApplicantCode(), userCode(user)), "A3资源异常局部重排禁止申请人自批并发布");
        boolean mpsRemaining = isMpsRemainingCase(entity);
        PlnMpsPublicationEvidenceReader.Segment dispatch = null;
        if (mpsRemaining) {
            mpsRemainingEvidenceReader.requireWriteAccess();
            // 已应用决定必须先查永久账。MES成功会改变指纹，不能先重采旧证据而误记STALE。
            List<PlnSchedulingOperationEntity> frozen = frozenOriginalOperations(entity);
            List<PlnSchedulingOperationEntity> proposal = proposalOperations(entity, caseLines(entity.getId()));
            requireProposalUnchanged(entity, proposal);
            var catalog = mpsPublicationEvidenceReader.catalog(entity);
            require(catalog.size() <= 1, "同一MPS案件存在多个原发布号，须对账，不能挑最后一条");
            require(catalog.isEmpty() || Objects.equals(catalog.get(0).getBusinessRequestId(), requestId),
                    "MES已有原发布链，请用原业务永久号恢复："
                            + (catalog.isEmpty() ? "" : catalog.get(0).getBusinessRequestId()));
            dispatch = mpsPublicationEvidenceReader.read(entity, requestId,
                    proposal.stream().map(this::toMesUpdate).toList(), originalMoved(entity, frozen).stream()
                            .map(this::toMesUpdate).toList(), frozen.stream().map(PlnSchedulingOperationEntity::getProcessCode).toList(),
                    PlnFrozenRouteNodeIdentity.nodeCodes(frozen));
            require(dispatch.originalPublisher() == null || Objects.equals(dispatch.originalPublisher(), userCode(user)),
                    "原发布链只能由原实名发布人续接；需要岗位交接时先人工对账，不能借用原号");
            if (dispatch.applied() != null) {
                return completeMpsPublication(entity, requestId, user, frozen, proposal, dispatch);
            }
            mpsPublicationEvidenceReader.requireRestored(dispatch);
        }
        require(entity.getExpiresTime() != null && entity.getExpiresTime().isAfter(LocalDateTime.now()),
                "资源异常候选已过期，必须重新评估；已应用未补记请先核对原发布链");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        require(Objects.equals(entity.getOriginalScheduleFingerprint(), scheduleFingerprint(originals)),
                "正式排程已变化，旧方案失效");
        boolean recoveryOptimization = "MDM_RECOVERY".equals(entity.getEvidenceSourceType());
        boolean executionDeviation = "EXECUTION".equals(entity.getResourceType());
        ResourceEvidence currentEvidence = recoveryOptimization ? recaptureRecoveryEvidence(entity)
                : executionDeviation ? recaptureExecutionEvidence(entity)
                : captureEvidence(entity.getResourceType(), entity.getResourceCode(), originals,
                entity.getEventStartTime(), Boolean.TRUE.equals(entity.getOpenEnded())
                        ? impactEnd(originals, entity.getEventStartTime()) : entity.getExpectedRecoveryTime(),
                Boolean.TRUE.equals(entity.getOpenEnded()));
        // 新业务入口不把已过时的候选时刻下发为正式计划；旧Agent永久请求公式保持不变。
        boolean mpsWindowPassed = mpsRemaining
                && caseLines(entity.getId()).stream().filter(line -> "MOVE".equals(line.getActionType()))
                .anyMatch(line -> line.getProposedStartTime() == null
                        || !line.getProposedStartTime().isAfter(LocalDateTime.now()));
        if (mpsWindowPassed || (!recoveryOptimization && currentEvidence.directCodes.isEmpty())
                || !Objects.equals(entity.getResourceEvidenceFingerprint(), currentEvidence.fingerprint)) {
            entity.setStatus("STALE").setPublishRequestId(requestId)
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user))
                    .setPublishedTime(LocalDateTime.now());
            require(caseMapper.updateById(entity) == 1, "资源异常候选失效状态保存失败");
            if (entity.getScenarioId() != null) {
                PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
                header.setScenarioStatus("FAILED");
                require(scenarioMapper.updateById(header) == 1, "资源异常情景失效状态保存失败");
            }
            audit(entity, requestId, "EVIDENCE_STALE", beforeStatus, "STALE",
                    mpsWindowPassed ? "MPS剩余工序候选开工时刻已过，须重新评估，不下发过去计划"
                            : "权威资源事实已变化，禁止发布旧候选");
            return receipt(entity, null);
        }
        PlnResourceReplanPolicyEntity policy = requirePolicy(entity.getPolicyId(), false);
        require("ACTIVE".equals(policy.getStatus()) && Objects.equals(policy.getPolicyFingerprint(), entity.getPolicyFingerprint())
                && isEffective(policy, LocalDateTime.now()), "资源异常重排策略已失效或发生变化");
        List<PlnResourceReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> candidates = proposalOperations(entity, lines);
        if (recoveryOptimization) {
            LocalDateTime currentFreeze = LocalDateTime.now().plusMinutes(policy.getFreezeMinutes());
            require(lines.stream().allMatch(line -> line.getOriginalStartTime() != null
                            && line.getOriginalStartTime().isAfter(currentFreeze)),
                    "恢复优化工序已进入当前冻结区，须重新评估或现场处置");
        }
        String currentProposalFingerprint = hash(entity.getResourceEvidenceFingerprint() + '|'
                + entity.getPolicyFingerprint() + '|' + entity.getOriginalScheduleFingerprint() + '|'
                + entity.getMesExecutionFingerprint() + '|' + scheduleFingerprint(candidates));
        require(Objects.equals(entity.getProposalFingerprint(), currentProposalFingerprint), "候选工序快照已变化");
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidates);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, candidateByCode),
                entity.getOriginalTaskId(), entity.getIssueId());
        if (executionDeviation) {
            require(candidates.stream().allMatch(item -> item.getScheduledStartTime() != null
                            && item.getScheduledStartTime().isAfter(entity.getFreezeUntil())),
                    "执行偏差候选再次落入冻结区，禁止发布");
        } else {
            ResourceEvidence candidateEvidence = captureEvidence(entity.getResourceType(), entity.getResourceCode(), candidates,
                    entity.getEventStartTime(), Boolean.TRUE.equals(entity.getOpenEnded())
                            ? impactEnd(candidates, entity.getEventStartTime()) : entity.getExpectedRecoveryTime(),
                    Boolean.TRUE.equals(entity.getOpenEnded()));
            require(candidateEvidence.directCodes.isEmpty(), "候选排程再次命中不可用资源，禁止发布");
        }
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        require(Objects.equals(entity.getMesExecutionFingerprint(), mesBefore.getExecutionFingerprint()),
                "MES执行或派工事实已变化，旧方案失效");
        Set<String> movedCodes = candidates.stream().map(PlnFrozenRouteNodeIdentity::key).collect(Collectors.toSet());
        validateMesMovable(mesBefore, movedCodes);
        String mesRequestId = dispatch == null ? requestId + ":MES" : dispatch.applyRequestId();
        String compensationRequestId = dispatch == null ? requestId + ":MES-COMPENSATE" : dispatch.compensateRequestId();
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(mesRequestId, entity,
                dispatch == null ? mesBefore.getExecutionFingerprint() : dispatch.beforeFingerprint(), candidates);
        List<PlnSchedulingOperationEntity> compensation = originals.stream()
                .filter(item -> movedCodes.contains(PlnFrozenRouteNodeIdentity.key(item))).map(this::detachedOperation).toList();
        try {
            for (PlnSchedulingOperationEntity original : originals) {
                PlnSchedulingOperationEntity candidate = candidateByCode.get(PlnFrozenRouteNodeIdentity.key(original));
                if (candidate != null) {
                    copySchedule(candidate, original);
                    require(updateFormalPlan(original), "正式工序排程写回失败");
                }
            }
            updateFormalSummary(issue, originals, "资源异常局部重排已复核发布");
            entity.setPublishedScheduleFingerprint(scheduleFingerprint(originals));
            entity.setStatus("PUBLISHED").setPublishRequestId(requestId)
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user)).setPublishedTime(LocalDateTime.now())
                    .setMesPublishedFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "资源异常局部重排发布状态更新失败");
            if (entity.getScenarioId() != null) {
                PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
                header.setScenarioStatus("PUBLISHED");
                require(scenarioMapper.updateById(header) == 1, "资源异常多候选情景发布状态更新失败");
            }
            audit(entity, requestId, "PUBLISH", beforeStatus, "PUBLISHED",
                    "已重排" + candidates.size() + "道工序、改派" + entity.getReassignedOperationCount()
                            + "道并同步MES，应用永久号=" + mesRequestId);
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(compensationRequestId, entity, mesReceipt.getAfterExecutionFingerprint(),
                    compensation, "发布失败恢复MES原排程", ex);
            throw ex;
        }
    }

    /**
     * 只补记已应用且仍匹配的决定，不再调用MES修改接口或补偿接口。
     * 实际接收/开工/报工可以推进；原根计划数量、MPS来源、正式任务及未移动工序须保持一致。
     */
    private PlanResourceLocalReplanDTO.PublishReceipt completeMpsPublication(
            PlnResourceReplanCaseEntity entity, String requestId, MomUser user,
            List<PlnSchedulingOperationEntity> frozen, List<PlnSchedulingOperationEntity> proposal,
            PlnMpsPublicationEvidenceReader.Segment dispatch) {
        var applied = dispatch.applied();
        var mesReceipt = applied.getReceipt();
        PlnSchedulingScenarioEntity selectedHeader = scenarioMapper.selectById(entity.getScenarioId());
        require(entity.getExpiresTime() != null && mesReceipt.getAppliedTime().isBefore(entity.getExpiresTime())
                        && selectedHeader != null && selectedHeader.getSelectedTime() != null
                        && !mesReceipt.getAppliedTime().isBefore(selectedHeader.getSelectedTime())
                        && proposal.stream().allMatch(row -> row.getScheduledStartTime() != null
                        && row.getScheduledStartTime().isAfter(mesReceipt.getAppliedTime())),
                "原MES应用不在已选候选的有效发布窗口内，须对账而不是补造审批");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        require(Boolean.TRUE.equals(issue.getLockStatus())
                        && Objects.equals(issue.getLockTaskId(), entity.getOriginalTaskId())
                        && "issue".equals(issue.getType()) && !"4".equals(issue.getStatus()),
                "原根计划正式任务、类型或关闭状态已变化，不能覆盖另一次业务决定");
        validateMpsMirrorSource(entity, issue);
        List<PlnSchedulingOperationEntity> formal = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .eq(PlnSchedulingOperationEntity::getTaskId, entity.getOriginalTaskId())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).last("FOR UPDATE"));
        require(formal.size() == frozen.size()
                        && Objects.equals(scheduleFingerprint(formal), entity.getOriginalScheduleFingerprint()),
                "PLAN原正式排程或完整工序已变化，禁止以MES回执覆盖新排程");
        var candidates = uniqueByProcess(proposal);
        var current = applied.getCurrent();
        require(current != null && Boolean.TRUE.equals(current.getMatches()) && current.getFacts() != null,
                "当前MES完整计划核对未通过，不能补记");
        require(current.getPlannedQuantity() != null && current.getPlannedQuantity().compareTo(issue.getNum()) == 0
                        && current.getOperationQuantities() != null
                        && frozen.stream().allMatch(row -> row.getPlannedQuantity() != null
                        && current.getOperationQuantities().get(PlnFrozenRouteNodeIdentity.key(row)) != null
                        && row.getPlannedQuantity().compareTo(current.getOperationQuantities().get(PlnFrozenRouteNodeIdentity.key(row))) == 0),
                "MES根工单或工序计划量不同于原冻结数量，禁止按剩余量猜测恢复");
        validateMesMirrorIdentity(entity, frozen, candidates.keySet(), current.getFacts());
        for (var row : formal) {
            var candidate = candidates.get(PlnFrozenRouteNodeIdentity.key(row));
            if (candidate != null) {
                copySchedule(candidate, row);
                require(updateFormalPlan(row), "原MES发布决定补记正式工序失败，请保留原号重试");
            }
        }
        updateFormalSummary(issue, formal, "核对MES永久回执补记已应用的MPS剩余工序重排");
        entity.setPublishedScheduleFingerprint(scheduleFingerprint(formal)).setStatus("PUBLISHED")
                .setPublishRequestId(requestId).setPublisherCode(userCode(user)).setPublisherName(userName(user))
                .setPublishedTime(mesReceipt.getAppliedTime()).setMesPublishedFingerprint(mesReceipt.getAfterExecutionFingerprint());
        require(caseMapper.updateById(entity) == 1, "MPS已应用发布状态补记失败");
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
        header.setScenarioStatus("PUBLISHED");
        require(scenarioMapper.updateById(header) == 1, "MPS已应用情景状态补记失败");
        audit(entity, requestId, "PUBLISH", "SELECTED", "PUBLISHED",
                "核对原MES永久回执[" + dispatch.applyRequestId() + "]补记" + proposal.size()
                        + "道工序；原应用=" + mesReceipt.getAppliedTime() + "，不再下发或覆盖实际执行");
        // 本地补记异常仅回滚PLAN；不能补偿已经开工的MES决定，后续仍用同一永久号核对。
        return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public PlnMpsPublicationRecoveryDTO mpsPublicationRecovery(Long caseId) {
        mpsRemainingEvidenceReader.requireReadAccess();
        var entity = requireCase(caseId, false);
        require(isMpsRemainingCase(entity), "跨账续接仅用于有完整业务冻结来源的MPS剩余工序案件");
        var view = new PlnMpsPublicationRecoveryDTO().setCaseId(caseId).setIssueNo(entity.getIssueNo())
                .setCaseStatus(entity.getStatus()).setRecoverable(false);
        if (Set.of("PUBLISHED", "ROLLED_BACK").contains(entity.getStatus())) {
            // 只证明PLAN原决定已经入账；不把后续安全回退或执行推进误报为原号丢失。
            return view.setState("PLAN_COMMITTED").setBusinessRequestId(entity.getPublishRequestId());
        }
        var catalog = mpsPublicationEvidenceReader.catalog(entity);
        if (catalog.isEmpty()) return view.setState("NO_APPLICATION");
        if (catalog.size() != 1) {
            view.getGaps().add("同一案件有多个原MES发布号，须人工逐链对账，不能挑最新一条");
            return view.setState("RECONCILE_REQUIRED");
        }
        var ref = catalog.get(0);
        view.setBusinessRequestId(ref.getBusinessRequestId()).setAppliedBy(ref.getAppliedBy()).setAppliedTime(ref.getAppliedTime());
        try {
            require("SELECTED".equals(entity.getStatus()), "原案件不是已选待发布状态，不能跨状态补记");
            var frozen = frozenOriginalOperations(entity);
            var proposed = proposalOperations(entity, caseLines(entity.getId()));
            requireProposalUnchanged(entity, proposed);
            var segment = mpsPublicationEvidenceReader.read(entity, ref.getBusinessRequestId(), proposed.stream()
                            .map(this::toMesUpdate).toList(), originalMoved(entity, frozen).stream().map(this::toMesUpdate).toList(),
                    frozen.stream().map(PlnSchedulingOperationEntity::getProcessCode).toList(),
                    PlnFrozenRouteNodeIdentity.nodeCodes(frozen));
            mpsPublicationEvidenceReader.requireRestored(segment);
            view.setMesRequestId(segment.applyRequestId()).setRetrySegment(segment.index());
            var latest = segment.applied() == null ? segment.lastCompensation() : segment.applied();
            require(latest != null && latest.getCurrent() != null && latest.getCurrent().getFacts() != null,
                    "未得到当前完整MES计划证据");
            view.setCurrentMesFingerprint(latest.getCurrent().getFacts().getExecutionFingerprint())
                    .setState(segment.applied() == null ? "COMPENSATED" : "PENDING_PLAN_ACK");
            if (segment.applied() != null) view.setAppliedTime(segment.applied().getReceipt().getAppliedTime());
            require(Objects.equals(currentUser().getUsername(), segment.originalPublisher())
                            && !Objects.equals(entity.getApplicantCode(), currentUser().getUsername()),
                    "当前账号不是原独立发布人，只可核对；不能替他人接续原永久号");
            view.setRecoverable(true);
        } catch (CheckedException ex) {
            view.getGaps().add(ex.getMessage());
            if (view.getState() == null) view.setState("RECONCILE_REQUIRED");
        }
        return view;
    }

    /**
     * 原冻结根计划及MPS来源必须保留；完成量/质量事实允许按原业务推进，不反向清零。
     */
    private void validateMpsMirrorSource(PlnResourceReplanCaseEntity entity, PlnIssueEntity issue) {
        try {
            JsonNode raw = objectMapper.readTree(objectMapper.readTree(entity.getResourceEvidenceSnapshot())
                    .path("fullExecutionSnapshot").asText());
            JsonNode root = raw.path(6);
            require(raw.isArray() && Set.of(PlnMpsRemainingEvidenceReader.FORMULA, PlnMpsRemainingEvidenceReader.NODE_FORMULA).contains(raw.path(0).asText())
                            && Objects.equals(root.path(0).asText(), issue.getId().toString())
                            && Objects.equals(root.path(1).asText(), issue.getIssueNo())
                            && issue.getMpsLineId() != null
                            && Objects.equals(root.path(3).asText(), issue.getMpsLineId().toString())
                            && Objects.equals(root.path(4).asText(), issue.getPlanningSourceKey())
                            && issue.getNum() != null && new BigDecimal(root.path(5).asText()).compareTo(issue.getNum()) == 0
                            && Objects.equals(entity.getProductCode(), issue.getProductCode()),
                    "原根计划的MPS来源、产品或数量已变化，不能补记旧决定");
            var line = mpsLineMapper.selectOne(new LambdaQueryWrapper<PlnMpsLineEntity>()
                    .eq(PlnMpsLineEntity::getId, issue.getMpsLineId())
                    .eq(PlnMpsLineEntity::getTenantId, entity.getTenantId())
                    .eq(PlnMpsLineEntity::getFactoryCode, entity.getFactoryCode()));
            var task = taskMapper.selectById(entity.getOriginalTaskId());
            require(line != null && Boolean.TRUE.equals(line.getActive()) && !Boolean.TRUE.equals(line.getPending())
                            && Objects.equals(line.getBucketKey(), issue.getPlanningSourceKey())
                            && Objects.equals(line.getProductCode(), issue.getProductCode())
                            && line.getPlanQuantity() != null && line.getPlanQuantity().compareTo(issue.getNum()) == 0
                            && task != null && "official".equalsIgnoreCase(task.getMode())
                            && Objects.equals(String.valueOf(task.getScenarioId()), raw.path(3).asText()),
                    "MPS原桶活动范围或正式任务来源已变化，须跨账对账");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException ex) {
            throw new CheckedException("MPS原冻结来源不可解析，禁止补造恢复依据");
        }
    }

    /**
     * 核对原物理工单/工序身份和保护范围；实际状态推进不等于计划属性漂移。
     */
    private void validateMesMirrorIdentity(PlnResourceReplanCaseEntity entity,
                                           List<PlnSchedulingOperationEntity> frozen, Set<String> moved,
                                           MesApsLocalScheduleDTO.StateBundle current) {
        try {
            JsonNode raw = objectMapper.readTree(objectMapper.readTree(entity.getResourceEvidenceSnapshot())
                    .path("fullExecutionSnapshot").asText());
            Map<String, JsonNode> originalMes = new HashMap<>();
            for (JsonNode row : raw.path(10)) {
                require(originalMes.putIfAbsent(row.size() > 13 && !row.path(13).isNull() ? row.path(13).asText() : row.path(3).asText(), row) == null, "原MES冻结映射重复");
            }
            Map<String, MesApsLocalScheduleDTO.OperationState> byCode = PlnFrozenRouteNodeIdentity.correspondence(frozen, current);
            require(byCode.keySet().equals(originalMes.keySet()) && byCode.size() == frozen.size(), "MES全量物理映射已变化");
            for (var operation : frozen) {
                var old = originalMes.get(PlnFrozenRouteNodeIdentity.key(operation));
                var row = byCode.get(PlnFrozenRouteNodeIdentity.key(operation));
                require(old != null && row != null && row.getProduceTaskId() != null
                                && Objects.equals(old.path(0).asText(), row.getProduceTaskId().toString())
                                && Objects.equals(old.path(1).asText(), row.getProduceTaskCode())
                                && Objects.equals(old.path(2).asText(), row.getTaskCode())
                                && Objects.equals(old.path(4).asInt(), row.getRouteOrder()),
                        "原MES工单/工序主键、编码或顺序变化，不能接到新物理工单");
                if (!moved.contains(PlnFrozenRouteNodeIdentity.key(operation))) {
                    require(Objects.equals(operation.getScheduledStartTime(), row.getPlannedStartTime())
                                    && Objects.equals(operation.getScheduledEndTime(), row.getPlannedEndTime()),
                            "保护工序[" + operation.getProcessCode() + "]计划已漂移，须对账");
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("原MES冻结物理映射不可解析");
        }
    }

    private List<PlnSchedulingOperationEntity> frozenOriginalOperations(PlnResourceReplanCaseEntity entity) {
        try {
            JsonNode rows = objectMapper.readTree(entity.getOriginalScheduleSnapshot());
            require(rows.isArray() && !rows.isEmpty() && rows.size() <= 1000, "原完整APS工序快照缺失或超限");
            List<PlnSchedulingOperationEntity> result = new ArrayList<>();
            for (JsonNode row : rows) result.add(readOperation(row.toString()));
            require(uniqueByProcess(result).size() == result.size()
                            && Objects.equals(entity.getOriginalScheduleFingerprint(), scheduleFingerprint(result)),
                    "原正式APS快照指纹不一致，不能恢复");
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("原完整APS工序快照不可解析");
        }
    }

    private List<PlnSchedulingOperationEntity> originalMoved(PlnResourceReplanCaseEntity entity,
                                                             List<PlnSchedulingOperationEntity> originals) {
        Set<String> codes = caseLines(entity.getId()).stream().filter(row -> "MOVE".equals(row.getActionType()))
                .map(line -> PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), uniqueByProcess(originals))).collect(Collectors.toSet());
        var result = originals.stream().filter(row -> codes.contains(PlnFrozenRouteNodeIdentity.key(row))).toList();
        require(!result.isEmpty() && result.size() == codes.size(), "原补偿范围与冻结MOVE工序不一致");
        return result;
    }

    private void requireProposalUnchanged(PlnResourceReplanCaseEntity entity, List<PlnSchedulingOperationEntity> proposal) {
        require(Objects.equals(entity.getProposalFingerprint(), hash(entity.getResourceEvidenceFingerprint() + '|'
                        + entity.getPolicyFingerprint() + '|' + entity.getOriginalScheduleFingerprint() + '|'
                        + entity.getMesExecutionFingerprint() + '|' + scheduleFingerprint(proposal))),
                "原不可变候选指纹不一致，不能恢复");
    }

    /**
     * 通用HTTP日期契约为秒级；仅对尚未冻结的新MPS候选规范精度，避免两端计划时刻不一致。
     */
    private void normalizeMpsCandidateTime(PlnSchedulingOperationEntity row) {
        LocalDateTime start = row.getScheduledStartTime() == null ? null : row.getScheduledStartTime().withNano(0);
        LocalDateTime end = row.getScheduledEndTime() == null ? null : row.getScheduledEndTime().withNano(0);
        LocalDateTime transfer = row.getTransferReadyTime() == null ? null : row.getTransferReadyTime().withNano(0);
        require(schedulingOperationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getId, row.getId())
                        .eq(PlnSchedulingOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, row.getFactoryCode())
                        .set(PlnSchedulingOperationEntity::getScheduledStartTime, start)
                        .set(PlnSchedulingOperationEntity::getScheduledEndTime, end)
                        .set(PlnSchedulingOperationEntity::getTransferReadyTime, transfer)
                        .set(PlnSchedulingOperationEntity::getUpdateBy, currentUser().getUsername())
                        .set(PlnSchedulingOperationEntity::getUpdateTime, LocalDateTime.now())) == 1,
                "新MPS候选时刻精度规范失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(PlanResourceLocalReplanDTO.CancelCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "取消参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("CANCEL:" + requestId);
        PlnResourceReplanCaseEntity duplicate = caseMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getCancelRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                            && Objects.equals(duplicate.getCancelReason(), command.getReason().trim()),
                    "取消幂等号已被不同请求使用");
            return true;
        }
        PlnResourceReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        MomUser user = currentUser();
        if (entity.getScenarioId() != null) {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "EVALUATED".equals(header.getScenarioStatus()),
                    "仅尚未选择的资源异常情景可以整体取消");
            List<PlnResourceReplanCaseEntity> cases = caseMapper.selectList(
                    new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                            .eq(PlnResourceReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
            for (PlnResourceReplanCaseEntity item : cases) {
                String childRequestId = Objects.equals(item.getId(), entity.getId())
                        ? requestId : requestId + ':' + item.getId();
                String before = item.getStatus();
                item.setStatus("CANCELLED").setCancelRequestId(childRequestId)
                        .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                        .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
                require(caseMapper.updateById(item) == 1, "资源异常多候选情景取消失败");
                audit(item, childRequestId, "CANCEL", before, "CANCELLED", command.getReason().trim());
            }
            header.setScenarioStatus("CANCELLED");
            require(scenarioMapper.updateById(header) == 1, "资源异常情景取消状态保存失败");
            return true;
        }
        require("PROPOSED".equals(entity.getStatus()), "仅待发布资源异常候选允许取消");
        entity.setStatus("CANCELLED").setCancelRequestId(requestId).setCancelReason(command.getReason().trim())
                .setCancelledBy(userCode(user)).setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
        require(caseMapper.updateById(entity) == 1, "资源异常候选取消失败");
        audit(entity, requestId, "CANCEL", "PROPOSED", "CANCELLED", command.getReason().trim());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanResourceLocalReplanDTO.PublishReceipt rollback(PlanResourceLocalReplanDTO.RollbackCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()) && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "回退参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("ROLLBACK:" + requestId);
        PlnResourceReplanCaseEntity duplicate = caseMapper.selectOne(
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getRollbackRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                            && Objects.equals(duplicate.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                            && Objects.equals(duplicate.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint())
                            && Objects.equals(duplicate.getRollbackReason(), command.getReason().trim()),
                    "回退幂等号已被不同请求使用");
            return receipt(duplicate, duplicate.getMesRollbackFingerprint());
        }
        PlnResourceReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        require("PUBLISHED".equals(entity.getStatus()), "仅已发布且未回退方案允许回退");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "回退指纹与已发布方案不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getPublisherCode(), userCode(user)), "发布人与回退复核人必须分离");
        if (isMpsRemainingCase(entity)) {
            mpsRemainingEvidenceReader.requireWriteAccess();
        }
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        List<PlnResourceReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> current = lockedOperations(issue);
        Map<String, PlnSchedulingOperationEntity> currentByCode = uniqueByProcess(current);
        List<PlnSchedulingOperationEntity> rollbackOperations = new ArrayList<>();
        for (PlnResourceReplanLineEntity line : lines) {
            if (!"MOVE".equals(line.getActionType())) continue;
            PlnSchedulingOperationEntity now = currentByCode.get(PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), currentByCode));
            PlnSchedulingOperationEntity proposed = readOperation(line.getProposedOperationSnapshot());
            require(now != null && sameSchedule(now, proposed),
                    "工序[" + line.getProcessCode() + "]发布后已再次变更，禁止覆盖回退");
            var restore = readOperation(line.getOriginalOperationSnapshot());
            require(Objects.equals(restore.getId(), line.getOriginalOperationId()), "回退快照与原工序主键不一致");
            PlnFrozenRouteNodeIdentity.sameIdentity(restore, now);
            PlnFrozenRouteNodeIdentity.sameIdentity(proposed, now);
            rollbackOperations.add(restore);
        }
        Map<String, PlnSchedulingOperationEntity> rollbackByCode = uniqueByProcess(rollbackOperations);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(current, rollbackByCode),
                entity.getOriginalTaskId(), entity.getIssueId());
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(current, mesBefore);
        require(Objects.equals(entity.getMesPublishedFingerprint(), mesBefore.getExecutionFingerprint()),
                "MES执行或派工事实已变化，禁止回退");
        validateMesMovable(mesBefore, rollbackByCode.keySet());
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(requestId + ":MES", entity,
                mesBefore.getExecutionFingerprint(), rollbackOperations);
        List<PlnSchedulingOperationEntity> compensation = lines.stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(line -> readOperation(line.getProposedOperationSnapshot())).toList();
        try {
            for (PlnSchedulingOperationEntity formal : current) {
                PlnSchedulingOperationEntity old = rollbackByCode.get(PlnFrozenRouteNodeIdentity.key(formal));
                if (old != null) {
                    copySchedule(old, formal);
                    require(updateFormalPlan(formal), "正式排程回退失败");
                }
            }
            updateFormalSummary(issue, current, "资源异常局部重排已受控回退");
            entity.setStatus("ROLLED_BACK").setRollbackRequestId(requestId)
                    .setRollbackReason(command.getReason().trim()).setRolledBackBy(userCode(user))
                    .setRolledBackByName(userName(user)).setRolledBackTime(LocalDateTime.now())
                    .setMesRollbackFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "资源异常局部重排回退状态更新失败");
            audit(entity, requestId, "ROLLBACK", "PUBLISHED", "ROLLED_BACK", command.getReason().trim());
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(requestId + ":MES-COMPENSATE", entity, mesReceipt.getAfterExecutionFingerprint(),
                    compensation, "回退失败恢复MES已发布排程", ex);
            throw ex;
        }
    }

    private void validateAssess(PlanResourceLocalReplanDTO.AssessCommand command) {
        boolean openEnded = command != null && Boolean.TRUE.equals(command.getOpenEnded());
        require(command != null && command.getIssueId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getResourceType()) && StrUtil.isNotBlank(command.getResourceCode())
                && command.getEventStartTime() != null && (openEnded || command.getExpectedRecoveryTime() != null)
                && StrUtil.isNotBlank(command.getReason()), "资源异常评估参数不完整");
        normalizeResourceType(command.getResourceType());
        if (openEnded) return;
        require(command.getExpectedRecoveryTime().isAfter(command.getEventStartTime()), "预计恢复时刻必须晚于异常开始时刻");
        require(command.getExpectedRecoveryTime().isAfter(LocalDateTime.now()), "预计恢复时刻必须晚于当前时刻");
        require(Duration.between(command.getEventStartTime(), command.getExpectedRecoveryTime()).toDays() <= 366,
                "单次资源异常窗口不能超过366天");
    }

    private ResourceEvidence captureEvidence(String type, String code,
                                             List<PlnSchedulingOperationEntity> operations,
                                             LocalDateTime eventStart, LocalDateTime recovery,
                                             boolean openEnded) {
        List<PlnSchedulingOperationEntity> assigned = operations.stream()
                .filter(item -> assignedTo(item, type, code)).toList();
        if (assigned.isEmpty()) {
            return new ResourceEvidence(type + "_AVAILABILITY", code, code,
                    json(Map.of("resourceType", type, "resourceCode", code, "assignedOperations", List.of())),
                    hash(type + '|' + code + "|NO_ASSIGNMENT"), List.of());
        }
        LocalDateTime queryStart = assigned.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).map(value -> earlier(value, eventStart)).orElse(eventStart);
        LocalDateTime queryEnd = assigned.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).map(value -> later(value, recovery)).orElse(recovery);
        require(queryEnd.isAfter(queryStart) && Duration.between(queryStart, queryEnd).toDays() <= 730,
                "资源异常证据查询范围无效或超过730天");
        return switch (type) {
            case "DEVICE" -> deviceEvidence(code, assigned, queryStart, queryEnd, eventStart, recovery, openEnded);
            case "TOOLING" -> toolingEvidence(code, assigned, queryStart, queryEnd, eventStart, recovery, openEnded);
            case "EMPLOYEE" -> workforceEvidence(code, assigned, queryStart, queryEnd, eventStart, recovery, openEnded);
            default -> throw new CheckedException("不支持的资源类型");
        };
    }

    private ResourceEvidence deviceEvidence(String code, List<PlnSchedulingOperationEntity> operations,
                                            LocalDateTime queryStart, LocalDateTime queryEnd,
                                            LocalDateTime eventStart, LocalDateTime recovery,
                                            boolean openEnded) {
        TpmDeviceAvailabilityQueryDTO query = new TpmDeviceAvailabilityQueryDTO();
        query.setDeviceCodes(List.of(code));
        query.setStartTime(queryStart);
        query.setEndTime(queryEnd);
        R<List<TpmDeviceUnavailableWindowDTO>> response = deviceAvailabilityService.unavailableWindows(query);
        require(response != null && response.isOk() && response.getData() != null,
                "TPM设备不可用事实不可核验，禁止生成或发布重排");
        List<TpmDeviceUnavailableWindowDTO> windows = response.getData().stream()
                .filter(item -> Objects.equals(code, item.getDeviceCode()))
                .sorted(Comparator.comparing(TpmDeviceUnavailableWindowDTO::getStartTime,
                                Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(TpmDeviceUnavailableWindowDTO::getSourceType, Comparator.nullsLast(String::compareTo))
                        .thenComparing(TpmDeviceUnavailableWindowDTO::getSourceCode, Comparator.nullsLast(String::compareTo)))
                .toList();
        List<String> direct = operations.stream().filter(operation -> windows.stream().anyMatch(window ->
                        overlaps(operation.getScheduledStartTime(), operation.getScheduledEndTime(),
                                window.getStartTime(), window.getEndTime())))
                .map(PlnFrozenRouteNodeIdentity::key).distinct().sorted().toList();
        String sourceType = windows.stream().map(TpmDeviceUnavailableWindowDTO::getSourceType)
                .filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(","));
        String sourceNo = windows.stream().map(TpmDeviceUnavailableWindowDTO::getSourceCode)
                .filter(StrUtil::isNotBlank).distinct().limit(10).collect(Collectors.joining(","));
        String name = windows.stream().map(TpmDeviceUnavailableWindowDTO::getDeviceName)
                .filter(StrUtil::isNotBlank).findFirst().orElseGet(() -> resourceName(operations, "DEVICE"));
        Map<String, Object> snapshot = evidenceSnapshot("DEVICE", code, queryStart, queryEnd, eventStart,
                recovery, openEnded, windows);
        String serialized = json(snapshot);
        return new ResourceEvidence(StrUtil.blankToDefault(sourceType, "TPM_DEVICE_AVAILABILITY"),
                StrUtil.blankToDefault(sourceNo, code), name, serialized, hash(serialized), direct);
    }

    private ResourceEvidence toolingEvidence(String code, List<PlnSchedulingOperationEntity> operations,
                                             LocalDateTime queryStart, LocalDateTime queryEnd,
                                             LocalDateTime eventStart, LocalDateTime recovery,
                                             boolean openEnded) {
        MdmToolingAvailabilityQueryDTO query = new MdmToolingAvailabilityQueryDTO();
        query.setRouteCodes(operations.stream().map(PlnSchedulingOperationEntity::getRouteCode)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList());
        query.setProcessCodes(operations.stream().map(PlnSchedulingOperationEntity::getProcessCode)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList());
        query.setRouteNodeCodes(Optional.ofNullable(PlnFrozenRouteNodeIdentity.nodeCodes(operations)).orElse(List.of()));
        query.setToolingCodes(List.of(code));
        query.setStartTime(queryStart);
        query.setEndTime(queryEnd);
        R<List<MdmToolingCandidateDTO>> response = toolingService.availability(query);
        require(response != null && response.isOk() && response.getData() != null,
                "MDM工装可用性事实不可核验，禁止生成或发布重排");
        List<MdmToolingCandidateDTO> candidates = response.getData().stream()
                .filter(item -> Objects.equals(code, item.getToolingCode()))
                .sorted(Comparator.comparing(MdmToolingCandidateDTO::getRouteCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(MdmToolingCandidateDTO::getProcessCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(MdmToolingCandidateDTO::getRequirementId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        List<String> direct = operations.stream().filter(operation -> overlaps(operation.getScheduledStartTime(),
                        operation.getScheduledEndTime(), eventStart, recovery))
                .filter(operation -> toolingUnavailable(operation, code, candidates))
                .map(PlnFrozenRouteNodeIdentity::key).distinct().sorted().toList();
        String name = candidates.stream().map(MdmToolingCandidateDTO::getToolingName)
                .filter(StrUtil::isNotBlank).findFirst().orElseGet(() -> resourceName(operations, "TOOLING"));
        Map<String, Object> snapshot = evidenceSnapshot("TOOLING", code, queryStart, queryEnd,
                eventStart, recovery, openEnded, candidates);
        String serialized = json(snapshot);
        return new ResourceEvidence("MDM_TOOLING_AVAILABILITY", code, name, serialized, hash(serialized), direct);
    }

    private boolean toolingUnavailable(PlnSchedulingOperationEntity operation, String code,
                                       List<MdmToolingCandidateDTO> candidates) {
        List<MdmToolingCandidateDTO> matched = candidates.stream()
                .filter(item -> Objects.equals(item.getRouteCode(), operation.getRouteCode())
                        && Objects.equals(item.getProcessCode(), operation.getProcessCode())
                        && (StrUtil.isBlank(operation.getSourceRouteNodeCode())
                        || Objects.equals(item.getRouteNodeCode(), operation.getSourceRouteNodeCode()))
                        && Objects.equals(item.getToolingCode(), code)).toList();
        if (matched.isEmpty()) return true;
        return matched.stream().anyMatch(item -> !Boolean.TRUE.equals(item.getQualified())
                || Optional.ofNullable(item.getUnavailableWindows()).orElse(List.of()).stream()
                .anyMatch(window -> overlaps(operation.getScheduledStartTime(), operation.getScheduledEndTime(),
                        window.getStartTime(), window.getEndTime())));
    }

    private ResourceEvidence workforceEvidence(String code, List<PlnSchedulingOperationEntity> operations,
                                               LocalDateTime queryStart, LocalDateTime queryEnd,
                                               LocalDateTime eventStart, LocalDateTime recovery,
                                               boolean openEnded) {
        MdmWorkforceAvailabilityQueryDTO query = new MdmWorkforceAvailabilityQueryDTO();
        query.setEmployeeCodes(List.of(code));
        query.setProcessCodes(operations.stream().map(PlnSchedulingOperationEntity::getProcessCode)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList());
        query.setStartTime(queryStart);
        query.setEndTime(queryEnd);
        R<List<MdmWorkforceCandidateDTO>> response = workforceService.availability(query);
        require(response != null && response.isOk() && response.getData() != null,
                "MDM人员能力与在岗事实不可核验，禁止生成或发布重排");
        List<MdmWorkforceCandidateDTO> candidates = response.getData().stream()
                .filter(item -> Objects.equals(code, item.getEmployeeCode()))
                .sorted(Comparator.comparing(MdmWorkforceCandidateDTO::getProcessCode,
                        Comparator.nullsLast(String::compareTo))).toList();
        List<String> direct = operations.stream().filter(operation -> overlaps(operation.getScheduledStartTime(),
                        operation.getScheduledEndTime(), eventStart, recovery))
                .filter(operation -> workforceUnavailable(operation, code, candidates))
                .map(PlnFrozenRouteNodeIdentity::key).distinct().sorted().toList();
        String name = candidates.stream().map(MdmWorkforceCandidateDTO::getEmployeeName)
                .filter(StrUtil::isNotBlank).findFirst().orElseGet(() -> resourceName(operations, "EMPLOYEE"));
        Map<String, Object> snapshot = evidenceSnapshot("EMPLOYEE", code, queryStart, queryEnd,
                eventStart, recovery, openEnded, candidates);
        String serialized = json(snapshot);
        return new ResourceEvidence("MDM_WORKFORCE_AVAILABILITY", code, name, serialized, hash(serialized), direct);
    }

    private boolean workforceUnavailable(PlnSchedulingOperationEntity operation, String code,
                                         List<MdmWorkforceCandidateDTO> candidates) {
        MdmWorkforceCandidateDTO candidate = candidates.stream()
                .filter(item -> Objects.equals(code, item.getEmployeeCode())
                        && Objects.equals(operation.getProcessCode(), item.getProcessCode()))
                .findFirst().orElse(null);
        if (candidate == null || !Boolean.TRUE.equals(candidate.getEmployeeActive())
                || !Boolean.TRUE.equals(candidate.getQualified())) return true;
        return Optional.ofNullable(candidate.getAvailableWindows()).orElse(List.of()).stream()
                .noneMatch(window -> covers(window, operation.getScheduledStartTime(), operation.getScheduledEndTime()));
    }

    private Map<String, Object> evidenceSnapshot(String type, String code, LocalDateTime queryStart,
                                                 LocalDateTime queryEnd, LocalDateTime eventStart,
                                                 LocalDateTime recovery, boolean openEnded, Object facts) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resourceType", type);
        snapshot.put("resourceCode", code);
        snapshot.put("queryStart", queryStart);
        snapshot.put("queryEnd", queryEnd);
        snapshot.put("eventStart", eventStart);
        snapshot.put("openEnded", openEnded);
        snapshot.put("expectedRecovery", openEnded ? null : recovery);
        snapshot.put("impactEnd", recovery);
        snapshot.put("authorityFacts", facts);
        return snapshot;
    }

    private LocalDateTime impactEnd(List<PlnSchedulingOperationEntity> operations, LocalDateTime eventStart) {
        LocalDateTime end = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(eventStart.plusSeconds(1));
        return end.isAfter(eventStart) ? end : eventStart.plusSeconds(1);
    }

    private boolean assignedTo(PlnSchedulingOperationEntity operation, String type, String code) {
        return switch (type) {
            case "DEVICE" -> Objects.equals(code, operation.getDeviceCode());
            case "TOOLING" -> splitCodes(operation.getToolingCodes()).contains(code);
            case "EMPLOYEE" -> splitCodes(operation.getEmployeeCode()).contains(code);
            default -> false;
        };
    }

    private String resourceName(List<PlnSchedulingOperationEntity> operations, String type) {
        return operations.stream().map(item -> switch (type) {
            case "DEVICE" -> item.getDeviceName();
            case "TOOLING" -> item.getToolingNames();
            case "EMPLOYEE" -> item.getEmployeeName();
            default -> null;
        }).filter(StrUtil::isNotBlank).findFirst().orElse(null);
    }

    private void ensureNoLiveProposal(Long issueId) {
        LocalDateTime now = LocalDateTime.now();
        long own = caseMapper.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getIssueId, issueId)
                .in(PlnResourceReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnResourceReplanCaseEntity::getExpiresTime, now));
        long quality = qualityCaseMapper.selectCount(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getIssueId, issueId)
                .in(PlnQualityReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnQualityReplanCaseEntity::getExpiresTime, now));
        long shortage = shortageCaseMapper.selectCount(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getIssueId, issueId)
                .in(PlnShortageReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnShortageReplanCaseEntity::getExpiresTime, now));
        long insertion = insertionCaseMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getInsertionIssueId, issueId)
                .in(PlnInsertionReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnInsertionReplanCaseEntity::getExpiresTime, now));
        long displaced = insertionIssueMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                .eq(PlnInsertionReplanIssueEntity::getIssueId, issueId)
                .inSql(PlnInsertionReplanIssueEntity::getCaseId,
                        "SELECT id FROM pln_insertion_replan_case WHERE del_flag='0' AND status IN ('PROPOSED','SELECTED')"
                                + " AND expires_time>CURRENT_TIMESTAMP"));
        require(own + quality + shortage + insertion + displaced == 0,
                "该生产计划已有待发布的资源、质量、缺料或插单重排候选，请先完成、取消或等待其过期");
    }

    private void saveLines(PlnResourceReplanCaseEntity entity,
                           List<PlnSchedulingOperationEntity> originals,
                           Map<String, PlnSchedulingOperationEntity> candidates,
                           List<String> directCodes, Set<String> affectedCodes,
                           LocalDateTime freezeUntil, MesApsLocalScheduleDTO.StateBundle mesBundle) {
        Map<String, MesApsLocalScheduleDTO.OperationState> mesByCode = PlnFrozenRouteNodeIdentity.correspondence(originals, mesBundle);
        for (PlnSchedulingOperationEntity original : originals) {
            boolean direct = directCodes.contains(PlnFrozenRouteNodeIdentity.key(original));
            boolean affected = affectedCodes.contains(PlnFrozenRouteNodeIdentity.key(original));
            PlnSchedulingOperationEntity candidate = candidates.get(PlnFrozenRouteNodeIdentity.key(original));
            MesApsLocalScheduleDTO.OperationState mes = mesByCode.get(PlnFrozenRouteNodeIdentity.key(original));
            boolean frozen = original.getScheduledStartTime() == null
                    || !original.getScheduledStartTime().isAfter(freezeUntil)
                    || mes != null && !Boolean.TRUE.equals(mes.getMovable());
            String action = affected ? "MOVE" : frozen ? "FROZEN" : "UNCHANGED";
            PlnResourceReplanLineEntity line = new PlnResourceReplanLineEntity()
                    .setCaseId(entity.getId()).setOriginalOperationId(original.getId())
                    .setProposalOperationId(affected ? candidate.getId() : null)
                    .setOperationSeq(original.getOperationSeq()).setSourceRouteNodeCode(original.getSourceRouteNodeCode()).setProcessCode(original.getProcessCode())
                    .setProcessName(original.getProcessName()).setDirectAffected(direct)
                    .setDownstreamAffected(affected && !direct).setActionType(action)
                    .setBlockedReason("FROZEN".equals(action) ? frozenReason(original, mes, freezeUntil) : null)
                    .setOriginalStartTime(original.getScheduledStartTime()).setOriginalEndTime(original.getScheduledEndTime())
                    .setProposedStartTime(affected ? candidate.getScheduledStartTime() : original.getScheduledStartTime())
                    .setProposedEndTime(affected ? candidate.getScheduledEndTime() : original.getScheduledEndTime())
                    .setOriginalResourceCode(original.getResourceCode())
                    .setProposedResourceCode(affected ? candidate.getResourceCode() : original.getResourceCode())
                    .setOriginalDeviceCode(original.getDeviceCode())
                    .setProposedDeviceCode(affected ? candidate.getDeviceCode() : original.getDeviceCode())
                    .setOriginalEmployeeCode(original.getEmployeeCode())
                    .setProposedEmployeeCode(affected ? candidate.getEmployeeCode() : original.getEmployeeCode())
                    .setOriginalToolingCodes(original.getToolingCodes())
                    .setProposedToolingCodes(affected ? candidate.getToolingCodes() : original.getToolingCodes())
                    .setResourceReassigned(affected && resourceChanged(original, candidate))
                    .setShiftMinutes(affected ? Math.abs(Duration.between(original.getScheduledStartTime(),
                            candidate.getScheduledStartTime()).toMinutes()) : 0)
                    .setOriginalOperationSnapshot(json(original))
                    .setProposedOperationSnapshot(affected ? json(candidate) : null)
                    .setFactoryCode(entity.getFactoryCode());
            require(lineMapper.insert(line) == 1, "资源异常局部重排工序差异保存失败");
        }
    }

    private boolean resourceChanged(PlnSchedulingOperationEntity left, PlnSchedulingOperationEntity right) {
        return right != null && (!Objects.equals(left.getResourceCode(), right.getResourceCode())
                || !Objects.equals(left.getResourceSlotNo(), right.getResourceSlotNo())
                || !Objects.equals(left.getDeviceCode(), right.getDeviceCode())
                || !Objects.equals(left.getEmployeeCode(), right.getEmployeeCode())
                || !Objects.equals(left.getToolingCodes(), right.getToolingCodes()));
    }

    private PlnResourceReplanPolicyEntity resolvePolicy(PlnIssueEntity issue, String type,
                                                        String code, LocalDateTime at) {
        return policyMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>()
                        .eq(PlnResourceReplanPolicyEntity::getFactoryCode, issue.getFactoryCode())
                        .eq(PlnResourceReplanPolicyEntity::getStatus, "ACTIVE")
                        .and(w -> w.isNull(PlnResourceReplanPolicyEntity::getEffectiveFrom)
                                .or().le(PlnResourceReplanPolicyEntity::getEffectiveFrom, at))
                        .and(w -> w.isNull(PlnResourceReplanPolicyEntity::getEffectiveTo)
                                .or().gt(PlnResourceReplanPolicyEntity::getEffectiveTo, at)))
                .stream().filter(item -> "ALL".equals(item.getResourceType()) || Objects.equals(type, item.getResourceType()))
                .filter(item -> StrUtil.isBlank(item.getResourceCode()) || Objects.equals(code, item.getResourceCode()))
                .sorted(Comparator.comparingInt((PlnResourceReplanPolicyEntity item) -> policySpecificity(item, type, code))
                        .reversed().thenComparing(PlnResourceReplanPolicyEntity::getPriority,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlnResourceReplanPolicyEntity::getPolicyVersion,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst().orElse(null);
    }

    private int policySpecificity(PlnResourceReplanPolicyEntity item, String type, String code) {
        if (Objects.equals(type, item.getResourceType()) && Objects.equals(code, item.getResourceCode())) return 3;
        if (Objects.equals(type, item.getResourceType()) && StrUtil.isBlank(item.getResourceCode())) return 2;
        return 1;
    }

    private String policyFingerprint(PlnResourceReplanPolicyEntity entity) {
        return hash(String.join("|", entity.getPolicyCode(), entity.getResourceType(),
                Objects.toString(entity.getResourceCode(), "*"), Objects.toString(entity.getFreezeMinutes(), ""),
                Objects.toString(entity.getMaxDelayMinutes(), ""), Objects.toString(entity.getPriority(), ""),
                Objects.toString(entity.getPolicyVersion(), ""), Objects.toString(entity.getEffectiveFrom(), ""),
                Objects.toString(entity.getEffectiveTo(), "")));
    }

    private PlnResourceReplanPolicyEntity requirePolicy(Long id, boolean lock) {
        LambdaQueryWrapper<PlnResourceReplanPolicyEntity> query =
                new LambdaQueryWrapper<PlnResourceReplanPolicyEntity>().eq(PlnResourceReplanPolicyEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnResourceReplanPolicyEntity result = policyMapper.selectOne(query);
        require(result != null, "资源异常重排策略不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private PlnResourceReplanCaseEntity requireCase(Long id, boolean lock) {
        LambdaQueryWrapper<PlnResourceReplanCaseEntity> query =
                new LambdaQueryWrapper<PlnResourceReplanCaseEntity>().eq(PlnResourceReplanCaseEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnResourceReplanCaseEntity result = caseMapper.selectOne(query);
        require(result != null, "资源异常局部重排案件不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private PlnIssueEntity lockIssue(Long id) {
        PlnIssueEntity result = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id).last("FOR UPDATE"));
        require(result != null, "生产计划不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private List<PlnSchedulingOperationEntity> lockedOperations(PlnIssueEntity issue) {
        require(Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null,
                "生产计划尚未形成正式锁定排程");
        require(!Set.of("3", "4").contains(issue.getStatus()), "生产计划已完成或关闭，禁止局部重排");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).last("FOR UPDATE"));
        require(!rows.isEmpty() && rows.stream().noneMatch(item -> Boolean.TRUE.equals(item.getHardBlocked())),
                "正式APS工序排程不存在或存在硬阻断");
        return rows;
    }

    private List<PlnResourceReplanLineEntity> caseLines(Long caseId) {
        return lineMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanLineEntity>()
                .eq(PlnResourceReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnResourceReplanLineEntity::getOperationSeq));
    }

    private List<PlnSchedulingOperationEntity> proposalOperations(PlnResourceReplanCaseEntity entity,
                                                                  List<PlnResourceReplanLineEntity> lines) {
        Set<Long> ids = lines.stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(PlnResourceReplanLineEntity::getProposalOperationId).filter(Objects::nonNull).collect(Collectors.toSet());
        require(!ids.isEmpty(), "方案没有可发布工序");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().in(PlnSchedulingOperationEntity::getId, ids)
                        .eq(PlnSchedulingOperationEntity::getTaskId, entity.getProposalTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, entity.getIssueId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        require(rows.size() == ids.size(), "资源异常候选排程工序已缺失");
        Map<Long, PlnResourceReplanLineEntity> byId = lines.stream()
                .filter(line -> line.getProposalOperationId() != null)
                .collect(Collectors.toMap(PlnResourceReplanLineEntity::getProposalOperationId, Function.identity()));
        for (PlnSchedulingOperationEntity row : rows) {
            PlnSchedulingOperationEntity frozen = readOperation(byId.get(row.getId()).getProposedOperationSnapshot());
            var line = byId.get(row.getId());
            var original = readOperation(line.getOriginalOperationSnapshot());
            require(Objects.equals(original.getId(), line.getOriginalOperationId()), "重排明细与原工序主键不一致");
            PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), uniqueByProcess(List.of(original)));
            PlnFrozenRouteNodeIdentity.sameIdentity(row, original);
            PlnFrozenRouteNodeIdentity.sameIdentity(row, frozen);
            require(Objects.equals(operationFingerprint(row), operationFingerprint(frozen)),
                    "资源异常候选工序[" + row.getProcessCode() + "]已被修改");
        }
        return rows;
    }

    private MesApsLocalScheduleDTO.StateBundle mesStates(PlnIssueEntity issue) {
        R<MesApsLocalScheduleDTO.StateBundle> response = mesTaskService.apsOperationStates(
                issue.getIssueNo(), issue.getFactoryCode());
        require(response != null && response.isOk() && response.getData() != null
                        && StrUtil.isNotBlank(response.getData().getExecutionFingerprint()),
                "MES执行事实不可用，禁止资源异常局部重排");
        return response.getData();
    }

    private void validateMesMovable(MesApsLocalScheduleDTO.StateBundle bundle, Collection<String> processCodes) {
        if (!Boolean.TRUE.equals(bundle.getMesTaskPresent())) return;
        Map<String, MesApsLocalScheduleDTO.OperationState> byCode = PlnFrozenRouteNodeIdentity.states(bundle);
        for (String code : processCodes) {
            MesApsLocalScheduleDTO.OperationState state = PlnFrozenRouteNodeIdentity.state(byCode, code);
            require(state != null, "MES工艺快照缺少工序[" + code + "]");
            require(Boolean.TRUE.equals(state.getMovable()),
                    "MES工序[" + code + "]不可移动：" + state.getBlockedReason());
        }
    }

    private MesApsLocalScheduleDTO.ApplyReceipt applyMes(String requestId, PlnResourceReplanCaseEntity entity,
                                                         String expectedFingerprint,
                                                         List<PlnSchedulingOperationEntity> operations) {
        MesApsLocalScheduleDTO.ApplyCommand command = new MesApsLocalScheduleDTO.ApplyCommand();
        command.setRequestId(requestId).setPlanNo(entity.getIssueNo()).setFactoryCode(entity.getFactoryCode())
                .setExpectedExecutionFingerprint(expectedFingerprint).setReplanCaseId(entity.getId())
                .setOperations(operations.stream().map(this::toMesUpdate).toList());
        R<MesApsLocalScheduleDTO.ApplyReceipt> response = mesTaskService.applyApsLocalSchedule(command);
        require(response != null && response.isOk() && response.getData() != null,
                "MES未确认资源异常局部重排同步成功");
        return response.getData();
    }

    private MesApsLocalScheduleDTO.OperationUpdate toMesUpdate(PlnSchedulingOperationEntity item) {
        return new MesApsLocalScheduleDTO.OperationUpdate().setSourceRouteNodeCode(item.getSourceRouteNodeCode()).setRouteCode(item.getProcessCode())
                .setPlannedStartTime(item.getScheduledStartTime()).setPlannedEndTime(item.getScheduledEndTime())
                .setResourceCode(item.getResourceCode()).setResourceName(item.getResourceName())
                .setResourceSlotNo(item.getResourceSlotNo()).setResourceGroupCode(item.getResourceGroupCode())
                .setResourceGroupName(item.getResourceGroupName()).setResourceGroupCapacity(item.getResourceGroupCapacity())
                .setResourceGroupSlotNo(item.getResourceGroupSlotNo()).setDeviceCode(item.getDeviceCode())
                .setDeviceName(item.getDeviceName()).setEmployeeCode(item.getEmployeeCode())
                .setEmployeeName(item.getEmployeeName()).setToolingCodes(item.getToolingCodes())
                .setToolingNames(item.getToolingNames()).setToolingAllocationSnapshot(item.getToolingAllocationSnapshot())
                .setToolingLifeUsageSnapshot(item.getToolingLifeUsageSnapshot()).setApsOperationSnapshot(json(item));
    }

    private void compensateMes(String requestId, PlnResourceReplanCaseEntity entity, String expectedFingerprint,
                               List<PlnSchedulingOperationEntity> operations, String action,
                               RuntimeException originalFailure) {
        try {
            applyMes(requestId, entity, expectedFingerprint, operations);
        } catch (RuntimeException compensationFailure) {
            throw new CheckedException(action + "失败，PLAN事务将回滚但MES可能需要人工对账；原错误："
                    + originalFailure.getMessage() + "；补偿错误：" + compensationFailure.getMessage());
        }
    }

    private void saveProposalDetail(PlnSchedulingTaskEntity task, PlnIssueEntity issue,
                                    List<PlnSchedulingOperationEntity> operations) {
        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        BeanUtils.copyProperties(issue, detail, "id", "createTime", "updateTime", "createBy", "updateBy");
        detail.setId(null);
        detail.setTaskId(task.getId());
        detail.setIssueId(issue.getId());
        detail.setLockStatus(false);
        detail.setLockTaskId(issue.getLockTaskId());
        detail.setStatus("PROPOSED");
        detail.setOperationCount(operations.size());
        detail.setOperationStartTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .min(LocalDateTime::compareTo).orElse(null));
        detail.setOperationEndTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .max(LocalDateTime::compareTo).orElse(null));
        require(taskDetailMapper.insert(detail) == 1, "资源异常候选排程任务明细保存失败");
    }

    private void updateFormalSummary(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> operations,
                                     String summary) {
        LocalDateTime start = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime end = completion(operations);
        issue.setScheduledDate(start.toLocalDate());
        issue.setScheduledEndDate(end.toLocalDate());
        require(issueMapper.updateById(issue) == 1, "生产计划排程日期更新失败");
        PlnSchedulingTaskDetailEntity detail = taskDetailMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId()).last("LIMIT 1"));
        if (detail != null) {
            detail.setScheduledDate(start.toLocalDate());
            detail.setScheduledEndDate(end.toLocalDate());
            detail.setOperationStartTime(start);
            detail.setOperationEndTime(end);
            detail.setOperationCount(operations.size());
            detail.setOperationScheduleStatus("SCHEDULED");
            detail.setOperationHardBlocked(false);
            detail.setOperationScheduleSummary(summary);
            require(taskDetailMapper.updateById(detail) == 1, "正式排程任务明细更新失败");
        }
    }

    private Set<String> downstreamClosure(List<PlnSchedulingOperationEntity> operations, List<String> direct) {
        return PlnFrozenRouteNodeIdentity.closure(operations, direct);
    }

    private List<PlnSchedulingOperationEntity> effectiveOperations(List<PlnSchedulingOperationEntity> formal,
                                                                   Map<String, PlnSchedulingOperationEntity> replacements) {
        List<PlnSchedulingOperationEntity> result = new ArrayList<>();
        for (PlnSchedulingOperationEntity current : formal) {
            PlnSchedulingOperationEntity copy = detachedOperation(current);
            PlnSchedulingOperationEntity replacement = replacements.get(PlnFrozenRouteNodeIdentity.key(current));
            if (replacement != null) copySchedule(replacement, copy);
            result.add(copy);
        }
        return result;
    }

    private void copySchedule(PlnSchedulingOperationEntity source, PlnSchedulingOperationEntity target) {
        PlnFrozenRouteNodeIdentity.sameIdentity(source, target);
        target.setResourceType(source.getResourceType());
        target.setResourceCode(source.getResourceCode());
        target.setResourceName(source.getResourceName());
        target.setResourceSlotNo(source.getResourceSlotNo());
        target.setResourceGroupCode(source.getResourceGroupCode());
        target.setResourceGroupName(source.getResourceGroupName());
        target.setResourceGroupCapacity(source.getResourceGroupCapacity());
        target.setResourceGroupSlotNo(source.getResourceGroupSlotNo());
        target.setCandidateResourceSnapshot(source.getCandidateResourceSnapshot());
        target.setDeviceCode(source.getDeviceCode());
        target.setDeviceName(source.getDeviceName());
        target.setDeviceAvailabilitySnapshot(source.getDeviceAvailabilitySnapshot());
        target.setEmployeeCode(source.getEmployeeCode());
        target.setEmployeeName(source.getEmployeeName());
        target.setWorkforceAvailabilitySnapshot(source.getWorkforceAvailabilitySnapshot());
        target.setToolingCodes(source.getToolingCodes());
        target.setToolingNames(source.getToolingNames());
        target.setToolingAllocationSnapshot(source.getToolingAllocationSnapshot());
        target.setToolingLifeUsageSnapshot(source.getToolingLifeUsageSnapshot());
        target.setScheduledStartTime(source.getScheduledStartTime());
        target.setScheduledEndTime(source.getScheduledEndTime());
        target.setTransferReadyTime(source.getTransferReadyTime());
        target.setScheduleStatus(source.getScheduleStatus());
        target.setHardBlocked(source.getHardBlocked());
        target.setConstraintSummary(source.getConstraintSummary());
        target.setSourceSnapshot(source.getSourceSnapshot());
    }

    /**
     * 正式计划只写copySchedule对应属性，null也明确清空；不覆盖数量、来源及现场回传。
     */
    private boolean updateFormalPlan(PlnSchedulingOperationEntity row) {
        return schedulingOperationMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getId, row.getId())
                .eq(PlnSchedulingOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnSchedulingOperationEntity::getFactoryCode, row.getFactoryCode())
                .set(PlnSchedulingOperationEntity::getResourceType, row.getResourceType())
                .set(PlnSchedulingOperationEntity::getResourceCode, row.getResourceCode())
                .set(PlnSchedulingOperationEntity::getResourceName, row.getResourceName())
                .set(PlnSchedulingOperationEntity::getResourceSlotNo, row.getResourceSlotNo())
                .set(PlnSchedulingOperationEntity::getResourceGroupCode, row.getResourceGroupCode())
                .set(PlnSchedulingOperationEntity::getResourceGroupName, row.getResourceGroupName())
                .set(PlnSchedulingOperationEntity::getResourceGroupCapacity, row.getResourceGroupCapacity())
                .set(PlnSchedulingOperationEntity::getResourceGroupSlotNo, row.getResourceGroupSlotNo())
                .set(PlnSchedulingOperationEntity::getCandidateResourceSnapshot, row.getCandidateResourceSnapshot())
                .set(PlnSchedulingOperationEntity::getDeviceCode, row.getDeviceCode())
                .set(PlnSchedulingOperationEntity::getDeviceName, row.getDeviceName())
                .set(PlnSchedulingOperationEntity::getDeviceAvailabilitySnapshot, row.getDeviceAvailabilitySnapshot())
                .set(PlnSchedulingOperationEntity::getEmployeeCode, row.getEmployeeCode())
                .set(PlnSchedulingOperationEntity::getEmployeeName, row.getEmployeeName())
                .set(PlnSchedulingOperationEntity::getWorkforceAvailabilitySnapshot, row.getWorkforceAvailabilitySnapshot())
                .set(PlnSchedulingOperationEntity::getToolingCodes, row.getToolingCodes())
                .set(PlnSchedulingOperationEntity::getToolingNames, row.getToolingNames())
                .set(PlnSchedulingOperationEntity::getToolingAllocationSnapshot, row.getToolingAllocationSnapshot())
                .set(PlnSchedulingOperationEntity::getToolingLifeUsageSnapshot, row.getToolingLifeUsageSnapshot())
                .set(PlnSchedulingOperationEntity::getScheduledStartTime, row.getScheduledStartTime())
                .set(PlnSchedulingOperationEntity::getScheduledEndTime, row.getScheduledEndTime())
                .set(PlnSchedulingOperationEntity::getTransferReadyTime, row.getTransferReadyTime())
                .set(PlnSchedulingOperationEntity::getScheduleStatus, row.getScheduleStatus())
                .set(PlnSchedulingOperationEntity::getHardBlocked, row.getHardBlocked())
                .set(PlnSchedulingOperationEntity::getConstraintSummary, row.getConstraintSummary())
                .set(PlnSchedulingOperationEntity::getSourceSnapshot, row.getSourceSnapshot())
                .set(PlnSchedulingOperationEntity::getUpdateBy, currentUser().getUsername())
                .set(PlnSchedulingOperationEntity::getUpdateTime, LocalDateTime.now())) == 1;
    }

    private String scheduleFingerprint(List<PlnSchedulingOperationEntity> rows) {
        return hash(rows.stream().sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq,
                        Comparator.nullsLast(Integer::compareTo)).thenComparing(PlnSchedulingOperationEntity::getId,
                        Comparator.nullsLast(Long::compareTo))).map(this::operationFingerprint)
                .collect(Collectors.joining("||")));
    }

    private String operationFingerprint(PlnSchedulingOperationEntity item) {
        return String.join("|", Objects.toString(item.getId(), ""), Objects.toString(item.getTaskId(), ""),
                Objects.toString(item.getIssueId(), ""), Objects.toString(item.getOperationSeq(), ""),
                Objects.toString(item.getRouteCode(), ""), Objects.toString(item.getProcessCode(), ""),
                Objects.toString(item.getPredecessorCodes(), ""), Objects.toString(item.getResourceType(), ""),
                Objects.toString(item.getResourceCode(), ""), Objects.toString(item.getResourceSlotNo(), ""),
                Objects.toString(item.getResourceGroupCode(), ""), Objects.toString(item.getResourceGroupCapacity(), ""),
                Objects.toString(item.getResourceGroupSlotNo(), ""), Objects.toString(item.getDeviceCode(), ""),
                Objects.toString(item.getEmployeeCode(), ""), Objects.toString(item.getToolingCodes(), ""),
                Objects.toString(item.getCandidateResourceSnapshot(), ""),
                Objects.toString(item.getDeviceAvailabilitySnapshot(), ""),
                Objects.toString(item.getWorkforceAvailabilitySnapshot(), ""),
                Objects.toString(item.getToolingAllocationSnapshot(), ""),
                Objects.toString(item.getToolingLifeUsageSnapshot(), ""),
                Objects.toString(item.getScheduledStartTime(), ""), Objects.toString(item.getScheduledEndTime(), ""),
                Objects.toString(item.getTransferReadyTime(), ""), Objects.toString(item.getScheduleStatus(), ""),
                Objects.toString(item.getHardBlocked(), ""), Objects.toString(item.getLockStatus(), ""));
    }

    private boolean sameSchedule(PlnSchedulingOperationEntity left, PlnSchedulingOperationEntity right) {
        return Objects.equals(left.getScheduledStartTime(), right.getScheduledStartTime())
                && Objects.equals(left.getScheduledEndTime(), right.getScheduledEndTime())
                && Objects.equals(left.getResourceCode(), right.getResourceCode())
                && Objects.equals(left.getResourceSlotNo(), right.getResourceSlotNo())
                && Objects.equals(left.getDeviceCode(), right.getDeviceCode())
                && Objects.equals(left.getEmployeeCode(), right.getEmployeeCode())
                && Objects.equals(left.getToolingCodes(), right.getToolingCodes());
    }

    private Map<String, PlnSchedulingOperationEntity> uniqueByProcess(List<PlnSchedulingOperationEntity> rows) {
        return PlnFrozenRouteNodeIdentity.index(rows);
    }

    private PlnSchedulingOperationEntity readOperation(String value) {
        try {
            return objectMapper.readValue(value, PlnSchedulingOperationEntity.class);
        } catch (Exception ex) {
            throw new CheckedException("资源异常局部重排工序快照损坏");
        }
    }

    private PlnSchedulingOperationEntity detachedOperation(PlnSchedulingOperationEntity source) {
        PlnSchedulingOperationEntity result = new PlnSchedulingOperationEntity();
        BeanUtils.copyProperties(source, result);
        return result;
    }

    private LocalDateTime completion(List<PlnSchedulingOperationEntity> rows) {
        return rows.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElseThrow();
    }

    private long maximumShift(List<PlnSchedulingOperationEntity> originals,
                              Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .mapToLong(item -> Math.abs(Duration.between(item.getScheduledStartTime(),
                        candidates.get(PlnFrozenRouteNodeIdentity.key(item)).getScheduledStartTime()).toMinutes())).max().orElse(0);
    }

    private long totalShift(List<PlnSchedulingOperationEntity> originals,
                            Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .mapToLong(item -> Math.abs(Duration.between(item.getScheduledStartTime(),
                        candidates.get(PlnFrozenRouteNodeIdentity.key(item)).getScheduledStartTime()).toMinutes())).sum();
    }

    private BigDecimal candidateBaseScore(long completionDelay, long totalShift,
                                          int resourceChanges, long changeoverMinutes) {
        BigDecimal penalty = BigDecimal.valueOf(Math.min(45, completionDelay / 60.0 * 4)
                + Math.min(25, totalShift / 60.0)
                + Math.min(15, resourceChanges * 3.0)
                + Math.min(15, changeoverMinutes / 30.0));
        return BigDecimal.valueOf(100).subtract(penalty).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void rankCandidates(List<ResourceCandidateBuild> builds) {
        List<ResourceCandidateBuild> ordered = builds.stream().sorted(Comparator
                .comparing((ResourceCandidateBuild item) -> !Boolean.TRUE.equals(item.task().getCandidateFeasible()))
                .thenComparing((ResourceCandidateBuild item) -> item.task().getCandidateScore(), Comparator.reverseOrder())
                .thenComparingLong(ResourceCandidateBuild::completionDelay)
                .thenComparingLong(ResourceCandidateBuild::totalShift)
                .thenComparingInt(ResourceCandidateBuild::resourceChanges)
                .thenComparingLong(ResourceCandidateBuild::changeoverMinutes)
                .thenComparing(item -> item.task().getCandidateCode())).toList();
        for (int index = 0; index < ordered.size(); index++) {
            PlnSchedulingTaskEntity task = ordered.get(index).task();
            task.setCandidateRank(index + 1);
            require(taskMapper.updateById(task) == 1, "资源异常候选排名保存失败");
        }
    }

    private void enrichCandidate(PlnResourceReplanCaseEntity entity) {
        if (entity == null || entity.getScenarioId() == null || entity.getProposalTaskId() == null) return;
        PlnSchedulingTaskEntity task = taskMapper.selectById(entity.getProposalTaskId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
        if (task != null) {
            entity.setCandidateCode(task.getCandidateCode());
            entity.setCandidateName(task.getCandidateName());
            entity.setCandidateScore(task.getCandidateScore());
            entity.setCandidateRank(task.getCandidateRank());
            entity.setCandidateFeasible(task.getCandidateFeasible());
            entity.setSelectedCandidate(task.getSelectedCandidate());
            entity.setCandidateMetricsSnapshot(task.getCandidateMetricsSnapshot());
        }
        if (header != null) entity.setScenarioNo(header.getScenarioNo());
    }

    private String deliveryRisk(PlnIssueEntity issue, LocalDateTime completion) {
        if (issue.getDemandTime() == null) return "AT_RISK";
        LocalDateTime deadline = issue.getDemandTime().atTime(LocalTime.MAX);
        if (completion.isAfter(deadline)) return "LATE";
        return Duration.between(completion, deadline).toHours() < 24 ? "AT_RISK" : "ON_TIME";
    }

    private String frozenReason(PlnSchedulingOperationEntity operation,
                                MesApsLocalScheduleDTO.OperationState mes, LocalDateTime freezeUntil) {
        if (mes != null && !Boolean.TRUE.equals(mes.getMovable())) return mes.getBlockedReason();
        return "计划开始时间不晚于冻结边界" + freezeUntil;
    }

    private PlanResourceLocalReplanDTO.PublishReceipt receipt(PlnResourceReplanCaseEntity entity,
                                                              String mesFingerprint) {
        return new PlanResourceLocalReplanDTO.PublishReceipt().setCaseId(entity.getId()).setCaseNo(entity.getCaseNo())
                .setIssueNo(entity.getIssueNo()).setStatus(entity.getStatus())
                .setMovedOperationCount(entity.getMovedOperationCount())
                .setReassignedOperationCount(entity.getReassignedOperationCount())
                .setNewCompletionTime("ROLLED_BACK".equals(entity.getStatus())
                        ? entity.getOriginalCompletionTime() : entity.getProposedCompletionTime())
                .setMesAfterExecutionFingerprint(mesFingerprint);
    }

    private void audit(PlnResourceReplanCaseEntity entity, String requestId, String type,
                       String before, String after, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new PlnResourceReplanOperationEntity().setCaseId(entity.getId())
                        .setRequestId(requestId.trim()).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setContent(content).setOperatorCode(userCode(user)).setOperatorName(userName(user))
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1,
                "资源异常局部重排审计履历保存失败");
    }

    private boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                             LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart != null && aEnd != null && bStart != null
                && aStart.isBefore(Optional.ofNullable(bEnd).orElse(LocalDateTime.MAX)) && bStart.isBefore(aEnd);
    }

    private boolean covers(MdmWorkforceWindowDTO window, LocalDateTime start, LocalDateTime end) {
        return window != null && window.getStartTime() != null && window.getEndTime() != null
                && !window.getStartTime().isAfter(start) && !window.getEndTime().isBefore(end);
    }

    private boolean intervalsOverlap(LocalDateTime aStart, LocalDateTime aEnd,
                                     LocalDateTime bStart, LocalDateTime bEnd) {
        return (aEnd == null || bStart.isBefore(aEnd)) && (bEnd == null || aStart.isBefore(bEnd));
    }

    private boolean isEffective(PlnResourceReplanPolicyEntity policy, LocalDateTime time) {
        return (policy.getEffectiveFrom() == null || !policy.getEffectiveFrom().isAfter(time))
                && (policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(time));
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String message) {
        int result = value == null ? defaultValue : value;
        require(result >= min && result <= max, message);
        return result;
    }

    private String normalizePolicyType(String value) {
        String result = StrUtil.blankToDefault(value, "ALL").trim().toUpperCase(Locale.ROOT);
        require(POLICY_TYPES.contains(result), "策略类型仅支持ALL、DEVICE、TOOLING、EMPLOYEE或EXECUTION");
        return result;
    }

    private String normalizeResourceType(String value) {
        String result = StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
        require(RESOURCE_TYPES.contains(result), "异常来源类型仅支持DEVICE、TOOLING、EMPLOYEE或EXECUTION");
        return result;
    }

    private List<String> splitCodes(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }

    private void requireRecoveryReviewFactory(String factoryCode) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && currentUser() != null
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                        && Objects.equals(FactoryContextHolder.getFactoryCode(), factoryCode),
                "设备恢复排程复核须实名选择当前租户和实际工厂");
    }

    /**
     * 仅新业务公式追加当前工厂权限和时刻保护，不重解释旧Agent来源快照。
     */
    private boolean isMpsRemainingCase(PlnResourceReplanCaseEntity candidate) {
        return "EXECUTION".equals(candidate.getResourceType())
                && StrUtil.isNotBlank(candidate.getResourceEvidenceSnapshot())
                && PlnMpsRemainingEvidenceReader.FORMULA.equals(
                JSONUtil.parseObj(candidate.getResourceEvidenceSnapshot()).getStr("businessFormula"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("资源异常局部重排证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("资源异常局部重排证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record ResourceEvidence(String sourceType, String sourceNo, String resourceName,
                                    String snapshot, String fingerprint, List<String> directCodes) {
    }

    private record ResourceCandidateProfile(String code, String name, String solverStrategy,
                                            String sequenceStrategy) {
    }

    private record ResourceCandidateBuild(PlnResourceReplanCaseEntity entity,
                                          PlnSchedulingTaskEntity task,
                                          long completionDelay, long totalShift,
                                          int resourceChanges, long changeoverMinutes) {
    }
}
