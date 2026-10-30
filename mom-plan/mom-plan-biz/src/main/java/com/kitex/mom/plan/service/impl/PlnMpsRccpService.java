package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogQueryDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.plan.dto.PlnMpsCapacityBalanceDTO;
import com.kitex.mom.plan.dto.PlnMpsCapacityDTO;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnMpsPolicyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MPS阶段的粗能力承诺（RCCP）。
 *
 * <p>RCCP只用于在主生产计划发布前发现产线/工作中心级能力冲突，不替代APS的工序、
 * 设备、人员和工装有限能力排程。计算优先采用默认工艺路线的产线与生产速率；仅在
 * 路线速率缺失时，才回退到物料标准机器/人员工时。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsRccpService {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final int SCALE = 6;

    private final PlnMpsEffectiveCommitmentReader effectiveReader;
    private final RemoteMaterialService materialService;
    private final RemoteProcessRouteService processRouteService;
    private final RemoteClassesService classesService;
    private final RemoteCalendarService calendarService;
    private final RemoteMdmChangeoverService changeoverService;
    private final PlnMpsLotSizingService lotSizingService;

    /**
     * 计算并写入候选行的RCCP冻结结果；BLOCK模式下任何超载或证据不可核验都会拒绝提交。
     */
    public void assess(String factoryCode, PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> candidates) {
        assess(factoryCode, policy, candidates, true, Set.of());
    }

    /**
     * 剩余承诺只替代精确原桶，绝不扩大为整个产品需求。
     */
    public PlnMpsCapacityDTO.Preview previewRemaining(String factory, PlnMpsPolicyEntity policy,
                                                      List<PlnMpsLineEntity> candidates, Long originalLineId,
                                                      boolean enforcePolicy) {
        assess(factory, policy, candidates, enforcePolicy, Set.of(originalLineId));
        return previewResult(policy, candidates);
    }

    private void assess(String factoryCode, PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> candidates,
                        boolean enforcePolicy, Set<Long> preciseReplacement) {
        String mode = mode(policy);
        BigDecimal maxLoadRate = maxLoadRate(policy);
        if ("OFF".equals(mode)) {
            candidates.forEach(line -> line.setCapacityStatus("NOT_REQUIRED")
                    .setCapacityVerified(false));
            return;
        }

        Set<Long> replacedDemandIds = candidates.stream().map(PlnMpsLineEntity::getDemandId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var projection = effectiveReader.read(factoryCode, preciseReplacement.isEmpty() ? replacedDemandIds : Set.of(),
                preciseReplacement, candidates.stream().map(PlnMpsLineEntity::getVersionId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()));
        List<PlnMpsLineEntity> active = projection.rows();

        Map<String, MdmMaterialEntity> materials = loadMaterials(candidates, active);
        RouteCatalog routes = loadRoutes(candidates, active);
        LocalDate lastCandidate = candidates.stream().map(PlnMpsLineEntity::getPlannedDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(LocalDate.now());
        CapacityCalendar calendar = loadCalendar(factoryCode, LocalDate.now(),
                lastCandidate.isAfter(LocalDate.now()) ? lastCandidate : LocalDate.now());

        Map<PlnMpsLineEntity, CapacityBasis> candidateBasis = new IdentityHashMap<>();
        for (PlnMpsLineEntity line : candidates) {
            candidateBasis.put(line, resolveBasis(line, materials, routes));
        }
        Map<PlnMpsLineEntity, CapacityBasis> activeBasis = new IdentityHashMap<>();
        for (PlnMpsLineEntity line : active) {
            activeBasis.put(line, frozenOrResolvedBasis(line, materials, routes));
        }
        LineChangeoverCatalog changeovers = loadLineChangeoverCatalog(factoryCode, candidates, active);
        // 一次最终序列同时核算候选和当前余额；候选造成的既有余额换型不可漏计。
        applyLineChangeovers(candidates, active, candidateBasis, activeBasis, changeovers);
        Map<String, BigDecimal> committedByBucket = new LinkedHashMap<>();
        List<Map<String, Object>> activeEvidence = new ArrayList<>();
        List<String> unverifiedActiveDemands = new ArrayList<>(projection.gaps());
        for (PlnMpsLineEntity line : active) {
            CapacityBasis basis = activeBasis.get(line);
            if (!basis.verified || line.getPlannedDate() == null) {
                unverifiedActiveDemands.add(line.getDemandNo());
                continue;
            }
            if (!line.getPlannedDate().isBefore(LocalDate.now())) {
                String bucket = bucket(basis.resourceCode, line.getPlannedDate());
                committedByBucket.merge(bucket, basis.demandMinutes, BigDecimal::add);
                activeEvidence.add(evidenceLine(line, basis));
            }
        }
        loadOverdue(active, activeBasis, calendar, policy, committedByBucket, activeEvidence, unverifiedActiveDemands);
        boolean activeCommitmentsVerified = unverifiedActiveDemands.isEmpty();

        Map<String, BigDecimal> candidateByBucket = new LinkedHashMap<>();
        for (PlnMpsLineEntity line : candidates) {
            CapacityBasis basis = candidateBasis.get(line);
            if (basis.verified && line.getPlannedDate() != null) {
                candidateByBucket.merge(bucket(basis.resourceCode, line.getPlannedDate()),
                        basis.demandMinutes, BigDecimal::add);
            }
        }

        for (PlnMpsLineEntity line : candidates) {
            CapacityBasis basis = candidateBasis.get(line);
            String bucket = basis.resourceCode == null || line.getPlannedDate() == null
                    ? null : bucket(basis.resourceCode, line.getPlannedDate());
            BigDecimal committed = bucket == null ? BigDecimal.ZERO
                    : committedByBucket.getOrDefault(bucket, BigDecimal.ZERO);
            BigDecimal candidateLoad = bucket == null ? BigDecimal.ZERO
                    : candidateByBucket.getOrDefault(bucket, BigDecimal.ZERO);
            CalendarDay day = calendar.days.get(line.getPlannedDate());
            BigDecimal available = day == null ? null : day.availableMinutes;
            // 无法识别资源的旧活动承诺可能落入任一候选容量桶，不能静默忽略后宣称产能可信。
            boolean verified = basis.verified && day != null && day.verified && activeCommitmentsVerified;
            BigDecimal after = committed.add(candidateLoad);
            BigDecimal loadRate = available == null || available.signum() == 0 ? null
                    : after.multiply(BigDecimal.valueOf(100)).divide(available, 2, RoundingMode.HALF_UP);
            boolean overload = verified && (available.signum() == 0
                    ? after.signum() > 0
                    : after.multiply(BigDecimal.valueOf(100)).compareTo(
                    available.multiply(maxLoadRate)) > 0);
            String status = !verified ? "UNVERIFIED" : overload ? "OVERLOAD" : "PASS";
            String evaluationMessage = !basis.verified ? basis.message
                    : day == null || !day.verified ? "工作日历或班次能力不完整"
                    : !activeCommitmentsVerified ? "存在无法核验的活动MPS承诺："
                    + String.join("、", unverifiedActiveDemands)
                    : overload ? "该资源日期桶超过允许负荷率"
                    : "粗能力承诺未超过策略阈值";

            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("factoryCode", factoryCode);
            snapshot.put("plannedDate", line.getPlannedDate());
            snapshot.put("mode", mode);
            snapshot.put("maxLoadRate", normalized(maxLoadRate));
            snapshot.put("resourceCode", basis.resourceCode);
            snapshot.put("resourceName", basis.resourceName);
            snapshot.put("basis", basis.basis);
            snapshot.put("productCode", line.getProductCode());
            snapshot.put("planQuantity", normalized(line.getPlanQuantity()));
            snapshot.put("unitMinutes", normalized(basis.unitMinutes));
            snapshot.put("processingMinutes", normalized(line.getCapacityProcessingMinutes()));
            snapshot.put("changeoverMinutes", normalized(line.getCapacityChangeoverMinutes()));
            snapshot.put("changeoverFromFamilyCode", line.getCapacityFromFamilyCode());
            snapshot.put("changeoverToFamilyCode", line.getCapacityToFamilyCode());
            snapshot.put("changeoverRuleNo", line.getCapacityChangeoverRuleNo());
            snapshot.put("changeoverRuleVersion", line.getCapacityChangeoverRuleVersion());
            snapshot.put("lineChangeoverCatalogFingerprint", changeovers.evidenceFingerprint);
            snapshot.put("demandMinutes", normalized(basis.demandMinutes));
            snapshot.put("committedMinutes", normalized(committed));
            snapshot.put("candidateBucketMinutes", normalized(candidateLoad));
            snapshot.put("availableMinutes", normalized(available));
            snapshot.put("loadRate", normalized(loadRate));
            snapshot.put("capacityStatus", status);
            snapshot.put("basisVerified", basis.verified);
            snapshot.put("basisMessage", basis.message);
            snapshot.put("activeCommitmentsVerified", activeCommitmentsVerified);
            snapshot.put("unverifiedActiveDemands", unverifiedActiveDemands);
            snapshot.put("calendar", day == null ? null : day.snapshot);
            snapshot.put("activeCommitments", activeEvidence.stream()
                    .filter(item -> Objects.equals(item.get("resourceCode"), basis.resourceCode)
                            && Objects.equals(item.get("plannedDate"), line.getPlannedDate()))
                    .toList());
            String snapshotJson = JSONUtil.toJsonStr(snapshot);
            line.setCapacityResourceCode(basis.resourceCode)
                    .setCapacityResourceName(basis.resourceName)
                    .setCapacityBasis(basis.basis)
                    .setCapacityDemandMinutes(scale(basis.demandMinutes))
                    .setCapacityCommittedMinutes(scale(committed))
                    .setCapacityAvailableMinutes(scale(available))
                    .setCapacityLoadRate(loadRate)
                    .setCapacityStatus(status)
                    .setCapacityVerified(verified)
                    .setCapacityEvaluationMessage(evaluationMessage)
                    .setCapacityEvidenceSnapshot(snapshotJson)
                    .setCapacityEvidenceFingerprint(hash(snapshotJson));
        }

        if (enforcePolicy && "BLOCK".equals(mode)) {
            List<PlnMpsLineEntity> blocked = candidates.stream()
                    .filter(line -> !"PASS".equals(line.getCapacityStatus())).toList();
            if (!blocked.isEmpty()) {
                String summary = blocked.stream().limit(8)
                        .map(line -> line.getDemandNo() + "[" + line.getCapacityStatus() + "]")
                        .collect(Collectors.joining("、"));
                throw new CheckedException("RCCP阻断模式下存在超载或不可核验需求：" + summary
                        + (blocked.size() > 8 ? "等" + blocked.size() + "条" : ""));
            }
        }
    }

    /**
     * 复核时用当前活动承诺重新计算，任何RCCP证据漂移都要求退回重编。
     */
    public void verify(String factoryCode, PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> lines) {
        Map<String, String> frozen = new LinkedHashMap<>();
        lines.forEach(line -> frozen.put(capacityLineKey(line), line.getCapacityEvidenceFingerprint()));
        assess(factoryCode, policy, lines);
        for (PlnMpsLineEntity line : lines) {
            if (!Objects.equals(frozen.get(capacityLineKey(line)), line.getCapacityEvidenceFingerprint())) {
                throw new CheckedException("MPS候选RCCP证据已变化，请驳回后重新编制："
                        + line.getDemandNo() + "#" + line.getBucketNo());
            }
        }
    }

    public PlnMpsCapacityDTO.Preview preview(PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> lines) {
        if (!"OFF".equals(mode(policy))) {
            assess(lines.get(0).getFactoryCode(), policy, lines, false, Set.of());
        } else {
            lines.forEach(line -> line.setCapacityStatus("NOT_REQUIRED").setCapacityVerified(false));
        }
        return previewResult(policy, lines);
    }

    private PlnMpsCapacityDTO.Preview previewResult(PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> lines) {
        String overall = lines.stream().anyMatch(line -> "OVERLOAD".equals(line.getCapacityStatus()))
                ? ("BLOCK".equals(mode(policy)) ? "BLOCKED" : "WARNING")
                : lines.stream().anyMatch(line -> "UNVERIFIED".equals(line.getCapacityStatus()))
                ? ("BLOCK".equals(mode(policy)) ? "BLOCKED" : "WARNING") : "PASS";
        return new PlnMpsCapacityDTO.Preview().setMode(mode(policy)).setMaxLoadRate(maxLoadRate(policy))
                .setOverallStatus(overall).setLines(lines.stream().map(this::toPreviewLine).toList());
    }

    private PlnMpsCapacityDTO.Line toPreviewLine(PlnMpsLineEntity line) {
        String defaultMessage = switch (StrUtil.blankToDefault(line.getCapacityStatus(), "UNVERIFIED")) {
            case "PASS" -> "粗能力承诺未超过策略阈值";
            case "OVERLOAD" -> "该资源日期桶超过允许负荷率";
            case "NOT_REQUIRED" -> "当前策略未启用RCCP";
            default -> "工艺速率、资源或工作日历不完整，无法形成可信承诺";
        };
        String message = StrUtil.blankToDefault(line.getCapacityEvaluationMessage(), defaultMessage);
        return new PlnMpsCapacityDTO.Line().setDemandId(line.getDemandId()).setDemandNo(line.getDemandNo())
                .setBucketNo(line.getBucketNo()).setBucketKey(line.getBucketKey())
                .setProductCode(line.getProductCode()).setPlanQuantity(line.getPlanQuantity())
                .setPlannedDate(line.getPlannedDate())
                .setResourceCode(line.getCapacityResourceCode()).setResourceName(line.getCapacityResourceName())
                .setCapacityBasis(line.getCapacityBasis())
                .setProcessingMinutes(line.getCapacityProcessingMinutes())
                .setChangeoverMinutes(line.getCapacityChangeoverMinutes())
                .setFromFamilyCode(line.getCapacityFromFamilyCode())
                .setToFamilyCode(line.getCapacityToFamilyCode())
                .setChangeoverRuleNo(line.getCapacityChangeoverRuleNo())
                .setChangeoverRuleVersion(line.getCapacityChangeoverRuleVersion())
                .setDemandMinutes(line.getCapacityDemandMinutes())
                .setCommittedMinutes(line.getCapacityCommittedMinutes())
                .setAvailableMinutes(line.getCapacityAvailableMinutes()).setLoadRate(line.getCapacityLoadRate())
                .setStatus(line.getCapacityStatus()).setVerified(line.getCapacityVerified())
                .setMessage(message).setEvidenceFingerprint(line.getCapacityEvidenceFingerprint());
    }

    /**
     * 生成不绕过审批的RCCP负荷平衡建议。
     *
     * <p>MIN_CHANGE_FORWARD尽量保留原计划日，仅把超出容量的数量向后移动；
     * DELIVERY_FIRST按优先级和交期从冻结区后的最早可用工作日重新装载，允许提前生产。
     * 两种策略都以6位数量精度分配，并在返回前再次运行RCCP验证。</p>
     */
    public PlnMpsCapacityBalanceDTO.Result balance(String factoryCode, PlnMpsPolicyEntity policy,
                                                   List<PlnMpsLineEntity> candidates,
                                                   LocalDate horizonStart, LocalDate horizonEnd,
                                                   LocalDate freezeThrough) {
        PlnMpsCapacityDTO.Preview original = preview(policy, candidates);
        PlnMpsLotSizingService.Catalog lotCatalog = lotSizingService.load(factoryCode,
                candidates.stream().map(PlnMpsLineEntity::getProductCode).toList());
        PlnMpsCapacityBalanceDTO.Result result = new PlnMpsCapacityBalanceDTO.Result()
                .setMode(mode(policy)).setMaxLoadRate(maxLoadRate(policy)).setOriginal(original)
                .setLotPolicyEvidenceFingerprint(lotCatalog.evidenceFingerprint())
                .setProductsWithoutLotPolicy(lotCatalog.productsWithoutPolicy())
                .setMessages(new ArrayList<>()).setProposals(new ArrayList<>());
        boolean lotSizingRequired = candidates.stream().anyMatch(line -> {
            MdmProductionLotPolicyEntity lotPolicy = lotCatalog.policies().get(line.getProductCode());
            return lotPolicy != null && lotSizingService.violation(line.getPlanQuantity(), lotPolicy) != null;
        });
        if ("OFF".equals(mode(policy))) {
            if (!lotSizingRequired) {
                return result.setStatus("PASS").setMessages(List.of(
                        "当前候选已满足正式生产批量策略；工厂未启用RCCP，本次不评价产能"));
            }
            PlnMpsCapacityBalanceDTO.Proposal proposal = lotSizingProposal(candidates, lotCatalog);
            result.setProposals(List.of(proposal));
            return result.setStatus(Boolean.TRUE.equals(proposal.getFeasible()) ? "PROPOSED" : "UNRESOLVED")
                    .setMessages(Boolean.TRUE.equals(proposal.getFeasible())
                            ? List.of("已生成数量守恒的生产批量整形建议；应用后仍须提交并由另一岗位复核")
                            : List.of("需求总量无法在当前正式批量策略下形成数量守恒的合法批次"));
        }
        if ("PASS".equals(original.getOverallStatus()) && !lotSizingRequired) {
            return result.setStatus("PASS")
                    .setMessages(List.of("当前候选满足正式生产批量策略且未超过RCCP阈值，无需调整"));
        }
        BalanceContext context = balanceContext(factoryCode, policy, candidates, horizonStart, horizonEnd,
                lotCatalog);
        if (!context.messages.isEmpty()) {
            return result.setStatus("UNVERIFIED").setMessages(context.messages);
        }
        List<PlnMpsCapacityBalanceDTO.Proposal> proposals = new ArrayList<>();
        proposals.add(allocateProposal("MIN_CHANGE_FORWARD", "最小变更顺延", policy,
                candidates, context, horizonStart, horizonEnd, freezeThrough));
        proposals.add(allocateProposal("DELIVERY_FIRST", "交期优先前后平衡", policy,
                candidates, context, horizonStart, horizonEnd, freezeThrough));
        if (candidates.stream().anyMatch(line -> context.routes.qualified
                .getOrDefault(line.getProductCode(), List.of()).size() > 1)) {
            proposals.add(allocateProposal("QUALIFIED_RESOURCE_BALANCE", "合格替代产线平衡", policy,
                    candidates, context, horizonStart, horizonEnd, freezeThrough));
        }
        result.setProposals(proposals);
        boolean feasible = proposals.stream().anyMatch(item -> Boolean.TRUE.equals(item.getFeasible()));
        if (feasible) {
            result.setStatus("PROPOSED").setMessages(List.of(
                    "建议仅用于改写当前候选时间桶；应用后仍需重新评估、提交并由另一岗位复核发布"));
        } else {
            result.setStatus("UNRESOLVED").setMessages(List.of(
                    "当前滚动窗口内的可信剩余产能不足，建议扩大窗口、补齐班次能力或由计划员调整需求优先级"));
        }
        return result;
    }

    private BalanceContext balanceContext(String factoryCode, PlnMpsPolicyEntity policy,
                                          List<PlnMpsLineEntity> candidates,
                                          LocalDate horizonStart, LocalDate horizonEnd,
                                          PlnMpsLotSizingService.Catalog lotCatalog) {
        Set<Long> replacedDemandIds = candidates.stream().map(PlnMpsLineEntity::getDemandId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        var projection = effectiveReader.read(factoryCode, replacedDemandIds, Set.of(),
                candidates.stream().map(PlnMpsLineEntity::getVersionId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<PlnMpsLineEntity> active = projection.rows();
        Map<String, MdmMaterialEntity> materials = loadMaterials(candidates, active);
        RouteCatalog routes = loadRoutes(candidates, active);
        CapacityCalendar calendar = loadCalendar(factoryCode, LocalDate.now().isBefore(horizonStart) ? LocalDate.now() : horizonStart, horizonEnd);
        Map<PlnMpsLineEntity, CapacityBasis> basisByLine = new IdentityHashMap<>();
        List<String> messages = new ArrayList<>(projection.gaps());
        for (PlnMpsLineEntity line : candidates) {
            CapacityBasis basis = resolveBasis(line, materials, routes);
            basisByLine.put(line, basis);
            if (!basis.verified || basis.unitMinutes == null || basis.unitMinutes.signum() <= 0) {
                messages.add(line.getDemandNo() + "#" + line.getBucketNo() + "：" + basis.message);
            }
        }
        Map<String, BigDecimal> committed = new LinkedHashMap<>();
        Map<PlnMpsLineEntity, CapacityBasis> activeBasis = new IdentityHashMap<>();
        for (PlnMpsLineEntity line : active) {
            activeBasis.put(line, frozenOrResolvedBasis(line, materials, routes));
        }
        if (active.stream().anyMatch(this::currentQuantityBasis)) {
            applyLineChangeovers(List.of(), active, new IdentityHashMap<>(), activeBasis,
                    loadLineChangeoverCatalog(factoryCode, candidates, active));
        }
        for (PlnMpsLineEntity line : active) {
            CapacityBasis basis = activeBasis.get(line);
            if (!basis.verified || line.getPlannedDate() == null) {
                messages.add("活动MPS承诺无法核验：" + line.getDemandNo());
                continue;
            }
            if (!line.getPlannedDate().isBefore(LocalDate.now()) && !line.getPlannedDate().isAfter(horizonEnd)) {
                committed.merge(bucket(basis.resourceCode, line.getPlannedDate()),
                        basis.demandMinutes, BigDecimal::add);
            }
        }
        loadOverdue(active, activeBasis, calendar, policy, committed, new ArrayList<>(), messages);
        Map<String, BigDecimal> remaining = new LinkedHashMap<>();
        Set<String> resources = basisByLine.values().stream().filter(item -> item.verified)
                .map(CapacityBasis::resourceCode).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        contextResources(routes, candidates).forEach(resources::add);
        BigDecimal threshold = maxLoadRate(policy).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        for (String resource : resources) {
            for (LocalDate date = horizonStart; !date.isAfter(horizonEnd); date = date.plusDays(1)) {
                CalendarDay day = calendar.days.get(date);
                if (day == null || !day.verified || day.availableMinutes == null) continue;
                String key = bucket(resource, date);
                BigDecimal limit = day.availableMinutes.multiply(threshold);
                remaining.put(key, scale(limit.subtract(committed.getOrDefault(key, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO)));
            }
        }
        if (remaining.values().stream().noneMatch(value -> value != null && value.signum() > 0)) {
            messages.add("滚动窗口内没有可核验的剩余工作日能力");
        }
        return new BalanceContext(basisByLine, remaining, lotCatalog.policies(), routes,
                lotCatalog.productsWithoutPolicy(), lotCatalog.evidenceFingerprint(), messages);
    }

    /**
     * 未启用RCCP时只做批量整形，不借用未知产能，也不改变计划日期。
     */
    private PlnMpsCapacityBalanceDTO.Proposal lotSizingProposal(
            List<PlnMpsLineEntity> candidates, PlnMpsLotSizingService.Catalog catalog) {
        List<Allocation> allocations = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        BigDecimal unresolved = BigDecimal.ZERO;
        List<PlnMpsLineEntity> sources = allocationSources(candidates, catalog.policies());
        for (PlnMpsLineEntity source : sources) {
            MdmProductionLotPolicyEntity policy = catalog.policies().get(source.getProductCode());
            if (policy == null) {
                allocations.add(new Allocation(source, source.getPlannedDate(), source.getPlanQuantity(),
                        null, null));
                continue;
            }
            List<BigDecimal> quantities = lotSizingService.split(source.getPlanQuantity(), policy);
            if (quantities.isEmpty()) {
                unresolved = unresolved.add(source.getPlanQuantity());
                messages.add(source.getDemandNo() + "：需求总量" + normalized(source.getPlanQuantity())
                        + "无法同时满足最小/最大批量与整批倍数，未找到数量守恒的合法批次数");
                continue;
            }
            if (allocations.size() + quantities.size() > 500) {
                throw new CheckedException("批量整形超过单个MPS版本500个时间桶，请缩小本次编制范围");
            }
            quantities.forEach(quantity -> allocations.add(new Allocation(source, source.getPlannedDate(),
                    quantity, null, policy)));
        }
        renumberAllocations(allocations);
        List<PlnMpsCapacityBalanceDTO.Line> lines = allocations.stream().map(this::toBalanceLine).toList();
        boolean quantityConserved = quantityConserved(candidates, allocations);
        boolean rowLimit = lines.size() <= 500;
        if (!quantityConserved) messages.add("生产批量整形未通过按需求数量守恒校验");
        if (!rowLimit) messages.add("批量拆分后超过单个MPS版本500个时间桶限制");
        boolean feasible = unresolved.signum() == 0 && quantityConserved && rowLimit;
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList("LOT_SIZING_ONLY",
                candidates.stream().map(this::balanceSourceEvidence).toList(),
                lines.stream().map(line -> Arrays.asList(line.getDemandId(), line.getBucketNo(),
                        normalized(line.getPlanQuantity()), line.getPlannedDate(), line.getLotPolicyId(),
                        line.getLotPolicyVersion())).toList(), catalog.evidenceFingerprint())));
        Map<Long, List<PlnMpsLineEntity>> originalByDemand = candidates.stream().collect(Collectors.groupingBy(
                PlnMpsLineEntity::getDemandId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Allocation>> allocationByDemand = allocations.stream().collect(Collectors.groupingBy(
                item -> item.source.getDemandId(), LinkedHashMap::new, Collectors.toList()));
        int changed = (int) originalByDemand.entrySet().stream().filter(entry -> !sameShape(
                entry.getValue(), allocationByDemand.getOrDefault(entry.getKey(), List.of()))).count();
        return new PlnMpsCapacityBalanceDTO.Proposal().setStrategy("LOT_SIZING_ONLY")
                .setStrategyName("生产批量整形").setFeasible(feasible).setChangedBucketCount(changed)
                .setResourceChangedBucketCount(0).setChangeoverCount(0)
                .setChangeoverMinutes(BigDecimal.ZERO.setScale(SCALE))
                .setSplitBucketCount(Math.max(0, allocations.size() - candidates.size()))
                .setPulledInQuantity(BigDecimal.ZERO.setScale(SCALE))
                .setDelayedQuantity(BigDecimal.ZERO.setScale(SCALE)).setUnresolvedQuantity(scale(unresolved))
                .setMaxProjectedLateDays(0).setExplanation(
                        "不改变需求总量和计划日期，按正式最小/经济/最大批量与整批倍数拆分时间桶")
                .setEvidenceFingerprint(fingerprint).setMessages(messages).setLines(lines);
    }

    private boolean sameShape(List<PlnMpsLineEntity> before, List<Allocation> after) {
        if (before.size() != after.size()) return false;
        List<String> beforeShape = before.stream().map(item -> normalized(item.getPlanQuantity())
                + "@" + item.getPlannedDate()).sorted().toList();
        List<String> afterShape = after.stream().map(item -> normalized(item.quantity)
                + "@" + item.date).sorted().toList();
        return beforeShape.equals(afterShape);
    }

    private PlnMpsCapacityBalanceDTO.Proposal allocateProposal(String strategy, String strategyName,
                                                               PlnMpsPolicyEntity policy,
                                                               List<PlnMpsLineEntity> candidates,
                                                               BalanceContext context,
                                                               LocalDate horizonStart, LocalDate horizonEnd,
                                                               LocalDate freezeThrough) {
        Map<String, BigDecimal> remainingMinutes = new LinkedHashMap<>(context.remainingMinutes);
        List<PlnMpsLineEntity> ordered = new ArrayList<>(allocationSources(candidates, context.lotPolicies));
        ordered.sort(Comparator.comparing(PlnMpsLineEntity::getMrpPriority,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlnMpsLineEntity::getSourceDueDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlnMpsLineEntity::getPlannedDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlnMpsLineEntity::getDemandNo,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlnMpsLineEntity::getBucketNo,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        List<Allocation> allocations = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        BigDecimal unresolved = BigDecimal.ZERO;
        LocalDate earliest = horizonStart.isAfter(freezeThrough.plusDays(1))
                ? horizonStart : freezeThrough.plusDays(1);
        boolean qualifiedResourceBalance = "QUALIFIED_RESOURCE_BALANCE".equals(strategy);
        for (PlnMpsLineEntity source : ordered) {
            CapacityBasis defaultBasis = context.basisByLine.get(source);
            MdmProductionLotPolicyEntity lotPolicy = context.lotPolicies.get(source.getProductCode());
            BigDecimal quantityLeft = source.getPlanQuantity().setScale(SCALE, RoundingMode.UNNECESSARY);
            String lotViolation = lotPolicy == null ? null
                    : quantityLeft.compareTo(lotPolicy.getMinimumBatchQuantity()) < 0
                    ? "总量" + normalized(quantityLeft) + "小于最小生产批量"
                    + normalized(lotPolicy.getMinimumBatchQuantity())
                    : quantityLeft.remainder(lotPolicy.getBatchMultiple()).signum() != 0
                    ? "总量" + normalized(quantityLeft) + "不是整批倍数"
                    + normalized(lotPolicy.getBatchMultiple()) + "的整数倍" : null;
            if (lotViolation != null) {
                unresolved = unresolved.add(quantityLeft);
                messages.add(source.getDemandNo() + "#" + source.getBucketNo() + "：" + lotViolation);
                continue;
            }
            LocalDate firstDate = Set.of("MIN_CHANGE_FORWARD", "QUALIFIED_RESOURCE_BALANCE").contains(strategy)
                    ? (source.getPlannedDate().isAfter(earliest) ? source.getPlannedDate() : earliest)
                    : earliest;
            for (LocalDate date = firstDate; !date.isAfter(horizonEnd) && quantityLeft.signum() > 0
                    && allocations.size() <= 500;
                 date = date.plusDays(1)) {
                List<CapacityBasis> capacityOptions = qualifiedResourceBalance
                        ? alternativeBases(source, context.routes, defaultBasis)
                        : List.of(defaultBasis);
                for (CapacityBasis basis : capacityOptions) {
                    if (quantityLeft.signum() <= 0 || allocations.size() > 500) break;
                    String key = bucket(basis.resourceCode, date);
                    BigDecimal freeMinutes = remainingMinutes.getOrDefault(key, BigDecimal.ZERO);
                    // 最大批量限制的是单批，不是一天只能生产一批。逐批扣减同日剩余分钟，
                    // 再由完整RCCP预览复核加工/换型总负荷，不能因批量上限虚造延期。
                    while (freeMinutes.signum() > 0 && quantityLeft.signum() > 0 && allocations.size() <= 500) {
                        BigDecimal capacityQuantity = freeMinutes.divide(basis.unitMinutes, SCALE, RoundingMode.DOWN);
                        BigDecimal allocated = lotSizingService.allocatable(quantityLeft, capacityQuantity, lotPolicy);
                        if (allocated.signum() <= 0) {
                            break;
                        }
                        allocations.add(new Allocation(source, date, allocated, basis.resourceCode, lotPolicy));
                        quantityLeft = quantityLeft.subtract(allocated);
                        freeMinutes = scale(freeMinutes.subtract(basis.unitMinutes.multiply(allocated)).max(BigDecimal.ZERO));
                        remainingMinutes.put(key, freeMinutes);
                    }
                }
            }
            if (quantityLeft.signum() > 0) {
                unresolved = unresolved.add(quantityLeft);
                messages.add(source.getDemandNo() + "#" + source.getBucketNo()
                        + "仍有" + normalized(quantityLeft) + "未找到可信容量");
            }
        }
        renumberAllocations(allocations);
        List<PlnMpsCapacityBalanceDTO.Line> lines = allocations.stream()
                .map(this::toBalanceLine).toList();
        boolean rowLimit = lines.size() <= 500;
        if (!rowLimit) messages.add("建议拆分后超过单个MPS版本500个时间桶限制");
        boolean quantityConserved = quantityConserved(candidates, allocations);
        if (!quantityConserved) {
            messages.add("负荷平衡结果未通过按需求数量守恒校验，禁止一键应用");
        }
        List<PlnMpsLineEntity> proposedEntities = allocations.stream().map(this::toCandidate).toList();
        PlnMpsCapacityDTO.Preview proposedPreview = unresolved.signum() == 0 && rowLimit && quantityConserved
                ? preview(policy, proposedEntities) : null;
        boolean feasible = unresolved.signum() == 0 && rowLimit && quantityConserved && proposedPreview != null
                && "PASS".equals(proposedPreview.getOverallStatus())
                && proposedPreview.getLines().stream().allMatch(line -> "PASS".equals(line.getStatus()));
        if (!feasible && unresolved.signum() == 0 && rowLimit && quantityConserved) {
            messages.add("建议重算后仍存在超载或不可核验容量，禁止一键应用");
        }
        BigDecimal pulledIn = allocations.stream().filter(item -> item.date.isBefore(item.source.getPlannedDate()))
                .map(Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal delayed = allocations.stream().filter(item -> item.date.isAfter(item.source.getPlannedDate()))
                .map(Allocation::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<PlnMpsLineEntity, Long> parts = allocations.stream()
                .collect(Collectors.groupingBy(Allocation::source, IdentityHashMap::new, Collectors.counting()));
        int changed = (int) allocations.stream().filter(item -> !item.date.equals(item.source.getPlannedDate())
                || parts.getOrDefault(item.source, 0L) > 1
                || (StrUtil.isNotBlank(item.resourceCode)
                && !Objects.equals(item.resourceCode, context.basisByLine.get(item.source).resourceCode))).count();
        int resourceChanged = (int) allocations.stream().filter(item ->
                StrUtil.isNotBlank(item.resourceCode)
                        && !Objects.equals(item.resourceCode,
                        context.basisByLine.get(item.source).resourceCode)).count();
        int changeoverCount = proposedPreview == null ? 0 : (int) proposedPreview.getLines().stream()
                .filter(item -> item.getChangeoverMinutes() != null
                        && item.getChangeoverMinutes().signum() > 0).count();
        BigDecimal changeoverMinutes = proposedPreview == null ? BigDecimal.ZERO
                : proposedPreview.getLines().stream().map(PlnMpsCapacityDTO.Line::getChangeoverMinutes)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        int maxLateDays = allocations.stream().filter(item -> item.source.getSourceDueDate() != null)
                .mapToInt(item -> (int) Math.max(0,
                        java.time.temporal.ChronoUnit.DAYS.between(item.source.getSourceDueDate(), item.date)))
                .max().orElse(0);
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(strategy,
                candidates.stream().map(this::balanceSourceEvidence).toList(),
                lines.stream().map(line -> Arrays.asList(line.getDemandId(), line.getSourceBucketNo(),
                        line.getBucketNo(), normalized(line.getPlanQuantity()), line.getPlannedDate(),
                        line.getMrpPriority(), line.getResourceCode(), line.getLotPolicyId(),
                        line.getLotPolicyVersion(), normalized(line.getMinimumBatchQuantity()),
                        normalized(line.getEconomicBatchQuantity()), normalized(line.getMaximumBatchQuantity()),
                        normalized(line.getBatchMultiple()))).toList(),
                context.lotPolicyEvidenceFingerprint,
                proposedPreview == null ? null : proposedPreview.getLines().stream()
                        .map(PlnMpsCapacityDTO.Line::getEvidenceFingerprint).toList())));
        String explanation = switch (strategy) {
            case "MIN_CHANGE_FORWARD" -> "按MRP优先级保留原计划日可容纳数量，超出部分只向后续可信工作日顺延";
            case "QUALIFIED_RESOURCE_BALANCE" ->
                    "优先保留默认产线和原计划日；容量不足时仅在产品已启用工艺路线对应的合格产线间分配，再向后续可信工作日顺延";
            default -> "按MRP优先级和原交期，从冻结区后的最早可信工作日装载，允许提前以降低延期风险";
        };
        return new PlnMpsCapacityBalanceDTO.Proposal().setStrategy(strategy).setStrategyName(strategyName)
                .setFeasible(feasible).setChangedBucketCount(changed)
                .setResourceChangedBucketCount(resourceChanged)
                .setChangeoverCount(changeoverCount).setChangeoverMinutes(scale(changeoverMinutes))
                .setSplitBucketCount(Math.max(0, allocations.size() - candidates.size()))
                .setPulledInQuantity(scale(pulledIn)).setDelayedQuantity(scale(delayed))
                .setUnresolvedQuantity(scale(unresolved)).setMaxProjectedLateDays(maxLateDays)
                .setExplanation(explanation).setEvidenceFingerprint(fingerprint)
                .setMessages(messages).setLines(lines).setPreview(proposedPreview);
    }

    /**
     * 同一需求已被人工拆出多个不合法小桶时，先按需求合并后重新形成合法生产批次。
     * 未配置批量策略的产品保持原时间桶，避免在灰度上线期间改变旧逻辑。
     */
    private List<PlnMpsLineEntity> allocationSources(List<PlnMpsLineEntity> candidates,
                                                     Map<String, MdmProductionLotPolicyEntity> policies) {
        Map<Long, List<PlnMpsLineEntity>> grouped = candidates.stream().collect(Collectors.groupingBy(
                PlnMpsLineEntity::getDemandId, LinkedHashMap::new, Collectors.toList()));
        List<PlnMpsLineEntity> result = new ArrayList<>();
        grouped.values().forEach(rows -> {
            PlnMpsLineEntity first = rows.get(0);
            if (!policies.containsKey(first.getProductCode()) || rows.size() == 1) {
                result.addAll(rows);
                return;
            }
            BigDecimal total = rows.stream().map(PlnMpsLineEntity::getPlanQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            LocalDate earliestDate = rows.stream().map(PlnMpsLineEntity::getPlannedDate)
                    .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(first.getPlannedDate());
            Integer priority = rows.stream().map(PlnMpsLineEntity::getMrpPriority)
                    .filter(Objects::nonNull).min(Integer::compareTo).orElse(first.getMrpPriority());
            result.add(new PlnMpsLineEntity().setDemandId(first.getDemandId()).setDemandNo(first.getDemandNo())
                    .setBucketNo(rows.stream().map(PlnMpsLineEntity::getBucketNo).filter(Objects::nonNull)
                            .min(Integer::compareTo).orElse(1))
                    .setProductCode(first.getProductCode()).setWarehouseCode(first.getWarehouseCode())
                    .setPlanQuantity(total).setSourceDueDate(first.getSourceDueDate())
                    .setPlannedDate(earliestDate).setMrpPriority(priority).setFactoryCode(first.getFactoryCode()));
        });
        return result;
    }

    /**
     * 按需求核对调整前后数量，防止拆桶、合桶或后续算法扩展造成静默丢量、增量。
     */
    private boolean quantityConserved(List<PlnMpsLineEntity> candidates, List<Allocation> allocations) {
        Map<Long, BigDecimal> before = candidates.stream().collect(Collectors.groupingBy(
                PlnMpsLineEntity::getDemandId, LinkedHashMap::new,
                Collectors.reducing(BigDecimal.ZERO, PlnMpsLineEntity::getPlanQuantity, BigDecimal::add)));
        Map<Long, BigDecimal> after = allocations.stream().collect(Collectors.groupingBy(
                item -> item.source.getDemandId(), LinkedHashMap::new,
                Collectors.reducing(BigDecimal.ZERO, Allocation::quantity, BigDecimal::add)));
        return before.size() == after.size() && before.entrySet().stream().allMatch(entry ->
                entry.getValue() != null && after.get(entry.getKey()) != null
                        && entry.getValue().compareTo(after.get(entry.getKey())) == 0);
    }

    private void renumberAllocations(List<Allocation> allocations) {
        Map<Long, List<Allocation>> grouped = allocations.stream().collect(Collectors.groupingBy(
                item -> item.source.getDemandId(), LinkedHashMap::new, Collectors.toList()));
        grouped.values().forEach(rows -> {
            rows.sort(Comparator.comparing(Allocation::date)
                    .thenComparing(item -> item.source.getBucketNo())
                    .thenComparing(item -> item.source.getPlannedDate()));
            for (int index = 0; index < rows.size(); index++) rows.get(index).bucketNo = index + 1;
        });
    }

    private PlnMpsCapacityBalanceDTO.Line toBalanceLine(Allocation item) {
        String action = item.date.isBefore(item.source.getPlannedDate()) ? "PULL_IN"
                : item.date.isAfter(item.source.getPlannedDate()) ? "DELAY" : "KEEP";
        boolean rerouted = StrUtil.isNotBlank(item.resourceCode)
                && StrUtil.isNotBlank(item.source.getCapacityResourceCode())
                && !Objects.equals(item.resourceCode, item.source.getCapacityResourceCode());
        String reason = (rerouted ? "使用产品启用工艺路线对应的合格替代产线；" : "") + switch (action) {
            case "PULL_IN" -> "利用交期前可信剩余能力，降低延期风险";
            case "DELAY" -> "原资源日期桶容量不足，顺延至后续可信工作日";
            default -> item.lotPolicy == null ? "保留原计划日期与精确需求量"
                    : "按正式生产批量策略在原计划日拆分或保留合法批次";
        };
        return new PlnMpsCapacityBalanceDTO.Line().setDemandId(item.source.getDemandId())
                .setDemandNo(item.source.getDemandNo()).setSourceBucketNo(item.source.getBucketNo())
                .setBucketNo(item.bucketNo).setProductCode(item.source.getProductCode())
                .setPlanQuantity(item.quantity).setSourceDueDate(item.source.getSourceDueDate())
                .setOriginalDate(item.source.getPlannedDate()).setPlannedDate(item.date)
                .setMrpPriority(item.source.getMrpPriority()).setAction(action)
                .setResourceCode(item.resourceCode)
                .setLotPolicyId(item.lotPolicy == null ? null : item.lotPolicy.getId())
                .setLotPolicyNo(item.lotPolicy == null ? null : item.lotPolicy.getPolicyNo())
                .setLotPolicyVersion(item.lotPolicy == null ? null : item.lotPolicy.getVersionNo())
                .setMinimumBatchQuantity(item.lotPolicy == null ? null : item.lotPolicy.getMinimumBatchQuantity())
                .setEconomicBatchQuantity(item.lotPolicy == null ? null : item.lotPolicy.getEconomicBatchQuantity())
                .setMaximumBatchQuantity(item.lotPolicy == null ? null : item.lotPolicy.getMaximumBatchQuantity())
                .setBatchMultiple(item.lotPolicy == null ? null : item.lotPolicy.getBatchMultiple())
                .setReason(reason);
    }

    private PlnMpsLineEntity toCandidate(Allocation item) {
        return new PlnMpsLineEntity().setDemandId(item.source.getDemandId())
                .setDemandNo(item.source.getDemandNo()).setBucketNo(item.bucketNo)
                .setBucketKey("BALANCE-" + item.source.getDemandId() + "-" + item.bucketNo)
                .setProductCode(item.source.getProductCode()).setWarehouseCode(item.source.getWarehouseCode())
                .setPlanQuantity(item.quantity).setSourceDueDate(item.source.getSourceDueDate())
                .setPlannedDate(item.date).setMrpPriority(item.source.getMrpPriority())
                .setCapacityResourceCode(item.resourceCode)
                .setFactoryCode(item.source.getFactoryCode());
    }

    private List<Object> balanceSourceEvidence(PlnMpsLineEntity line) {
        return Arrays.asList(line.getDemandId(), line.getBucketNo(), normalized(line.getPlanQuantity()),
                line.getPlannedDate(), line.getMrpPriority(), line.getCapacityEvidenceFingerprint());
    }

    private Map<String, MdmMaterialEntity> loadMaterials(List<PlnMpsLineEntity> candidates,
                                                         List<PlnMpsLineEntity> active) {
        List<String> codes = productCodes(candidates, active);
        if (codes.isEmpty()) return Map.of();
        Map<String, MdmMaterialEntity> result = new LinkedHashMap<>();
        for (int start = 0; start < codes.size(); start += 200) {
            try {
                R<List<MdmMaterialEntity>> response = materialService.getListByCodes(
                        codes.subList(start, Math.min(start + 200, codes.size())));
                if (response != null && response.isOk() && response.getData() != null) {
                    response.getData().stream().filter(Objects::nonNull)
                            .forEach(item -> result.putIfAbsent(item.getMaterialCode(), item));
                }
            } catch (Exception ignored) {
                // 缺失批次对应产品会进入UNVERIFIED，不使用虚构标准工时。
            }
        }
        return result;
    }

    private RouteCatalog loadRoutes(List<PlnMpsLineEntity> candidates,
                                    List<PlnMpsLineEntity> active) {
        List<String> codes = productCodes(candidates, active);
        if (codes.isEmpty()) return new RouteCatalog(Map.of(), Map.of(), Set.of(), Set.of());
        try {
            R<List<MdmProcessRouteEntity>> response = processRouteService.getEnabledByProductCodes(codes);
            if (response == null || !response.isOk() || response.getData() == null) {
                return new RouteCatalog(Map.of(), Map.of(), Set.of(), Set.of());
            }
            Map<String, List<MdmProcessRouteEntity>> grouped = response.getData().stream()
                    .filter(Objects::nonNull).filter(item -> StrUtil.isNotBlank(item.getProductCode()))
                    .collect(Collectors.groupingBy(MdmProcessRouteEntity::getProductCode,
                            LinkedHashMap::new, Collectors.toList()));
            Map<String, MdmProcessRouteEntity> defaults = new LinkedHashMap<>();
            Map<String, List<MdmProcessRouteEntity>> qualified = new LinkedHashMap<>();
            Set<String> defaultConflicts = new LinkedHashSet<>();
            Set<String> qualificationConflicts = new LinkedHashSet<>();
            grouped.forEach((code, rows) -> {
                List<MdmProcessRouteEntity> valid = rows.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                        .filter(item -> StrUtil.isNotBlank(item.getProductLineCode()))
                        .filter(item -> item.getProductRate() != null && item.getProductRate().signum() > 0)
                        .toList();
                Map<String, List<MdmProcessRouteEntity>> byResource = valid.stream().collect(Collectors.groupingBy(
                        MdmProcessRouteEntity::getProductLineCode, LinkedHashMap::new, Collectors.toList()));
                List<MdmProcessRouteEntity> unambiguous = new ArrayList<>();
                byResource.forEach((resource, resourceRoutes) -> {
                    long rates = resourceRoutes.stream().map(MdmProcessRouteEntity::getProductRate)
                            .map(BigDecimal::stripTrailingZeros).distinct().count();
                    if (rates == 1) {
                        unambiguous.add(resourceRoutes.stream()
                                .sorted(Comparator.comparing(MdmProcessRouteEntity::getIsDefault,
                                                Comparator.nullsLast(Comparator.reverseOrder()))
                                        .thenComparing(MdmProcessRouteEntity::getId))
                                .findFirst().orElseThrow());
                    } else {
                        qualificationConflicts.add(code + "@" + resource);
                    }
                });
                if (!unambiguous.isEmpty()) qualified.put(code, unambiguous);
                List<MdmProcessRouteEntity> defaultRows = rows.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getIsDefault())).toList();
                if (defaultRows.size() == 1) defaults.put(code, defaultRows.get(0));
                else if (defaultRows.size() > 1) defaultConflicts.add(code);
            });
            return new RouteCatalog(defaults, qualified, defaultConflicts, qualificationConflicts);
        } catch (Exception ignored) {
            return new RouteCatalog(Map.of(), Map.of(), Set.of(), Set.of());
        }
    }

    private Set<String> contextResources(RouteCatalog routes, List<PlnMpsLineEntity> candidates) {
        Set<String> products = candidates.stream().map(PlnMpsLineEntity::getProductCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        return routes.qualified.entrySet().stream().filter(entry -> products.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .map(MdmProcessRouteEntity::getProductLineCode).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 返回默认资源优先、资源编码稳定排序的合格路线能力，供建议算法显式换线。
     */
    private List<CapacityBasis> alternativeBases(PlnMpsLineEntity line, RouteCatalog routes,
                                                 CapacityBasis defaultBasis) {
        List<CapacityBasis> result = routes.qualified.getOrDefault(line.getProductCode(), List.of()).stream()
                .filter(route -> sameFactory(line.getFactoryCode(), route.getFactoryCode()))
                .map(route -> routeBasis(line, route, Objects.equals(route.getProductLineCode(),
                        defaultBasis.resourceCode) ? "ROUTE_RATE" : "QUALIFIED_ROUTE_RATE"))
                .filter(CapacityBasis::verified)
                .sorted(Comparator.comparing((CapacityBasis item) ->
                                !Objects.equals(item.resourceCode, defaultBasis.resourceCode))
                        .thenComparing(CapacityBasis::resourceCode))
                .toList();
        return result.isEmpty() ? List.of(defaultBasis) : result;
    }

    /**
     * 读取产线级换型目录。工位、设备和工装规则属于APS细排，不能在MPS日期桶中重复扣减。
     */
    private LineChangeoverCatalog loadLineChangeoverCatalog(String factoryCode,
                                                            List<PlnMpsLineEntity> candidates,
                                                            List<PlnMpsLineEntity> active) {
        List<LocalDate> candidateDates = new ArrayList<>();
        candidates.stream().map(PlnMpsLineEntity::getPlannedDate).filter(Objects::nonNull).forEach(candidateDates::add);
        active.stream().filter(this::currentQuantityBasis).map(this::changeoverDate)
                .filter(Objects::nonNull).forEach(candidateDates::add);
        if (candidateDates.isEmpty()) {
            return LineChangeoverCatalog.unverified("MPS候选缺少计划日期，无法读取产线换型目录");
        }
        LocalDate start = candidateDates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = candidateDates.stream().max(LocalDate::compareTo).orElse(start);
        MdmChangeoverCatalogQueryDTO query = new MdmChangeoverCatalogQueryDTO();
        query.setFactoryCode(factoryCode);
        query.setProductCodes(productCodes(candidates, active));
        query.setStartTime(start.atStartOfDay());
        query.setEndTime(end.plusDays(1).atStartOfDay());
        try {
            R<MdmChangeoverCatalogDTO> response = changeoverService.catalog(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                return LineChangeoverCatalog.unverified("MDM未返回可核验的产线换型目录");
            }
            MdmChangeoverCatalogDTO data = response.getData();
            Map<String, String> families = Optional.ofNullable(data.getFamilyByProduct())
                    .orElse(Map.of());
            List<MdmChangeoverRuleEntity> rules = Optional.ofNullable(data.getRules()).orElse(List.of()).stream()
                    .filter(rule -> "LINE".equals(rule.getScopeType()))
                    .sorted(Comparator.comparing(MdmChangeoverRuleEntity::getResourceCode,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(MdmChangeoverRuleEntity::getFromFamilyCode,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(MdmChangeoverRuleEntity::getToFamilyCode,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(MdmChangeoverRuleEntity::getPriority,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(MdmChangeoverRuleEntity::getVersionNo,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            List<Object> evidence = new ArrayList<>();
            evidence.add(new TreeMap<>(families));
            evidence.add(rules.stream().map(rule -> Arrays.asList(rule.getId(), rule.getRuleNo(),
                    rule.getVersionNo(), rule.getResourceCode(), rule.getFromFamilyCode(),
                    rule.getToFamilyCode(), rule.getCleaningMinutes(), rule.getSetupMinutes(),
                    rule.getForbidden(), rule.getPriority(), rule.getValidStartTime(),
                    rule.getValidEndTime())).toList());
            return new LineChangeoverCatalog(families, rules, true, hash(JSONUtil.toJsonStr(evidence)), null);
        } catch (Exception ex) {
            return LineChangeoverCatalog.unverified("产线换型目录读取失败：" + ex.getMessage());
        }
    }

    /**
     * 按“活动承诺优先、候选按MRP优先级/交期稳定排序”的日期序列估算产线换型占用。
     * 原活动MPS的冻结分钟不回写；当前余额与候选在同一序列重新计算前置换型。
     * 禁止先把余额单算冻结再加入候选，否则会漏掉候选对既有余额的影响。
     */
    private void applyLineChangeovers(List<PlnMpsLineEntity> candidates,
                                      List<PlnMpsLineEntity> active,
                                      Map<PlnMpsLineEntity, CapacityBasis> candidateBasis,
                                      Map<PlnMpsLineEntity, CapacityBasis> activeBasis,
                                      LineChangeoverCatalog catalog) {
        candidates.forEach(line -> {
            CapacityBasis basis = candidateBasis.get(line);
            line.setCapacityProcessingMinutes(scale(basis == null ? null : basis.demandMinutes))
                    .setCapacityChangeoverMinutes(BigDecimal.ZERO.setScale(SCALE));
        });
        active.stream().filter(this::currentQuantityBasis).forEach(line -> {
            CapacityBasis basis = activeBasis.get(line);
            line.setCapacityProcessingMinutes(scale(basis == null ? null : basis.demandMinutes))
                    .setCapacityChangeoverMinutes(BigDecimal.ZERO.setScale(SCALE));
        });
        if (!catalog.verified) {
            candidates.forEach(line -> {
                CapacityBasis basis = candidateBasis.get(line);
                if (basis != null) candidateBasis.put(line, unverified(basis, catalog.message));
            });
            active.stream().filter(this::currentQuantityBasis).forEach(line -> {
                CapacityBasis basis = activeBasis.get(line);
                if (basis != null) activeBasis.put(line, unverified(basis, catalog.message));
            });
            return;
        }
        Set<String> governedResources = catalog.rules.stream().map(MdmChangeoverRuleEntity::getResourceCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        if (governedResources.isEmpty()) return;

        Comparator<SequenceLine> order = Comparator
                .comparing((SequenceLine item) -> changeoverDate(item.line),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SequenceLine::candidate)
                .thenComparing(item -> item.line.getMrpPriority(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.line.getSourceDueDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.line.getDemandNo(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.line.getBucketNo(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.line.getId(), Comparator.nullsLast(Comparator.naturalOrder()));

        for (String resource : governedResources) {
            List<SequenceLine> sequence = new ArrayList<>();
            active.stream().filter(line -> sameResource(resource, activeBasis.get(line)))
                    .forEach(line -> sequence.add(new SequenceLine(line, activeBasis.get(line), false)));
            candidates.stream().filter(line -> sameResource(resource, candidateBasis.get(line)))
                    .forEach(line -> sequence.add(new SequenceLine(line, candidateBasis.get(line), true)));
            sequence.sort(order);
            String previousFamily = null;
            boolean hasPrevious = false;
            for (SequenceLine item : sequence) {
                String family = catalog.familyByProduct.get(item.line.getProductCode());
                if (!item.candidate && !currentQuantityBasis(item.line)) {
                    previousFamily = family;
                    hasPrevious = true;
                    continue;
                }
                Map<PlnMpsLineEntity, CapacityBasis> target = item.candidate ? candidateBasis : activeBasis;
                item.line.setCapacityFromFamilyCode(hasPrevious ? previousFamily : null)
                        .setCapacityToFamilyCode(family);
                if (StrUtil.isBlank(family)) {
                    target.put(item.line, unverified(item.basis,
                            "产线已启用换型治理，但产品未维护排产产品族"));
                } else if (hasPrevious && StrUtil.isBlank(previousFamily)) {
                    target.put(item.line, unverified(item.basis,
                            "前序活动承诺产品未维护排产产品族，无法核验换型"));
                } else if (hasPrevious && !Objects.equals(previousFamily, family)) {
                    ChangeoverResolution resolution = resolveLineChangeover(catalog, resource,
                            previousFamily, family, changeoverDate(item.line));
                    if (!resolution.verified) {
                        target.put(item.line, unverified(item.basis, resolution.message));
                    } else {
                        BigDecimal minutes = BigDecimal.valueOf(resolution.minutes).setScale(SCALE);
                        item.line.setCapacityChangeoverMinutes(minutes)
                                .setCapacityChangeoverRuleNo(resolution.rule.getRuleNo())
                                .setCapacityChangeoverRuleVersion(resolution.rule.getVersionNo());
                        target.put(item.line, withChangeover(item.basis, minutes));
                    }
                }
                previousFamily = family;
                hasPrevious = true;
            }
        }
    }

    private ChangeoverResolution resolveLineChangeover(LineChangeoverCatalog catalog, String resource,
                                                       String fromFamily, String toFamily, LocalDate date) {
        if (date == null) return ChangeoverResolution.unverified("候选缺少计划日期，无法匹配产线换型规则");
        LocalDateTime effectiveAt = date.atStartOfDay();
        List<MdmChangeoverRuleEntity> matched = catalog.rules.stream()
                .filter(rule -> Objects.equals(resource, rule.getResourceCode()))
                .filter(rule -> Objects.equals(fromFamily, rule.getFromFamilyCode()))
                .filter(rule -> Objects.equals(toFamily, rule.getToFamilyCode()))
                .filter(rule -> rule.getValidStartTime() != null && !rule.getValidStartTime().isAfter(effectiveAt))
                .filter(rule -> rule.getValidEndTime() == null || rule.getValidEndTime().isAfter(effectiveAt))
                .toList();
        if (matched.isEmpty()) {
            return ChangeoverResolution.unverified("产线[" + resource + "]缺少产品族"
                    + fromFamily + "→" + toFamily + "的生效换型规则");
        }
        if (matched.size() > 1) {
            return ChangeoverResolution.unverified("产线[" + resource + "]产品族"
                    + fromFamily + "→" + toFamily + "存在重叠生效规则");
        }
        MdmChangeoverRuleEntity rule = matched.get(0);
        if (Boolean.TRUE.equals(rule.getForbidden())) {
            return ChangeoverResolution.unverified("产线[" + resource + "]禁止产品族"
                    + fromFamily + "→" + toFamily + "相邻投放");
        }
        return new ChangeoverResolution(true, rule.getTotalMinutes(), rule, null);
    }

    private boolean sameResource(String resource, CapacityBasis basis) {
        return basis != null && basis.verified && Objects.equals(resource, basis.resourceCode);
    }

    /**
     * 仅当前有效余额重算速率/换型，原冻结活动量及批准预留保持原依据。
     */
    private boolean currentQuantityBasis(PlnMpsLineEntity line) {
        return Set.of("REMAINING_CURRENT", "ORIGINAL_OVERDUE_CURRENT").contains(
                StrUtil.blankToDefault(line.getCapacityResponsibilityType(), "ORIGINAL"));
    }

    /**
     * 逾期未完成量从今日起装载，匹配当前生效换型规则；原计划日仍保留供审计。
     */
    private LocalDate changeoverDate(PlnMpsLineEntity line) {
        LocalDate date = line.getPlannedDate();
        return currentQuantityBasis(line) && date != null && date.isBefore(LocalDate.now()) ? LocalDate.now() : date;
    }

    private CapacityBasis unverified(CapacityBasis basis, String message) {
        return new CapacityBasis(basis.resourceCode, basis.resourceName, basis.basis, basis.unitMinutes,
                basis.demandMinutes, false, message);
    }

    private CapacityBasis withChangeover(CapacityBasis basis, BigDecimal changeoverMinutes) {
        return new CapacityBasis(basis.resourceCode, basis.resourceName, basis.basis, basis.unitMinutes,
                scale(basis.demandMinutes.add(changeoverMinutes)), basis.verified,
                basis.message + "；含产线级产品族换型" + normalized(changeoverMinutes) + "分钟");
    }

    private CapacityCalendar loadCalendar(String factoryCode, List<PlnMpsLineEntity> lines) {
        LocalDate start = lines.stream().map(PlnMpsLineEntity::getPlannedDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate end = lines.stream().map(PlnMpsLineEntity::getPlannedDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(start);
        return loadCalendar(factoryCode, start, end);
    }

    private CapacityCalendar loadCalendar(String factoryCode, LocalDate start, LocalDate end) {
        List<MdmClassesEntity> shifts = List.of();
        boolean shiftsVerified = false;
        try {
            R<List<MdmClassesEntity>> response = classesService.listActive();
            if (response != null && response.isOk() && response.getData() != null) {
                shifts = response.getData().stream().filter(Objects::nonNull)
                        .filter(item -> Boolean.TRUE.equals(item.getStatus()))
                        .filter(item -> StrUtil.isBlank(item.getFactoryCode())
                                || Objects.equals(factoryCode, item.getFactoryCode()))
                        .filter(item -> item.getStartTime() != null && item.getEndTime() != null).toList();
                shiftsVerified = !shifts.isEmpty();
            }
        } catch (Exception ignored) {
            shifts = List.of();
        }
        BigDecimal shiftMinutes = shifts.stream().map(this::shiftMinutes)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<LocalDate, String> workDays = new LinkedHashMap<>();
        boolean calendarVerified = false;
        try {
            R<List<MdmCalendarEntity>> response = calendarService.listByRange(start, end);
            if (response != null && response.isOk() && response.getData() != null) {
                response.getData().stream().filter(item -> item != null && item.getTime() != null)
                        .forEach(item -> workDays.put(item.getTime(), item.getStatus()));
                calendarVerified = true;
            }
        } catch (Exception ignored) {
            workDays.clear();
        }
        Map<LocalDate, CalendarDay> days = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean dateVerified = shiftsVerified && calendarVerified && workDays.containsKey(date);
            BigDecimal available = dateVerified && "0".equals(workDays.get(date))
                    ? shiftMinutes : dateVerified ? BigDecimal.ZERO : null;
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("date", date);
            snapshot.put("workdayStatus", workDays.get(date));
            snapshot.put("shiftMinutes", normalized(shiftMinutes));
            snapshot.put("shiftCodes", shifts.stream().map(MdmClassesEntity::getShiftCode).sorted().toList());
            snapshot.put("verified", dateVerified);
            days.put(date, new CalendarDay(scale(available), dateVerified, snapshot));
        }
        return new CapacityCalendar(days);
    }

    /**
     * 逾期责任优先装载今日起最早可信容量。旧日期/全量依据保留，未装载分钟形成全局缺口，
     * 不能因计划日已过去或日历不完整把积压填为零。
     */
    private void loadOverdue(List<PlnMpsLineEntity> active, Map<PlnMpsLineEntity, CapacityBasis> basisByLine,
                             CapacityCalendar calendar, PlnMpsPolicyEntity policy, Map<String, BigDecimal> committed,
                             List<Map<String, Object>> evidence, List<String> gaps) {
        var overdue = active.stream().filter(line -> line.getPlannedDate() != null && line.getPlannedDate().isBefore(LocalDate.now()))
                .sorted(Comparator.comparing(PlnMpsLineEntity::getPlannedDate).thenComparing(PlnMpsLineEntity::getId)
                        .thenComparing(PlnMpsLineEntity::getBucketNo, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        for (var line : overdue) {
            var basis = basisByLine.get(line);
            if (basis == null || !basis.verified || basis.demandMinutes == null) {
                gaps.add("逾期责任无可信分钟：" + line.getId());
                continue;
            }
            BigDecimal left = basis.demandMinutes;
            boolean calendarGap = false;
            for (var day : calendar.days.entrySet()) {
                if (day.getKey().isBefore(LocalDate.now())) continue;
                if (!day.getValue().verified || day.getValue().availableMinutes == null) {
                    calendarGap = true;
                    break; // 不能跨过未知最早日后声称已可信装载。
                }
                String key = bucket(basis.resourceCode, day.getKey());
                BigDecimal limit = day.getValue().availableMinutes.multiply(maxLoadRate(policy))
                        .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.DOWN);
                BigDecimal free = limit.subtract(committed.getOrDefault(key, BigDecimal.ZERO)).max(BigDecimal.ZERO);
                BigDecimal allocated = left.min(free);
                if (allocated.signum() > 0) {
                    committed.merge(key, allocated, BigDecimal::add);
                    var item = evidenceLine(line, basis);
                    item.put("originalPlannedDate", line.getPlannedDate());
                    item.put("plannedDate", day.getKey());
                    item.put("backlogAllocatedMinutes", normalized(allocated));
                    item.put("demandMinutes", normalized(allocated));
                    item.put("backlog", true);
                    evidence.add(item);
                    left = left.subtract(allocated);
                }
                if (left.signum() == 0) break;
            }
            if (left.signum() > 0) gaps.add("逾期责任" + line.getId() + "仍有" + normalized(left)
                    + "分钟未可信装载" + (calendarGap ? "（最早日历缺失）" : "（窗口能力不足）"));
        }
    }

    private CapacityBasis frozenOrResolvedBasis(PlnMpsLineEntity line,
                                                Map<String, MdmMaterialEntity> materials,
                                                RouteCatalog routes) {
        if (StrUtil.isNotBlank(line.getCapacityResourceCode())
                && line.getCapacityDemandMinutes() != null && line.getCapacityDemandMinutes().signum() > 0
                && Boolean.TRUE.equals(line.getCapacityVerified())) {
            return new CapacityBasis(line.getCapacityResourceCode(), line.getCapacityResourceName(),
                    line.getCapacityBasis(), null, line.getCapacityDemandMinutes(), true, "活动MPS冻结承诺");
        }
        return resolveBasis(line, materials, routes);
    }

    private CapacityBasis resolveBasis(PlnMpsLineEntity line,
                                       Map<String, MdmMaterialEntity> materials,
                                       RouteCatalog routes) {
        if (routes.defaultConflicts.contains(line.getProductCode())) {
            return new CapacityBasis(null, null, null, null, BigDecimal.ZERO, false,
                    "产品存在多条启用的默认工艺路线，禁止任意选择产能依据");
        }
        if (StrUtil.isNotBlank(line.getCapacityResourceCode())) {
            String requested = line.getCapacityResourceCode().trim();
            // 原可信标准工时工作中心不是客户端可任意填写的替代产线。
            // 剩余责任只在原STANDARD_HOURS依据仍匹配当前物料时沿用该资源并重算。
            if (line.getCapacityResponsibilityType() != null
                    && (line.getCapacityResponsibilityType().startsWith("REMAINING_") || currentQuantityBasis(line))
                    && "STANDARD_HOURS".equals(line.getCapacityBasis())) {
                MdmMaterialEntity material = materials.get(line.getProductCode());
                BigDecimal hours = material == null ? null : maxPositive(material.getStandardMachineHours(), material.getStandardLaborHours());
                if (material != null && Boolean.TRUE.equals(material.getStatus())
                        && Objects.equals(requested, material.getDefaultWorkCenter())
                        && sameFactory(line.getFactoryCode(), material.getFactoryCode()) && hours != null) {
                    BigDecimal unitMinutes = hours.multiply(SIXTY);
                    return new CapacityBasis(requested, requested, "STANDARD_HOURS", unitMinutes,
                            multiply(unitMinutes, line.getPlanQuantity()), true, "原工作中心当前标准工时的剩余责任预测");
                }
                return new CapacityBasis(requested, requested, "STANDARD_HOURS", null, null, false,
                        "原标准工时工作中心当前依据已缺失，不可擅自移派资源");
            }
            if (routes.qualificationConflicts.contains(line.getProductCode() + "@" + requested)) {
                return new CapacityBasis(null, null, null, null, BigDecimal.ZERO, false,
                        "显式选择的产线存在多条不同生产速率的启用路线，资格依据不唯一");
            }
            List<MdmProcessRouteEntity> matches = routes.qualified
                    .getOrDefault(line.getProductCode(), List.of()).stream()
                    .filter(item -> Objects.equals(requested, item.getProductLineCode()))
                    .filter(item -> sameFactory(line.getFactoryCode(), item.getFactoryCode())).toList();
            if (matches.size() != 1) {
                return new CapacityBasis(null, null, null, null, BigDecimal.ZERO, false,
                        "显式选择的产线不在该产品启用工艺路线中，或不属于当前工厂");
            }
            MdmProcessRouteEntity selected = matches.get(0);
            return routeBasis(line, selected, Boolean.TRUE.equals(selected.getIsDefault())
                    ? "ROUTE_RATE" : "QUALIFIED_ROUTE_RATE");
        }
        MdmProcessRouteEntity route = routes.defaults.get(line.getProductCode());
        if (route != null && StrUtil.isNotBlank(route.getProductLineCode())
                && route.getProductRate() != null && route.getProductRate().signum() > 0
                && sameFactory(line.getFactoryCode(), route.getFactoryCode())) {
            return routeBasis(line, route, "ROUTE_RATE");
        }
        MdmMaterialEntity material = materials.get(line.getProductCode());
        if (material != null && StrUtil.isNotBlank(material.getDefaultWorkCenter())
                && Boolean.TRUE.equals(material.getStatus())
                && sameFactory(line.getFactoryCode(), material.getFactoryCode())) {
            BigDecimal standardHours = maxPositive(material.getStandardMachineHours(),
                    material.getStandardLaborHours());
            if (standardHours != null) {
                BigDecimal unitMinutes = standardHours.multiply(SIXTY);
                return new CapacityBasis(material.getDefaultWorkCenter(), material.getDefaultWorkCenter(),
                        "STANDARD_HOURS", unitMinutes, multiply(unitMinutes, line.getPlanQuantity()), true,
                        "物料标准机器/人员工时较大值");
            }
        }
        return new CapacityBasis(null, null, null, null, BigDecimal.ZERO, false,
                "缺少启用的默认工艺路线产线/速率，且物料默认工作中心/标准工时不完整");
    }

    private CapacityBasis routeBasis(PlnMpsLineEntity line, MdmProcessRouteEntity route, String basis) {
        BigDecimal unitMinutes = SIXTY.divide(route.getProductRate(), SCALE, RoundingMode.HALF_UP);
        return new CapacityBasis(route.getProductLineCode(),
                StrUtil.blankToDefault(route.getProductLineName(), route.getProductLineCode()),
                basis, unitMinutes, multiply(unitMinutes, line.getPlanQuantity()), true,
                "QUALIFIED_ROUTE_RATE".equals(basis)
                        ? "产品启用工艺路线合格替代产线/生产速率" : "默认工艺路线生产速率");
    }

    private List<String> productCodes(List<PlnMpsLineEntity> candidates, List<PlnMpsLineEntity> active) {
        return java.util.stream.Stream.concat(candidates.stream(), active.stream())
                .map(PlnMpsLineEntity::getProductCode).filter(StrUtil::isNotBlank).distinct().sorted().toList();
    }

    private Map<String, Object> evidenceLine(PlnMpsLineEntity line, CapacityBasis basis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lineId", line.getId());
        result.put("demandId", line.getDemandId());
        result.put("originalLineId", line.getCapacityOriginalLineId());
        result.put("remainingCommitmentId", line.getCapacityCommitmentId());
        result.put("responsibilityType", line.getCapacityResponsibilityType());
        result.put("originalPlannedDate", line.getCapacityOriginalDate());
        result.put("demandNo", line.getDemandNo());
        result.put("plannedDate", line.getPlannedDate());
        result.put("resourceCode", basis.resourceCode);
        result.put("demandMinutes", normalized(basis.demandMinutes));
        result.put("capacityEvidenceFingerprint", line.getCapacityEvidenceFingerprint());
        return result;
    }

    private BigDecimal shiftMinutes(MdmClassesEntity shift) {
        LocalDate today = LocalDate.of(2000, 1, 1);
        LocalDateTime start = today.atTime(shift.getStartTime());
        LocalDateTime end = today.atTime(shift.getEndTime());
        if (!end.isAfter(start)) end = end.plusDays(1);
        return BigDecimal.valueOf(Duration.between(start, end).toMinutes());
    }

    private String mode(PlnMpsPolicyEntity policy) {
        String value = policy == null ? null : policy.getRccpMode();
        return Set.of("OFF", "WARN", "BLOCK").contains(value) ? value : "OFF";
    }

    private BigDecimal maxLoadRate(PlnMpsPolicyEntity policy) {
        return policy == null || policy.getRccpMaxLoadRate() == null
                ? BigDecimal.valueOf(100) : policy.getRccpMaxLoadRate();
    }

    private String bucket(String resourceCode, LocalDate date) {
        return resourceCode + "\u0001" + date;
    }

    private String capacityLineKey(PlnMpsLineEntity line) {
        return line.getDemandId() + ":" + line.getBucketNo();
    }

    private boolean sameFactory(String expected, String actual) {
        return StrUtil.isBlank(actual) || Objects.equals(expected, actual);
    }

    private BigDecimal maxPositive(BigDecimal left, BigDecimal right) {
        left = left != null && left.signum() > 0 ? left : null;
        right = right != null && right.signum() > 0 ? right : null;
        if (left == null) return right;
        if (right == null) return left;
        return left.max(right);
    }

    private BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? BigDecimal.ZERO : scale(left.multiply(right));
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.CEILING);
    }

    private String normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("RCCP证据指纹生成失败", ex);
        }
    }

    private record CapacityBasis(String resourceCode, String resourceName, String basis,
                                 BigDecimal unitMinutes, BigDecimal demandMinutes,
                                 boolean verified, String message) {
    }

    private record CalendarDay(BigDecimal availableMinutes, boolean verified, Map<String, Object> snapshot) {
    }

    private record CapacityCalendar(Map<LocalDate, CalendarDay> days) {
    }

    private record LineChangeoverCatalog(Map<String, String> familyByProduct,
                                         List<MdmChangeoverRuleEntity> rules,
                                         boolean verified,
                                         String evidenceFingerprint,
                                         String message) {
        private static LineChangeoverCatalog unverified(String message) {
            return new LineChangeoverCatalog(Map.of(), List.of(), false, null, message);
        }
    }

    private record SequenceLine(PlnMpsLineEntity line, CapacityBasis basis, boolean candidate) {
    }

    private record ChangeoverResolution(boolean verified, long minutes,
                                        MdmChangeoverRuleEntity rule, String message) {
        private static ChangeoverResolution unverified(String message) {
            return new ChangeoverResolution(false, 0L, null, message);
        }
    }

    private record RouteCatalog(Map<String, MdmProcessRouteEntity> defaults,
                                Map<String, List<MdmProcessRouteEntity>> qualified,
                                Set<String> defaultConflicts,
                                Set<String> qualificationConflicts) {
    }

    private record BalanceContext(Map<PlnMpsLineEntity, CapacityBasis> basisByLine,
                                  Map<String, BigDecimal> remainingMinutes,
                                  Map<String, MdmProductionLotPolicyEntity> lotPolicies,
                                  RouteCatalog routes,
                                  List<String> productsWithoutLotPolicy,
                                  String lotPolicyEvidenceFingerprint,
                                  List<String> messages) {
    }

    private static final class Allocation {
        private final PlnMpsLineEntity source;
        private final LocalDate date;
        private final BigDecimal quantity;
        private final String resourceCode;
        private final MdmProductionLotPolicyEntity lotPolicy;
        private int bucketNo;

        private Allocation(PlnMpsLineEntity source, LocalDate date, BigDecimal quantity,
                           String resourceCode, MdmProductionLotPolicyEntity lotPolicy) {
            this.source = source;
            this.date = date;
            this.quantity = quantity;
            this.resourceCode = resourceCode;
            this.lotPolicy = lotPolicy;
        }

        private PlnMpsLineEntity source() {
            return source;
        }

        private LocalDate date() {
            return date;
        }

        private BigDecimal quantity() {
            return quantity;
        }
    }
}
