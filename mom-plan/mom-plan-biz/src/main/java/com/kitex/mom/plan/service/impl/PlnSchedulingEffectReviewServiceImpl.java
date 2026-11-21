package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.api.feign.RemoteMesPlanExecutionService;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnSchedulingEffectActionService;
import com.kitex.mom.plan.service.PlnSchedulingEffectReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnSchedulingEffectReviewServiceImpl implements PlnSchedulingEffectReviewService {
    private static final String FORMULA_VERSION = "APS_EFFECT_V1";
    private static final String LEGACY_IDENTITY = "LEGACY_PROCESS_V1";
    private static final String NODE_IDENTITY = "FROZEN_NODE_V2";
    private static final Set<String> PUBLISHED_STATUSES = Set.of("PUBLISHED", "PARTIAL");
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "ROLLED_BACK");

    private final PlnSchedulingEffectReviewMapper reviewMapper;
    private final PlnSchedulingEffectObservationMapper observationMapper;
    private final PlnSchedulingEffectActionService effectActionService;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper operationMapper;
    private final RemoteMesTaskService mesTaskService;
    private final RemoteMesPlanExecutionService mesPlanExecution;
    private final RemoteMdmFactoryAccessService factoryAccessService;
    private final PlnSchedulingDurationEvidenceReader durationEvidenceReader;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public Page<PlnSchedulingEffectReviewEntity> page(SearchPage page, PlnSchedulingEffectReviewDTO.Query query) {
        PlnSchedulingEffectReviewDTO.Query q = query == null ? new PlnSchedulingEffectReviewDTO.Query() : query;
        String factoryCode = requireReadFactory();
        Page<PlnSchedulingEffectReviewEntity> result = reviewMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnSchedulingEffectReviewEntity>()
                .eq(q.getScenarioId() != null, PlnSchedulingEffectReviewEntity::getScenarioId, q.getScenarioId())
                .eq(PlnSchedulingEffectReviewEntity::getFactoryCode, factoryCode)
                .like(StrUtil.isNotBlank(q.getScenarioNo()), PlnSchedulingEffectReviewEntity::getScenarioNo, q.getScenarioNo())
                .eq(StrUtil.isNotBlank(q.getTriggerType()), PlnSchedulingEffectReviewEntity::getTriggerType,
                        upper(q.getTriggerType()))
                .eq(StrUtil.isNotBlank(q.getCandidateCode()), PlnSchedulingEffectReviewEntity::getCandidateCode,
                        upper(q.getCandidateCode()))
                .eq(StrUtil.isNotBlank(q.getReviewStatus()), PlnSchedulingEffectReviewEntity::getReviewStatus,
                        upper(q.getReviewStatus()))
                .orderByDesc(PlnSchedulingEffectReviewEntity::getLastObservationTime)
                .orderByDesc(PlnSchedulingEffectReviewEntity::getCreateTime));
        result.getRecords().forEach(review -> review.setPendingAction(effectActionService.pending(review)));
        return result;
    }

    @Override
    public PlnSchedulingEffectReviewDTO.Detail detail(Long reviewId) {
        require(reviewId != null, "效果回评ID不能为空");
        String factoryCode = requireReadFactory();
        PlnSchedulingEffectReviewEntity review = reviewMapper.selectById(reviewId);
        require(review != null, "效果回评不存在");
        require(Objects.equals(review.getFactoryCode(), factoryCode), "效果回评不属于当前工厂");
        return detailOf(review);
    }

    @Override
    public PlnSchedulingEffectReviewDTO.Detail detailByScenarioId(Long scenarioId) {
        require(scenarioId != null, "排产情景ID不能为空");
        String factoryCode = requireReadFactory();
        PlnSchedulingEffectReviewEntity review = reviewMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingEffectReviewEntity>()
                        .eq(PlnSchedulingEffectReviewEntity::getScenarioId, scenarioId)
                        .eq(PlnSchedulingEffectReviewEntity::getFactoryCode, factoryCode)
                        .orderByDesc(PlnSchedulingEffectReviewEntity::getCreateTime)
                        .last("LIMIT 1"));
        if (review == null) return null;
        requireFactory(review.getFactoryCode());
        return detailOf(review);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnSchedulingEffectReviewDTO.Detail capture(PlnSchedulingEffectReviewDTO.CaptureCommand command) {
        return captureInternal(command, "MANUAL");
    }

    @Override
    public int refreshDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        int refreshed = 0;
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        List<PlnSchedulingScenarioEntity> unreviewed = reviewMapper.selectUnreviewedPublishedScenarios(batchSize);
        for (PlnSchedulingScenarioEntity scenario : unreviewed) {
            if (runScheduled(scenario.getTenantId(), scenario.getFactoryCode(), transaction,
                    scheduledCommand(scenario.getId(), "首次自动采集"))) {
                refreshed++;
            }
        }

        int remaining = Math.max(0, batchSize - refreshed);
        if (remaining == 0) return refreshed;
        List<PlnSchedulingEffectReviewEntity> due = reviewMapper.selectDueReviews(LocalDateTime.now(), remaining);
        for (PlnSchedulingEffectReviewEntity review : due) {
            if (runScheduled(review.getTenantId(), review.getFactoryCode(), transaction,
                    scheduledCommand(review.getScenarioId(), "到期滚动重评"))) {
                refreshed++;
            }
        }
        return refreshed;
    }

    /** 每项从原发布单恢复查询组织，继承执行器实际身份，退出时归还线程原上下文。 */
    private boolean runScheduled(Long tenantId, String factory, TransactionTemplate transaction,
                                 PlnSchedulingEffectReviewDTO.CaptureCommand command) {
        String previousFactory = FactoryContextHolder.getFactoryCode();
        boolean previousSkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        Long previousTenant = TenantContextHolder.getTenantId();
        boolean previousTenantSkip = TenantContextHolder.getTenantSkip();
        try {
            require(tenantId != null && tenantId > 0 && StrUtil.isNotBlank(factory), "自动回评缺少原发布组织");
            TenantContextHolder.clear();
            TenantContextHolder.setTenantId(tenantId);
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(factory);
            Boolean result = transaction.execute(status -> captureInternal(command, "SCHEDULED") != null);
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ex) {
            log.error("APS候选效果自动回评失败，scenarioId={}，tenantId={}，不阻塞其他情景",
                    command.getScenarioId(), tenantId, ex);
            return false;
        } finally {
            TenantContextHolder.clear();
            TenantContextHolder.setTenantId(previousTenant);
            if (previousTenantSkip) {
                TenantContextHolder.setTenantSkip();
            }
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(previousFactory);
            if (previousSkip) {
                FactoryContextHolder.setFactorySkip();
            }
        }
    }

    private PlnSchedulingEffectReviewDTO.CaptureCommand scheduledCommand(Long scenarioId, String reason) {
        PlnSchedulingEffectReviewDTO.CaptureCommand command = new PlnSchedulingEffectReviewDTO.CaptureCommand();
        command.setScenarioId(scenarioId);
        command.setRequestId("AUTO:" + scenarioId + ':' + IdUtil.fastSimpleUUID().substring(0, 16));
        command.setToleranceMinutes(30);
        command.setReason(reason);
        return command;
    }

    private PlnSchedulingEffectReviewDTO.Detail captureInternal(PlnSchedulingEffectReviewDTO.CaptureCommand command,
                                                                String observationType) {
        require(command != null && command.getScenarioId() != null && StrUtil.isNotBlank(command.getRequestId()),
                "效果回评采集参数不完整");
        require(StrUtil.isNotBlank(command.getReason()), "效果回评原因不能为空");
        String requestId = command.getRequestId().trim();
        require(requestId.length() <= 64, "效果回评请求号不能超过64个字符");
        require(command.getReason().trim().length() <= 500, "效果回评原因不能超过500个字符");
        int tolerance = Objects.requireNonNullElse(command.getToleranceMinutes(), 30);
        require(tolerance >= 0 && tolerance <= 1440, "效果回评偏差容差必须在0到1440分钟之间");

        reviewMapper.lockKey("REQUEST:" + requestId);
        reviewMapper.lockKey("SCENARIO:" + command.getScenarioId());
        scenarioMapper.lock("EFFECT_REVIEW:" + command.getScenarioId());
        PlnSchedulingScenarioEntity scenario = scenarioMapper.selectById(command.getScenarioId());
        require(scenario != null, "排产情景不存在");
        if ("MANUAL".equals(observationType)) {
            requireWriteFactory(scenario);
        } else {
            requireFactory(scenario.getFactoryCode());
        }
        require(PUBLISHED_STATUSES.contains(scenario.getScenarioStatus()), "仅已发布或部分发布的候选情景可以效果回评");
        require(scenario.getSelectedTaskId() != null, "已发布情景缺少选中候选任务");
        PlnSchedulingTaskEntity task = taskMapper.selectById(scenario.getSelectedTaskId());
        require(task != null && Objects.equals(task.getScenarioId(), scenario.getId())
                        && Boolean.TRUE.equals(task.getSelectedCandidate()),
                "情景选中候选与任务账不一致");
        require(Objects.equals(task.getFactoryCode(), scenario.getFactoryCode()), "选中候选与情景工厂不一致");

        // 身份协议随首次回评冻结，重采旧账不能静默升级其范围JSON和指纹。
        PlnSchedulingEffectReviewEntity review = reviewMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingEffectReviewEntity>()
                        .eq(PlnSchedulingEffectReviewEntity::getScenarioId, scenario.getId()));
        PlannedScope scope = plannedScope(scenario, task, identityVersion(review));
        String requestFingerprint = hash(String.join("|", String.valueOf(scenario.getId()), requestId,
                scope.fingerprint(), String.valueOf(tolerance), command.getReason().trim(), observationType));
        PlnSchedulingEffectObservationEntity duplicate = observationMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingEffectObservationEntity>()
                        .eq(PlnSchedulingEffectObservationEntity::getRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "同一效果回评请求号的情景、容差或原因不一致");
            PlnSchedulingEffectReviewEntity duplicateReview = reviewMapper.selectById(duplicate.getReviewId());
            require(duplicateReview != null, "幂等观察记录关联的效果回评不存在");
            return detailOf(duplicateReview);
        }

        if (review == null) {
            review = createReview(scenario, task, scope);
        } else {
            review = reviewMapper.lockById(review.getId());
            require(review != null, "效果回评并发状态已变化");
        }

        LocalDateTime now = LocalDateTime.now();
        Evaluation evaluation;
        if (!Objects.equals(review.getPlannedScopeFingerprint(), scope.fingerprint())) {
            evaluation = Evaluation.dataGap(scope.operations().size(), scope.issueNos().size(),
                    "已发布候选工序快照发生漂移，已停止采用变化后的计划进行效果计算");
        } else if ("ROLLED_BACK".equals(reviewMapper.selectLifecycleStatus(scenario.getId()))) {
            evaluation = Evaluation.rolledBack(scope.operations().size(), scope.issueNos().size(),
                    "专用局部重排方案已受控回退，效果回评停止滚动采集");
        } else {
            evaluation = evaluate(scope, tolerance);
        }

        if (scope.batches() != null && "SCHEDULED".equals(observationType)
                && "SOURCE_UNAVAILABLE".equals(evaluation.status()) && review.getCurrentObservationId() != null) {
            // 暂缺实名授权/网络不可用时只登记待重试，不能覆盖最近一次可回看的事实或重开偏差行动。
            String error = "自动采集待重试：" + evaluation.errorMessage();
            require(reviewMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingEffectReviewEntity>()
                    .eq(PlnSchedulingEffectReviewEntity::getId, review.getId())
                    .eq(PlnSchedulingEffectReviewEntity::getTenantId, scenario.getTenantId())
                    .eq(PlnSchedulingEffectReviewEntity::getFactoryCode, scenario.getFactoryCode())
                    .set(PlnSchedulingEffectReviewEntity::getLastError, error)
                    .set(PlnSchedulingEffectReviewEntity::getNextEvaluationTime, nextEvaluationTime(evaluation.status(), now))) == 1,
                    "原批次回评的重试时刻登记失败");
            return detailOf(reviewMapper.selectById(review.getId()));
        }
        if (scope.usesNodes()) {
            Map<String, Object> snapshot = new LinkedHashMap<>(evaluation.snapshot());
            snapshot.put("identityContractVersion", scope.identityVersion());
            if (scope.batches() != null) {
                // 来源份额与完工交期只在原发布案件和候选内成立，序列化时保留完整主键。
                snapshot.put("scenarioId", scenario.getId().toString());
                snapshot.put("selectedTaskId", task.getId().toString());
            }
            evaluation = evaluation.withStatus(evaluation.status(), evaluation.errorMessage(), snapshot);
        }
        String actualSnapshot = json(evaluation.snapshot());
        // 观察时间属于审计快照，不属于MES业务事实；重采同一事实必须得到相同指纹。
        Map<String, Object> semanticSnapshot = new LinkedHashMap<>(evaluation.snapshot());
        semanticSnapshot.remove("capturedTime");
        String actualFingerprint = hash(json(semanticSnapshot));
        BigDecimal actualScore = actualScore(evaluation, tolerance);
        BigDecimal realizationGap = actualScore == null || task.getCandidateScore() == null ? null
                : actualScore.subtract(task.getCandidateScore()).setScale(2, RoundingMode.HALF_UP);
        String conclusion = conclusion(evaluation, actualScore, realizationGap);
        MomUser user = currentUser();
        PlnSchedulingEffectObservationEntity observation = new PlnSchedulingEffectObservationEntity()
                .setReviewId(review.getId()).setScenarioId(scenario.getId()).setRequestId(requestId)
                .setRequestFingerprint(requestFingerprint).setObservationType(observationType)
                .setObservationStatus(evaluation.status()).setToleranceMinutes(tolerance)
                .setReason(command.getReason().trim()).setActualSnapshot(actualSnapshot)
                .setActualFingerprint(actualFingerprint).setScoreFormulaVersion(FORMULA_VERSION)
                .setActualEffectScore(actualScore).setRealizationGap(realizationGap).setConclusion(conclusion)
                .setErrorMessage(evaluation.errorMessage()).setObservedBy(userCode(user, observationType))
                .setObservedByName(userName(user, observationType)).setObservedTime(now)
                .setFactoryCode(scenario.getFactoryCode());
        copyMetrics(evaluation, observation);
        require(observationMapper.insert(observation) == 1, "效果观察记录保存失败");

        review.setReviewStatus(evaluation.status()).setLatestExecutionFingerprint(actualFingerprint)
                .setCurrentObservationId(observation.getId()).setLastObservationTime(now)
                .setFirstObservationTime(review.getFirstObservationTime() == null ? now : review.getFirstObservationTime())
                .setObservationCount(Objects.requireNonNullElse(review.getObservationCount(), 0) + 1)
                .setActualEffectScore(actualScore).setRealizationGap(realizationGap)
                .setScoreFormulaVersion(FORMULA_VERSION).setConclusion(conclusion)
                .setLastError(evaluation.errorMessage()).setNextEvaluationTime(nextEvaluationTime(evaluation.status(), now));
        if ("COMPLETED".equals(evaluation.status()) && review.getCompletedTime() == null) review.setCompletedTime(now);
        copyMetrics(evaluation, review);
        // 默认非空更新策略会残留上一轮评分或错误。显式清空本次已失效的可空值。
        require(reviewMapper.update(review, new LambdaUpdateWrapper<PlnSchedulingEffectReviewEntity>()
                .eq(PlnSchedulingEffectReviewEntity::getId, review.getId())
                .eq(PlnSchedulingEffectReviewEntity::getTenantId, scenario.getTenantId())
                .eq(PlnSchedulingEffectReviewEntity::getFactoryCode, scenario.getFactoryCode())
                .set(actualScore == null, PlnSchedulingEffectReviewEntity::getActualEffectScore, null)
                .set(realizationGap == null, PlnSchedulingEffectReviewEntity::getRealizationGap, null)
                .set(review.getLastError() == null, PlnSchedulingEffectReviewEntity::getLastError, null)
                .set(review.getNextEvaluationTime() == null, PlnSchedulingEffectReviewEntity::getNextEvaluationTime, null)) == 1,
                "效果回评汇总保存失败");
        effectActionService.reconcile(review, observation);
        return detailOf(review);
    }

    private PlnSchedulingEffectReviewEntity createReview(PlnSchedulingScenarioEntity scenario,
                                                         PlnSchedulingTaskEntity task, PlannedScope scope) {
        PlnSchedulingEffectReviewEntity review = new PlnSchedulingEffectReviewEntity()
                .setReviewNo("SER-" + IdUtil.getSnowflakeNextIdStr()).setScenarioId(scenario.getId())
                .setScenarioNo(scenario.getScenarioNo()).setTriggerType(scenario.getTriggerType())
                .setSelectedTaskId(task.getId()).setCandidateCode(task.getCandidateCode())
                .setCandidateName(task.getCandidateName()).setCandidateRank(task.getCandidateRank())
                .setCandidateScore(task.getCandidateScore()).setSelectedMetricsSnapshot(task.getCandidateMetricsSnapshot())
                .setPlannedScopeSnapshot(scope.snapshot()).setPlannedScopeFingerprint(scope.fingerprint())
                .setSourceIssueCount(scope.issueNos().size()).setPlannedOperationCount(scope.operations().size())
                .setReviewStatus("WAITING_DISPATCH").setObservationCount(0).setScoreFormulaVersion(FORMULA_VERSION)
                .setFactoryCode(scenario.getFactoryCode());
        require(reviewMapper.insert(review) == 1, "效果回评主账创建失败");
        return review;
    }

    /** 新账显式冻结身份协议；缺少标记的历史账严格保留原工序编码合同。 */
    private String identityVersion(PlnSchedulingEffectReviewEntity review) {
        if (review == null) {
            return null; // 新账按实际发布范围确定协议，已有观察始终沿用原标记。
        }
        try {
            var snapshot = objectMapper.readTree(review.getPlannedScopeSnapshot());
            require(snapshot != null && snapshot.isObject(), "原效果回评计划范围不可读取");
            if (!snapshot.has("identityContractVersion")) return LEGACY_IDENTITY;
            String version = snapshot.path("identityContractVersion").asText();
            require(NODE_IDENTITY.equals(version) || PlnBatchEffectScope.VERSION.equals(version), "原效果回评身份协议不受支持，不能改用其他口径");
            return version;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new CheckedException("原效果回评计划范围无法解析，禁止重建历史基线");
        }
    }

    private PlannedScope plannedScope(PlnSchedulingScenarioEntity scenario, PlnSchedulingTaskEntity task,
                                      String identityVersion) {
        List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, task.getId())
                        .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueNo));
        List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, task.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getIssueNo)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        require(!operations.isEmpty(), "选中候选没有可回评的工序级计划快照");
        require(operations.stream().allMatch(item -> StrUtil.isNotBlank(item.getIssueNo())
                        && StrUtil.isNotBlank(item.getProcessCode()) && item.getScheduledStartTime() != null
                        && item.getScheduledEndTime() != null
                        && item.getScheduledEndTime().isAfter(item.getScheduledStartTime())),
                "选中候选存在缺少计划号、工序编码或无效计划时间的工序，无法回评");
        if (identityVersion == null) {
            boolean configuredBatches = operations.stream().anyMatch(op -> "MERGED".equals(op.getSchedulingBatchType())
                    || "SPLIT".equals(op.getSchedulingBatchType()));
            identityVersion = configuredBatches ? PlnBatchEffectScope.VERSION : NODE_IDENTITY;
        }
        boolean byBatch = PlnBatchEffectScope.VERSION.equals(identityVersion);
        boolean nodes = byBatch || NODE_IDENTITY.equals(identityVersion);
        PlnBatchEffectScope batches = null;
        if (byBatch) {
            // 同一来源不同批次的节点、序号可以完全相同，固定批次顺序以保证重采指纹稳定。
            operations = operations.stream().sorted(Comparator.comparing(PlnSchedulingOperationEntity::getSchedulingBatchNo,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(PlnSchedulingOperationEntity::getOperationSeq, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(PlnSchedulingOperationEntity::getId)).toList();
            batches = new PlnBatchEffectScope(operations, details, task.getId(), scenario.getFactoryCode(), objectMapper);
        }
        Map<String, List<PlnSchedulingOperationEntity>> groups = operations.stream().collect(Collectors.groupingBy(
                op -> byBatch ? op.getSchedulingBatchNo() : op.getIssueNo(), LinkedHashMap::new, Collectors.toList()));
        if (nodes) {
            for (var rows : groups.values()) {
                PlnFrozenRouteNodeIdentity.index(rows);
                require(rows.stream().allMatch(row -> row.getOperationSeq() != null), "冻结工序序号缺失，无法核对真实节点");
            }
        } else {
            Set<String> uniqueKeys = new LinkedHashSet<>();
            require(operations.stream().allMatch(item -> uniqueKeys.add(item.getIssueNo() + '\u0000' + item.getProcessCode())),
                    "历史回评同一计划存在重复工序编码，不能静默改变原匹配合同");
        }

        Map<Long, PlnSchedulingTaskDetailEntity> detailsByIssueId = details.stream()
                .filter(item -> item.getIssueId() != null).collect(Collectors.toMap(
                        PlnSchedulingTaskDetailEntity::getIssueId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, PlnSchedulingTaskDetailEntity> detailsByIssueNo = details.stream()
                .filter(item -> StrUtil.isNotBlank(item.getIssueNo())).collect(Collectors.toMap(
                        PlnSchedulingTaskDetailEntity::getIssueNo, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Map<String, Object>> snapshotRows = new ArrayList<>();
        for (PlnSchedulingOperationEntity operation : operations) {
            PlnSchedulingTaskDetailEntity detail = operation.getIssueId() == null ? null : detailsByIssueId.get(operation.getIssueId());
            if (detail == null) detail = detailsByIssueNo.get(operation.getIssueNo());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("issueId", operation.getIssueId());
            row.put("issueNo", operation.getIssueNo());
            row.put("productCode", operation.getProductCode());
            row.put("productName", detail == null ? null : detail.getProductName());
            row.put("demandTime", detail == null ? null : detail.getDemandTime());
            row.put("processCode", operation.getProcessCode());
            row.put("processName", operation.getProcessName());
            row.put("operationSeq", operation.getOperationSeq());
            row.put("resourceType", operation.getResourceType());
            row.put("resourceCode", operation.getResourceCode());
            row.put("resourceSlotNo", operation.getResourceSlotNo());
            row.put("deviceCode", operation.getDeviceCode());
            row.put("employeeCode", operation.getEmployeeCode());
            row.put("toolingCodes", operation.getToolingCodes());
            row.put("plannedQuantity", operation.getPlannedQuantity());
            row.put("scheduledStartTime", operation.getScheduledStartTime());
            row.put("scheduledEndTime", operation.getScheduledEndTime());
            if (byBatch) {
                row.put("schedulingBatchNo", operation.getSchedulingBatchNo());
                row.put("schedulingBatchId", operation.getSchedulingBatchId().toString());
            }
            if (nodes) {
                row.put("schedulingOperationId", operation.getId().toString());
                row.put("sourceRouteNodeCode", operation.getSourceRouteNodeCode());
                row.put("routeCode", operation.getRouteCode());
                row.put("routeWorkingId", operation.getRouteWorkingId());
                row.put("predecessorNodeCodes", operation.getPredecessorNodeCodes());
                row.put("scheduledStartTimeExact", operation.getScheduledStartTime().toString());
                row.put("scheduledEndTimeExact", operation.getScheduledEndTime().toString());
            }
            snapshotRows.add(row);
        }
        Map<String, Object> scopeSnapshot = new LinkedHashMap<>();
        scopeSnapshot.put("scenarioId", scenario.getId());
        scopeSnapshot.put("selectedTaskId", task.getId());
        scopeSnapshot.put("sourceFingerprint", scenario.getSourceFingerprint());
        scopeSnapshot.put("candidateMetrics", jsonValue(task.getCandidateMetricsSnapshot()));
        scopeSnapshot.put("operations", snapshotRows);
        if (nodes) {
            scopeSnapshot.put("identityContractVersion", identityVersion);
        }
        if (byBatch) {
            scopeSnapshot.put("batches", batches.batches.values());
            scopeSnapshot.put("sourceDemands", batches.demands);
        }
        String snapshot = json(scopeSnapshot);
        String fingerprint = hash(snapshot);
        Map<String, LocalDate> demandByIssueNo = new LinkedHashMap<>();
        for (PlnSchedulingTaskDetailEntity detail : details) {
            if (StrUtil.isNotBlank(detail.getIssueNo()))
                demandByIssueNo.put(detail.getIssueNo(), detail.getDemandTime());
        }
        return new PlannedScope(operations, byBatch ? batches.demands.keySet() : groups.keySet(), demandByIssueNo,
                scenario.getFactoryCode(), snapshot, fingerprint, identityVersion, task.getId(), groups, batches);
    }

    private Evaluation evaluate(PlannedScope scope, int tolerance) {
        Map<String, MesApsLocalScheduleDTO.StateBundle> bundles = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> mappingGaps = new ArrayList<>();
        PlnBatchEffectObservation batchFacts = null;
        if (scope.batches() == null) {
            readPlanStates(scope, bundles, errors);
        } else {
            MesPlanExecutionEvidenceDTO.Evidence facts = null;
            try {
                var response = mesPlanExecution.query(new MesPlanExecutionEvidenceDTO.Query()
                        .setSchedulingTaskId(scope.taskId()).setFactoryCode(scope.factoryCode())
                        .setPlanNos(new ArrayList<>(scope.issueNos())));
                if (response != null && response.isOk() && response.getData() != null) {
                    facts = response.getData();
                } else {
                    errors.add("MES 候选批次取证失败，请核对原工单和当前账号权限");
                }
            } catch (RuntimeException ex) {
                errors.add("MES 候选批次取证不可用：" + safeMessage(ex));
            }
            if (facts != null) {
                try {
                    batchFacts = new PlnBatchEffectObservation(scope.batches(), facts, scope.taskId(),
                            TenantContextHolder.getTenantId(), scope.factoryCode());
                    bundles.putAll(batchFacts.bundles);
                    mappingGaps.addAll(batchFacts.gaps);
                } catch (RuntimeException ex) {
                    mappingGaps.add("MES 候选批次与发布依据无法核对：" + safeMessage(ex));
                }
            }
        }

        List<PlnSchedulingEffectReviewDTO.OperationEvidence> evidences = new ArrayList<>();
        Set<String> unmappedSources = new HashSet<>();
        int mesTaskCount = 0;
        for (var group : scope.groups().entrySet()) {
            List<PlnSchedulingOperationEntity> issueOperations = group.getValue();
            int gapsBeforeGroup = mappingGaps.size();
            String issueNo = issueOperations.get(0).getIssueNo();
            MesApsLocalScheduleDTO.StateBundle bundle = bundles.get(group.getKey());
            if (bundle != null && Boolean.TRUE.equals(bundle.getMesTaskPresent())) {
                mesTaskCount++;
            }
            Map<String, MesApsLocalScheduleDTO.OperationState> mesByIdentity = executionIndex(
                    scope, group.getKey(), issueOperations, bundle, mappingGaps);
            for (PlnSchedulingOperationEntity operation : issueOperations) {
                String key = scope.usesNodes()
                        ? PlnFrozenRouteNodeIdentity.key(operation) : operation.getProcessCode();
                MesApsLocalScheduleDTO.OperationState actual = mesByIdentity.get(key);
                PlnSchedulingEffectReviewDTO.OperationEvidence evidence = new PlnSchedulingEffectReviewDTO.OperationEvidence()
                        .setIssueId(operation.getIssueId()).setIssueNo(issueNo).setProductCode(operation.getProductCode())
                        .setProcessCode(operation.getProcessCode()).setProcessName(operation.getProcessName())
                        .setOperationSeq(operation.getOperationSeq()).setResourceCode(operation.getResourceCode())
                        .setPlannedStartTime(operation.getScheduledStartTime()).setPlannedEndTime(operation.getScheduledEndTime())
                        .setMesTaskPresent(bundle != null && Boolean.TRUE.equals(bundle.getMesTaskPresent()))
                        .setMatched(actual != null);
                if (scope.batches() != null) {
                    evidence.setSchedulingBatchNo(operation.getSchedulingBatchNo())
                            .setSchedulingBatchId(operation.getSchedulingBatchId().toString());
                }
                if (scope.usesNodes()) {
                    evidence.setSchedulingOperationId(operation.getId().toString())
                            .setSourceRouteNodeCode(operation.getSourceRouteNodeCode())
                            .setMesSourceRouteNodeCode(actual == null ? null : actual.getSourceRouteNodeCode());
                }
                if (actual == null) {
                    evidence.setGapReason(bundle == null ? "MES权威事实不可用"
                            : Boolean.TRUE.equals(bundle.getMesTaskPresent())
                            ? scope.usesNodes() ? "MES未能对应原冻结工序" : "MES缺少同编码工序"
                            : "MES生产任务尚未建立");
                    if (bundle != null && Boolean.TRUE.equals(bundle.getMesTaskPresent())) {
                        mappingGaps.add("计划[" + issueNo + "]MES缺少工序[" + operation.getProcessCode() + "]");
                    }
                } else {
                    LocalDateTime actualStart = actual.getActualStartTime();
                    LocalDateTime actualFinish = actual.getActualFinishTime();
                    if (scope.usesNodes()) {
                        try {
                            actualStart = exactTime(actual.getActualStartTimeText(), actualStart);
                            actualFinish = exactTime(actual.getActualFinishTimeText(), actualFinish);
                        } catch (RuntimeException ex) {
                            evidence.setGapReason("MES实际开完工时间无法解析");
                            actualStart = null;
                            actualFinish = null;
                        }
                    }
                    if (actualFinish != null && (actualStart == null || actualFinish.isBefore(actualStart))) {
                        if (scope.usesNodes()) {
                            evidence.setGapReason("MES实际开完工时间不完整或倒置");
                        } else {
                            mappingGaps.add("计划[" + issueNo + "]MES工序[" + operation.getProcessCode()
                                    + "]实际开完工时间不完整或倒置");
                        }
                    }
                    if (StrUtil.isNotBlank(evidence.getGapReason())) {
                        mappingGaps.add("计划[" + issueNo + "]节点[" + key + "]" + evidence.getGapReason());
                    }
                    Long startDeviation = minutes(operation.getScheduledStartTime(), actualStart);
                    Long finishDeviation = minutes(operation.getScheduledEndTime(), actualFinish);
                    evidence.setMesTaskCode(StrUtil.blankToDefault(actual.getTaskCode(), actual.getProduceTaskCode()))
                            .setMesProduceTaskCode(actual.getProduceTaskCode())
                            .setMesProduceTaskId(actual.getProduceTaskId() == null ? null : actual.getProduceTaskId().toString())
                            .setMesActualStartTimeText(actual.getActualStartTimeText())
                            .setMesActualFinishTimeText(actual.getActualFinishTimeText())
                            .setMesStatus(actual.getStatus()).setMovable(actual.getMovable())
                            .setMovementBlockedReason(actual.getBlockedReason())
                            .setActualStartTime(actualStart)
                            .setActualFinishTime(actualFinish)
                            .setCompleteQuantity(actual.getCompleteQuantity() == null ? null : actual.getCompleteQuantity().toPlainString())
                            .setStartDeviationMinutes(startDeviation).setFinishDeviationMinutes(finishDeviation)
                            .setStartAdherent(startDeviation == null ? null : Math.abs(startDeviation) <= tolerance)
                            .setFinishAdherent(finishDeviation == null ? null : Math.abs(finishDeviation) <= tolerance);
                }
                evidences.add(evidence);
            }
            if (scope.batches() != null && mappingGaps.size() != gapsBeforeGroup) {
                scope.batches().batches.get(group.getKey()).shares().forEach(share -> unmappedSources.add(share.issueNo()));
            }
        }

        durationEvidenceReader.enrich(scope.operations(), evidences, scope.factoryCode());
        List<PlnBatchEffectObservation.SourceResult> sources = batchFacts == null ? List.of() : batchFacts.sources.stream()
                .map(row -> unmappedSources.contains(row.issueNo()) ? row.withoutDeliveryEvidence() : row).toList();
        Evaluation result = metrics(scope, evidences, mesTaskCount, sources);
        String status;
        String error = null;
        if (!errors.isEmpty()) {
            status = "SOURCE_UNAVAILABLE";
            error = String.join("；", errors);
        } else if (!mappingGaps.isEmpty()) {
            status = "DATA_GAP";
            error = String.join("；", mappingGaps);
        } else if (mesTaskCount == 0) {
            status = "WAITING_DISPATCH";
        } else if (result.startedOperationCount() == 0 && result.completedOperationCount() == 0
                && evidences.stream().noneMatch(item -> positive(item.getCompleteQuantity()))) {
            status = "WAITING_EXECUTION";
        } else if (result.completedOperationCount() == scope.operations().size()
                && (scope.batches() == null || result.completedIssueCount() == scope.issueNos().size())) {
            status = "COMPLETED";
        } else {
            status = "IN_PROGRESS";
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("formulaVersion", FORMULA_VERSION);
        snapshot.put("durationEvidenceVersion", "APS_RUN_SEGMENTS_V2");
        snapshot.put("capturedTime", LocalDateTime.now());
        snapshot.put("toleranceMinutes", tolerance);
        snapshot.put("status", status);
        snapshot.put("sourceErrors", errors);
        snapshot.put("mappingGaps", mappingGaps);
        if (scope.batches() == null) {
            snapshot.put("mesExecutionFingerprints", bundles.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, item -> item.getValue().getExecutionFingerprint(), (a, b) -> a, LinkedHashMap::new)));
        } else {
            snapshot.put("sourceFulfillment", sources);
            if (batchFacts != null) {
                snapshot.put("batchFacts", batchFacts.receipts);
            }
        }
        snapshot.put("operations", evidences);
        return result.withStatus(status, error, snapshot);
    }

    /** 普通计划及历史基线继续调用原 MES 计划号取证接口。 */
    private void readPlanStates(PlannedScope scope, Map<String, MesApsLocalScheduleDTO.StateBundle> bundles,
                                List<String> errors) {
        for (String issueNo : scope.issueNos()) {
            try {
                R<MesApsLocalScheduleDTO.StateBundle> response = mesTaskService.apsOperationStates(
                        issueNo, scope.factoryCode());
                if (response == null || !response.isOk() || response.getData() == null
                        || response.getData().getExecutionFingerprint() == null
                        || response.getData().getExecutionFingerprint().length() != 64) {
                    errors.add("计划[" + issueNo + "]MES执行事实不可用");
                } else {
                    MesApsLocalScheduleDTO.StateBundle bundle = response.getData();
                    if (scope.usesNodes() && (!Objects.equals(issueNo, bundle.getPlanNo())
                            || !Objects.equals(scope.factoryCode(), bundle.getFactoryCode()) || bundle.getMesTaskPresent() == null)) {
                        errors.add("计划[" + issueNo + "]MES组织、来源或工单存在性未明确返回");
                    } else if (StrUtil.isNotBlank(bundle.getPlanNo()) && !Objects.equals(issueNo, bundle.getPlanNo())) {
                        errors.add("计划[" + issueNo + "]MES返回了其他计划的执行事实");
                    } else if (StrUtil.isNotBlank(bundle.getFactoryCode())
                            && !Objects.equals(scope.factoryCode(), bundle.getFactoryCode())) {
                        errors.add("计划[" + issueNo + "]MES返回了其他工厂的执行事实");
                    } else {
                        bundles.put(issueNo, bundle);
                    }
                }
            } catch (RuntimeException ex) {
                errors.add("计划[" + issueNo + "]MES执行事实读取失败：" + safeMessage(ex));
            }
        }
    }

    /**
     * 新协议核对每张根计划的完整冻结集合；任何重复、遗漏或序号不符都不挑首条继续评分。
     * 历史协议保留原唯一主工序匹配方式，不回填当前路线节点。
     */
    private Map<String, MesApsLocalScheduleDTO.OperationState> executionIndex(
            PlannedScope scope, String issueNo, List<PlnSchedulingOperationEntity> operations,
            MesApsLocalScheduleDTO.StateBundle bundle, List<String> gaps) {
        if (bundle == null) return Map.of();
        if (scope.usesNodes()) {
            try {
                return PlnFrozenRouteNodeIdentity.correspondence(operations, bundle);
            } catch (CheckedException ex) {
                gaps.add("计划[" + issueNo + "]" + ex.getMessage());
                return Map.of();
            }
        }
        Map<String, MesApsLocalScheduleDTO.OperationState> legacy = new LinkedHashMap<>();
        for (var state : Optional.ofNullable(bundle.getOperations()).orElse(List.of())) {
            if (StrUtil.isBlank(state.getRouteCode())) continue;
            if (legacy.putIfAbsent(state.getRouteCode(), state) != null) {
                gaps.add("计划[" + issueNo + "]MES存在重复工序[" + state.getRouteCode() + "]");
            }
        }
        return legacy;
    }

    private LocalDateTime exactTime(String text, LocalDateTime fallback) {
        return StrUtil.isBlank(text) ? fallback : LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private Evaluation metrics(PlannedScope scope,
                               List<PlnSchedulingEffectReviewDTO.OperationEvidence> evidences, int mesTaskCount,
                               List<PlnBatchEffectObservation.SourceResult> sources) {
        int matched = (int) evidences.stream().filter(item -> Boolean.TRUE.equals(item.getMatched())).count();
        List<Long> startDeviations = evidences.stream().map(PlnSchedulingEffectReviewDTO.OperationEvidence::getStartDeviationMinutes)
                .filter(Objects::nonNull).toList();
        List<Long> finishDeviations = evidences.stream().map(PlnSchedulingEffectReviewDTO.OperationEvidence::getFinishDeviationMinutes)
                .filter(Objects::nonNull).toList();
        int started = startDeviations.size();
        int completed = finishDeviations.size();
        int startAdherent = (int) evidences.stream().filter(item -> Boolean.TRUE.equals(item.getStartAdherent())).count();
        int finishAdherent = (int) evidences.stream().filter(item -> Boolean.TRUE.equals(item.getFinishAdherent())).count();
        int checkpoints = started + completed;
        int adherentCheckpoints = startAdherent + finishAdherent;

        Map<String, List<PlnSchedulingEffectReviewDTO.OperationEvidence>> byIssue = evidences.stream()
                .collect(Collectors.groupingBy(PlnSchedulingEffectReviewDTO.OperationEvidence::getIssueNo,
                        LinkedHashMap::new, Collectors.toList()));
        int completedIssues = 0;
        int onTimeIssues = 0;
        int lateIssues = 0;
        if (scope.batches() != null) {
            onTimeIssues = (int) sources.stream().filter(row -> "ON_TIME".equals(row.status())).count();
            lateIssues = (int) sources.stream().filter(row -> "LATE".equals(row.status())).count();
            completedIssues = (int) sources.stream().filter(row -> row.finishedAt() != null).count();
        } else {
            for (Map.Entry<String, List<PlnSchedulingEffectReviewDTO.OperationEvidence>> entry : byIssue.entrySet()) {
                if (entry.getValue().isEmpty() || entry.getValue().stream().anyMatch(item -> item.getActualFinishTime() == null))
                    continue;
                completedIssues++;
                LocalDate demand = scope.demandByIssueNo().get(entry.getKey());
                if (demand == null) continue;
                LocalDateTime actualFinish = entry.getValue().stream().map(PlnSchedulingEffectReviewDTO.OperationEvidence::getActualFinishTime)
                        .max(Comparator.naturalOrder()).orElse(null);
                if (actualFinish != null && !actualFinish.isAfter(demand.atTime(LocalTime.MAX))) onTimeIssues++;
                else lateIssues++;
            }
        }
        return new Evaluation(null, null, Map.of(), mesTaskCount, matched, started, completed,
                rate(completed, scope.operations().size()), rate(startAdherent, started), rate(finishAdherent, completed),
                rate(adherentCheckpoints, checkpoints), averageAbs(startDeviations), maxAbs(startDeviations),
                averageAbs(finishDeviations), maxAbs(finishDeviations), sum(startDeviations), sum(finishDeviations),
                positiveSum(startDeviations) + positiveSum(finishDeviations), completedIssues, onTimeIssues, lateIssues);
    }

    private BigDecimal actualScore(Evaluation value, int tolerance) {
        if (!("IN_PROGRESS".equals(value.status()) || "COMPLETED".equals(value.status()))) return null;
        BigDecimal adherence = Objects.requireNonNullElse(value.scheduleAdherenceRate(), BigDecimal.ZERO);
        BigDecimal completion = Objects.requireNonNullElse(value.completionRate(), BigDecimal.ZERO);
        BigDecimal onTime = value.completedIssueCount() == 0 ? adherence
                : rate(value.onTimeIssueCount(), value.completedIssueCount());
        long positiveDelayTotal = value.totalPositiveDelayMinutes();
        int checkpoints = Math.max(1, value.startedOperationCount() + value.completedOperationCount());
        BigDecimal averagePositiveDelay = BigDecimal.valueOf(positiveDelayTotal)
                .divide(BigDecimal.valueOf(checkpoints), 4, RoundingMode.HALF_UP);
        BigDecimal delayScale = BigDecimal.valueOf(Math.max(60, tolerance * 4L));
        BigDecimal delayScore = BigDecimal.valueOf(100).subtract(averagePositiveDelay
                        .multiply(BigDecimal.valueOf(100)).divide(delayScale, 4, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO);
        return adherence.multiply(BigDecimal.valueOf(0.35))
                .add(completion.multiply(BigDecimal.valueOf(0.25)))
                .add(onTime.multiply(BigDecimal.valueOf(0.25)))
                .add(delayScore.multiply(BigDecimal.valueOf(0.15)))
                .min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private String conclusion(Evaluation value, BigDecimal actualScore, BigDecimal realizationGap) {
        return switch (value.status()) {
            case "ROLLED_BACK" -> "候选方案已受控回退，停止评价其后续执行效果";
            case "SOURCE_UNAVAILABLE" -> "MES权威执行事实不可用，已保留失败证据并等待自动重采";
            case "DATA_GAP" -> "已发布APS工序与MES执行工序无法完整对应，需要主数据或接口责任人处理";
            case "WAITING_DISPATCH" -> "候选已发布但MES生产任务尚未建立";
            case "WAITING_EXECUTION" -> "MES任务已建立，尚无实际开工、完工或完成数量";
            case "IN_PROGRESS" -> "执行中：完成" + value.completedOperationCount() + "/"
                    + Math.max(value.matchedOperationCount(), value.completedOperationCount()) + "道工序，当前兑现分"
                    + Objects.toString(actualScore, "--") + gapText(realizationGap);
            case "COMPLETED" -> "执行完成：准时计划" + value.onTimeIssueCount() + "个、延期"
                    + value.lateIssueCount() + "个，最终兑现分" + Objects.toString(actualScore, "--")
                    + gapText(realizationGap);
            default -> value.errorMessage();
        };
    }

    private String gapText(BigDecimal gap) {
        return gap == null ? "" : "，较候选预测" + (gap.signum() >= 0 ? "+" : "") + gap.toPlainString() + "分";
    }

    private LocalDateTime nextEvaluationTime(String status, LocalDateTime now) {
        return switch (status) {
            case "COMPLETED", "ROLLED_BACK" -> null;
            case "SOURCE_UNAVAILABLE" -> now.plusMinutes(10);
            case "WAITING_DISPATCH" -> now.plusMinutes(15);
            default -> now.plusMinutes(30);
        };
    }

    private void copyMetrics(Evaluation e, PlnSchedulingEffectReviewEntity target) {
        target.setMesTaskCount(e.mesTaskCount()).setMatchedOperationCount(e.matchedOperationCount())
                .setStartedOperationCount(e.startedOperationCount()).setCompletedOperationCount(e.completedOperationCount())
                .setCompletionRate(e.completionRate()).setStartAdherenceRate(e.startAdherenceRate())
                .setFinishAdherenceRate(e.finishAdherenceRate()).setScheduleAdherenceRate(e.scheduleAdherenceRate())
                .setAverageAbsoluteStartDeviationMinutes(e.averageAbsoluteStartDeviationMinutes())
                .setMaximumAbsoluteStartDeviationMinutes(e.maximumAbsoluteStartDeviationMinutes())
                .setAverageAbsoluteFinishDeviationMinutes(e.averageAbsoluteFinishDeviationMinutes())
                .setMaximumAbsoluteFinishDeviationMinutes(e.maximumAbsoluteFinishDeviationMinutes())
                .setTotalSignedStartDelayMinutes(e.totalSignedStartDelayMinutes())
                .setTotalSignedFinishDelayMinutes(e.totalSignedFinishDelayMinutes())
                .setCompletedIssueCount(e.completedIssueCount()).setOnTimeIssueCount(e.onTimeIssueCount())
                .setLateIssueCount(e.lateIssueCount());
    }

    private void copyMetrics(Evaluation e, PlnSchedulingEffectObservationEntity target) {
        target.setMesTaskCount(e.mesTaskCount()).setMatchedOperationCount(e.matchedOperationCount())
                .setStartedOperationCount(e.startedOperationCount()).setCompletedOperationCount(e.completedOperationCount())
                .setCompletionRate(e.completionRate()).setStartAdherenceRate(e.startAdherenceRate())
                .setFinishAdherenceRate(e.finishAdherenceRate()).setScheduleAdherenceRate(e.scheduleAdherenceRate())
                .setAverageAbsoluteStartDeviationMinutes(e.averageAbsoluteStartDeviationMinutes())
                .setMaximumAbsoluteStartDeviationMinutes(e.maximumAbsoluteStartDeviationMinutes())
                .setAverageAbsoluteFinishDeviationMinutes(e.averageAbsoluteFinishDeviationMinutes())
                .setMaximumAbsoluteFinishDeviationMinutes(e.maximumAbsoluteFinishDeviationMinutes())
                .setTotalSignedStartDelayMinutes(e.totalSignedStartDelayMinutes())
                .setTotalSignedFinishDelayMinutes(e.totalSignedFinishDelayMinutes())
                .setCompletedIssueCount(e.completedIssueCount()).setOnTimeIssueCount(e.onTimeIssueCount())
                .setLateIssueCount(e.lateIssueCount());
    }

    private PlnSchedulingEffectReviewDTO.Detail detailOf(PlnSchedulingEffectReviewEntity review) {
        review.setPendingAction(effectActionService.pending(review));
        return new PlnSchedulingEffectReviewDTO.Detail().setReview(review)
                .setObservations(observationMapper.selectList(
                        new LambdaQueryWrapper<PlnSchedulingEffectObservationEntity>()
                                .eq(PlnSchedulingEffectObservationEntity::getReviewId, review.getId())
                                .orderByDesc(PlnSchedulingEffectObservationEntity::getObservedTime)
                                .orderByDesc(PlnSchedulingEffectObservationEntity::getId)));
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal averageAbs(Collection<Long> values) {
        if (values.isEmpty()) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(values.stream().mapToLong(Math::abs).sum())
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private long maxAbs(Collection<Long> values) {
        return values.stream().mapToLong(Math::abs).max().orElse(0);
    }

    private long sum(Collection<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum();
    }

    private long positiveSum(Collection<Long> values) {
        return values.stream().filter(value -> value > 0).mapToLong(Long::longValue).sum();
    }

    private Long minutes(LocalDateTime planned, LocalDateTime actual) {
        return planned == null || actual == null ? null : Duration.between(planned, actual).toMinutes();
    }

    private boolean positive(String value) {
        if (StrUtil.isBlank(value)) return false;
        try {
            return new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Object jsonValue(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("效果回评证据序列化失败：" + ex.getMessage());
        }
    }

    private String hash(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (StrUtil.isBlank(message)) return ex.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user, String type) {
        return "SCHEDULED".equals(type) || user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user, String type) {
        return "SCHEDULED".equals(type) || user == null ? "系统定时任务" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }

    /**
     * 页面读路径必须有明确的工厂上下文和MDM授权；后台租户巡检不经过该入口。
     */
    private String requireReadFactory() {
        String factoryCode = FactoryContextHolder.getFactoryCode();
        MomUser user = currentUser();
        require(StrUtil.isNotBlank(factoryCode) && user != null && user.getId() != null
                        && TenantContextHolder.getTenantId() != null
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "效果回评查询必须使用真实用户及明确的租户工厂");
        R<Boolean> access = factoryAccessService.check(new MdmFactoryAccessCheck()
                .setUserId(user.getId()).setFactoryCode(factoryCode).setRequiredPermission("read"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
                "当前人员缺少该工厂读取权限或MDM工厂核验不可用");
        return factoryCode;
    }

    /**
     * 人工重采可以改变待确认行动，因此必须验证目标工厂的真实写权限。
     */
    private void requireWriteFactory(PlnSchedulingScenarioEntity scenario) {
        MomUser user = currentUser();
        require(user != null && user.getId() != null
                        && scenario.getTenantId() != null
                        && Objects.equals(scenario.getTenantId(), TenantContextHolder.getTenantId())
                        && StrUtil.isNotBlank(scenario.getFactoryCode())
                        && Objects.equals(scenario.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "人工效果重采必须使用真实用户及明确的目标租户工厂");
        R<Boolean> access = factoryAccessService.check(new MdmFactoryAccessCheck()
                .setUserId(user.getId()).setFactoryCode(scenario.getFactoryCode()).setRequiredPermission("write"));
        require(access != null && access.isOk() && Boolean.TRUE.equals(access.getData()),
                "当前人员缺少该工厂写权限或MDM工厂核验不可用");
    }

    private record PlannedScope(List<PlnSchedulingOperationEntity> operations, Set<String> issueNos,
                                Map<String, LocalDate> demandByIssueNo, String factoryCode,
                                String snapshot, String fingerprint, String identityVersion, Long taskId,
                                Map<String, List<PlnSchedulingOperationEntity>> groups, PlnBatchEffectScope batches) {
        boolean usesNodes() {
            return !LEGACY_IDENTITY.equals(identityVersion);
        }
    }

    private record Evaluation(String status, String errorMessage, Map<String, Object> snapshot,
                              int mesTaskCount, int matchedOperationCount, int startedOperationCount,
                              int completedOperationCount, BigDecimal completionRate, BigDecimal startAdherenceRate,
                              BigDecimal finishAdherenceRate, BigDecimal scheduleAdherenceRate,
                              BigDecimal averageAbsoluteStartDeviationMinutes,
                              long maximumAbsoluteStartDeviationMinutes,
                              BigDecimal averageAbsoluteFinishDeviationMinutes,
                              long maximumAbsoluteFinishDeviationMinutes,
                              long totalSignedStartDelayMinutes, long totalSignedFinishDelayMinutes,
                              long totalPositiveDelayMinutes,
                              int completedIssueCount, int onTimeIssueCount, int lateIssueCount) {
        private static Evaluation dataGap(int operations, int issues, String message) {
            return empty("DATA_GAP", operations, issues, message);
        }

        private static Evaluation rolledBack(int operations, int issues, String message) {
            return empty("ROLLED_BACK", operations, issues, message);
        }

        private static Evaluation empty(String status, int operations, int issues, String message) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("formulaVersion", FORMULA_VERSION);
            snapshot.put("capturedTime", LocalDateTime.now());
            snapshot.put("status", status);
            snapshot.put("message", message);
            snapshot.put("operations", List.of());
            return new Evaluation(status, message, snapshot, 0, 0, 0, 0,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 0,
                    BigDecimal.ZERO.setScale(2), 0, 0, 0, 0, 0, 0, 0);
        }

        private Evaluation withStatus(String newStatus, String error, Map<String, Object> newSnapshot) {
            return new Evaluation(newStatus, error, newSnapshot, mesTaskCount, matchedOperationCount,
                    startedOperationCount, completedOperationCount, completionRate, startAdherenceRate,
                    finishAdherenceRate, scheduleAdherenceRate, averageAbsoluteStartDeviationMinutes,
                    maximumAbsoluteStartDeviationMinutes, averageAbsoluteFinishDeviationMinutes,
                    maximumAbsoluteFinishDeviationMinutes, totalSignedStartDelayMinutes,
                    totalSignedFinishDelayMinutes, totalPositiveDelayMinutes,
                    completedIssueCount, onTimeIssueCount, lateIssueCount);
        }
    }
}
