package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnProductDemandService;
import com.kitex.mom.plan.service.PlnQualityLocalReplanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 质量偏差驱动局部重排。阈值捕获与MES回执同事务，只读取PLAN数据；
 * MES事实仅在人工评估、发布和回退阶段读取，避免同步回调调用形成跨服务事务环。
 */
@Service
@RequiredArgsConstructor
public class PlnQualityLocalReplanServiceImpl implements PlnQualityLocalReplanService {
    private static final String FORMULA_VERSION = "QUALITY_RISK_V2";
    private static final int PROPOSAL_VALID_HOURS = 24;
    private static final Set<String> OPEN_CASE_STATUSES = Set.of(
            "PENDING_ASSESSMENT", "SUPPLEMENT_REQUIRED", "PROPOSED", "SELECTED", "SUPERSEDED");

    private final PlnQualityReplanPolicyMapper policyMapper;
    private final PlnQualityDeviationEvaluationMapper evaluationMapper;
    private final PlnQualityReplanCaseMapper caseMapper;
    private final PlnQualityReplanLineMapper lineMapper;
    private final PlnQualityReplanOperationMapper operationMapper;
    private final PlnQualitySupplementDemandMapper supplementMapper;
    private final PlnQualitySupplementWithdrawalMapper withdrawalMapper;
    private final PlnIssueCallbackReceiptMapper callbackReceiptMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnShortageReplanCaseMapper shortageCaseMapper;
    private final PlnResourceReplanCaseMapper resourceCaseMapper;
    private final PlnInsertionReplanCaseMapper insertionCaseMapper;
    private final PlnInsertionReplanIssueMapper insertionIssueMapper;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnProductDemandService productDemandService;
    private final PlanMatMrpService planMatMrpService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final PlnQualitySupplementSourceGuard supplementSourceGuard;
    private final PlnQualitySupplementPlanningProjector supplementPlanning;
    private final PlnQualitySupplementProgressReader supplementProgress;
    private final PlnMpsCapacityPublicationLock publicationLock;
    private final ObjectMapper objectMapper;

