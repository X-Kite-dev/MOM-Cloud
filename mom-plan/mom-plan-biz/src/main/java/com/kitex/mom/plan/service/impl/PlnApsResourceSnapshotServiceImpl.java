package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsResourceDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsResourceSnapshotService;
import com.kitex.mom.plan.service.aps.collector.PlnApsResourceCollectionContext;
import com.kitex.mom.plan.service.aps.collector.PlnApsResourceCollector;
import com.kitex.mom.plan.service.aps.collector.PlnApsResourceEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 跨模块资源取证编排实现。
 *
 * <p>只调用各模块受控接口。关键模块不可用、证据截断或数据缺口都会进入结构化问题并故障关闭。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnApsResourceSnapshotServiceImpl
        extends ServiceImpl<PlnApsResourceSnapshotMapper, PlnApsResourceSnapshotEntity>
        implements PlnApsResourceSnapshotService {

    private static final Set<String> CLOSED = Set.of("COMPLETED", "CANCELLED", "SUPERSEDED");

    private final PlnApsCaseService caseService;
    private final PlnApsReadinessIssueMapper issueMapper;
    private final OmsOrderDetailsMapper orderDetailsMapper;
    private final PlnMpsLineMapper mpsLineMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueSourceMapper;
    private final List<PlnApsResourceCollector> collectors;
    private final ObjectMapper objectMapper;

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (Exception e) {
            throw new CheckedException("APS来源主键格式不正确");
        }
    }

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsResourceDTO.Snapshot capture(Long caseId, String requestId, String expectedCaseFingerprint) {
        require(caseId != null && caseId > 0, "APS案件主键不能为空");
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= 100,
                "资源取证请求号不能为空且不能超过100个字符");
        require(isFingerprint(expectedCaseFingerprint), "资源取证必须携带有效的案件上下文指纹");
        Scope scope = scope();
        PlnApsCaseDTO caseView = caseService.detail(caseId);
        require(!CLOSED.contains(caseView.getCaseStatus()), "已关闭的APS案件不能刷新资源证据");
        String cleanRequestId = requestId.trim();
        String requestFingerprint = sha256(String.join("|", "APS_RESOURCE_CAPTURE_V1", scope.tenantId().toString(),
                scope.factoryCode(), caseId.toString(), cleanRequestId, expectedCaseFingerprint));
        baseMapper.lock("APS_RESOURCE_CAPTURE:" + scope.tenantId() + ':' + scope.factoryCode() + ':' + cleanRequestId);
        PlnApsResourceSnapshotEntity replay = baseMapper.selectOne(snapshotQuery(scope)
                .eq(PlnApsResourceSnapshotEntity::getRequestId, cleanRequestId).last("LIMIT 1"));
        if (replay != null) {
            require(Objects.equals(replay.getRequestFingerprint(), requestFingerprint)
                            && Objects.equals(replay.getCaseId(), caseId),
                    "同一资源取证请求号已对应不同案件或上下文");
            PlnApsResourceDTO.Snapshot replayView = toDto(replay);
            replayView.setCaseFingerprint(caseView.getContextFingerprint());
            return replayView;
        }
        require(Objects.equals(caseView.getContextFingerprint(), expectedCaseFingerprint),
                "APS案件上下文已变化，请刷新后重新获取资源证据");

        PlnApsResourceCollectionContext context = context(caseView, scope);
        List<PlnApsResourceEvidence> evidence = collect(context);
        SnapshotMaterial material = material(evidence);
        int version = nextVersion(caseId, scope);
        Long snapshotId = IdUtil.getSnowflakeNextId();
        LocalDateTime capturedAt = LocalDateTime.now();
        MomUser user = SecurityUtils.getUser();
        PlnApsResourceSnapshotEntity snapshot = new PlnApsResourceSnapshotEntity()
                .setCaseId(caseId).setSnapshotNo("APS-RS-" + snapshotId)
                .setVersionNo(version).setRequestId(cleanRequestId).setRequestFingerprint(requestFingerprint)
                .setScopeFingerprint(expectedCaseFingerprint).setSourceSummary(json(material.sourceSummary()))
                .setEvidenceRefs(json(material.evidenceRefs())).setReadinessStatus(material.readiness())
                .setModuleStatusSummary(json(material.moduleStatuses())).setComplete(material.complete())
                .setStale(false).setCapturedBy(user.getUsername()).setCapturedByName(user.getUsername())
                .setCapturedTime(capturedAt).setFactoryCode(scope.factoryCode());
        snapshot.setId(snapshotId);
        snapshot.setTenantId(scope.tenantId());
        snapshot.setCreateBy(user.getUsername());
        require(baseMapper.insert(snapshot) == 1, "保存APS资源快照失败");
        persistIssues(caseId, snapshotId, scope, user.getUsername(), evidence);
        PlnApsCaseDTO updated = caseService.bindResourceSnapshot(caseId, snapshotId, material.fingerprint(),
                material.readiness(), cleanRequestId, expectedCaseFingerprint);
        PlnApsResourceDTO.Snapshot result = toDto(snapshot);
        result.setCaseFingerprint(updated.getContextFingerprint()).setResourceFingerprint(material.fingerprint());
        return result;
    }

    @Override
    public PlnApsResourceDTO.Snapshot detail(Long caseId, Long snapshotId) {
        Scope scope = scope();
        caseService.detail(caseId);
        PlnApsResourceSnapshotEntity snapshot = requireSnapshot(caseId, snapshotId, scope);
        return toDto(snapshot);
    }

    /**
     * 供失效复核服务重采当前证据，不落新快照。
     */
    public CurrentEvidence currentEvidence(Long caseId) {
        Scope scope = scope();
        PlnApsCaseDTO caseView = caseService.detail(caseId);
        return new CurrentEvidence(caseView, material(collect(context(caseView, scope))));
    }

    public PlnApsResourceSnapshotEntity requireSnapshot(Long caseId, Long snapshotId, Scope scope) {
        require(snapshotId != null && snapshotId > 0, "APS资源快照主键不能为空");
        PlnApsResourceSnapshotEntity snapshot = baseMapper.selectOne(snapshotQuery(scope)
                .eq(PlnApsResourceSnapshotEntity::getId, snapshotId)
                .eq(PlnApsResourceSnapshotEntity::getCaseId, caseId).last("LIMIT 1"));
        require(snapshot != null, "APS资源快照不存在或不属于当前租户工厂");
        return snapshot;
    }

    public Scope currentScope() {
        return scope();
    }

    private PlnApsResourceCollectionContext context(PlnApsCaseDTO caseView, Scope scope) {
        List<PlnApsResourceCollectionContext.SourceRef> refs = Optional.ofNullable(caseView.getSources())
                .orElse(List.of()).stream().map(source -> new PlnApsResourceCollectionContext.SourceRef(
                        source.getSourceType(), parseId(source.getSourceId()), source.getSourceCode(),
                        source.getSourceVersion(), source.getSourceFingerprint())).toList();
        Set<String> planNoSet = new TreeSet<>();
        Set<String> productCodeSet = new TreeSet<>();
        for (PlnApsResourceCollectionContext.SourceRef source : refs) {
            switch (source.type()) {
                case "OMS_ORDER" -> orderDetailsMapper.selectList(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                                .eq(OmsOrderDetailsEntity::getOrderCode, source.code())
                                .eq(OmsOrderDetailsEntity::getFactoryCode, scope.factoryCode()))
                        .stream().map(OmsOrderDetailsEntity::getProductCode).filter(StrUtil::isNotBlank)
                        .forEach(productCodeSet::add);
                case "MPS_VERSION" -> mpsLineMapper.selectList(new LambdaQueryWrapper<PlnMpsLineEntity>()
                                .eq(PlnMpsLineEntity::getVersionId, source.id())
                                .eq(PlnMpsLineEntity::getTenantId, scope.tenantId())
                                .eq(PlnMpsLineEntity::getFactoryCode, scope.factoryCode()))
                        .stream().map(PlnMpsLineEntity::getProductCode).filter(StrUtil::isNotBlank)
                        .forEach(productCodeSet::add);
                case "MRP_LINE" -> {
                    PlanMatMrpEntity row = mrpMapper.selectById(source.id());
                    if (row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                            && Objects.equals(row.getFactoryCode(), scope.factoryCode()) && StrUtil.isNotBlank(row.getMatCode())) {
                        productCodeSet.add(row.getMatCode());
                    }
                }
                case "PRODUCTION_PLAN" -> {
                    PlnIssueEntity row = issueSourceMapper.selectById(source.id());
                    if (row != null && Objects.equals(row.getTenantId(), scope.tenantId())
                            && Objects.equals(row.getFactoryCode(), scope.factoryCode())) {
                        if (StrUtil.isNotBlank(row.getProductCode())) productCodeSet.add(row.getProductCode());
                        if (StrUtil.isNotBlank(row.getIssueNo())) planNoSet.add(row.getIssueNo());
                    }
                }
                default -> {
                }
            }
        }
        List<String> planNos = List.copyOf(planNoSet);
        List<String> productCodes = List.copyOf(productCodeSet);
        LocalDateTime start = Optional.ofNullable(caseView.getCreateTime()).orElse(LocalDateTime.now())
                .withSecond(0).withNano(0);
        return new PlnApsResourceCollectionContext(scope.tenantId(), scope.factoryCode(), parseId(caseView.getId()),
                caseView.getContextFingerprint(), start, start.plusDays(30), refs, productCodes, planNos, List.of(),
                caseView.getPlanningPolicyId(), caseView.getPlanningPolicyVersion(), caseView.getPlanningPolicyFingerprint(),
                caseView.getMrpAuthority(), caseView.getFormalPlanContractId(), caseView.getFormalPlanContractFingerprint());
    }

    private List<PlnApsResourceEvidence> collect(PlnApsResourceCollectionContext context) {
        return collectors.stream().sorted(Comparator.comparingInt(PlnApsResourceCollector::order))
                .map(collector -> safeCollect(collector, context)).toList();
    }

    private PlnApsResourceEvidence safeCollect(PlnApsResourceCollector collector,
                                               PlnApsResourceCollectionContext context) {
        try {
            PlnApsResourceEvidence result = collector.collect(context);
            if (result == null || !Objects.equals(result.module(), collector.module())) {
                throw new IllegalStateException("采集器返回空证据或模块不匹配");
            }
            return result;
        } catch (Exception error) {
            String message = StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName());
            var gap = new PlnApsResourceEvidence.Gap(collector.module() + "_COLLECTOR_FAILED", "INTEGRATION",
                    "BLOCKER", "MODULE", collector.module(), collector.module() + "资源采集失败",
                    message.length() > 300 ? message.substring(0, 300) : message,
                    "/plan/plnSchedulingWorkbench/index", Map.of("module", collector.module()));
            String fingerprint = sha256(json(Map.of("module", collector.module(), "error", gap)));
            return new PlnApsResourceEvidence(collector.module(), "UNAVAILABLE", true, "UNAVAILABLE",
                    LocalDateTime.now(), Map.of("available", false), List.of(), List.of(gap), fingerprint);
        }
    }

    private SnapshotMaterial material(List<PlnApsResourceEvidence> evidence) {
        Map<String, Object> sources = new LinkedHashMap<>();
        Map<String, PlnApsResourceDTO.ModuleStatus> statuses = new LinkedHashMap<>();
        List<String> refs = new ArrayList<>();
        int blocker = 0;
        int warning = 0;
        for (PlnApsResourceEvidence item : evidence) {
            sources.put(item.module(), item.summary());
            refs.addAll(item.evidenceRefs());
            int gapCount = item.gaps().size();
            blocker += (int) item.gaps().stream().filter(gap -> "BLOCKER".equals(gap.severity())).count();
            warning += (int) item.gaps().stream().filter(gap -> "WARNING".equals(gap.severity())).count();
            statuses.put(item.module(), new PlnApsResourceDTO.ModuleStatus().setModule(item.module())
                    .setStatus(item.status()).setCritical(item.critical()).setSourceVersion(item.sourceVersion())
                    .setSourceFingerprint(item.contentFingerprint()).setObservedAt(item.observedAt())
                    .setEvidenceCount(item.evidenceRefs().size()).setGapCount(gapCount)
                    .setMessage("COMPLETE".equals(item.status()) ? "证据完整" :
                            ("PARTIAL".equals(item.status()) ? "证据存在缺口" : "模块不可用")));
        }
        List<String> normalizedRefs = refs.stream().filter(Objects::nonNull).distinct().sorted().toList();
        String readiness = blocker > 0 ? "BLOCKED" : (warning > 0 ? "WARNING" : "READY");
        boolean complete = evidence.stream().filter(PlnApsResourceEvidence::critical)
                .allMatch(item -> "COMPLETE".equals(item.status())) && blocker == 0;
        String fingerprint = resourceFingerprint(sources, normalizedRefs, statuses);
        return new SnapshotMaterial(sources, normalizedRefs, statuses, readiness, complete, blocker, warning, fingerprint);
    }

    private void persistIssues(Long caseId, Long snapshotId, Scope scope, String operator,
                               List<PlnApsResourceEvidence> evidence) {
        for (PlnApsResourceEvidence item : evidence)
            for (PlnApsResourceEvidence.Gap gap : item.gaps()) {
                PlnApsReadinessIssueEntity issue = new PlnApsReadinessIssueEntity()
                        .setCaseId(caseId).setSnapshotId(snapshotId).setIssueCode(gap.code())
                        .setCategory(gap.category()).setSeverity(gap.severity()).setSourceModule(item.module())
                        .setSourceType(gap.sourceType()).setSourceCode(gap.sourceCode()).setSourceVersion(item.sourceVersion())
                        .setSourceFingerprint(item.contentFingerprint()).setTitle(gap.title()).setDescription(gap.description())
                        .setResolutionRoute(gap.resolutionRoute()).setResolutionParams(json(gap.resolutionParams()))
                        .setIssueStatus("OPEN").setFactoryCode(scope.factoryCode());
                issue.setId(IdUtil.getSnowflakeNextId());
                issue.setTenantId(scope.tenantId());
                issue.setCreateBy(operator);
                require(issueMapper.insert(issue) == 1, "保存APS资源预检问题失败");
            }
    }

    private PlnApsResourceDTO.Snapshot toDto(PlnApsResourceSnapshotEntity snapshot) {
        Scope scope = scope();
        List<PlnApsReadinessIssueEntity> issues = issueMapper.selectList(new LambdaQueryWrapper<PlnApsReadinessIssueEntity>()
                .eq(PlnApsReadinessIssueEntity::getTenantId, scope.tenantId())
                .eq(PlnApsReadinessIssueEntity::getFactoryCode, scope.factoryCode())
                .eq(PlnApsReadinessIssueEntity::getSnapshotId, snapshot.getId())
                .orderByDesc(PlnApsReadinessIssueEntity::getSeverity).orderByAsc(PlnApsReadinessIssueEntity::getId));
        Map<String, PlnApsResourceDTO.ModuleStatus> modules = read(snapshot.getModuleStatusSummary(), new TypeReference<>() {
        });
        int blockers = (int) issues.stream().filter(issue -> "BLOCKER".equals(issue.getSeverity())).count();
        int warnings = (int) issues.stream().filter(issue -> "WARNING".equals(issue.getSeverity())).count();
        int infos = issues.size() - blockers - warnings;
        return new PlnApsResourceDTO.Snapshot().setSnapshotId(text(snapshot.getId()))
                .setSnapshotNo(snapshot.getSnapshotNo()).setVersionNo(snapshot.getVersionNo())
                .setCaseId(text(snapshot.getCaseId())).setCaseFingerprint(snapshot.getScopeFingerprint())
                .setResourceFingerprint(persistedFingerprint(snapshot, modules)).setReadinessStatus(snapshot.getReadinessStatus())
                .setComplete(snapshot.getComplete()).setStale(snapshot.getStale()).setStaleReason(snapshot.getStaleReason())
                .setCapturedTime(snapshot.getCapturedTime()).setModules(modules)
                .setCounts(new PlnApsResourceDTO.Counts().setInfo(infos).setWarning(warnings).setBlocker(blockers))
                .setIssues(issues.stream().map(this::issueDto).toList());
    }

    private String persistedFingerprint(PlnApsResourceSnapshotEntity snapshot,
                                        Map<String, PlnApsResourceDTO.ModuleStatus> modules) {
        Map<String, Object> sources = read(snapshot.getSourceSummary(), new TypeReference<>() {
        });
        List<String> refs = read(snapshot.getEvidenceRefs(), new TypeReference<>() {
        });
        return resourceFingerprint(sources, refs, modules);
    }

    private String resourceFingerprint(Map<String, Object> sources, List<String> refs,
                                       Map<String, PlnApsResourceDTO.ModuleStatus> statuses) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sources", sources);
        canonical.put("refs", refs);
        canonical.put("statuses", statuses);
        JsonNode tree = objectMapper.valueToTree(canonical);
        stripVolatileTime(tree);
        return sha256(tree.toString());
    }

    private void stripVolatileTime(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(List.of("observedAt", "capturedAt"));
            object.elements().forEachRemaining(this::stripVolatileTime);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::stripVolatileTime);
        }
    }

    private PlnApsResourceDTO.Issue issueDto(PlnApsReadinessIssueEntity issue) {
        return new PlnApsResourceDTO.Issue().setId(text(issue.getId())).setIssueCode(issue.getIssueCode())
                .setCategory(issue.getCategory()).setSeverity(issue.getSeverity()).setSourceModule(issue.getSourceModule())
                .setSourceType(issue.getSourceType()).setSourceCode(issue.getSourceCode())
                .setSourceVersion(issue.getSourceVersion()).setImpactedObjectType(issue.getImpactedObjectType())
                .setImpactedObjectId(text(issue.getImpactedObjectId())).setImpactedObjectCode(issue.getImpactedObjectCode())
                .setTitle(issue.getTitle()).setDescription(issue.getDescription())
                .setResolutionRoute(issue.getResolutionRoute()).setIssueStatus(issue.getIssueStatus());
    }

    private int nextVersion(Long caseId, Scope scope) {
        PlnApsResourceSnapshotEntity latest = baseMapper.selectOne(snapshotQuery(scope)
                .eq(PlnApsResourceSnapshotEntity::getCaseId, caseId)
                .orderByDesc(PlnApsResourceSnapshotEntity::getVersionNo).last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private LambdaQueryWrapper<PlnApsResourceSnapshotEntity> snapshotQuery(Scope scope) {
        return new LambdaQueryWrapper<PlnApsResourceSnapshotEntity>()
                .eq(PlnApsResourceSnapshotEntity::getTenantId, scope.tenantId())
                .eq(PlnApsResourceSnapshotEntity::getFactoryCode, scope.factoryCode());
    }

    private Scope scope() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory), "智能APS缺少租户或factory-code工厂上下文");
        return new Scope(tenant, factory);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CheckedException("APS资源快照无法规范序列化");
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new CheckedException("APS资源快照内容损坏，禁止继续使用");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少SHA-256", e);
        }
    }

    public record Scope(Long tenantId, String factoryCode) {
    }

    public record CurrentEvidence(PlnApsCaseDTO caseView, SnapshotMaterial material) {
    }

    public record SnapshotMaterial(Map<String, Object> sourceSummary, List<String> evidenceRefs,
                                   Map<String, PlnApsResourceDTO.ModuleStatus> moduleStatuses,
                                   String readiness, boolean complete, int blockerCount,
                                   int warningCount, String fingerprint) {
    }
}
