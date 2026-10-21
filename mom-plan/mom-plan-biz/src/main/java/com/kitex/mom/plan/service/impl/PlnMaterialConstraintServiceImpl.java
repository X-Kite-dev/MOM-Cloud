package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmRouteMaterialAssignmentQueryDTO;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.vo.MdmProcessRouteWorkingVO;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.plan.constant.MrpSubstituteStatusEnum;
import com.kitex.mom.plan.constant.SchedulingMaterialStatusEnum;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnMaterialConstraintService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlnMaterialConstraintServiceImpl implements PlnMaterialConstraintService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final PlanMatMrpService planMatMrpService;
    private final RemoteProcessRouteService remoteProcessRouteService;

    @Override
    public Map<Long, PlnMaterialConstraintEvaluationDTO> evaluate(List<PlnIssueEntity> issues,
                                                                  LocalDate baselineDate) {
        if (CollUtil.isEmpty(issues)) return Map.of();
        LocalDate baseline = baselineDate == null ? LocalDate.now() : baselineDate;
        Set<String> demandNos = issues.stream()
                .map(issue -> StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo()))
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        List<PlanMatMrpEntity> mrpRows = demandNos.isEmpty() ? List.of()
                : planMatMrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .in(PlanMatMrpEntity::getPlanDemandNo, demandNos));
        planMatMrpService.enrichReservationStatuses(mrpRows);
        Map<String, List<PlanMatMrpEntity>> grouped = mrpRows.stream()
                .collect(Collectors.groupingBy(PlanMatMrpEntity::getPlanDemandNo));
        Map<String, SupplyCatalog> supplyCache = new HashMap<>();
        Map<Long, PlnMaterialConstraintEvaluationDTO> result = new LinkedHashMap<>();
        for (PlnIssueEntity issue : issues) {
            if (issue.getId() == null) continue;
            String demandNo = StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo());
            List<PlanMatMrpEntity> rows = grouped.getOrDefault(demandNo, List.of()).stream()
                    .filter(row -> matchesPlanningSource(issue, row)).toList();
            String supplyKey = StrUtil.nullToEmpty(issue.getFactoryCode()) + "\u0000"
                    + StrUtil.nullToEmpty(demandNo) + "\u0000"
                    + StrUtil.nullToEmpty(issue.getPlanningSourceKey());
            SupplyCatalog supply = supplyCache.computeIfAbsent(supplyKey,
                    ignored -> loadSupplyCatalog(issue));
            result.put(issue.getId(), evaluateIssue(issue, rows, supply, baseline));
        }
        return result;
    }

    private SupplyCatalog loadSupplyCatalog(PlnIssueEntity issue) {
        String demandNo = StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo());
        if (StrUtil.isBlank(issue.getFactoryCode()) || StrUtil.isBlank(demandNo)) {
            return SupplyCatalog.unverified("计划缺少工厂或需求号，无法读取正式供应证据");
        }
        try {
            PlanMrpShortageQueryDTO query = new PlanMrpShortageQueryDTO();
            query.setFactoryCode(issue.getFactoryCode());
            query.setPlanDemandNo(demandNo);
            // V9要求采购/委外使用分钟级正式承诺；仍不依赖当前候选尚未生成的工序影响快照。
            query.setSupplyEvidenceVersion(9);
            PlanMrpShortageAnalysisDTO analysis = planMatMrpService.shortageAnalysis(query);
            if (analysis == null || analysis.getLines() == null) {
                return SupplyCatalog.unverified("缺料分析没有返回正式供应证据");
            }
            Map<Long, PlanMrpShortageLineDTO> byMrpId = analysis.getLines().stream()
                    .filter(item -> item.getMrpId() != null)
                    .collect(Collectors.toMap(PlanMrpShortageLineDTO::getMrpId,
                            Function.identity(), (left, right) -> left, LinkedHashMap::new));
            return new SupplyCatalog(byMrpId, true, null);
        } catch (Exception ex) {
            return SupplyCatalog.unverified("正式供应证据读取失败：" + safeMessage(ex));
        }
    }

    private boolean matchesPlanningSource(PlnIssueEntity issue, PlanMatMrpEntity mrp) {
        if (issue.getMpsLineId() != null) return Objects.equals(issue.getMpsLineId(), mrp.getMpsLineId());
        if (StrUtil.isNotBlank(issue.getPlanningSourceKey())) {
            return Objects.equals(issue.getPlanningSourceKey(), mrp.getPlanningSourceKey());
        }
        return mrp.getMpsLineId() == null;
    }

    private PlnMaterialConstraintEvaluationDTO evaluateIssue(PlnIssueEntity issue,
                                                             List<PlanMatMrpEntity> demandRows,
                                                             SupplyCatalog supply,
                                                             LocalDate baseline) {
        LocalDateTime checkedTime = LocalDateTime.now();
        LocalDateTime baselineTime = baseline.isAfter(checkedTime.toLocalDate())
                ? baseline.atStartOfDay() : checkedTime.truncatedTo(ChronoUnit.MINUTES);
        if (CollUtil.isEmpty(demandRows)) {
            return new PlnMaterialConstraintEvaluationDTO()
                    .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                    .setStatus(SchedulingMaterialStatusEnum.MRP_NOT_CALCULATED.getCode())
                    .setHardBlocked(Boolean.TRUE).setBomLineageVerified(Boolean.FALSE)
                    .setSummary("未找到计划号对应的MRP结果，必须先完成MRP运算与下达")
                    .setLines(List.of()).setOperationConstraints(List.of()).setCheckedTime(checkedTime);
        }

        LineageCatalog lineage = parseLineage(demandRows);
        if (!lineage.verified) {
            return new PlnMaterialConstraintEvaluationDTO()
                    .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                    .setStatus(SchedulingMaterialStatusEnum.BOM_LINEAGE_INVALID.getCode())
                    .setHardBlocked(Boolean.TRUE).setBomLineageVerified(Boolean.FALSE)
                    .setBomPathFingerprint(lineage.fingerprint)
                    .setSummary(lineage.error + "；请重新执行MRP生成逐层需求证据")
                    .setLines(List.of()).setOperationConstraints(List.of()).setCheckedTime(checkedTime);
        }

        List<NodeState> directNodes = lineage.nodesByPath.values().stream()
                .filter(node -> Objects.equals(issue.getProductCode(), node.path.getParentMaterialCode()))
                .sorted(Comparator.comparing(node -> node.path.getBomPath())).toList();
        if (directNodes.isEmpty()) {
            return new PlnMaterialConstraintEvaluationDTO()
                    .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                    .setStatus(SchedulingMaterialStatusEnum.READY.getCode())
                    .setHardBlocked(Boolean.FALSE).setMaterialAvailableDate(baseline)
                    .setMaterialAvailableTime(baselineTime)
                    .setBomPathFingerprint(lineage.fingerprint).setBomLineageVerified(Boolean.TRUE)
                    .setSummary("当前生产计划没有需要展开的直接下级物料")
                    .setLines(List.of()).setOperationConstraints(List.of()).setCheckedTime(checkedTime);
        }

        lineage.nodesByPath.values().forEach(node -> node.assessment = assessSupply(
                node.row, node.path.getNetDemandNum(), supply, baselineTime));
        EvaluationState state = new EvaluationState(baselineTime);
        Set<String> relevantPaths = new LinkedHashSet<>();
        for (NodeState direct : directNodes) {
            evaluateNode(direct, baselineTime, new LinkedHashSet<>());
            collectSubtreePaths(direct, relevantPaths);
            mergeNodeIntoState(direct, state);
        }
        List<PlnMaterialConstraintLineDTO> lines = relevantPaths.stream()
                .map(lineage.nodesByPath::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((NodeState node) -> node.path.getBomLevel())
                        .thenComparing(node -> node.path.getBomPath()))
                .map(node -> toLine(node, directNodes.contains(node))).toList();

        List<PlnOperationMaterialConstraintDTO> operationConstraints =
                buildOperationConstraints(issue, directNodes, lineage.fingerprint, state, baseline);
        String summary = summary(state, directNodes.size(), relevantPaths.size());
        return new PlnMaterialConstraintEvaluationDTO()
                .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                .setStatus(state.status).setHardBlocked(state.hardBlocked)
                .setMaterialAvailableDate(state.availableTime.toLocalDate())
                .setMaterialAvailableTime(state.availableTime)
                .setBomPathFingerprint(lineage.fingerprint).setBomLineageVerified(Boolean.TRUE)
                .setSummary(summary).setLines(lines).setOperationConstraints(operationConstraints)
                .setCheckedTime(checkedTime);
    }

    private LineageCatalog parseLineage(List<PlanMatMrpEntity> rows) {
        Map<String, NodeState> nodes = new LinkedHashMap<>();
        List<String> fingerprints = new ArrayList<>();
        try {
            for (PlanMatMrpEntity row : rows) {
                if (row.getId() == null || StrUtil.isBlank(row.getBomPathSnapshot())
                        || StrUtil.isBlank(row.getBomPathFingerprint())) {
                    return LineageCatalog.invalid("MRP物料[" + row.getMatCode() + "]缺少BOM逐层路径快照");
                }
                String actualFingerprint = hash(row.getBomPathSnapshot());
                if (!actualFingerprint.equals(row.getBomPathFingerprint())) {
                    return LineageCatalog.invalid("MRP物料[" + row.getMatCode() + "]BOM路径指纹不一致");
                }
                List<PlanBomRequirementPathDTO> paths = OBJECT_MAPPER.readValue(row.getBomPathSnapshot(),
                        new TypeReference<List<PlanBomRequirementPathDTO>>() {
                        });
                if (paths.isEmpty()) return LineageCatalog.invalid("MRP物料[" + row.getMatCode() + "]BOM路径为空");
                BigDecimal gross = BigDecimal.ZERO;
                BigDecimal allocated = BigDecimal.ZERO;
                BigDecimal net = BigDecimal.ZERO;
                for (PlanBomRequirementPathDTO path : paths) {
                    if (path == null || StrUtil.isBlank(path.getBomPath())
                            || StrUtil.isBlank(path.getParentBomPath()) || path.getBomLevel() == null
                            || path.getBomLevel() < 1 || !Objects.equals(row.getMatCode(), path.getMaterialCode())) {
                        return LineageCatalog.invalid("MRP物料[" + row.getMatCode() + "]存在无效BOM路径节点");
                    }
                    if (nodes.putIfAbsent(path.getBomPath(), new NodeState(row, path)) != null) {
                        return LineageCatalog.invalid("BOM路径[" + path.getBomPath() + "]重复");
                    }
                    gross = gross.add(optional(path.getGrossDemandNum()));
                    allocated = allocated.add(optional(path.getStockAllocatedNum()));
                    net = net.add(optional(path.getNetDemandNum()));
                }
                if (!same(gross, row.getGrossDemandNum()) || !same(allocated, row.getStockAllocatedNum())
                        || !same(net, row.getNetDemandNum())) {
                    return LineageCatalog.invalid("MRP物料[" + row.getMatCode() + "]聚合数量与BOM路径数量不守恒");
                }
                if (allocated.signum() > 0 && (!same(allocated, row.getInventoryPeggedNum())
                        || StrUtil.isBlank(row.getMrpRunNo()) || StrUtil.isBlank(row.getInventoryPeggingFingerprint())
                        || !Set.of("PLANNED", "RESERVED", "CONSUMED").contains(row.getInventoryPeggingStatus()))) {
                    return LineageCatalog.invalid("MRP物料[" + row.getMatCode()
                            + "]缺少有效的跨需求库存Pegging证据");
                }
                fingerprints.add(row.getId() + ":" + row.getBomPathFingerprint() + ":"
                        + StrUtil.nullToEmpty(row.getInventoryPeggingFingerprint()));
            }
            for (NodeState node : nodes.values()) {
                NodeState parent = nodes.get(node.path.getParentBomPath());
                if (parent != null) {
                    if (node.path.getBomLevel() != parent.path.getBomLevel() + 1
                            || !Objects.equals(node.path.getParentMaterialCode(), parent.path.getMaterialCode())) {
                        return LineageCatalog.invalid("BOM路径[" + node.path.getBomPath() + "]父子层级或物料关系不一致");
                    }
                    parent.children.add(node);
                } else if (!node.path.getParentBomPath().startsWith("ROOT:")) {
                    return LineageCatalog.invalid("BOM路径[" + node.path.getBomPath() + "]引用了不存在的父节点");
                }
            }
            for (NodeState node : nodes.values()) {
                if (Boolean.TRUE.equals(node.path.getChildBomExploded()) && node.children.isEmpty()) {
                    return LineageCatalog.invalid("BOM路径[" + node.path.getBomPath() + "]标记已展开但没有下层节点");
                }
            }
            fingerprints.sort(String::compareTo);
            return new LineageCatalog(nodes, true, hash(String.join("|", fingerprints)), null);
        } catch (Exception ex) {
            return LineageCatalog.invalid("BOM逐层路径快照无法解析：" + safeMessage(ex));
        }
    }

    private LineAssessment assessSupply(PlanMatMrpEntity row, BigDecimal pathRemaining,
                                        SupplyCatalog supply, LocalDateTime baseline) {
        LineAssessment result = new LineAssessment();
        result.status = SchedulingMaterialStatusEnum.READY.getCode();
        result.expectedTime = baseline;
        result.precision = "INVENTORY_NOW";
        result.basis = "MRP_ALLOCATED_STOCK";
        String substituteStatus = StrUtil.blankToDefault(row.getSubstituteStatus(), "NONE");
        if (MrpSubstituteStatusEnum.POLICY_CONFLICT.getCode().equals(substituteStatus)) {
            result.block(SchedulingMaterialStatusEnum.POLICY_CONFLICT, 100, "BOM替代策略冲突");
        } else if (MrpSubstituteStatusEnum.MULTILEVEL_RECALC_REQUIRED.getCode().equals(substituteStatus)) {
            result.block(SchedulingMaterialStatusEnum.MULTILEVEL_SUBSTITUTE_RECALC_REQUIRED, 100,
                    "自制中间件替代需撤回下层供应并受控重算MRP");
        } else if (MrpSubstituteStatusEnum.PENDING.getCode().equals(substituteStatus)) {
            result.block(SchedulingMaterialStatusEnum.SUBSTITUTE_PENDING, 95, "替代建议待处理");
        }
        boolean approved = Set.of(MrpSubstituteStatusEnum.APPROVED.getCode(),
                MrpSubstituteStatusEnum.PARTIAL_APPROVED.getCode()).contains(substituteStatus);
        if (approved) {
            if (StrUtil.isBlank(row.getMaterialReservationNo()) || !"ACTIVE".equals(row.getMaterialReservationStatus())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_RISK, 90, "替代方案没有有效WMS预留");
            } else if (Boolean.FALSE.equals(row.getMaterialReservationHealthy())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_RISK, 90,
                        StrUtil.blankToDefault(row.getMaterialReservationRisk(), "替代预留复核未通过"));
            } else if (!Boolean.TRUE.equals(row.getMaterialReservationHealthy())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_UNVERIFIED, 85, "替代预留状态暂未复核");
            }
        }
        if (Boolean.TRUE.equals(row.getStatus()) && optional(row.getStockAllocatedNum()).signum() > 0) {
            if (StrUtil.isBlank(row.getMainMaterialReservationNo())
                    || !"ACTIVE".equals(row.getMainMaterialReservationStatus())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_RISK, 90, "主料库存分配没有有效WMS预留");
            } else if (Boolean.FALSE.equals(row.getMainMaterialReservationHealthy())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_RISK, 90,
                        StrUtil.blankToDefault(row.getMainMaterialReservationRisk(), "主料预留复核未通过"));
            } else if (!Boolean.TRUE.equals(row.getMainMaterialReservationHealthy())) {
                result.block(SchedulingMaterialStatusEnum.RESERVATION_UNVERIFIED, 85, "主料预留状态暂未复核");
            }
        }

        // 聚合供应证据的覆盖日期可被同物料各路径共享，但已由本路径库存覆盖的数量不应被其他路径缺口拖延。
        BigDecimal aggregateNet = optional(row.getNetDemandNum());
        BigDecimal aggregateRemaining = optional(row.getRemainingDemandNum());
        BigDecimal remaining = aggregateNet.signum() <= 0 ? BigDecimal.ZERO
                : optional(pathRemaining).multiply(aggregateRemaining)
                .divide(aggregateNet, 12, java.math.RoundingMode.HALF_UP);
        if (remaining.signum() <= 0) {
            if (approved) result.basis = "APPROVED_SUBSTITUTE_RESERVATION";
            return result;
        }
        result.expectedTime = null;
        result.precision = null;
        result.basis = null;
        if (!Boolean.TRUE.equals(row.getStatus())) {
            result.block(SchedulingMaterialStatusEnum.SUPPLY_NOT_RELEASED, 80,
                    "仍缺" + plain(remaining) + "，供应计划尚未下达");
            return result;
        }
        if (!supply.verified) {
            result.block(SchedulingMaterialStatusEnum.SUPPLY_EVIDENCE_UNVERIFIED, 82, supply.error);
            return result;
        }
        PlanMrpShortageLineDTO evidence = supply.byMrpId.get(row.getId());
        if (evidence == null || Boolean.FALSE.equals(evidence.getSupplyEvidenceComplete())) {
            result.block(SchedulingMaterialStatusEnum.SUPPLY_EVIDENCE_UNVERIFIED, 82,
                    evidence == null ? "未找到对应正式供应证据" : "正式供应证据不完整："
                            + StrUtil.blankToDefault(evidence.getSupplyPlanStatus(), "UNKNOWN"));
            return result;
        }
        LocalDateTime expected = evidence.getExpectedSupplyTime() != null
                ? evidence.getExpectedSupplyTime()
                : evidence.getExpectedSupplyDate() == null ? null
                : evidence.getExpectedSupplyDate().atTime(LocalTime.of(23, 59));
        if (expected == null) {
            result.block(SchedulingMaterialStatusEnum.SUPPLY_DATE_MISSING, 75,
                    "已下达供应但未形成覆盖全部缺口的预计可用时刻，状态="
                            + StrUtil.blankToDefault(evidence.getSupplyPlanStatus(), "UNKNOWN"));
            return result;
        }
        result.expectedTime = expected;
        result.precision = StrUtil.blankToDefault(evidence.getSupplyTimePrecision(),
                evidence.getExpectedSupplyTime() == null ? "DATE_END" : "MINUTE");
        result.basis = StrUtil.blankToDefault(evidence.getSupplyDateBasis(), "FORMAL_SUPPLY_EVIDENCE");
        if (result.expectedTime.isBefore(baseline)) {
            result.block(SchedulingMaterialStatusEnum.SUPPLY_OVERDUE, 70,
                    "正式供应时刻" + result.expectedTime + "已逾期且仍未齐套");
        } else {
            result.status = SchedulingMaterialStatusEnum.WAITING_SUPPLY.getCode();
        }
        return result;
    }

    private void evaluateNode(NodeState node, LocalDateTime baseline, Set<String> visiting) {
        if (node.evaluated) return;
        if (!visiting.add(node.path.getBomPath())) {
            node.block(SchedulingMaterialStatusEnum.BOM_LINEAGE_INVALID.getCode(), "BOM路径存在循环引用");
            return;
        }
        for (NodeState child : node.children) evaluateNode(child, baseline, visiting);
        node.effectiveTime = node.assessment == null ? null : node.assessment.expectedTime;
        node.effectivePrecision = node.assessment == null ? null : node.assessment.precision;
        node.status = node.assessment == null ? SchedulingMaterialStatusEnum.SUPPLY_EVIDENCE_UNVERIFIED.getCode()
                : node.assessment.status;
        node.hardBlocked = node.assessment == null || node.assessment.hardBlocked;
        if (node.assessment != null) node.reasons.addAll(node.assessment.reasons);
        List<NodeState> consideredChildren = Boolean.TRUE.equals(node.path.getChildBomExploded())
                ? node.children : List.of();
        node.childConvergenceTime = consideredChildren.stream().map(child -> child.effectiveTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        node.childConvergencePrecision = mergePrecision(consideredChildren.stream()
                .filter(child -> Objects.equals(child.effectiveTime, node.childConvergenceTime))
                .map(child -> child.effectivePrecision).toList());
        Optional<NodeState> blockedChild = consideredChildren.stream().filter(child -> child.hardBlocked).findFirst();
        if (blockedChild.isPresent()) {
            node.block(blockedChild.get().status, "下层BOM[" + blockedChild.get().path.getMaterialCode()
                    + "]未形成可信可用时刻");
            node.convergenceStatus = "CHILD_BLOCKED";
        } else if (node.childConvergenceTime != null) {
            if (node.effectiveTime == null || node.childConvergenceTime.isAfter(node.effectiveTime)) {
                if (MatAttributeEnum.MES.getCode().equals(node.path.getSupplyType())
                        && node.assessment != null && node.assessment.expectedTime != null) {
                    node.block(SchedulingMaterialStatusEnum.MULTILEVEL_CONVERGENCE_CONFLICT.getCode(),
                            "自制预计完工时刻" + node.assessment.expectedTime + "早于下层物料汇合时刻"
                                    + node.childConvergenceTime);
                    node.convergenceStatus = "PRODUCTION_BEFORE_COMPONENTS";
                }
                node.effectiveTime = node.childConvergenceTime;
                node.effectivePrecision = node.childConvergencePrecision;
            } else if (node.childConvergenceTime.equals(node.effectiveTime)) {
                node.effectivePrecision = mergePrecision(Arrays.asList(node.effectivePrecision,
                        node.childConvergencePrecision));
            }
            if (node.convergenceStatus == null) node.convergenceStatus = "CHILDREN_CONVERGED";
        } else {
            node.convergenceStatus = node.hardBlocked ? "BLOCKED" : "DIRECT_SUPPLY";
        }
        if (node.effectiveTime == null && !node.hardBlocked) node.effectiveTime = baseline;
        node.evaluated = true;
        visiting.remove(node.path.getBomPath());
    }

    private void mergeNodeIntoState(NodeState node, EvaluationState state) {
        if (node.hardBlocked) {
            block(state, materialStatus(node.status), priority(node.status),
                    "物料[" + node.path.getMaterialCode() + "]路径[" + node.path.getBomPath() + "]"
                            + String.join("；", node.reasons));
        } else if (node.effectiveTime != null && node.effectiveTime.isAfter(state.availableTime)) {
            state.availableTime = node.effectiveTime;
            state.waitingCount++;
            if (!state.hardBlocked && state.priority < 50) {
                state.status = SchedulingMaterialStatusEnum.WAITING_SUPPLY.getCode();
                state.priority = 50;
            }
        }
    }

    private List<PlnOperationMaterialConstraintDTO> buildOperationConstraints(
            PlnIssueEntity issue, List<NodeState> directNodes, String lineageFingerprint,
            EvaluationState state, LocalDate baseline) {
        Set<String> materialCodes = directNodes.stream().map(node -> node.path.getMaterialCode())
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        if (materialCodes.isEmpty()) return List.of();
        MdmProcessRouteEntity route;
        try {
            R<MdmProcessRouteEntity> response = remoteProcessRouteService.getDefaultByProductCode(issue.getProductCode());
            if (response == null || !response.isOk()) {
                block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                        "MDM默认工艺路线暂无法核验，不能形成工序投料汇合约束");
                return List.of();
            }
            route = response.getData();
        } catch (Exception ex) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "MDM默认工艺路线读取失败：" + safeMessage(ex));
            return List.of();
        }
        if (route == null || StrUtil.isBlank(route.getRouteCode())) return List.of();
        if (!Objects.equals(issue.getFactoryCode(), route.getFactoryCode())) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "默认路线与计划工厂不一致，不能形成节点投料约束");
            return List.of();
        }
        List<MdmProcessRouteWorkingVO> routeWorkings = new ArrayList<>();
        try {
            R<List<MdmProcessRouteWorkingEntity>> response = remoteProcessRouteService.getProcedureList(route.getRouteCode());
            if (response == null || !response.isOk() || response.getData() == null || response.getData().isEmpty())
                throw new IllegalArgumentException("路线真实节点清单不可核验");
            Set<String> identities = new HashSet<>();
            for (MdmProcessRouteWorkingEntity row : response.getData()) {
                if (row == null || !Objects.equals(route.getRouteCode(), row.getRouteCode())
                        || !Objects.equals(issue.getFactoryCode(), row.getFactoryCode())
                        || StrUtil.isBlank(row.getProcessCode()))
                    throw new IllegalArgumentException("路线节点主工序或工厂范围不一致");
                String code = PlnRouteNodeSchedulingSupport.nodeCode(row.getId(), row.getRouteNodeCode());
                if (!identities.add(code)) throw new IllegalArgumentException("路线节点重复");
                MdmProcessRouteWorkingVO working = new MdmProcessRouteWorkingVO();
                working.setId(row.getId());
                working.setRouteNodeCode(code);
                working.setProcessCode(row.getProcessCode());
                working.setProcessName(row.getProcessName());
                routeWorkings.add(working);
            }
        } catch (Exception ex) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "路线投料节点身份无法核验：" + safeMessage(ex));
            return List.of();
        }
        List<MdmProcessProcedureMatEntity> assignments;
        try {
            MdmRouteMaterialAssignmentQueryDTO query = new MdmRouteMaterialAssignmentQueryDTO();
            query.setFactoryCode(issue.getFactoryCode());
            query.setRouteCodes(Set.of(route.getRouteCode()));
            query.setMaterialCodes(materialCodes);
            R<List<MdmProcessProcedureMatEntity>> response = remoteProcessRouteService.getMaterialAssignments(query);
            if (response == null || !response.isOk() || response.getData() == null) {
                block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                        "MDM工序投料映射暂无法核验");
                return List.of();
            }
            assignments = response.getData();
        } catch (Exception ex) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "MDM工序投料映射读取失败：" + safeMessage(ex));
            return List.of();
        }
        Map<String, List<MdmProcessProcedureMatEntity>> assignmentsByNode = new LinkedHashMap<>();
        Map<String, MdmProcessRouteWorkingVO> workingsByNode = new LinkedHashMap<>();
        try {
            for (MdmProcessProcedureMatEntity assignment : assignments) {
                if (assignment == null || !Objects.equals(route.getRouteCode(), assignment.getRouteCode())
                        || !Objects.equals(issue.getFactoryCode(), assignment.getFactoryCode())
                        || !materialCodes.contains(assignment.getMaterialCode()))
                    throw new IllegalArgumentException("投料返回范围外路线、工厂或物料");
                MdmProcessRouteWorkingVO working = PlnRouteNodeSchedulingSupport.working(routeWorkings,
                        assignment.getRouteNodeCode(), assignment.getProcessCode(), null);
                assignmentsByNode.computeIfAbsent(working.getRouteNodeCode(), ignored -> new ArrayList<>()).add(assignment);
                workingsByNode.put(working.getRouteNodeCode(), working);
            }
        } catch (Exception ex) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "工序投料不能精确归属真实节点：" + safeMessage(ex));
            return List.of();
        }
        Set<String> mappedMaterials = assignments.stream().map(MdmProcessProcedureMatEntity::getMaterialCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        Set<String> missing = materialCodes.stream().filter(code -> !mappedMaterials.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missing.isEmpty()) {
            block(state, SchedulingMaterialStatusEnum.MATERIAL_OPERATION_MAPPING_MISSING, 88,
                    "直接物料[" + String.join(",", missing) + "]未维护工序投料映射");
        }
        Map<String, List<NodeState>> nodesByMaterial = directNodes.stream()
                .collect(Collectors.groupingBy(node -> node.path.getMaterialCode(), LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<NodeState>> nodesByRouteNode = new LinkedHashMap<>();
        assignmentsByNode.forEach((nodeCode, rows) -> rows.forEach(item ->
                nodesByRouteNode.computeIfAbsent(nodeCode, ignored -> new ArrayList<>())
                        .addAll(nodesByMaterial.getOrDefault(item.getMaterialCode(), List.of()))));
        List<PlnOperationMaterialConstraintDTO> result = new ArrayList<>();
        for (Map.Entry<String, List<NodeState>> entry : nodesByRouteNode.entrySet()) {
            List<NodeState> nodes = entry.getValue().stream().distinct()
                    .sorted(Comparator.comparing(node -> node.path.getBomPath())).toList();
            boolean hardBlocked = nodes.stream().anyMatch(node -> node.hardBlocked);
            LocalDateTime available = nodes.stream().map(node -> node.effectiveTime).filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(baseline.atStartOfDay());
            String timePrecision = mergePrecision(nodes.stream()
                    .filter(node -> Objects.equals(node.effectiveTime, available))
                    .map(node -> node.effectivePrecision).toList());
            String status = hardBlocked ? SchedulingOperationStatus.BLOCKED
                    : available.isAfter(baseline.atStartOfDay()) ? SchedulingOperationStatus.WAITING
                    : SchedulingOperationStatus.READY;
            List<String> codes = nodes.stream().map(node -> node.path.getMaterialCode()).distinct().toList();
            List<String> paths = nodes.stream().map(node -> node.path.getBomPath()).toList();
            String summary = hardBlocked
                    ? "工序投料存在硬阻断：" + nodes.stream().filter(node -> node.hardBlocked)
                    .map(node -> node.path.getMaterialCode() + "=" + String.join("/", node.reasons))
                    .collect(Collectors.joining("；"))
                    : "工序" + workingsByNode.get(entry.getKey()).getProcessCode() + "[" + entry.getKey()
                    + "]的" + codes.size() + "项物料于" + available + "汇合";
            String assignmentEvidence = assignmentsByNode.get(entry.getKey()).stream()
                    .sorted(Comparator.comparing(MdmProcessProcedureMatEntity::getMaterialCode)
                            .thenComparing(item -> Objects.requireNonNullElse(item.getStep(), Integer.MAX_VALUE))
                            .thenComparing(MdmProcessProcedureMatEntity::getId,
                                    Comparator.nullsLast(Long::compareTo)))
                    .map(item -> item.getId() + ":" + item.getMaterialCode() + ":"
                            + item.getQuantity() + ":" + item.getStep())
                    .collect(Collectors.joining("|"));
            String evidence = hash(route.getRouteCode() + "|" + entry.getKey() + "|"
                    + nodes.stream().map(node -> node.path.getBomPath() + ":" + node.effectiveTime + ":"
                    + node.effectivePrecision + ":"
                    + node.status).collect(Collectors.joining("|")) + "|" + assignmentEvidence
                    + "|" + lineageFingerprint);
            result.add(new PlnOperationMaterialConstraintDTO()
                    .setRouteCode(route.getRouteCode()).setSourceRouteNodeCode(entry.getKey())
                    .setProcessCode(workingsByNode.get(entry.getKey()).getProcessCode())
                    .setProcessName(workingsByNode.get(entry.getKey()).getProcessName())
                    .setMaterialAvailableDate(available.toLocalDate()).setMaterialAvailableTime(available)
                    .setMaterialTimePrecision(timePrecision)
                    .setStatus(status).setHardBlocked(hardBlocked)
                    .setSummary(summary).setMaterialCodes(codes).setBomPaths(paths)
                    .setEvidenceFingerprint(evidence));
        }
        result.sort(Comparator.comparing(PlnRouteNodeSchedulingSupport::materialKey));
        return result;
    }

    private PlnMaterialConstraintLineDTO toLine(NodeState node, boolean direct) {
        PlanMatMrpEntity row = node.row;
        PlanBomRequirementPathDTO path = node.path;
        LineAssessment assessment = node.assessment;
        return new PlnMaterialConstraintLineDTO()
                .setMrpId(row.getId()).setBomLineId(path.getBomLineId()).setBomId(path.getBomId())
                .setSubitemBomId(path.getSubitemBomId()).setBomPath(path.getBomPath())
                .setParentBomPath(path.getParentBomPath()).setBomLevel(path.getBomLevel())
                .setParentMaterialCode(path.getParentMaterialCode()).setDirectToScheduledProduct(direct)
                .setHasChildBom(path.getHasChildBom()).setMaterialCode(path.getMaterialCode())
                .setMaterialName(path.getMaterialName()).setDemandWarehouseCode(row.getDemandWarehouseCode())
                .setGrossDemandNum(path.getGrossDemandNum()).setStockAllocatedNum(path.getStockAllocatedNum())
                .setRemainingDemandNum(path.getNetDemandNum()).setReadinessStatus(row.getReadinessStatus())
                .setSubstituteStatus(StrUtil.blankToDefault(row.getSubstituteStatus(), "NONE"))
                .setSupplyType(row.getType()).setSupplyIssued(row.getStatus())
                .setPlannedReleaseDate(path.getPlannedReleaseDate())
                .setExpectedAvailableDate(assessment == null || assessment.expectedTime == null
                        ? null : assessment.expectedTime.toLocalDate())
                .setChildConvergenceDate(node.childConvergenceTime == null
                        ? null : node.childConvergenceTime.toLocalDate())
                .setEffectiveAvailableDate(node.effectiveTime == null ? null : node.effectiveTime.toLocalDate())
                .setExpectedAvailableTime(assessment == null ? null : assessment.expectedTime)
                .setChildConvergenceTime(node.childConvergenceTime)
                .setEffectiveAvailableTime(node.effectiveTime)
                .setSupplyTimePrecision(assessment == null ? null : assessment.precision)
                .setChildConvergenceTimePrecision(node.childConvergencePrecision)
                .setEffectiveTimePrecision(node.effectivePrecision)
                .setAvailabilityBasis(assessment == null ? null : assessment.basis)
                .setConvergenceStatus(node.convergenceStatus)
                .setChildBomPaths(node.children.stream().map(child -> child.path.getBomPath()).toList())
                .setReservationNo(row.getMaterialReservationNo()).setReservationStatus(row.getMaterialReservationStatus())
                .setReservationHealthy(row.getMaterialReservationHealthy())
                .setMainReservationNo(row.getMainMaterialReservationNo())
                .setMainReservationStatus(row.getMainMaterialReservationStatus())
                .setMainReservationHealthy(row.getMainMaterialReservationHealthy())
                .setBlockingReason(String.join("；", node.reasons));
    }

    private void collectSubtreePaths(NodeState node, Set<String> result) {
        if (!result.add(node.path.getBomPath())) return;
        if (Boolean.TRUE.equals(node.path.getChildBomExploded())) {
            node.children.forEach(child -> collectSubtreePaths(child, result));
        }
    }

    private String summary(EvaluationState state, int directCount, int pathCount) {
        if (state.hardBlocked) {
            List<String> reasons = state.reasons.stream().distinct().toList();
            String result = reasons.stream().limit(3).collect(Collectors.joining("；"));
            return result + (reasons.size() > 3 ? "；另有" + (reasons.size() - 3) + "项风险" : "");
        }
        if (SchedulingMaterialStatusEnum.WAITING_SUPPLY.getCode().equals(state.status)) {
            return directCount + "项直接物料、" + pathCount + "个BOM路径节点已逐层汇合，预计最早"
                    + state.availableTime + "满足全部投料";
        }
        return directCount + "项直接物料、" + pathCount + "个BOM路径节点当前齐套";
    }

    private void block(EvaluationState state, SchedulingMaterialStatusEnum status, int priority,
                       String reason) {
        state.hardBlocked = true;
        state.reasons.add(reason);
        if (priority > state.priority) {
            state.status = status.getCode();
            state.priority = priority;
        }
    }

    private SchedulingMaterialStatusEnum materialStatus(String status) {
        try {
            return SchedulingMaterialStatusEnum.valueOf(status);
        } catch (Exception ignored) {
            return SchedulingMaterialStatusEnum.SUPPLY_EVIDENCE_UNVERIFIED;
        }
    }

    private int priority(String status) {
        if (SchedulingMaterialStatusEnum.BOM_LINEAGE_INVALID.getCode().equals(status)) return 110;
        if (SchedulingMaterialStatusEnum.POLICY_CONFLICT.getCode().equals(status)) return 100;
        if (SchedulingMaterialStatusEnum.MULTILEVEL_SUBSTITUTE_RECALC_REQUIRED.getCode().equals(status)) return 100;
        if (SchedulingMaterialStatusEnum.SUBSTITUTE_PENDING.getCode().equals(status)) return 95;
        if (SchedulingMaterialStatusEnum.RESERVATION_RISK.getCode().equals(status)) return 90;
        if (SchedulingMaterialStatusEnum.MULTILEVEL_CONVERGENCE_CONFLICT.getCode().equals(status)) return 89;
        if (SchedulingMaterialStatusEnum.SUPPLY_EVIDENCE_UNVERIFIED.getCode().equals(status)) return 82;
        if (SchedulingMaterialStatusEnum.SUPPLY_NOT_RELEASED.getCode().equals(status)) return 80;
        if (SchedulingMaterialStatusEnum.SUPPLY_DATE_MISSING.getCode().equals(status)) return 75;
        if (SchedulingMaterialStatusEnum.SUPPLY_OVERDUE.getCode().equals(status)) return 70;
        return 60;
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return optional(left).compareTo(optional(right)) == 0;
    }

    private String plain(BigDecimal value) {
        return optional(value).stripTrailingZeros().toPlainString();
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("物料约束证据指纹生成失败", ex);
        }
    }

    private String safeMessage(Exception ex) {
        return StrUtil.maxLength(StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 200);
    }

    private String mergePrecision(Collection<String> values) {
        if (values == null) return null;
        if (values.stream().anyMatch("DATE_END"::equals)) return "DATE_END";
        if (values.stream().anyMatch("MINUTE"::equals)) return "MINUTE";
        if (values.stream().anyMatch("INVENTORY_NOW"::equals)) return "INVENTORY_NOW";
        return null;
    }

    private static final class SchedulingOperationStatus {
        private static final String READY = "READY";
        private static final String WAITING = "WAITING_SUPPLY";
        private static final String BLOCKED = "BLOCKED";
    }

    private record SupplyCatalog(Map<Long, PlanMrpShortageLineDTO> byMrpId,
                                 boolean verified, String error) {
        private static SupplyCatalog unverified(String error) {
            return new SupplyCatalog(Map.of(), false, error);
        }
    }

    private record LineageCatalog(Map<String, NodeState> nodesByPath,
                                  boolean verified, String fingerprint, String error) {
        private static LineageCatalog invalid(String error) {
            return new LineageCatalog(Map.of(), false, null, error);
        }
    }

    private static final class LineAssessment {
        private final List<String> reasons = new ArrayList<>();
        private String status;
        private boolean hardBlocked;
        private int priority;
        private LocalDateTime expectedTime;
        private String precision;
        private String basis;

        private void block(SchedulingMaterialStatusEnum value, int valuePriority, String reason) {
            hardBlocked = true;
            reasons.add(reason);
            if (valuePriority > priority) {
                status = value.getCode();
                priority = valuePriority;
            }
        }
    }

    private static final class NodeState {
        private final PlanMatMrpEntity row;
        private final PlanBomRequirementPathDTO path;
        private final List<NodeState> children = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private LineAssessment assessment;
        private boolean evaluated;
        private boolean hardBlocked;
        private String status;
        private LocalDateTime childConvergenceTime;
        private String childConvergencePrecision;
        private LocalDateTime effectiveTime;
        private String effectivePrecision;
        private String convergenceStatus;

        private NodeState(PlanMatMrpEntity row, PlanBomRequirementPathDTO path) {
            this.row = row;
            this.path = path;
        }

        private void block(String valueStatus, String reason) {
            hardBlocked = true;
            status = valueStatus;
            reasons.add(reason);
        }
    }

    private static final class EvaluationState {
        private final List<String> reasons = new ArrayList<>();
        private String status = SchedulingMaterialStatusEnum.READY.getCode();
        private boolean hardBlocked;
        private int priority;
        private int waitingCount;
        private LocalDateTime availableTime;

        private EvaluationState(LocalDateTime baseline) {
            availableTime = baseline;
        }
    }
}