    @Override
    public Page<PlnQualityReplanPolicyEntity> policyPage(SearchPage page,
                                                         PlanQualityLocalReplanDTO.PolicyQuery query) {
        PlanQualityLocalReplanDTO.PolicyQuery q = query == null
                ? new PlanQualityLocalReplanDTO.PolicyQuery() : query;
        return policyMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                .like(StrUtil.isNotBlank(q.getPolicyCode()), PlnQualityReplanPolicyEntity::getPolicyCode, q.getPolicyCode())
                .like(StrUtil.isNotBlank(q.getProductCode()), PlnQualityReplanPolicyEntity::getProductCode, q.getProductCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnQualityReplanPolicyEntity::getStatus, q.getStatus())
                .orderByDesc(PlnQualityReplanPolicyEntity::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualityReplanPolicyEntity savePolicyDraft(PlanQualityLocalReplanDTO.PolicyDraftCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getPolicyCode())
                && StrUtil.isNotBlank(command.getPolicyName()), "策略编码和名称不能为空");
        String factoryCode = StrUtil.blankToDefault(command.getFactoryCode(), FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factoryCode), "当前工厂不能为空");
        requireFactory(factoryCode);
        String thresholdMode = StrUtil.blankToDefault(command.getThresholdMode(), "ANY").trim().toUpperCase();
        require(Set.of("ANY", "ALL").contains(thresholdMode), "阈值组合方式只能为ANY或ALL");
        validateThreshold(command.getMinAtRiskQuantity(), command.getMinDeviationRate(), command.getMinScrapQuantity());
        int freezeMinutes = bounded(command.getFreezeMinutes(), 120, 30, 7 * 24 * 60,
                "冻结区分钟数必须在30分钟到7天之间");
        int maxDelayMinutes = bounded(command.getMaxDelayMinutes(), 7 * 24 * 60, 1, 366 * 24 * 60,
                "最大允许延期必须在1分钟到366天之间");
        int priority = bounded(command.getPriority(), 100, 0, 9999, "策略优先级必须在0到9999之间");
        require(command.getEffectiveFrom() == null || command.getEffectiveTo() == null
                || command.getEffectiveTo().isAfter(command.getEffectiveFrom()), "策略失效时间必须晚于生效时间");
        policyMapper.lockKey(factoryCode + ':' + command.getPolicyCode().trim());
        PlnQualityReplanPolicyEntity entity;
        MomUser user = currentUser();
        if (command.getId() != null) {
            entity = policyMapper.selectOne(new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                    .eq(PlnQualityReplanPolicyEntity::getId, command.getId()).last("FOR UPDATE"));
            require(entity != null && "DRAFT".equals(entity.getStatus()), "仅草稿策略允许修改");
            require(Objects.equals(entity.getFactoryCode(), factoryCode), "策略不属于当前工厂");
        } else {
            Integer lastVersion = policyMapper.selectList(new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                            .eq(PlnQualityReplanPolicyEntity::getFactoryCode, factoryCode)
                            .eq(PlnQualityReplanPolicyEntity::getPolicyCode, command.getPolicyCode().trim())
                            .orderByDesc(PlnQualityReplanPolicyEntity::getPolicyVersion).last("LIMIT 1"))
                    .stream().findFirst().map(PlnQualityReplanPolicyEntity::getPolicyVersion).orElse(0);
            entity = new PlnQualityReplanPolicyEntity().setPolicyVersion(lastVersion + 1)
                    .setStatus("DRAFT").setFactoryCode(factoryCode)
                    .setAuthorCode(userCode(user)).setAuthorName(userName(user));
        }
        entity.setPolicyCode(command.getPolicyCode().trim()).setPolicyName(command.getPolicyName().trim())
                .setProductCode(trimToNull(command.getProductCode())).setThresholdMode(thresholdMode)
                .setMinAtRiskQuantity(nonNegative(command.getMinAtRiskQuantity()))
                .setMinDeviationRate(nonNegative(command.getMinDeviationRate()))
                .setMinScrapQuantity(nonNegative(command.getMinScrapQuantity()))
                .setFreezeMinutes(freezeMinutes).setMaxDelayMinutes(maxDelayMinutes).setPriority(priority)
                .setEffectiveFrom(command.getEffectiveFrom()).setEffectiveTo(command.getEffectiveTo());
        entity.setPolicyFingerprint(policyFingerprint(entity));
        require(entity.getId() == null ? policyMapper.insert(entity) == 1 : policyMapper.updateById(entity) == 1,
                "质量偏差策略草稿保存失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualityReplanPolicyEntity activatePolicy(PlanQualityLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId()),
                "策略启用参数不完整");
        policyMapper.lockKey("ACTIVATE:" + command.getRequestId().trim());
        PlnQualityReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                        .eq(PlnQualityReplanPolicyEntity::getActivationRequestId, command.getRequestId().trim()));
        if (duplicate != null) return duplicate;
        PlnQualityReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("DRAFT".equals(entity.getStatus()), "仅草稿策略允许启用");
        policyMapper.lockKey("SCOPE:" + entity.getFactoryCode() + ':'
                + Objects.toString(entity.getProductCode(), "*"));
        MomUser user = currentUser();
        require(!Objects.equals(entity.getAuthorCode(), userCode(user)), "策略编制人与启用复核人必须分离");
        LocalDateTime from = Optional.ofNullable(entity.getEffectiveFrom()).orElse(LocalDateTime.now());
        List<PlnQualityReplanPolicyEntity> active = policyMapper.selectList(
                new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                        .eq(PlnQualityReplanPolicyEntity::getFactoryCode, entity.getFactoryCode())
                        .eq(PlnQualityReplanPolicyEntity::getStatus, "ACTIVE")
                        .and(w -> {
                            if (entity.getProductCode() == null) {
                                w.isNull(PlnQualityReplanPolicyEntity::getProductCode)
                                        .or().eq(PlnQualityReplanPolicyEntity::getProductCode, "");
                            } else {
                                w.eq(PlnQualityReplanPolicyEntity::getProductCode, entity.getProductCode());
                            }
                        }));
        boolean overlap = active.stream().anyMatch(item -> intervalsOverlap(from, entity.getEffectiveTo(),
                Optional.ofNullable(item.getEffectiveFrom()).orElse(LocalDateTime.MIN), item.getEffectiveTo()));
        require(!overlap, "同工厂、同产品范围存在生效期重叠的启用策略，请先退役旧版本或调整生效期");
        entity.setEffectiveFrom(from).setPolicyFingerprint(policyFingerprint(entity)).setStatus("ACTIVE")
                .setActivationRequestId(command.getRequestId().trim()).setApproverCode(userCode(user))
                .setApproverName(userName(user)).setApprovedTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "质量偏差策略启用失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualityReplanPolicyEntity retirePolicy(PlanQualityLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "策略退役参数不完整");
        policyMapper.lockKey("RETIRE:" + command.getRequestId().trim());
        PlnQualityReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                        .eq(PlnQualityReplanPolicyEntity::getRetireRequestId, command.getRequestId().trim()));
        if (duplicate != null) return duplicate;
        PlnQualityReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("ACTIVE".equals(entity.getStatus()), "仅启用策略允许退役");
        MomUser user = currentUser();
        entity.setStatus("RETIRED").setRetireRequestId(command.getRequestId().trim())
                .setRetireReason(command.getReason().trim()).setRetiredBy(userCode(user))
                .setRetiredTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "质量偏差策略退役失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureCallback(PlnIssueCallbackReceiptEntity receipt, PlnIssueEntity issue) {
        if (receipt == null || receipt.getId() == null || issue == null) return;
        syncSupplementFulfillment(receipt, issue);
        if (!isQualityReceipt(receipt)) return;
        evaluate(receipt, issue, "CALLBACK:" + receipt.getRequestId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualityDeviationEvaluationEntity reevaluate(PlanQualityLocalReplanDTO.ReevaluateCommand command) {
        require(command != null && command.getIssueId() != null && StrUtil.isNotBlank(command.getRequestId()),
                "质量偏差重新评估参数不完整");
        PlnIssueEntity issue = lockIssue(command.getIssueId());
        PlnIssueCallbackReceiptEntity receipt = callbackReceiptMapper.selectOne(
                new LambdaQueryWrapper<PlnIssueCallbackReceiptEntity>()
                        .eq(PlnIssueCallbackReceiptEntity::getIssueId, issue.getId())
                        .and(w -> w.ne(PlnIssueCallbackReceiptEntity::getUnqualifiedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getQualityIsolatedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getQualityReleasedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getQualityRejectedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getScrapQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getReworkQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getReworkCompletedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getReworkQualifiedQuantity, BigDecimal.ZERO)
                                .or().ne(PlnIssueCallbackReceiptEntity::getReworkRejectedQuantity, BigDecimal.ZERO))
                        .orderByDesc(PlnIssueCallbackReceiptEntity::getReceivedTime)
                        .orderByDesc(PlnIssueCallbackReceiptEntity::getId).last("LIMIT 1"));
        require(receipt != null && isQualityReceipt(receipt), "该计划尚无可复核的质量数量回执");
        return evaluate(receipt, issue, "MANUAL:" + command.getRequestId().trim());
    }

    private PlnQualityDeviationEvaluationEntity evaluate(PlnIssueCallbackReceiptEntity receipt, PlnIssueEntity issue,
                                                         String evaluationRequestId) {
        caseMapper.lockKey("EVALUATION:" + evaluationRequestId);
        PlnQualityDeviationEvaluationEntity existing = evaluationMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityDeviationEvaluationEntity>()
                        .eq(PlnQualityDeviationEvaluationEntity::getEvaluationRequestId, evaluationRequestId));
        if (existing != null) return existing;
        caseMapper.lockKey("ISSUE:" + issue.getId());
        RiskMetrics metrics = calculateRisk(issue);
        PlnQualityReplanPolicyEntity policy = resolvePolicy(issue, receipt.getReceivedTime());
        String evidenceFingerprint = currentEvidenceFingerprint(issue, metrics);
        ThresholdResult threshold = policy == null ? new ThresholdResult(false, "未配置当前工厂/产品的生效策略")
                : threshold(policy, metrics);
        PlnQualityReplanCaseEntity activeCase = caseMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                        .eq(PlnQualityReplanCaseEntity::getIssueId, issue.getId())
                        .isNull(PlnQualityReplanCaseEntity::getSourceCaseId)
                        .in(PlnQualityReplanCaseEntity::getStatus, OPEN_CASE_STATUSES)
                        .orderByDesc(PlnQualityReplanCaseEntity::getLatestTriggeredTime).last("LIMIT 1 FOR UPDATE"));
        if (activeCase != null && activeCase.getScenarioId() != null) {
            PlnSchedulingScenarioEntity activeScenario = scenarioMapper.selectById(activeCase.getScenarioId());
            if (activeScenario != null && "PUBLISHED".equals(activeScenario.getScenarioStatus())) activeCase = null;
        }
        String result = policy == null ? "NO_POLICY" : threshold.triggered ? "TRIGGERED" : "BELOW_THRESHOLD";
        if (policy != null && !threshold.triggered && activeCase != null) result = "RECOVERED";
        PlnQualityDeviationEvaluationEntity evaluation = new PlnQualityDeviationEvaluationEntity()
                .setEvaluationRequestId(evaluationRequestId).setSourceReceiptId(receipt.getId())
                .setSourceRequestId(receipt.getRequestId())
                .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                .setPolicyId(policy == null ? null : policy.getId()).setPolicyCode(policy == null ? null : policy.getPolicyCode())
                .setPolicyVersion(policy == null ? null : policy.getPolicyVersion())
                .setPolicyFingerprint(policy == null ? null : policy.getPolicyFingerprint())
                .setFormulaVersion(FORMULA_VERSION).setPlannedQuantity(nz(issue.getNum()))
                .setUnqualifiedQuantity(nz(issue.getUnqualifiedNum()))
                .setQualityIsolatedQuantity(nz(issue.getQualityIsolatedNum()))
                .setQualityReleasedQuantity(nz(issue.getQualityReleasedNum()))
                .setQualityRejectedQuantity(nz(issue.getQualityRejectedNum()))
                .setScrapQuantity(nz(issue.getScrapNum())).setReworkQuantity(nz(issue.getReworkNum()))
                .setReworkCompletedQuantity(nz(issue.getReworkCompletedNum()))
                .setReworkQualifiedQuantity(nz(issue.getReworkQualifiedNum()))
                .setReworkRejectedQuantity(nz(issue.getReworkRejectedNum()))
                .setPendingDispositionQuantity(metrics.pendingDisposition).setOpenReworkQuantity(metrics.openRework)
                .setAtRiskQuantity(metrics.atRisk).setDeviationRate(metrics.rate)
                .setEvaluationResult(result).setEvaluationReason(threshold.reason)
                .setQualityEvidenceFingerprint(evidenceFingerprint).setEvaluatedTime(LocalDateTime.now())
                .setFactoryCode(issue.getFactoryCode());
        require(evaluationMapper.insert(evaluation) == 1, "质量偏差阈值评估账保存失败");

        if (threshold.triggered) {
            String pendingStatus = metrics.pendingDisposition.add(metrics.openRework).signum() > 0
                    ? "PENDING_ASSESSMENT" : "SUPPLEMENT_REQUIRED";
            if (activeCase == null) {
                activeCase = new PlnQualityReplanCaseEntity().setCaseNo("QRC-" + IdUtil.getSnowflakeNextIdStr())
                        .setTriggerEvaluationId(evaluation.getId()).setIssueId(issue.getId())
                        .setIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                        .setProductName(issue.getProductName()).setFirstTriggeredTime(LocalDateTime.now())
                        .setStatus(pendingStatus).setFactoryCode(issue.getFactoryCode());
            } else if (Set.of("PROPOSED", "SELECTED", "SUPERSEDED").contains(activeCase.getStatus())) {
                invalidateOpenScenario(activeCase, "质量数量事实已变化");
                activeCase.setStatus("STALE");
                require(caseMapper.updateById(activeCase) == 1, "旧质量重排候选失效状态保存失败");
                activeCase = new PlnQualityReplanCaseEntity().setCaseNo("QRC-" + IdUtil.getSnowflakeNextIdStr())
                        .setTriggerEvaluationId(evaluation.getId()).setIssueId(issue.getId())
                        .setIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                        .setProductName(issue.getProductName()).setFirstTriggeredTime(LocalDateTime.now())
                        .setStatus(pendingStatus).setFactoryCode(issue.getFactoryCode());
            } else {
                activeCase.setStatus(pendingStatus);
            }
            applyMetrics(activeCase, evaluation, policy, metrics, evidenceFingerprint);
            require(activeCase.getId() == null ? caseMapper.insert(activeCase) == 1 : caseMapper.updateById(activeCase) == 1,
                    "质量偏差处置案件保存失败");
            evaluation.setCaseId(activeCase.getId());
            require(evaluationMapper.updateById(evaluation) == 1, "质量偏差评估关联案件失败");
        } else if (activeCase != null && policy != null) {
            invalidateOpenScenario(activeCase, "质量风险已恢复至阈值以下");
            applyMetrics(activeCase, evaluation, policy, metrics, evidenceFingerprint);
            activeCase.setStatus("RECOVERED");
            require(caseMapper.updateById(activeCase) == 1, "质量偏差恢复状态保存失败");
            evaluation.setCaseId(activeCase.getId());
            require(evaluationMapper.updateById(evaluation) == 1, "质量偏差恢复评估关联失败");
        } else if (activeCase != null) {
            invalidateOpenScenario(activeCase, "质量策略缺失");
            activeCase.setStatus("STALE").setLatestEvaluationId(evaluation.getId())
                    .setQualityEvidenceFingerprint(evidenceFingerprint).setLatestTriggeredTime(LocalDateTime.now());
            require(caseMapper.updateById(activeCase) == 1, "质量策略缺失时案件失效状态保存失败");
            evaluation.setCaseId(activeCase.getId());
            require(evaluationMapper.updateById(evaluation) == 1, "质量策略缺失评估关联失败");
        }
        invalidateSubmittedSupplements(issue, evaluation);
        return evaluation;
    }

    @Override
    public Page<PlnQualityDeviationEvaluationEntity> evaluationPage(SearchPage page,
                                                                    PlanQualityLocalReplanDTO.EvaluationQuery query) {
        PlanQualityLocalReplanDTO.EvaluationQuery q = query == null
                ? new PlanQualityLocalReplanDTO.EvaluationQuery() : query;
        return evaluationMapper.selectPage(page.toMpPage(),
                new LambdaQueryWrapper<PlnQualityDeviationEvaluationEntity>()
                        .like(StrUtil.isNotBlank(q.getIssueNo()), PlnQualityDeviationEvaluationEntity::getIssueNo, q.getIssueNo())
                        .like(StrUtil.isNotBlank(q.getProductCode()), PlnQualityDeviationEvaluationEntity::getProductCode, q.getProductCode())
                        .eq(StrUtil.isNotBlank(q.getEvaluationResult()), PlnQualityDeviationEvaluationEntity::getEvaluationResult, q.getEvaluationResult())
                        .orderByDesc(PlnQualityDeviationEvaluationEntity::getEvaluatedTime));
    }

    @Override
    public Page<PlnQualityReplanCaseEntity> casePage(SearchPage page, PlanQualityLocalReplanDTO.CaseQuery query) {
        LocalDateTime now = LocalDateTime.now();
        caseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnQualityReplanCaseEntity>()
                .in(PlnQualityReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .le(PlnQualityReplanCaseEntity::getExpiresTime, now)
                .set(PlnQualityReplanCaseEntity::getStatus, "EXPIRED"));
        scenarioMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getTriggerType, "QUALITY_LOCAL_REPLAN")
                .in(PlnSchedulingScenarioEntity::getScenarioStatus, "EVALUATED", "SELECTED")
                .le(PlnSchedulingScenarioEntity::getExpiresTime, now)
                .set(PlnSchedulingScenarioEntity::getScenarioStatus, "EXPIRED"));
        PlanQualityLocalReplanDTO.CaseQuery q = query == null ? new PlanQualityLocalReplanDTO.CaseQuery() : query;
        Page<PlnQualityReplanCaseEntity> result = caseMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .like(StrUtil.isNotBlank(q.getCaseNo()), PlnQualityReplanCaseEntity::getCaseNo, q.getCaseNo())
                .like(StrUtil.isNotBlank(q.getIssueNo()), PlnQualityReplanCaseEntity::getIssueNo, q.getIssueNo())
                .like(StrUtil.isNotBlank(q.getProductCode()), PlnQualityReplanCaseEntity::getProductCode, q.getProductCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnQualityReplanCaseEntity::getStatus, q.getStatus())
                .orderByDesc(PlnQualityReplanCaseEntity::getLatestTriggeredTime));
        enrichCaseSupplementCoverage(result.getRecords());
        result.getRecords().forEach(this::enrichCandidate);
        return result;
    }

    @Override
    public PlanQualityLocalReplanDTO.CaseDetail detail(Long caseId) {
        PlnQualityReplanCaseEntity entity = requireCase(caseId, false);
        requireFactory(entity.getFactoryCode());
        PlanQualityLocalReplanDTO.CaseDetail result = new PlanQualityLocalReplanDTO.CaseDetail();
        BeanUtils.copyProperties(entity, result);
        result.setLines(lineMapper.selectList(new LambdaQueryWrapper<PlnQualityReplanLineEntity>()
                .eq(PlnQualityReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnQualityReplanLineEntity::getOperationSeq)));
        Long factRootCaseId = entity.getSourceCaseId() == null ? entity.getId() : entity.getSourceCaseId();
        result.setEvaluations(evaluationMapper.selectList(new LambdaQueryWrapper<PlnQualityDeviationEvaluationEntity>()
                .eq(PlnQualityDeviationEvaluationEntity::getCaseId, factRootCaseId)
                .orderByDesc(PlnQualityDeviationEvaluationEntity::getEvaluatedTime)));
        List<PlnQualitySupplementDemandEntity> supplements = supplementMapper.selectList(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getSourceIssueId, entity.getIssueId())
                        .orderByDesc(PlnQualitySupplementDemandEntity::getSubmittedTime));
        enrichSupplements(supplements);
        result.setSupplements(supplements);
        enrichCaseSupplementCoverage(List.of(result));
        enrichCandidate(result);
        return result;
    }

    @Override
    public PlanQualityLocalReplanDTO.Scenario scenario(Long scenarioId) {
        require(scenarioId != null, "质量重排情景不能为空");
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(scenarioId);
        require(header != null && "QUALITY_LOCAL_REPLAN".equals(header.getTriggerType()), "质量重排情景不存在");
        requireFactory(header.getFactoryCode());
        List<PlanQualityLocalReplanDTO.CaseDetail> candidates = caseMapper.selectList(
                        new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                                .eq(PlnQualityReplanCaseEntity::getScenarioId, scenarioId)
                                .orderByAsc(PlnQualityReplanCaseEntity::getId)).stream()
                .map(item -> detail(item.getId())).sorted(Comparator.comparing(
                        PlanQualityLocalReplanDTO.CaseDetail::getCandidateRank,
                        Comparator.nullsLast(Integer::compareTo))).toList();
        Long rootId = candidates.stream().map(item -> item.getSourceCaseId() == null ? item.getId() : item.getSourceCaseId())
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new PlanQualityLocalReplanDTO.Scenario().setScenario(header)
                .setSourceCaseId(rootId).setCandidates(candidates);
    }

    @Override
    public Page<PlnQualitySupplementDemandEntity> supplementPage(
            SearchPage page, PlanQualityLocalReplanDTO.SupplementQuery query) {
        PlanQualityLocalReplanDTO.SupplementQuery q = query == null
                ? new PlanQualityLocalReplanDTO.SupplementQuery() : query;
        Page<PlnQualitySupplementDemandEntity> result = supplementMapper.selectPage(page.toMpPage(),
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .like(StrUtil.isNotBlank(q.getSupplementNo()),
                                PlnQualitySupplementDemandEntity::getSupplementNo, q.getSupplementNo())
                        .like(StrUtil.isNotBlank(q.getSourceIssueNo()),
                                PlnQualitySupplementDemandEntity::getSourceIssueNo, q.getSourceIssueNo())
                        .like(StrUtil.isNotBlank(q.getProductCode()),
                                PlnQualitySupplementDemandEntity::getProductCode, q.getProductCode())
                        .eq(StrUtil.isNotBlank(q.getStatus()),
                                PlnQualitySupplementDemandEntity::getStatus, q.getStatus())
                        .orderByDesc(PlnQualitySupplementDemandEntity::getSubmittedTime));
        enrichSupplements(result.getRecords());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualitySupplementDemandEntity submitSupplement(
            PlanQualityLocalReplanDTO.SupplementSubmitCommand command) {
        PlnQualitySupplementSourceGuard.requireActor();
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && command.getRequestQuantity() != null && command.getRequestQuantity().signum() > 0
                && command.getDemandDate() != null && StrUtil.isNotBlank(command.getDemandWarehouseCode())
                && StrUtil.isNotBlank(command.getExpectedQualityEvidenceFingerprint())
                && StrUtil.isNotBlank(command.getReason()), "补产需求提交参数不完整");
        require(!command.getDemandDate().isBefore(LocalDate.now()), "补产需求日期不能早于当前日期");
        String submitRequestId = command.getRequestId().trim();
        supplementMapper.lockKey("SUBMIT:" + submitRequestId);
        PlnQualitySupplementDemandEntity replay = supplementMapper.selectOne(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getSubmitRequestId, submitRequestId));
        if (replay != null) {
            boolean sameRequest = Objects.equals(replay.getCaseId(), command.getCaseId())
                    && nz(replay.getRequestQuantity()).compareTo(command.getRequestQuantity()) == 0
                    && Objects.equals(replay.getDemandDate(), command.getDemandDate())
                    && Objects.equals(replay.getDemandWarehouseCode(), command.getDemandWarehouseCode().trim())
                    && Objects.equals(replay.getReason(), command.getReason().trim());
            require(sameRequest, "补产提交幂等号已被不同请求使用");
            enrichSupplement(replay);
            return replay;
        }
        PlnQualityReplanCaseEntity entity = requireCase(command.getCaseId(), false);
        requireFactory(entity.getFactoryCode());
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        caseMapper.lockKey("SUPPLEMENT-ISSUE:" + issue.getId());
        entity = requireCase(command.getCaseId(), true);
        PlnQualityReplanCaseEntity latest = caseMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                        .eq(PlnQualityReplanCaseEntity::getIssueId, issue.getId())
                        .isNull(PlnQualityReplanCaseEntity::getSourceCaseId)
                        .orderByDesc(PlnQualityReplanCaseEntity::getLatestTriggeredTime)
                        .orderByDesc(PlnQualityReplanCaseEntity::getId).last("LIMIT 1 FOR UPDATE"));
        require(latest != null && Objects.equals(latest.getId(), entity.getId()),
                "该案件不是当前生产计划的最新质量事实，请从最新案件提交补产");
        require(!Set.of("STALE", "EXPIRED", "CANCELLED").contains(entity.getStatus()),
                "失效、过期或取消案件不能提交补产需求");
        RiskMetrics currentMetrics = calculateRisk(issue);
        require(Objects.equals(entity.getQualityEvidenceFingerprint(), command.getExpectedQualityEvidenceFingerprint())
                        && Objects.equals(entity.getQualityEvidenceFingerprint(), currentEvidenceFingerprint(issue, currentMetrics)),
                "质量事实已变化，请刷新最新案件后再提交补产");
        BigDecimal required = nz(issue.getScrapNum());
        require(required.signum() > 0, "当前没有已审批净报废量；返工判退须先完成再次返工或MRB报废处置");
        String lossFingerprint = supplementEvidenceFingerprint(issue);
        String requestFingerprint = hash(String.join("|", Objects.toString(entity.getId(), ""), lossFingerprint,
                command.getRequestQuantity().stripTrailingZeros().toPlainString(), command.getDemandDate().toString(),
                command.getDemandWarehouseCode().trim(), command.getReason().trim()));
        SupplementTotals totals = supplementTotals(issue.getId(), null);
        BigDecimal available = positive(required.subtract(totals.covered).subtract(totals.pending));
        require(command.getRequestQuantity().compareTo(available) <= 0,
                "补产申请数量超过未覆盖缺口；当前报废" + plain(required) + "，已建需求"
                        + plain(totals.covered) + "，待复核" + plain(totals.pending)
                        + "，最多可申请" + plain(available));
        MomUser user = currentUser();
        PlnQualitySupplementDemandEntity supplement = new PlnQualitySupplementDemandEntity()
                .setSupplementNo("QSD-" + IdUtil.getSnowflakeNextIdStr()).setSubmitRequestId(submitRequestId)
                .setRequestFingerprint(requestFingerprint).setCaseId(entity.getId()).setSourceIssueId(issue.getId())
                .setSourceIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                .setProductName(issue.getProductName()).setSourceRequiredQuantity(required)
                .setCoveredQuantitySnapshot(totals.covered).setPendingQuantitySnapshot(totals.pending)
                .setRequestQuantity(command.getRequestQuantity().stripTrailingZeros())
                .setQualityEvidenceFingerprint(lossFingerprint).setDemandDate(command.getDemandDate())
                .setDemandWarehouseCode(command.getDemandWarehouseCode().trim()).setReason(command.getReason().trim())
                .setStatus("SUBMITTED").setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setSubmittedTime(LocalDateTime.now()).setFactoryCode(issue.getFactoryCode());
        require(supplementMapper.insert(supplement) == 1, "质量补产需求提交失败");
        audit(entity, submitRequestId, "SUPPLEMENT_SUBMIT", entity.getStatus(), entity.getStatus(),
                "提交补产" + plain(supplement.getRequestQuantity()) + "，需求日" + supplement.getDemandDate()
                        + "，领料仓" + supplement.getDemandWarehouseCode());
        enrichSupplement(supplement);
        return supplement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualitySupplementDemandEntity reviewSupplement(
            PlanQualityLocalReplanDTO.SupplementReviewCommand command) {
        require(command != null && command.getSupplementId() != null && StrUtil.isNotBlank(command.getRequestId())
                && command.getApproved() != null && StrUtil.isNotBlank(command.getExpectedRequestFingerprint())
                && StrUtil.isNotBlank(command.getReason()), "补产需求复核参数不完整");
        MomUser user = PlnQualitySupplementSourceGuard.requireActor();
        String requestId = command.getRequestId().trim();
        supplementMapper.lockKey("REVIEW:" + requestId);
        PlnQualitySupplementDemandEntity duplicate = supplementMapper.selectOne(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getReviewRequestId, requestId));
        if (duplicate != null) {
            requireFactory(duplicate.getFactoryCode());
            require(Objects.equals(duplicate.getId(), command.getSupplementId())
                            && Objects.equals(duplicate.getRequestFingerprint(), command.getExpectedRequestFingerprint()),
                    "补产复核幂等号已被不同请求使用");
            boolean sameDecision = Boolean.TRUE.equals(command.getApproved())
                    ? Set.of("DEMAND_CREATED", "MRP_GENERATED", "FULFILLED", "WITHDRAWN", "STALE").contains(duplicate.getStatus())
                    : "REJECTED".equals(duplicate.getStatus());
            require(sameDecision && Objects.equals(duplicate.getReviewReason(), command.getReason().trim()),
                    "补产复核幂等号已被不同审批结论使用");
            enrichSupplement(duplicate);
            return duplicate;
        }
        PlnQualitySupplementDemandEntity supplement = requireSupplement(command.getSupplementId(), false);
        requireFactory(supplement.getFactoryCode());
        PlnIssueEntity sourceIssue = lockIssue(supplement.getSourceIssueId());
        caseMapper.lockKey("SUPPLEMENT-ISSUE:" + sourceIssue.getId());
        supplement = requireSupplement(command.getSupplementId(), true);
        require("SUBMITTED".equals(supplement.getStatus()), "仅待复核补产需求允许审批");
        require(Objects.equals(supplement.getRequestFingerprint(), command.getExpectedRequestFingerprint()),
                "补产请求指纹不一致，请刷新后重试");
        require(!Objects.equals(supplement.getApplicantCode(), userCode(user)), "补产申请人与复核人必须分离");
        PlnQualityReplanCaseEntity qualityCase = requireCase(supplement.getCaseId(), false);
        require(Objects.equals(qualityCase.getFactoryCode(), supplement.getFactoryCode())
                        && Objects.equals(qualityCase.getIssueId(), sourceIssue.getId())
                        && Objects.equals(qualityCase.getProductCode(), sourceIssue.getProductCode())
                        && Objects.equals(supplement.getSourceIssueNo(), sourceIssue.getIssueNo())
                        && Objects.equals(supplement.getProductCode(), sourceIssue.getProductCode()),
                "补产需求、质量案件与原生产计划来源不一致");
        if (!Boolean.TRUE.equals(command.getApproved())) {
            supplement.setStatus("REJECTED").setReviewRequestId(requestId).setReviewReason(command.getReason().trim())
                    .setReviewerCode(userCode(user)).setReviewerName(userName(user)).setReviewedTime(LocalDateTime.now());
            require(supplementMapper.updateById(supplement) == 1, "补产需求驳回失败");
            audit(qualityCase, requestId, "SUPPLEMENT_REJECT", qualityCase.getStatus(), qualityCase.getStatus(),
                    "驳回补产需求" + supplement.getSupplementNo() + "：" + command.getReason().trim());
            enrichSupplement(supplement);
            return supplement;
        }
        BigDecimal currentRequired = nz(sourceIssue.getScrapNum());
        SupplementTotals totals = supplementTotals(sourceIssue.getId(), supplement.getId());
        if (!Objects.equals(supplement.getQualityEvidenceFingerprint(), supplementEvidenceFingerprint(sourceIssue))
                || totals.covered.add(supplement.getRequestQuantity()).compareTo(currentRequired) > 0) {
            supplement.setStatus("STALE").setReviewRequestId(requestId)
                    .setReviewReason(command.getReason().trim()).setReviewerCode(userCode(user))
                    .setReviewerName(userName(user)).setReviewedTime(LocalDateTime.now());
            require(supplementMapper.updateById(supplement) == 1, "失效补产需求状态保存失败");
            audit(qualityCase, requestId, "SUPPLEMENT_STALE", qualityCase.getStatus(), qualityCase.getStatus(),
                    "复核时发现报废事实变化或缺口已被覆盖，补产申请" + supplement.getSupplementNo() + "失效");
            enrichSupplement(supplement);
            return supplement;
        }

        PlnProductDemandEntity demand = new PlnProductDemandEntity();
        demand.setFactoryCode(sourceIssue.getFactoryCode());
        demand.setCustomerCode(sourceIssue.getCustomerCode());
        demand.setCustomerName(sourceIssue.getCustomerName());
        demand.setProductCode(sourceIssue.getProductCode());
        demand.setProductName(sourceIssue.getProductName());
        demand.setSpecification(sourceIssue.getSpecificationModel());
        demand.setProductUnit(supplementSourceGuard.productUnit(sourceIssue));
        demand.setDemandWarehouseCode(supplement.getDemandWarehouseCode());
        demand.setPlanEndDate(supplement.getDemandDate());
        demand.setNeedNum(supplement.getRequestQuantity());
        demand.setMrpPriority(900);
        demand.setCompleteNum(BigDecimal.ZERO);
        demand.setSourceType("QUALITY_SUPPLEMENT");
        demand.setSourceId(supplement.getId());
        demand.setSourceNo(supplement.getSupplementNo());
        demand.setSourceQuantitySnapshot(currentRequired);
        demand.setSourceEvidenceFingerprint(supplement.getQualityEvidenceFingerprint());
        require(Boolean.TRUE.equals(productDemandService.savePlan(demand)) && demand.getId() != null
                && StrUtil.isNotBlank(demand.getDemandNo()), "补产产品需求创建失败");
        require(Boolean.TRUE.equals(productDemandService.receive(new Long[]{demand.getId()})), "补产产品需求接收失败");
        supplement.setPlanningModelVersion(2).setStatus("DEMAND_CREATED").setReviewRequestId(requestId)
                .setReviewReason(command.getReason().trim()).setReviewerCode(userCode(user))
                .setReviewerName(userName(user)).setReviewedTime(LocalDateTime.now())
                .setProductDemandId(demand.getId()).setProductDemandNo(demand.getDemandNo());
        require(supplementMapper.updateById(supplement) == 1, "补产需求审批回执写回失败");
        audit(qualityCase, requestId, "SUPPLEMENT_APPROVE", qualityCase.getStatus(), qualityCase.getStatus(),
                "复核通过补产" + plain(supplement.getRequestQuantity()) + "；已接收产品需求=" + demand.getDemandNo());
        enrichSupplement(supplement);
        return supplement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualitySupplementDemandEntity planSupplement(PlanQualityLocalReplanDTO.SupplementPlanCommand command) {
        require(command != null && command.getSupplementId() != null && StrUtil.isNotBlank(command.getRequestId())
                && command.getRequestId().trim().length() <= 64
                && StrUtil.isNotBlank(command.getExpectedRequestFingerprint()), "继续补产计划参数不完整");
        PlnQualitySupplementSourceGuard.requireActor();
        publicationLock.acquire(FactoryContextHolder.getFactoryCode());
        String requestId = command.getRequestId().trim();
        supplementMapper.lockKey("PLAN:" + requestId);
        String payload = hash(command.getSupplementId() + "|" + command.getExpectedRequestFingerprint());
        PlnQualityReplanOperationEntity old = operationMapper.selectOne(
                new LambdaQueryWrapper<PlnQualityReplanOperationEntity>().eq(PlnQualityReplanOperationEntity::getRequestId, requestId));
        var supplement = requireSupplement(command.getSupplementId(), false);
        requireFactory(supplement.getFactoryCode());
        require(Integer.valueOf(2).equals(supplement.getPlanningModelVersion())
                        && Objects.equals(supplement.getRequestFingerprint(), command.getExpectedRequestFingerprint()),
                "补产请求版本或指纹不一致，请刷新后重试");
        if (old != null) {
            require(Objects.equals(old.getFactoryCode(), supplement.getFactoryCode())
                            && Objects.equals(old.getCaseId(), supplement.getCaseId())
                            && "SUPPLEMENT_PLAN".equals(old.getOperationType()) && Objects.equals(old.getContent(), payload),
                    "补产计划幂等号已被不同请求使用");
            enrichSupplement(supplement);
            return supplement;
        }
        require(Set.of("DEMAND_CREATED", "MRP_GENERATED").contains(supplement.getStatus()), "补产当前状态不能继续计划");
        var demand = productDemandService.getById(supplement.getProductDemandId());
        require(demand != null, "补产产品需求不存在");
        supplementPlanning.validateBinding(supplement, demand);
        supplementPlanning.requireNoWithdrawal(supplement);
        if ("DEMAND_CREATED".equals(supplement.getStatus())) {
            R<?> result = productDemandService.mrp(new Long[]{demand.getId()});
            require(result != null && result.isOk(), "补产MRP计划生成失败");
        } else {
            require("2".equals(demand.getPlanState()), "补产需求状态与MRP回执不一致");
            supplementProgress.ownedRoots(supplement, supplementProgress.mrpRows(supplement));
        }
        supplement = requireSupplement(command.getSupplementId(), false);
        require("MRP_GENERATED".equals(supplement.getStatus()), "补产MRP未形成完整根计划回执");
        var qualityCase = requireCase(supplement.getCaseId(), false);
        audit(qualityCase, requestId, "SUPPLEMENT_PLAN", qualityCase.getStatus(), qualityCase.getStatus(), payload);
        enrichSupplement(supplement);
        return supplement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnQualitySupplementDemandEntity cancelSupplement(
            PlanQualityLocalReplanDTO.SupplementCancelCommand command) {
        require(command != null && command.getSupplementId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "补产需求取消参数不完整");
        String requestId = command.getRequestId().trim();
        supplementMapper.lockKey("CANCEL:" + requestId);
        PlnQualitySupplementDemandEntity duplicate = supplementMapper.selectOne(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getCancelRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getSupplementId())
                            && Objects.equals(duplicate.getCancelReason(), command.getReason().trim()),
                    "补产取消幂等号已被不同请求使用");
            enrichSupplement(duplicate);
            return duplicate;
        }
        PlnQualitySupplementDemandEntity supplement = requireSupplement(command.getSupplementId(), true);
        requireFactory(supplement.getFactoryCode());
        require("SUBMITTED".equals(supplement.getStatus()),
                "仅待复核申请允许取消；已生成MRP的补产须走下游计划撤回与预留释放流程");
        MomUser user = currentUser();
        require(Objects.equals(supplement.getApplicantCode(), userCode(user)), "仅申请人可以取消待复核补产需求");
        supplement.setStatus("CANCELLED").setCancelRequestId(requestId).setCancelReason(command.getReason().trim())
                .setCancelledBy(userCode(user)).setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
        require(supplementMapper.updateById(supplement) == 1, "补产需求取消失败");
        PlnQualityReplanCaseEntity qualityCase = requireCase(supplement.getCaseId(), false);
        audit(qualityCase, requestId, "SUPPLEMENT_CANCEL", qualityCase.getStatus(), qualityCase.getStatus(),
                "取消补产需求" + supplement.getSupplementNo() + "：" + command.getReason().trim());
        enrichSupplement(supplement);
        return supplement;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanQualityLocalReplanDTO.Scenario assess(PlanQualityLocalReplanDTO.AssessCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "质量局部重排评估参数不完整");
        require(command.getRequestId().trim().length() <= 48, "质量局部重排请求号不能超过48位");
        require(command.getExpectedRecoveryTime() != null && command.getExpectedRecoveryTime().isAfter(LocalDateTime.now()),
                "预计质量恢复时刻必须晚于当前时刻");
        List<String> directCodes = normalizeCodes(command.getAffectedProcessCodes());
        require(!directCodes.isEmpty() || command.getAffectedSourceRouteNodeCodes() != null, "必须明确选择被质量阻断的直接影响工序，禁止系统猜测");
        String requestSnapshot = json(command);
        String requestFingerprint = hash(requestSnapshot);
        scenarioMapper.lock("QUALITY_SCENARIO_ASSESS:" + command.getRequestId().trim());
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, command.getRequestId().trim()));
        if (duplicate != null) {
            require("QUALITY_LOCAL_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "评估幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }
        PlnQualityReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        require("PENDING_ASSESSMENT".equals(entity.getStatus()),
                "仅最新待评估案件允许生成候选；失效或过期案件请先重新评估当前质量事实");
        require(Objects.equals(entity.getQualityEvidenceFingerprint(), command.getExpectedQualityEvidenceFingerprint()),
                "质量数量证据已变化，请刷新后重新评估");
        require(nz(entity.getPendingDispositionQuantity()).add(nz(entity.getOpenReworkQuantity())).signum() > 0,
                "当前偏差仅剩不可逆报废，必须先走补产需求闭环，不能用移动原计划掩盖数量缺口");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        ensureNoOtherLiveProposal(issue.getId());
        RiskMetrics currentMetrics = calculateRisk(issue);
        require(Objects.equals(entity.getQualityEvidenceFingerprint(), currentEvidenceFingerprint(issue, currentMetrics)),
                "质量累计数量或计划状态已变化，请刷新案件");
        PlnQualityReplanPolicyEntity policy = requirePolicy(entity.getPolicyId(), false);
        require("ACTIVE".equals(policy.getStatus()) && Objects.equals(policy.getPolicyFingerprint(), entity.getPolicyFingerprint())
                && isEffective(policy, LocalDateTime.now()), "触发策略已失效或发生变化，必须重新评估阈值");
        LocalDateTime freezeUntil = LocalDateTime.now().plusMinutes(policy.getFreezeMinutes());
        require(command.getExpectedRecoveryTime().isAfter(freezeUntil), "预计恢复时刻仍在冻结区内，应由现场处置而不是移动排程");
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        Map<String, PlnSchedulingOperationEntity> originalByCode = uniqueByProcess(originals);
        directCodes = PlnFrozenRouteNodeIdentity.selection(originals, directCodes, command.getAffectedSourceRouteNodeCodes());
        require(originalByCode.keySet().containsAll(directCodes), "直接影响工序与正式APS排程不一致");
        Set<String> impactedCodes = downstreamClosure(originals, directCodes);
        for (String code : impactedCodes) {
            PlnSchedulingOperationEntity operation = originalByCode.get(code);
            require(operation != null && operation.getScheduledStartTime() != null
                            && operation.getScheduledStartTime().isAfter(freezeUntil),
                    "工序[" + code + "]已进入冻结区，禁止自动移动");
        }
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        validateMesMovable(mesBefore, impactedCodes);
        PlnSchedulingTaskEntity originalTask = taskMapper.selectById(issue.getLockTaskId());
        require(originalTask != null && "official".equalsIgnoreCase(originalTask.getMode()), "原正式排产任务不存在");
        String originalFp = scheduleFingerprint(originals);
        MomUser user = currentUser();
        LocalDateTime now = LocalDateTime.now();
        String sourceFingerprint = hash(entity.getQualityEvidenceFingerprint() + '|' + policy.getPolicyFingerprint()
                + '|' + originalFp + '|' + mesBefore.getExecutionFingerprint());
        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("QRS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(command.getRequestId().trim());
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("QUALITY_LOCAL_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(command.getExpectedRecoveryTime().toLocalDate());
        header.setPlanNum(1);
        header.setSourceIssueIdsSnapshot(json(List.of(issue.getId())));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode(policy.getPolicyCode());
        header.setRuleVersion(policy.getPolicyVersion());
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(entity.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "质量异常多候选情景保存失败");

        PlnQualityReplanCaseEntity sourceTemplate = new PlnQualityReplanCaseEntity();
        BeanUtils.copyProperties(entity, sourceTemplate);
        List<QualityCandidateProfile> profiles = List.of(
                new QualityCandidateProfile("RECOVERY", "最早恢复", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new QualityCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new QualityCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<QualityCandidateBuild> builds = new ArrayList<>();
        for (int index = 0; index < profiles.size(); index++) {
            builds.add(buildQualityCandidate(header, profiles.get(index), command, requestSnapshot,
                    entity, sourceTemplate, index == 0, issue, originalTask, originals, directCodes,
                    impactedCodes, mesBefore, freezeUntil, originalFp, policy, user, now));
        }
        rankQualityCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "质量异常多候选情景评估状态保存失败");
        return scenario(header.getId());
    }

    private QualityCandidateBuild buildQualityCandidate(PlnSchedulingScenarioEntity header,
                                                        QualityCandidateProfile profile,
                                                        PlanQualityLocalReplanDTO.AssessCommand command,
                                                        String requestSnapshot,
                                                        PlnQualityReplanCaseEntity root,
                                                        PlnQualityReplanCaseEntity sourceTemplate,
                                                        boolean useRoot,
                                                        PlnIssueEntity issue,
                                                        PlnSchedulingTaskEntity originalTask,
                                                        List<PlnSchedulingOperationEntity> originals,
                                                        List<String> directCodes,
                                                        Set<String> impactedCodes,
                                                        MesApsLocalScheduleDTO.StateBundle mesBefore,
                                                        LocalDateTime freezeUntil,
                                                        String originalFp,
                                                        PlnQualityReplanPolicyEntity policy,
                                                        MomUser user,
                                                        LocalDateTime now) {
        PlnSchedulingTaskEntity proposalTask = new PlnSchedulingTaskEntity();
        BeanUtils.copyProperties(originalTask, proposalTask, "id", "createTime", "updateTime", "createBy", "updateBy");
        proposalTask.setId(null);
        proposalTask.setTaskNo("QRP-" + IdUtil.getSnowflakeNextIdStr());
        proposalTask.setTriggerType("QUALITY_LOCAL_REPLAN");
        proposalTask.setMode("simulate");
        proposalTask.setTaskStatus("EVALUATING");
        proposalTask.setScheduleDate(command.getExpectedRecoveryTime().toLocalDate());
        proposalTask.setPlanCount(1);
        proposalTask.setScenarioId(header.getId());
        proposalTask.setCandidateCode(profile.code());
        proposalTask.setCandidateName(profile.name());
        proposalTask.setSequenceStrategy(profile.sequenceStrategy());
        proposalTask.setCrossPriorityWindowDays(0);
        proposalTask.setObjectiveWeightsSnapshot(json(Map.of("localReplanStrategy", profile.solverStrategy(),
                "objective", profile.name(), "hardConstraintsRelaxed", false,
                "qualityEvidenceFingerprint", root.getQualityEvidenceFingerprint())));
        proposalTask.setSelectedCandidate(false);
        proposalTask.setRemark("质量异常情景" + header.getScenarioNo() + "候选[" + profile.name()
                + "]，根案件=" + root.getCaseNo() + "，原任务=" + originalTask.getTaskNo());
        proposalTask.setFactoryCode(root.getFactoryCode());
        require(taskMapper.insert(proposalTask) == 1, "质量局部重排候选任务保存失败");

        PlnIssueEntity previewIssue = new PlnIssueEntity();
        BeanUtils.copyProperties(issue, previewIssue);
        operationSchedulingService.previewLocalReplan(proposalTask.getId(), issue.getLockTaskId(), previewIssue,
                command.getExpectedRecoveryTime(), profile.solverStrategy());
        List<PlnSchedulingOperationEntity> candidates = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, proposalTask.getId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        PlnFrozenRouteNodeIdentity.replacementSet(originals, candidates);
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidates);
        require(candidateByCode.keySet().containsAll(impactedCodes), "候选排程未覆盖全部受质量影响工序");
        for (String code : directCodes)
            require(!candidateByCode.get(code).getScheduledStartTime()
                    .isBefore(command.getExpectedRecoveryTime()), "直接影响工序[" + code + "]候选开工早于质量恢复时刻");
        List<PlnSchedulingOperationEntity> moved = impactedCodes.stream().map(candidateByCode::get)
                .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq)).toList();
        Map<String, PlnSchedulingOperationEntity> movedByCode = uniqueByProcess(moved);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, movedByCode),
                issue.getLockTaskId(), issue.getId());
        LocalDateTime originalCompletion = completion(originals);
        LocalDateTime proposedCompletion = completion(effectiveOperations(originals, movedByCode));
        long delay = Math.max(0, Duration.between(originalCompletion, proposedCompletion).toMinutes());
        long maxShift = maximumShift(originals, candidateByCode, impactedCodes);
        long totalShift = totalShift(originals, candidateByCode, impactedCodes);
        int resourceChanges = resourceChanges(originals, candidateByCode, impactedCodes);
        long changeoverMinutes = moved.stream().map(PlnSchedulingOperationEntity::getChangeoverMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        boolean policyFeasible = delay <= policy.getMaxDelayMinutes();
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
        metrics.put("atRiskQuantity", root.getAtRiskQuantity());
        metrics.put("pendingDispositionQuantity", root.getPendingDispositionQuantity());
        metrics.put("openReworkQuantity", root.getOpenReworkQuantity());
        metrics.put("requiredSupplementQuantity", root.getRequiredSupplementQuantity());
        proposalTask.setCandidateMetricsSnapshot(json(metrics));
        proposalTask.setCandidateFeasible(policyFeasible);
        proposalTask.setCandidateScore(policyFeasible
                ? candidateBaseScore(delay, totalShift, resourceChanges, changeoverMinutes)
                : BigDecimal.ZERO.setScale(2));
        proposalTask.setCandidateRank(1);
        proposalTask.setEvaluatedTime(LocalDateTime.now());
        proposalTask.setTaskStatus("SUCCESS");
        require(taskMapper.updateById(proposalTask) == 1, "质量异常候选指标保存失败");

        String proposalFp = hash(root.getQualityEvidenceFingerprint() + '|' + policy.getPolicyFingerprint() + '|'
                + originalFp + '|' + mesBefore.getExecutionFingerprint() + '|' + scheduleFingerprint(moved));
        PlnQualityReplanCaseEntity candidate = useRoot ? root : new PlnQualityReplanCaseEntity();
        String before = useRoot ? root.getStatus() : null;
        if (!useRoot) {
            BeanUtils.copyProperties(sourceTemplate, candidate,
                    "id", "createTime", "updateTime", "createBy", "updateBy");
            candidate.setId(null);
            candidate.setCaseNo("QRC-" + IdUtil.getSnowflakeNextIdStr()).setSourceCaseId(root.getId());
        } else {
            candidate.setSourceCaseId(null);
            lineMapper.delete(new LambdaQueryWrapper<PlnQualityReplanLineEntity>()
                    .eq(PlnQualityReplanLineEntity::getCaseId, candidate.getId()));
        }
        candidate.setScenarioId(header.getId())
                .setAssessRequestId(command.getRequestId().trim() + ':' + profile.code())
                .setAssessRequestSnapshot(requestSnapshot).setOriginalTaskId(issue.getLockTaskId())
                .setProposalTaskId(proposalTask.getId()).setExpectedRecoveryTime(command.getExpectedRecoveryTime())
                .setAffectedProcessCodes(directCodes.stream().map(code -> uniqueByProcess(originals).get(code).getProcessCode()).distinct().collect(Collectors.joining(","))).setFreezeUntil(freezeUntil)
                .setMaxDelayMinutes(policy.getMaxDelayMinutes()).setDirectAffectedCount(directCodes.size())
                .setMovedOperationCount(moved.size()).setMaximumShiftMinutes(maxShift)
                .setOriginalCompletionTime(originalCompletion).setProposedCompletionTime(proposedCompletion)
                .setDeliveryRisk(deliveryRisk(issue, proposedCompletion)).setMesExecutionFingerprint(mesBefore.getExecutionFingerprint())
                .setOriginalScheduleFingerprint(originalFp).setOriginalScheduleSnapshot(json(originals))
                .setProposedScheduleSnapshot(json(moved)).setProposalFingerprint(proposalFp)
                .setReason(command.getReason().trim()).setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setProposedTime(now).setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS)).setStatus("PROPOSED");
        require(useRoot ? caseMapper.updateById(candidate) == 1 : caseMapper.insert(candidate) == 1,
                "质量局部重排候选案件保存失败");
        saveQualityLines(candidate, originals, candidateByCode, directCodes, impactedCodes, freezeUntil, mesBefore);
        saveProposalDetail(proposalTask, previewIssue, moved);
        audit(candidate, candidate.getAssessRequestId(), "ASSESS_PROPOSE", before, "PROPOSED",
                "候选[" + profile.name() + "]：质量风险" + candidate.getAtRiskQuantity() + "，影响"
                        + directCodes.size() + "道直接工序，联动移动" + moved.size() + "道，延期" + delay
                        + "分钟；报废补产缺口" + candidate.getRequiredSupplementQuantity()
                        + (policyFeasible ? "" : "，超过策略上限，不可选择"));
        return new QualityCandidateBuild(candidate, proposalTask, delay, totalShift, resourceChanges, changeoverMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanQualityLocalReplanDTO.Scenario select(PlanQualityLocalReplanDTO.SelectCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getCandidateCaseId() != null
                        && StrUtil.isNotBlank(command.getExpectedSourceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "质量异常候选选择参数不完整");
        PlnQualityReplanCaseEntity selectedCase = requireCase(command.getCandidateCaseId(), false);
        requireFactory(selectedCase.getFactoryCode());
        require(selectedCase.getScenarioId() != null, "历史单方案不需要候选选择");
        scenarioMapper.lock("QUALITY_SCENARIO_SELECT:" + selectedCase.getScenarioId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getId, selectedCase.getScenarioId()).last("FOR UPDATE"));
        require(header != null && "QUALITY_LOCAL_REPLAN".equals(header.getTriggerType()), "质量异常多候选情景不存在");
        String selectionFingerprint = hash(command.getRequestId().trim() + '|' + command.getCandidateCaseId()
                + '|' + command.getExpectedSourceFingerprint() + '|' + command.getReason().trim());
        if (Set.of("SELECTED", "PUBLISHED").contains(header.getScenarioStatus())) {
            require(Objects.equals(header.getSelectionRequestId(), command.getRequestId().trim())
                            && Objects.equals(header.getSelectionFingerprint(), selectionFingerprint),
                    "该质量情景已完成候选选择，不允许覆盖审计结论");
            return scenario(header.getId());
        }
        require("EVALUATED".equals(header.getScenarioStatus()), "当前质量情景状态不允许选择候选");
        require(header.getExpiresTime() != null && header.getExpiresTime().isAfter(LocalDateTime.now()),
                "质量异常多候选情景已过期，必须重新评估");
        require(Objects.equals(header.getSourceFingerprint(), command.getExpectedSourceFingerprint()), "情景来源指纹不一致");
        require("PROPOSED".equals(selectedCase.getStatus()), "候选不属于当前可选择情景");
        List<PlnQualityReplanCaseEntity> cases = caseMapper.selectList(
                new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                        .eq(PlnQualityReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
        require(cases.size() == header.getCandidateCount(), "质量情景候选数量不完整");
        List<PlnSchedulingTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, header.getId()));
        PlnSchedulingTaskEntity selectedTask = tasks.stream()
                .filter(item -> Objects.equals(item.getId(), selectedCase.getProposalTaskId())).findFirst()
                .orElseThrow(() -> new CheckedException("候选任务不属于当前质量情景"));
        require(Boolean.TRUE.equals(selectedTask.getCandidateFeasible()), "存在硬阻断的候选不能进入发布链");
        cases.forEach(item -> {
            item.setStatus(Objects.equals(item.getId(), selectedCase.getId()) ? "SELECTED" : "SUPERSEDED");
            require(caseMapper.updateById(item) == 1, "质量异常候选选择状态保存失败");
        });
        tasks.forEach(item -> {
            boolean selected = Objects.equals(item.getId(), selectedTask.getId());
            item.setSelectedCandidate(selected);
            if (!selected) item.setTaskStatus("SUPERSEDED");
            require(taskMapper.updateById(item) == 1, "质量异常候选任务选择状态保存失败");
        });
        header.setSelectedTaskId(selectedTask.getId());
        header.setSelectionRequestId(command.getRequestId().trim());
        header.setSelectionFingerprint(selectionFingerprint);
        header.setSelectionReason(command.getReason().trim());
        header.setSelectedBy(userCode(currentUser()));
        header.setSelectedTime(LocalDateTime.now());
        header.setScenarioStatus("SELECTED");
        require(scenarioMapper.updateById(header) == 1, "质量异常候选选择审计保存失败");
        audit(selectedCase, command.getRequestId(), "SELECT", "PROPOSED", "SELECTED",
                "从三个有限产能候选中选择[" + selectedTask.getCandidateName() + "]：" + command.getReason().trim());
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanQualityLocalReplanDTO.PublishReceipt publish(PlanQualityLocalReplanDTO.PublishCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedQualityEvidenceFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "质量局部重排发布参数不完整");
        caseMapper.lockKey("PUBLISH:" + command.getRequestId().trim());
        PlnQualityReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getPublishRequestId, command.getRequestId().trim()));
        if (duplicate != null) return receipt(duplicate, duplicate.getMesPublishedFingerprint());
        PlnQualityReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        String beforeStatus = entity.getStatus();
        if (entity.getScenarioId() == null) {
            require("PROPOSED".equals(entity.getStatus()), "仅待发布质量重排方案可以发布");
        } else {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "SELECTED".equals(header.getScenarioStatus())
                            && Objects.equals(header.getSelectedTaskId(), entity.getProposalTaskId())
                            && "SELECTED".equals(entity.getStatus()),
                    "质量异常多候选情景必须先选择且只能发布被选方案");
        }
        require(entity.getExpiresTime() != null && entity.getExpiresTime().isAfter(LocalDateTime.now()), "方案已过期，必须重新评估");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getQualityEvidenceFingerprint(), command.getExpectedQualityEvidenceFingerprint())
                        && Objects.equals(entity.getMesExecutionFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "审批冻结的方案、质量或MES指纹不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getApplicantCode(), userCode(user)), "A3质量局部重排禁止申请人自批并发布");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        RiskMetrics metrics = calculateRisk(issue);
        require(Objects.equals(entity.getQualityEvidenceFingerprint(), currentEvidenceFingerprint(issue, metrics)),
                "质量累计数量或计划状态已变化，旧方案失效");
        PlnQualityReplanPolicyEntity policy = requirePolicy(entity.getPolicyId(), false);
        require("ACTIVE".equals(policy.getStatus()) && Objects.equals(policy.getPolicyFingerprint(), entity.getPolicyFingerprint())
                && threshold(policy, metrics).triggered, "质量策略已失效、变化或当前偏差已低于阈值");
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        require(Objects.equals(entity.getOriginalScheduleFingerprint(), scheduleFingerprint(originals)), "正式排程已变化，旧方案失效");
        List<PlnQualityReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> candidates = proposalOperations(entity, lines);
        String currentProposalFp = hash(entity.getQualityEvidenceFingerprint() + '|' + entity.getPolicyFingerprint() + '|'
                + entity.getOriginalScheduleFingerprint() + '|' + entity.getMesExecutionFingerprint() + '|'
                + scheduleFingerprint(candidates));
        require(Objects.equals(entity.getProposalFingerprint(), currentProposalFp), "候选工序快照已变化，旧审批失效");
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidates);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, candidateByCode),
                entity.getOriginalTaskId(), entity.getIssueId());
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        require(Objects.equals(entity.getMesExecutionFingerprint(), mesBefore.getExecutionFingerprint()), "MES执行或派工事实已变化，旧方案失效");
        Set<String> movedCodes = candidates.stream().map(PlnFrozenRouteNodeIdentity::key).collect(Collectors.toSet());
        validateMesMovable(mesBefore, movedCodes);
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(command.getRequestId() + ":MES", entity,
                mesBefore.getExecutionFingerprint(), candidates);
        List<PlnSchedulingOperationEntity> compensation = originals.stream()
                .filter(item -> movedCodes.contains(PlnFrozenRouteNodeIdentity.key(item))).map(this::detachedOperation).toList();
        try {
            for (PlnSchedulingOperationEntity original : originals) {
                PlnSchedulingOperationEntity candidate = candidateByCode.get(PlnFrozenRouteNodeIdentity.key(original));
                if (candidate != null) {
                    copySchedule(candidate, original);
                    require(schedulingOperationMapper.updateById(original) == 1, "正式工序排程写回失败");
                }
            }
            updateFormalSummary(issue, originals, "质量偏差局部重排已复核发布");
            entity.setStatus("PUBLISHED").setPublishRequestId(command.getRequestId().trim())
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user)).setPublishedTime(LocalDateTime.now())
                    .setMesPublishedFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "质量局部重排发布状态更新失败");
            if (entity.getScenarioId() != null) {
                PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
                header.setScenarioStatus("PUBLISHED");
                require(scenarioMapper.updateById(header) == 1, "质量异常多候选情景发布状态更新失败");
            }
            audit(entity, command.getRequestId(), "PUBLISH", beforeStatus, "PUBLISHED",
                    "已重排" + candidates.size() + "道未执行工序并同步MES；报废补产缺口仍为"
                            + entity.getRequiredSupplementQuantity());
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(command.getRequestId() + ":MES-COMPENSATE", entity,
                    mesReceipt.getAfterExecutionFingerprint(), compensation, "发布失败恢复MES原排程", ex);
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(PlanQualityLocalReplanDTO.CancelCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "取消参数不完整");
        caseMapper.lockKey("CANCEL:" + command.getRequestId().trim());
        PlnQualityReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getCancelRequestId, command.getRequestId().trim()));
        if (duplicate != null) return true;
        PlnQualityReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        if (entity.getScenarioId() != null) {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "EVALUATED".equals(header.getScenarioStatus()),
                    "仅尚未选择的质量异常情景可以整体取消");
            MomUser user = currentUser();
            List<PlnQualityReplanCaseEntity> cases = caseMapper.selectList(
                    new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                            .eq(PlnQualityReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
            for (PlnQualityReplanCaseEntity item : cases) {
                String childRequestId = Objects.equals(item.getId(), entity.getId())
                        ? command.getRequestId().trim() : command.getRequestId().trim() + ':' + item.getId();
                String before = item.getStatus();
                item.setStatus("CANCELLED").setCancelRequestId(childRequestId)
                        .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                        .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
                require(caseMapper.updateById(item) == 1, "质量异常多候选情景取消失败");
                audit(item, childRequestId, "CANCEL", before, "CANCELLED", command.getReason().trim());
            }
            header.setScenarioStatus("CANCELLED");
            require(scenarioMapper.updateById(header) == 1, "质量异常情景取消状态保存失败");
            return true;
        }
        require(Set.of("PENDING_ASSESSMENT", "SUPPLEMENT_REQUIRED", "PROPOSED").contains(entity.getStatus()),
                "仅未发布案件可以取消");
        String before = entity.getStatus();
        MomUser user = currentUser();
        entity.setStatus("CANCELLED").setCancelRequestId(command.getRequestId().trim())
                .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
        require(caseMapper.updateById(entity) == 1, "质量偏差案件取消失败");
        audit(entity, command.getRequestId(), "CANCEL", before, "CANCELLED", command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanQualityLocalReplanDTO.PublishReceipt rollback(PlanQualityLocalReplanDTO.RollbackCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()) && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "回退参数不完整");
        caseMapper.lockKey("ROLLBACK:" + command.getRequestId().trim());
        PlnQualityReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getRollbackRequestId, command.getRequestId().trim()));
        if (duplicate != null) return receipt(duplicate, duplicate.getMesRollbackFingerprint());
        PlnQualityReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        require("PUBLISHED".equals(entity.getStatus()), "仅已发布且未回退方案允许回退");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "回退指纹与已发布方案不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getPublisherCode(), userCode(user)), "发布人与回退复核人必须分离");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        List<PlnQualityReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> current = lockedOperations(issue);
        Map<String, PlnSchedulingOperationEntity> currentByCode = uniqueByProcess(current);
        List<PlnSchedulingOperationEntity> rollbackOperations = new ArrayList<>();
        for (PlnQualityReplanLineEntity line : lines) {
            if (!"MOVE".equals(line.getActionType())) continue;
            PlnSchedulingOperationEntity now = currentByCode.get(PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), currentByCode));
            PlnSchedulingOperationEntity proposed = readOperation(line.getProposedOperationSnapshot());
            require(now != null && sameSchedule(now, proposed), "工序[" + line.getProcessCode() + "]发布后已再次变更，禁止覆盖回退");
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
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(command.getRequestId() + ":MES", entity,
                mesBefore.getExecutionFingerprint(), rollbackOperations);
        List<PlnSchedulingOperationEntity> compensation = lines.stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(line -> readOperation(line.getProposedOperationSnapshot())).toList();
        try {
            for (PlnSchedulingOperationEntity formal : current) {
                PlnSchedulingOperationEntity old = rollbackByCode.get(PlnFrozenRouteNodeIdentity.key(formal));
                if (old != null) {
                    copySchedule(old, formal);
                    require(schedulingOperationMapper.updateById(formal) == 1, "正式排程回退失败");
                }
            }
            updateFormalSummary(issue, current, "质量偏差局部重排已受控回退");
            entity.setStatus("ROLLED_BACK").setRollbackRequestId(command.getRequestId().trim())
                    .setRollbackReason(command.getReason().trim()).setRolledBackBy(userCode(user))
                    .setRolledBackByName(userName(user)).setRolledBackTime(LocalDateTime.now())
                    .setMesRollbackFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "质量局部重排回退状态更新失败");
            audit(entity, command.getRequestId(), "ROLLBACK", "PUBLISHED", "ROLLED_BACK", command.getReason());
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(command.getRequestId() + ":MES-COMPENSATE", entity,
                    mesReceipt.getAfterExecutionFingerprint(), compensation, "回退失败恢复MES已发布排程", ex);
            throw ex;
        }
    }

    private boolean isQualityReceipt(PlnIssueCallbackReceiptEntity receipt) {
        return Stream.of(receipt.getUnqualifiedQuantity(), receipt.getQualityIsolatedQuantity(),
                        receipt.getQualityReleasedQuantity(), receipt.getQualityRejectedQuantity(), receipt.getScrapQuantity(),
                        receipt.getReworkQuantity(), receipt.getReworkCompletedQuantity(), receipt.getReworkQualifiedQuantity(),
                        receipt.getReworkRejectedQuantity())
                .anyMatch(value -> value != null && value.signum() != 0);
    }

    private RiskMetrics calculateRisk(PlnIssueEntity issue) {
        BigDecimal unqualified = nz(issue.getUnqualifiedNum());
        BigDecimal isolated = nz(issue.getQualityIsolatedNum());
        BigDecimal released = nz(issue.getQualityReleasedNum());
        BigDecimal rejected = nz(issue.getQualityRejectedNum());
        BigDecimal scrap = nz(issue.getScrapNum());
        BigDecimal rework = nz(issue.getReworkNum());
        BigDecimal reworkQualified = nz(issue.getReworkQualifiedNum());
        BigDecimal reworkRejected = nz(issue.getReworkRejectedNum());
        // 直接不合格和QMS隔离可能描述同一批产出，取较大值既覆盖QMS独立发现，也避免重复计险。
        BigDecimal pending = positive(unqualified.max(isolated).add(rejected).add(reworkRejected)
                .subtract(released).subtract(rework).subtract(scrap));
        BigDecimal openRework = positive(rework.subtract(reworkQualified).subtract(reworkRejected));
        BigDecimal atRisk = scrap.add(pending).add(openRework);
        BigDecimal planned = nz(issue.getNum());
        BigDecimal rate = planned.signum() == 0 ? BigDecimal.ZERO
                : atRisk.multiply(BigDecimal.valueOf(100)).divide(planned, 6, RoundingMode.HALF_UP);
        return new RiskMetrics(pending, openRework, scrap, atRisk, rate);
    }

    private ThresholdResult threshold(PlnQualityReplanPolicyEntity policy, RiskMetrics metrics) {
        List<Boolean> checks = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        addThreshold(checks, hits, policy.getMinAtRiskQuantity(), metrics.atRisk, "风险数量");
        addThreshold(checks, hits, policy.getMinDeviationRate(), metrics.rate, "偏差率(%)");
        addThreshold(checks, hits, policy.getMinScrapQuantity(), metrics.scrap, "报废数量");
        boolean triggered = "ALL".equals(policy.getThresholdMode())
                ? !checks.isEmpty() && checks.stream().allMatch(Boolean::booleanValue)
                : checks.stream().anyMatch(Boolean::booleanValue);
        return new ThresholdResult(triggered, triggered ? "命中" + String.join("、", hits)
                : "未达到策略阈值；当前风险数量=" + metrics.atRisk + "，偏差率=" + metrics.rate + "%"
                + "，报废数量=" + metrics.scrap);
    }

    private void addThreshold(List<Boolean> checks, List<String> hits, BigDecimal threshold,
                              BigDecimal actual, String label) {
        if (threshold == null || threshold.signum() <= 0) return;
        boolean hit = actual.compareTo(threshold) >= 0;
        checks.add(hit);
        if (hit) hits.add(label + actual + ">=" + threshold);
    }

    private PlnQualityReplanPolicyEntity resolvePolicy(PlnIssueEntity issue, LocalDateTime at) {
        LocalDateTime time = Optional.ofNullable(at).orElse(LocalDateTime.now());
        return policyMapper.selectList(new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                        .eq(PlnQualityReplanPolicyEntity::getFactoryCode, issue.getFactoryCode())
                        .eq(PlnQualityReplanPolicyEntity::getStatus, "ACTIVE")
                        .and(w -> w.isNull(PlnQualityReplanPolicyEntity::getEffectiveFrom)
                                .or().le(PlnQualityReplanPolicyEntity::getEffectiveFrom, time))
                        .and(w -> w.isNull(PlnQualityReplanPolicyEntity::getEffectiveTo)
                                .or().gt(PlnQualityReplanPolicyEntity::getEffectiveTo, time)))
                .stream().filter(item -> StrUtil.isBlank(item.getProductCode())
                        || Objects.equals(item.getProductCode(), issue.getProductCode()))
                .sorted(Comparator.comparing((PlnQualityReplanPolicyEntity item) ->
                                Objects.equals(item.getProductCode(), issue.getProductCode())).reversed()
                        .thenComparing(PlnQualityReplanPolicyEntity::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlnQualityReplanPolicyEntity::getPolicyVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst().orElse(null);
    }

    private void applyMetrics(PlnQualityReplanCaseEntity entity,
                              PlnQualityDeviationEvaluationEntity evaluation,
                              PlnQualityReplanPolicyEntity policy, RiskMetrics metrics, String evidenceFingerprint) {
        entity.setLatestEvaluationId(evaluation.getId()).setPolicyId(policy.getId())
                .setPolicyCode(policy.getPolicyCode()).setPolicyVersion(policy.getPolicyVersion())
                .setPolicyFingerprint(policy.getPolicyFingerprint()).setFormulaVersion(FORMULA_VERSION)
                .setPlannedQuantity(evaluation.getPlannedQuantity()).setPendingDispositionQuantity(metrics.pendingDisposition)
                .setOpenReworkQuantity(metrics.openRework).setIrreversibleLossQuantity(metrics.scrap)
                .setAtRiskQuantity(metrics.atRisk).setDeviationRate(metrics.rate)
                .setRequiredSupplementQuantity(metrics.scrap).setQualityMetricsSnapshot(json(evaluation))
                .setQualityEvidenceFingerprint(evidenceFingerprint).setLatestTriggeredTime(LocalDateTime.now());
    }

    /**
     * 当前事实指纹不包含回执ID，供评估/发布时重采。
     */
    private String currentEvidenceFingerprint(PlnIssueEntity issue, RiskMetrics metrics) {
        return hash(String.join("|", Objects.toString(issue.getId(), ""), Objects.toString(issue.getIssueNo(), ""),
                Objects.toString(issue.getStatus(), ""), Objects.toString(issue.getLockTaskId(), ""),
                Objects.toString(issue.getNum(), ""), Objects.toString(issue.getFinishNum(), ""),
                Objects.toString(issue.getUnqualifiedNum(), ""), Objects.toString(issue.getQualityIsolatedNum(), ""),
                Objects.toString(issue.getQualityReleasedNum(), ""), Objects.toString(issue.getQualityRejectedNum(), ""),
                Objects.toString(issue.getScrapNum(), ""), Objects.toString(issue.getReworkNum(), ""),
                Objects.toString(issue.getReworkCompletedNum(), ""), Objects.toString(issue.getReworkQualifiedNum(), ""),
                Objects.toString(issue.getReworkRejectedNum(), ""), metrics.pendingDisposition.toPlainString(),
                metrics.openRework.toPlainString(), metrics.atRisk.toPlainString(), metrics.rate.toPlainString(), FORMULA_VERSION));
    }

    private void saveQualityLines(PlnQualityReplanCaseEntity entity,
                                  List<PlnSchedulingOperationEntity> originals,
                                  Map<String, PlnSchedulingOperationEntity> candidates,
                                  List<String> directCodes, Set<String> affectedCodes,
                                  LocalDateTime freezeUntil,
                                  MesApsLocalScheduleDTO.StateBundle mesBundle) {
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
            PlnQualityReplanLineEntity line = new PlnQualityReplanLineEntity()
                    .setCaseId(entity.getId()).setOriginalOperationId(original.getId())
                    .setProposalOperationId(affected ? candidate.getId() : null).setOperationSeq(original.getOperationSeq())
                    .setSourceRouteNodeCode(original.getSourceRouteNodeCode()).setProcessCode(original.getProcessCode()).setProcessName(original.getProcessName())
                    .setDirectAffected(direct).setDownstreamAffected(affected && !direct).setActionType(action)
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
                    .setShiftMinutes(affected ? Math.abs(Duration.between(original.getScheduledStartTime(),
                            candidate.getScheduledStartTime()).toMinutes()) : 0)
                    .setOriginalOperationSnapshot(json(original))
                    .setProposedOperationSnapshot(affected ? json(candidate) : null)
                    .setFactoryCode(entity.getFactoryCode());
            require(lineMapper.insert(line) == 1, "质量局部重排工序差异保存失败");
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
        require(taskDetailMapper.insert(detail) == 1, "质量候选排程任务明细保存失败");
    }

    private void updateFormalSummary(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> operations, String summary) {
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

    private MesApsLocalScheduleDTO.ApplyReceipt applyMes(String requestId, PlnQualityReplanCaseEntity entity,
                                                         String expectedFingerprint,
                                                         List<PlnSchedulingOperationEntity> operations) {
        MesApsLocalScheduleDTO.ApplyCommand command = new MesApsLocalScheduleDTO.ApplyCommand();
        command.setRequestId(requestId).setPlanNo(entity.getIssueNo()).setFactoryCode(entity.getFactoryCode())
                .setExpectedExecutionFingerprint(expectedFingerprint).setReplanCaseId(entity.getId())
                .setOperations(operations.stream().map(this::toMesUpdate).toList());
        R<MesApsLocalScheduleDTO.ApplyReceipt> response = remoteMesTaskService.applyApsLocalSchedule(command);
        require(response != null && response.isOk() && response.getData() != null, "MES未确认质量局部重排同步成功");
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

    private void compensateMes(String requestId, PlnQualityReplanCaseEntity entity, String expectedFingerprint,
                               List<PlnSchedulingOperationEntity> operations, String action, RuntimeException originalFailure) {
        try {
            applyMes(requestId, entity, expectedFingerprint, operations);
        } catch (RuntimeException compensationFailure) {
            throw new CheckedException(action + "失败，PLAN事务将回滚但MES可能需要人工对账；原错误："
                    + originalFailure.getMessage() + "；补偿错误：" + compensationFailure.getMessage());
        }
    }

    private List<PlnSchedulingOperationEntity> proposalOperations(PlnQualityReplanCaseEntity entity,
                                                                  List<PlnQualityReplanLineEntity> lines) {
        Set<Long> ids = lines.stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(PlnQualityReplanLineEntity::getProposalOperationId).filter(Objects::nonNull).collect(Collectors.toSet());
        require(!ids.isEmpty(), "方案没有可发布工序");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().in(PlnSchedulingOperationEntity::getId, ids)
                        .eq(PlnSchedulingOperationEntity::getTaskId, entity.getProposalTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, entity.getIssueId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        require(rows.size() == ids.size(), "质量候选排程工序已缺失");
        Map<Long, PlnQualityReplanLineEntity> byId = lines.stream().filter(line -> line.getProposalOperationId() != null)
                .collect(Collectors.toMap(PlnQualityReplanLineEntity::getProposalOperationId, Function.identity()));
        for (PlnSchedulingOperationEntity row : rows) {
            PlnSchedulingOperationEntity frozen = readOperation(byId.get(row.getId()).getProposedOperationSnapshot());
            var line = byId.get(row.getId());
            var original = readOperation(line.getOriginalOperationSnapshot());
            require(Objects.equals(original.getId(), line.getOriginalOperationId()), "重排明细与原工序主键不一致");
            PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), uniqueByProcess(List.of(original)));
            PlnFrozenRouteNodeIdentity.sameIdentity(row, original);
            PlnFrozenRouteNodeIdentity.sameIdentity(row, frozen);
            require(Objects.equals(operationFingerprint(row), operationFingerprint(frozen)),
                    "质量候选工序[" + row.getProcessCode() + "]已被修改");
        }
        return rows;
    }

    private List<PlnSchedulingOperationEntity> lockedOperations(PlnIssueEntity issue) {
        require(Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null, "生产计划尚未形成正式锁定排程");
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

    private MesApsLocalScheduleDTO.StateBundle mesStates(PlnIssueEntity issue) {
        R<MesApsLocalScheduleDTO.StateBundle> response = remoteMesTaskService.apsOperationStates(
                issue.getIssueNo(), issue.getFactoryCode());
        require(response != null && response.isOk() && response.getData() != null
                && StrUtil.isNotBlank(response.getData().getExecutionFingerprint()), "MES执行事实不可用，禁止质量局部重排");
        return response.getData();
    }

    private void validateMesMovable(MesApsLocalScheduleDTO.StateBundle bundle, Collection<String> processCodes) {
        if (!Boolean.TRUE.equals(bundle.getMesTaskPresent())) return;
        Map<String, MesApsLocalScheduleDTO.OperationState> byCode = PlnFrozenRouteNodeIdentity.states(bundle);
        for (String code : processCodes) {
            MesApsLocalScheduleDTO.OperationState state = PlnFrozenRouteNodeIdentity.state(byCode, code);
            require(state != null, "MES工艺快照缺少工序[" + code + "]");
            require(Boolean.TRUE.equals(state.getMovable()), "MES工序[" + code + "]不可移动：" + state.getBlockedReason());
        }
    }

    private void ensureNoOtherLiveProposal(Long issueId) {
        LocalDateTime now = LocalDateTime.now();
        long shortage = shortageCaseMapper.selectCount(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getIssueId, issueId)
                .in(PlnShortageReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnShortageReplanCaseEntity::getExpiresTime, now));
        long resource = resourceCaseMapper.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getIssueId, issueId)
                .in(PlnResourceReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnResourceReplanCaseEntity::getExpiresTime, now));
        long insertion = insertionCaseMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getInsertionIssueId, issueId)
                .in(PlnInsertionReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnInsertionReplanCaseEntity::getExpiresTime, now));
        long displaced = insertionIssueMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                .eq(PlnInsertionReplanIssueEntity::getIssueId, issueId)
                .inSql(PlnInsertionReplanIssueEntity::getCaseId,
                        "SELECT id FROM pln_insertion_replan_case WHERE del_flag='0' AND status IN ('PROPOSED','SELECTED')"
                                + " AND expires_time>CURRENT_TIMESTAMP"));
        require(shortage + resource + insertion + displaced == 0,
                "该生产计划已有待发布的缺料、资源或插单重排候选，请先完成、取消或等待其过期");
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

    private String policyFingerprint(PlnQualityReplanPolicyEntity entity) {
        return hash(String.join("|", entity.getPolicyCode(), Objects.toString(entity.getProductCode(), "*"),
                entity.getThresholdMode(), Objects.toString(entity.getMinAtRiskQuantity(), ""),
                Objects.toString(entity.getMinDeviationRate(), ""), Objects.toString(entity.getMinScrapQuantity(), ""),
                Objects.toString(entity.getFreezeMinutes(), ""), Objects.toString(entity.getMaxDelayMinutes(), ""),
                Objects.toString(entity.getPriority(), ""), Objects.toString(entity.getPolicyVersion(), ""),
                Objects.toString(entity.getEffectiveFrom(), ""), Objects.toString(entity.getEffectiveTo(), "")));
    }

    private void invalidateSubmittedSupplements(PlnIssueEntity issue,
                                                PlnQualityDeviationEvaluationEntity evaluation) {
        String current = supplementEvidenceFingerprint(issue);
        List<PlnQualitySupplementDemandEntity> stale = supplementMapper.selectList(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getSourceIssueId, issue.getId())
                        .eq(PlnQualitySupplementDemandEntity::getStatus, "SUBMITTED")
                        .ne(PlnQualitySupplementDemandEntity::getQualityEvidenceFingerprint, current)
                        .orderByAsc(PlnQualitySupplementDemandEntity::getId).last("FOR UPDATE"));
        for (PlnQualitySupplementDemandEntity supplement : stale) {
            supplement.setStatus("STALE");
            require(supplementMapper.updateById(supplement) == 1, "旧补产申请失效状态保存失败");
            PlnQualityReplanCaseEntity qualityCase = requireCase(supplement.getCaseId(), false);
            String requestId = "QSUP-STALE-" + supplement.getId() + '-'
                    + hash(evaluation.getEvaluationRequestId()).substring(0, 16);
            audit(qualityCase, requestId, "SUPPLEMENT_STALE", qualityCase.getStatus(), qualityCase.getStatus(),
                    "已审批净报废量发生变化，补产申请" + supplement.getSupplementNo() + "自动失效");
        }
    }

    private void syncSupplementFulfillment(PlnIssueCallbackReceiptEntity receipt, PlnIssueEntity issue) {
        List<PlnQualitySupplementDemandEntity> rows = supplementMapper.selectList(
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .and(q -> q.eq(PlnQualitySupplementDemandEntity::getSupplementIssueId, issue.getId())
                                .or(staged -> staged.eq(PlnQualitySupplementDemandEntity::getPlanningModelVersion, 2)
                                        .eq(PlnQualitySupplementDemandEntity::getProductDemandNo, issue.getSourceDemandNo())
                                        .eq(PlnQualitySupplementDemandEntity::getProductCode, issue.getProductCode())))
                        .eq(PlnQualitySupplementDemandEntity::getStatus, "MRP_GENERATED")
                        .orderByAsc(PlnQualitySupplementDemandEntity::getId).last("FOR UPDATE"));
        for (PlnQualitySupplementDemandEntity supplement : rows) {
            BigDecimal completed = nz(issue.getFinishNum());
            if (Integer.valueOf(2).equals(supplement.getPlanningModelVersion())) {
                var mrp = supplementProgress.mrpRows(supplement);
                var roots = supplementProgress.ownedRoots(supplement, mrp);
                if (roots.stream().noneMatch(root -> Objects.equals(root.getId(), issue.getId()))) continue;
                completed = supplementProgress.completed(roots);
            }
            if (completed.compareTo(nz(supplement.getRequestQuantity())) < 0) continue;
            supplement.setStatus("FULFILLED").setFulfilledTime(LocalDateTime.now());
            require(supplementMapper.updateById(supplement) == 1, "补产完成状态写回失败");
            PlnQualityReplanCaseEntity qualityCase = requireCase(supplement.getCaseId(), false);
            String requestId = "QSUP-FULFILL-" + supplement.getId() + '-'
                    + hash(receipt.getRequestId()).substring(0, 16);
            audit(qualityCase, requestId, "SUPPLEMENT_FULFILL", qualityCase.getStatus(), qualityCase.getStatus(),
                    "补产计划" + issue.getIssueNo() + "累计完成" + plain(completed)
                            + "，已覆盖补产需求" + plain(supplement.getRequestQuantity()));
        }
    }

    private void enrichCaseSupplementCoverage(List<? extends PlnQualityReplanCaseEntity> cases) {
        Set<Long> issueIds = cases.stream().map(PlnQualityReplanCaseEntity::getIssueId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (issueIds.isEmpty()) return;
        Map<Long, List<PlnQualitySupplementDemandEntity>> grouped = supplementMapper.sourceSupplementsForIssues(
                        TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), issueIds)
                .stream().collect(Collectors.groupingBy(PlnQualitySupplementDemandEntity::getSourceIssueId));
        for (PlnQualityReplanCaseEntity item : cases) {
            SupplementTotals totals = supplementTotals(grouped.getOrDefault(item.getIssueId(), List.of()), null);
            BigDecimal required = nz(item.getRequiredSupplementQuantity());
            item.setCoveredSupplementQuantity(totals.covered);
            item.setPendingSupplementQuantity(totals.pending);
            item.setOutstandingSupplementQuantity(positive(required.subtract(totals.covered).subtract(totals.pending)));
            item.setOverCoveredSupplementQuantity(positive(totals.covered.subtract(required)));
        }
    }

    private void enrichSupplements(List<PlnQualitySupplementDemandEntity> supplements) {
        if (supplements == null || supplements.isEmpty()) return;
        Set<String> demandNos = supplements.stream().map(PlnQualitySupplementDemandEntity::getProductDemandNo)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        Map<String, List<PlanMatMrpEntity>> mrpByDemand = demandNos.isEmpty() ? Map.of()
                : planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .in(PlanMatMrpEntity::getPlanDemandNo, demandNos)).stream()
                .collect(Collectors.groupingBy(PlanMatMrpEntity::getPlanDemandNo));
        Set<Long> issueIds = supplements.stream().map(PlnQualitySupplementDemandEntity::getSupplementIssueId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PlnIssueEntity> issues = issueIds.isEmpty() ? Map.of()
                : issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                        .in(PlnIssueEntity::getId, issueIds)).stream()
                .collect(Collectors.toMap(PlnIssueEntity::getId, Function.identity(), (left, right) -> left));
        Set<Long> supplementIds = supplements.stream().map(PlnQualitySupplementDemandEntity::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PlnQualitySupplementWithdrawalEntity> withdrawals = supplementIds.isEmpty() ? Map.of()
                : withdrawalMapper.selectList(new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                        .in(PlnQualitySupplementWithdrawalEntity::getSupplementId, supplementIds)
                        .orderByDesc(PlnQualitySupplementWithdrawalEntity::getCreateTime)
                        .orderByDesc(PlnQualitySupplementWithdrawalEntity::getId)).stream()
                .collect(Collectors.toMap(PlnQualitySupplementWithdrawalEntity::getSupplementId,
                        Function.identity(), (latest, older) -> latest, LinkedHashMap::new));
        for (PlnQualitySupplementDemandEntity supplement : supplements) {
            applyDownstreamState(supplement,
                    mrpByDemand.getOrDefault(supplement.getProductDemandNo(), List.of()),
                    issues.get(supplement.getSupplementIssueId()));
            applyWithdrawalState(supplement, withdrawals.get(supplement.getId()));
        }
        supplementProgress.enrichAll(supplements);
    }

    private void enrichSupplement(PlnQualitySupplementDemandEntity supplement) {
        if (supplement == null) return;
        List<PlanMatMrpEntity> mrp = StrUtil.isBlank(supplement.getProductDemandNo()) ? List.of()
                : planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getPlanDemandNo, supplement.getProductDemandNo()));
        PlnIssueEntity issue = supplement.getSupplementIssueId() == null ? null
                : issueMapper.selectById(supplement.getSupplementIssueId());
        applyDownstreamState(supplement, mrp, issue);
        supplementProgress.enrich(supplement, Integer.valueOf(2).equals(supplement.getPlanningModelVersion())
                && StrUtil.isNotBlank(supplement.getProductDemandNo()) ? supplementProgress.mrpRows(supplement) : mrp);
        PlnQualitySupplementWithdrawalEntity withdrawal = withdrawalMapper.selectOne(
                new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                        .eq(PlnQualitySupplementWithdrawalEntity::getSupplementId, supplement.getId())
                        .orderByDesc(PlnQualitySupplementWithdrawalEntity::getCreateTime)
                        .orderByDesc(PlnQualitySupplementWithdrawalEntity::getId).last("LIMIT 1"));
        applyWithdrawalState(supplement, withdrawal);
    }

    private void applyWithdrawalState(PlnQualitySupplementDemandEntity supplement,
                                      PlnQualitySupplementWithdrawalEntity withdrawal) {
        if (withdrawal == null) return;
        supplement.setWithdrawalId(withdrawal.getId());
        supplement.setWithdrawalNo(withdrawal.getWithdrawalNo());
        supplement.setWithdrawalStatus(withdrawal.getStatus());
        supplement.setWithdrawalEvidenceFingerprint(withdrawal.getEvidenceFingerprint());
        supplement.setWithdrawalLastError(withdrawal.getLastError());
        supplement.setWithdrawalPhaseSummary(String.join(" / ",
                "SUPPLY:" + StrUtil.blankToDefault(withdrawal.getSupplyPhaseStatus(), "PENDING"),
                "MES:" + StrUtil.blankToDefault(withdrawal.getMesPhaseStatus(), "PENDING"),
                "WMS:" + StrUtil.blankToDefault(withdrawal.getWmsPhaseStatus(), "PENDING"),
                "APS:" + StrUtil.blankToDefault(withdrawal.getApsPhaseStatus(), "PENDING"),
                "PLAN:" + StrUtil.blankToDefault(withdrawal.getPlanPhaseStatus(), "PENDING")));
    }

    private void applyDownstreamState(PlnQualitySupplementDemandEntity supplement,
                                      List<PlanMatMrpEntity> mrp, PlnIssueEntity issue) {
        supplement.setMrpLineCount(mrp.size());
        supplement.setMrpIssuedLineCount((int) mrp.stream().filter(row -> Boolean.TRUE.equals(row.getStatus())).count());
        supplement.setSupplementIssueStatus(issue == null ? null : issue.getStatus());
        supplement.setSupplementIssueLocked(issue == null ? null : issue.getLockStatus());
        supplement.setCompletedQuantity(issue == null ? BigDecimal.ZERO : nz(issue.getFinishNum()));
        if (Set.of("SUBMITTED", "REJECTED", "CANCELLED", "WITHDRAWN", "STALE").contains(supplement.getStatus())) {
            supplement.setDownstreamStatus(supplement.getStatus());
            supplement.setDownstreamMessage(switch (supplement.getStatus()) {
                case "SUBMITTED" -> "等待非申请人复核";
                case "REJECTED" -> "复核已驳回，未生成下游需求";
                case "CANCELLED" -> "申请人已取消，未生成下游需求";
                case "WITHDRAWN" -> "补产需求及未执行下游计划已受控撤回";
                default -> "已审批报废量变化，须按最新事实重新申请";
            });
            return;
        }
        if ("DEMAND_CREATED".equals(supplement.getStatus())) {
            supplement.setDownstreamStatus("PLANNING_PENDING").setDownstreamMessage("需求已接收，等待MPS / MRP计划");
        } else if ("FULFILLED".equals(supplement.getStatus())
                || issue != null && nz(issue.getFinishNum()).compareTo(nz(supplement.getRequestQuantity())) >= 0) {
            supplement.setDownstreamStatus("FULFILLED").setDownstreamMessage("补产数量已完成并回写PLAN");
        } else if (mrp.isEmpty()) {
            supplement.setDownstreamStatus("MRP_MISSING").setDownstreamMessage("补产需求缺少MRP结果，必须人工复核");
        } else if (mrp.stream().anyMatch(row -> !Boolean.TRUE.equals(row.getStatus()))) {
            supplement.setDownstreamStatus("MRP_PENDING").setDownstreamMessage("MRP已生成，等待物料供应计划下达");
        } else if (issue == null) {
            supplement.setDownstreamStatus("ISSUE_MISSING").setDownstreamMessage("补产生产计划缺失，必须人工复核");
        } else if (IssueStatusEnum.NOT_ISSUED.getCode().equals(issue.getStatus())) {
            supplement.setDownstreamStatus("CLOSED_UNFULFILLED").setDownstreamMessage("补产计划已关闭但数量未覆盖，缺口仍未闭环");
        } else if (!Boolean.TRUE.equals(issue.getLockStatus())) {
            supplement.setDownstreamStatus("APS_PENDING").setDownstreamMessage("物料供应已下达，等待APS排产锁定");
        } else if (IssueStatusEnum.UNISSUED.getCode().equals(issue.getStatus())) {
            supplement.setDownstreamStatus("MES_PENDING").setDownstreamMessage("APS已锁定，等待下达MES");
        } else if (IssueStatusEnum.ISSUED.getCode().equals(issue.getStatus())) {
            supplement.setDownstreamStatus("MES_ACCEPTED").setDownstreamMessage("MES已接收，等待生产执行");
        } else if (IssueStatusEnum.PRODUCT.getCode().equals(issue.getStatus())) {
            supplement.setDownstreamStatus("MES_EXECUTING").setDownstreamMessage("补产正在执行");
        } else {
            supplement.setDownstreamStatus("COMPLETION_DEFICIT").setDownstreamMessage("计划已完成但回写数量不足，禁止伪闭环");
        }
    }

    private SupplementTotals supplementTotals(Long issueId, Long excludedId) {
        return supplementTotals(supplementMapper.sourceSupplements(TenantContextHolder.getTenantId(),
                FactoryContextHolder.getFactoryCode(), issueId), excludedId);
    }

    private SupplementTotals supplementTotals(List<PlnQualitySupplementDemandEntity> rows, Long excludedId) {
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (PlnQualitySupplementDemandEntity row : rows) {
            if (Objects.equals(row.getId(), excludedId)) continue;
            if (Set.of("DEMAND_CREATED", "MRP_GENERATED", "FULFILLED").contains(row.getStatus())) {
                covered = covered.add(nz(row.getRequestQuantity()));
            } else if ("SUBMITTED".equals(row.getStatus())) {
                pending = pending.add(nz(row.getRequestQuantity()));
            }
        }
        return new SupplementTotals(covered, pending);
    }

    private String supplementEvidenceFingerprint(PlnIssueEntity issue) {
        return hash(String.join("|", Objects.toString(issue.getId(), ""), Objects.toString(issue.getIssueNo(), ""),
                Objects.toString(issue.getFactoryCode(), ""), Objects.toString(issue.getProductCode(), ""),
                Objects.toString(issue.getNum(), ""), Objects.toString(issue.getScrapNum(), "")));
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

    private PlnQualityReplanPolicyEntity requirePolicy(Long id, boolean lock) {
        LambdaQueryWrapper<PlnQualityReplanPolicyEntity> query = new LambdaQueryWrapper<PlnQualityReplanPolicyEntity>()
                .eq(PlnQualityReplanPolicyEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnQualityReplanPolicyEntity result = policyMapper.selectOne(query);
        require(result != null, "质量偏差策略不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private PlnQualityReplanCaseEntity requireCase(Long id, boolean lock) {
        LambdaQueryWrapper<PlnQualityReplanCaseEntity> query = new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                .eq(PlnQualityReplanCaseEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnQualityReplanCaseEntity result = caseMapper.selectOne(query);
        require(result != null, "质量偏差处置案件不存在");
        return result;
    }

    private PlnQualitySupplementDemandEntity requireSupplement(Long id, boolean lock) {
        LambdaQueryWrapper<PlnQualitySupplementDemandEntity> query =
                new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                        .eq(PlnQualitySupplementDemandEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        PlnQualitySupplementDemandEntity result = supplementMapper.selectOne(query);
        require(result != null, "质量补产需求不存在");
        return result;
    }

    private PlnIssueEntity lockIssue(Long id) {
        PlnIssueEntity result = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id).last("FOR UPDATE"));
        require(result != null, "生产计划不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private List<PlnQualityReplanLineEntity> caseLines(Long caseId) {
        return lineMapper.selectList(new LambdaQueryWrapper<PlnQualityReplanLineEntity>()
                .eq(PlnQualityReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnQualityReplanLineEntity::getOperationSeq));
    }

    private void audit(PlnQualityReplanCaseEntity entity, String requestId, String type,
                       String before, String after, String content) {
        MomUser user = currentUser();
        require(operationMapper.insert(new PlnQualityReplanOperationEntity().setCaseId(entity.getId())
                        .setRequestId(requestId.trim()).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setContent(content).setOperatorCode(userCode(user)).setOperatorName(userName(user))
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1,
                "质量局部重排审计履历保存失败");
    }

    private PlanQualityLocalReplanDTO.PublishReceipt receipt(PlnQualityReplanCaseEntity entity, String mesFingerprint) {
        return new PlanQualityLocalReplanDTO.PublishReceipt().setCaseId(entity.getId()).setCaseNo(entity.getCaseNo())
                .setIssueNo(entity.getIssueNo()).setStatus(entity.getStatus())
                .setMovedOperationCount(entity.getMovedOperationCount())
                .setRequiredSupplementQuantity(entity.getRequiredSupplementQuantity())
                .setNewCompletionTime("ROLLED_BACK".equals(entity.getStatus())
                        ? entity.getOriginalCompletionTime() : entity.getProposedCompletionTime())
                .setMesAfterExecutionFingerprint(mesFingerprint);
    }

    private PlnSchedulingOperationEntity readOperation(String value) {
        try {
            return objectMapper.readValue(value, PlnSchedulingOperationEntity.class);
        } catch (Exception ex) {
            throw new CheckedException("质量局部重排工序快照损坏");
        }
    }

    private PlnSchedulingOperationEntity detachedOperation(PlnSchedulingOperationEntity source) {
        PlnSchedulingOperationEntity result = new PlnSchedulingOperationEntity();
        BeanUtils.copyProperties(source, result);
        return result;
    }

    private Map<String, PlnSchedulingOperationEntity> uniqueByProcess(List<PlnSchedulingOperationEntity> rows) {
        return PlnFrozenRouteNodeIdentity.index(rows);
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

    private int resourceChanges(List<PlnSchedulingOperationEntity> originals,
                                Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return (int) originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .filter(item -> {
                    PlnSchedulingOperationEntity candidate = candidates.get(PlnFrozenRouteNodeIdentity.key(item));
                    return !Objects.equals(item.getResourceCode(), candidate.getResourceCode())
                            || !Objects.equals(item.getDeviceCode(), candidate.getDeviceCode())
                            || !Objects.equals(item.getEmployeeCode(), candidate.getEmployeeCode())
                            || !Objects.equals(item.getToolingCodes(), candidate.getToolingCodes());
                }).count();
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

    private void rankQualityCandidates(List<QualityCandidateBuild> builds) {
        List<QualityCandidateBuild> ordered = builds.stream().sorted(Comparator
                .comparing((QualityCandidateBuild item) -> !Boolean.TRUE.equals(item.task().getCandidateFeasible()))
                .thenComparing((QualityCandidateBuild item) -> item.task().getCandidateScore(), Comparator.reverseOrder())
                .thenComparingLong(QualityCandidateBuild::completionDelay)
                .thenComparingLong(QualityCandidateBuild::totalShift)
                .thenComparingInt(QualityCandidateBuild::resourceChanges)
                .thenComparingLong(QualityCandidateBuild::changeoverMinutes)
                .thenComparing(item -> item.task().getCandidateCode())).toList();
        for (int index = 0; index < ordered.size(); index++) {
            PlnSchedulingTaskEntity task = ordered.get(index).task();
            task.setCandidateRank(index + 1);
            require(taskMapper.updateById(task) == 1, "质量异常候选排名保存失败");
        }
    }

    private void enrichCandidate(PlnQualityReplanCaseEntity entity) {
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

    private void invalidateOpenScenario(PlnQualityReplanCaseEntity root, String reason) {
        if (root == null || root.getScenarioId() == null) return;
        PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getId, root.getScenarioId()).last("FOR UPDATE"));
        if (header == null || Set.of("PUBLISHED", "CANCELLED", "EXPIRED", "FAILED").contains(header.getScenarioStatus()))
            return;
        List<PlnQualityReplanCaseEntity> cases = caseMapper.selectList(
                new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                        .eq(PlnQualityReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
        for (PlnQualityReplanCaseEntity item : cases) {
            if (!Set.of("PROPOSED", "SELECTED", "SUPERSEDED").contains(item.getStatus())) continue;
            String before = item.getStatus();
            item.setStatus("STALE");
            require(caseMapper.updateById(item) == 1, "旧质量多候选失效状态保存失败");
            audit(item, "QUALITY-STALE:" + IdUtil.getSnowflakeNextIdStr(),
                    "EVIDENCE_STALE", before, "STALE", reason);
        }
        header.setScenarioStatus("FAILED");
        require(scenarioMapper.updateById(header) == 1, "旧质量情景失效状态保存失败");
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

    private boolean isEffective(PlnQualityReplanPolicyEntity policy, LocalDateTime time) {
        return (policy.getEffectiveFrom() == null || !policy.getEffectiveFrom().isAfter(time))
                && (policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(time));
    }

    private boolean intervalsOverlap(LocalDateTime aStart, LocalDateTime aEnd,
                                     LocalDateTime bStart, LocalDateTime bEnd) {
        return (aEnd == null || bStart.isBefore(aEnd)) && (bEnd == null || aStart.isBefore(bEnd));
    }

    private void validateThreshold(BigDecimal atRisk, BigDecimal rate, BigDecimal scrap) {
        require(Stream.of(atRisk, rate, scrap).filter(Objects::nonNull).allMatch(value -> value.signum() >= 0),
                "质量偏差阈值不能为负数");
        require(Stream.of(atRisk, rate, scrap).filter(Objects::nonNull).anyMatch(value -> value.signum() > 0),
                "至少配置一个大于零的质量偏差阈值");
        require(rate == null || rate.compareTo(BigDecimal.valueOf(100)) <= 0, "偏差率阈值不能超过100%");
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String message) {
        int result = value == null ? defaultValue : value;
        require(result >= min && result <= max, message);
        return result;
    }

    private List<String> normalizeCodes(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
    }

    private List<String> splitCodes(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private String trimToNull(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal positive(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private String plain(BigDecimal value) {
        return nz(value).stripTrailingZeros().toPlainString();
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("质量局部重排证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("质量局部重排证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record QualityCandidateProfile(String code, String name, String solverStrategy,
                                           String sequenceStrategy) {
    }

    private record QualityCandidateBuild(PlnQualityReplanCaseEntity entity,
                                         PlnSchedulingTaskEntity task,
                                         long completionDelay, long totalShift,
                                         int resourceChanges, long changeoverMinutes) {
    }

    private record RiskMetrics(BigDecimal pendingDisposition, BigDecimal openRework,
                               BigDecimal scrap, BigDecimal atRisk, BigDecimal rate) {
    }

    private record ThresholdResult(boolean triggered, String reason) {
    }

    private record SupplementTotals(BigDecimal covered, BigDecimal pending) {
    }
}
