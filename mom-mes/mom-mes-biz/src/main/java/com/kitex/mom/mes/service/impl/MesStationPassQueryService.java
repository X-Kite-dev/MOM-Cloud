package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTravelerEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesTravelerMapper;
import com.kitex.mom.mes.service.*;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.util.MesPredecessorPassEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PAD/APP 扫码过站查询，只返回服务器判定的工序候选，不在客户端硬编码客户路线。
 */
@Service
@RequiredArgsConstructor
public class MesStationPassQueryService {
    private final MesTravelerMapper travelerMapper;
    private final MesTaskArrangeService arrangeService;
    private final MesTaskService taskService;
    private final MesTaskReportService reportService;
    private final MesExecutionTemplateService executionTemplateService;
    private final MesIdentifierMappingService identifierMappingService;
    private final MesIdentifierRouteReturnCoordinator routeReturnCoordinator;
    private final MesOfflineReportPermitService offlineReportPermitService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesIdentifierBindingService identifierBindings;
    private final MesExecutionNodeReader executionNodes;

    public Map<String, Object> preview(String transferCode) {
        return preview(transferCode, true, true);
    }

    /**
     * 载具成员一致性校验只读取实时候选，不为每个成员签发不会被使用的离线凭证。
     */
    public Map<String, Object> previewCarrierMember(String transferCode) {
        return preview(transferCode, false, false);
    }

    /** 载具报工提交前使用当前报工人权限重算候选，但不签发逐件离线凭证。 */
    public Map<String, Object> previewCarrierReportMember(String transferCode) {
        return preview(transferCode, false, true);
    }

    private Map<String, Object> preview(String transferCode, boolean issueOfflinePermit, boolean requireAssigned) {
        String identifier = StrUtil.blankToDefault(transferCode, "").trim();
        if (identifier.isEmpty()) {
            throw new CheckedException("请扫描条码或流转卡");
        }
        MomUser user = SecurityUtils.getUser();
        if (user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("无法识别当前操作人员");
        }
        String factory = factoryAccess.require("read");
        identifierBindings.assertOrdinaryScanAllowed(identifier);
        List<String> canonicalCandidates = identifierMappingService.findCanonicalCandidates(identifier);
        List<MesTravelerEntity> travelers = travelerMapper.selectList(new LambdaQueryWrapper<MesTravelerEntity>()
                .eq(MesTravelerEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTravelerEntity::getFactoryCode, factory)
                .in(MesTravelerEntity::getTravelerCode, canonicalCandidates));
        MesTravelerEntity traveler = travelers.stream().findFirst().orElse(null);
        List<MesTaskReportEntity> reports = reportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .and(item -> item.in(MesTaskReportEntity::getTransferCode, canonicalCandidates)
                        .or().eq(MesTaskReportEntity::getSubmittedIdentifier, identifier))
                .orderByAsc(MesTaskReportEntity::getReportTime));

        // 冲销/零有效量历史仍用于找原工单，但是否已过站只由公共有效证据口径判断。
        Set<String> taskCodes = reports.stream().map(MesTaskReportEntity::getTaskCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        travelers.stream().map(MesTravelerEntity::getTaskCode)
                .filter(StrUtil::isNotBlank).forEach(taskCodes::add);

        boolean firstScan = taskCodes.isEmpty();
        List<MesTaskArrangeEntity> arrangements;
        if (firstScan) {
            arrangements = arrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                            .eq(MesTaskArrangeEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name())
                            .orderByAsc(MesTaskArrangeEntity::getRouteOrder))
                    .stream().filter(item -> !requireAssigned || split(item.getWorkerCode()).contains(user.getUsername()))
                    .filter(this::isScannableMode).toList();
        } else {
            arrangements = arrangeService.list(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                    .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                    .in(MesTaskArrangeEntity::getTaskCode, taskCodes)
                    .orderByAsc(MesTaskArrangeEntity::getRouteOrder)
                    .orderByAsc(MesTaskArrangeEntity::getDispatcherTime));
        }

        List<String> candidateTaskCodes = arrangements.stream().map(MesTaskArrangeEntity::getTaskCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        Map<String, MesFrozenExecutionGraph> graphs = Map.of();
        Map<String, String> graphFailures = Map.of();
        String graphReadBlockReason = null;
        try {
            // 首次扫码也先获取本人真实派工所属的完整工单图，不把派工主工序子集当冻结图。
            var graphBatch = executionNodes.readCandidates(TenantContextHolder.getTenantId(), factory, candidateTaskCodes);
            graphs = graphBatch.graphs();
            graphFailures = graphBatch.failures();
        } catch (IllegalArgumentException | CheckedException exception) {
            // 查询范围本身不合法时整体拒绝；单个工单坏图由独立失败目录隔离。
            graphReadBlockReason = "冻结执行图校验失败：" + exception.getMessage();
        }
        Set<String> orderCodes = new LinkedHashSet<>(taskCodes);
        orderCodes.addAll(candidateTaskCodes);
        List<MesTaskEntity> orders = orderCodes.isEmpty() ? List.of()
                : taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, factory)
                .in(MesTaskEntity::getCode, orderCodes));
        Map<String, MesTaskEntity> orderByCode = orders.stream().collect(Collectors.toMap(
                MesTaskEntity::getCode, item -> item, (left, right) -> left, LinkedHashMap::new));

        List<Map<String, Object>> processList = new ArrayList<>();
        for (MesTaskArrangeEntity arrange : arrangements.stream().sorted(Comparator.comparing(item ->
                Objects.requireNonNullElse(item.getRouteOrder(), Integer.MAX_VALUE))).toList()) {
            MesProduceTaskEntity current = null;
            List<MesProduceTaskEntity> predecessors = List.of();
            String nodeBlockReason = graphReadBlockReason;
            if (nodeBlockReason == null && graphFailures.containsKey(arrange.getTaskCode())) {
                nodeBlockReason = "工单" + arrange.getTaskCode() + "冻结执行图校验失败："
                        + graphFailures.get(arrange.getTaskCode());
            }
            if (nodeBlockReason == null) {
                try {
                    MesFrozenExecutionGraph graph = graphs.get(arrange.getTaskCode());
                    if (graph == null) {
                        throw new CheckedException("缺少原工单完整实际执行图");
                    }
                    current = graph.current(arrange);
                    predecessors = graph.predecessors(current);
                } catch (IllegalArgumentException | CheckedException exception) {
                    nodeBlockReason = "冻结执行节点校验失败：" + exception.getMessage();
                }
            }
            // 有效图的首次候选只保留真实首节点；坏图保留明确阻断，不能默默当首站。
            if (firstScan && nodeBlockReason == null && !predecessors.isEmpty()) {
                continue;
            }
            boolean assigned = split(arrange.getWorkerCode()).contains(user.getUsername());
            var contract = executionTemplateService.contract(arrange);
            boolean manualAllowed = contract.getAllowedTriggers().contains("MANUAL")
                    && Boolean.TRUE.equals(contract.getMobileAllowed());
            MesTaskReportVO identifierPreview = new MesTaskReportVO();
            identifierPreview.setTransferCode(identifier);
            String identifierBlockReason = null;
            String routeReturnBlockReason = null;
            ReportModeEnum reportMode = null;
            MesTaskEntity task = orderByCode.get(arrange.getTaskCode());
            try {
                if (!"NONE".equals(contract.getIdentifierNodePolicy().getAction())) {
                    throw new CheckedException("动态区段须使用精确派工单/双码实例预览，旧扫码候选和离线凭证不支持");
                }
                if (task == null) {
                    throw new CheckedException("生产工单不存在，不能解析现场标识");
                }
                reportMode = ReportModeEnum.fromCodes(contract.getReportMode(), arrange.getRouteType());
                identifierMappingService.resolve(arrange, task, reportMode, identifierPreview);
            } catch (IllegalArgumentException | CheckedException exception) {
                identifierBlockReason = exception.getMessage();
            }
            boolean identifierAllowed = identifierBlockReason == null;
            String canonicalIdentifier = identifierAllowed ? identifierPreview.getTransferCode() : null;
            if (identifierAllowed) {
                try {
                    routeReturnCoordinator.previewExecution(identifierPreview, arrange, task, reportMode);
                } catch (CheckedException exception) {
                    routeReturnBlockReason = exception.getMessage();
                }
            }
            boolean routeReturn = MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(
                    identifierPreview.getExecutionKind());
            MesProduceTaskEntity actualCurrent = current;
            MesTaskReportEntity passed = identifierAllowed && nodeBlockReason == null
                    ? reports.stream().filter(report -> routeReturn
                            ? MesPredecessorPassEvidence.effective(report)
                                    && Objects.equals(report.getTenantId(), arrange.getTenantId())
                                    && Objects.equals(report.getFactoryCode(), arrange.getFactoryCode())
                                    && Objects.equals(report.getTaskCode(), arrange.getTaskCode())
                                    && Objects.equals(report.getTransferCode(), canonicalIdentifier)
                                    && Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode())
                                    && Objects.equals(report.getRouteReturnId(), identifierPreview.getRouteReturnId())
                            : MesPredecessorPassEvidence.matches(report, actualCurrent, canonicalIdentifier))
                    .reduce((left, right) -> right).orElse(null) : null;
            boolean identifierPredecessorGate = reportMode == ReportModeEnum.ONE_CODE
                    || reportMode == ReportModeEnum.PACKAGE_ONE_CODE || reportMode == ReportModeEnum.TRANSFER_CARD;
            // 数量模式仍由原数量/质量等门办理，不用单件关联替它构造前序证据。
            List<MesProduceTaskEntity> missingPredecessors = !routeReturn && identifierAllowed
                    && nodeBlockReason == null && identifierPredecessorGate
                    ? MesPredecessorPassEvidence.missing(predecessors, reports, canonicalIdentifier) : List.of();
            List<String> blockingPredecessorNodes = missingPredecessors.stream()
                    .map(this::nodeLabel).toList();
            boolean predecessorsPassed = nodeBlockReason == null && (routeReturn
                    ? routeReturnBlockReason == null : identifierAllowed && missingPredecessors.isEmpty());
            boolean producing = routeReturn
                    ? Set.of(MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.FINISH.name())
                    .contains(arrange.getStatus())
                    : MesProduceTaskStatusEnum.PRODUCT.name().equals(arrange.getStatus());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", arrange.getId());
            row.put("transferCode", identifier);
            row.put("submittedIdentifier", identifier);
            row.put("canonicalIdentifier", canonicalIdentifier);
            row.put("identifierType", identifierAllowed
                    ? identifierPreview.getIdentifierType() : null);
            row.put("identifierMappingId", identifierAllowed
                    ? identifierPreview.getIdentifierMappingId() : null);
            row.put("arrangeCode", arrange.getCode());
            row.put("produceTaskCode", arrange.getProduceTaskCode());
            row.put("taskCode", arrange.getTaskCode());
            row.put("routeCode", arrange.getRouteCode());
            row.put("routeName", arrange.getRouteName());
            row.put("sourceRouteNodeCode", arrange.getSourceRouteNodeCode());
            row.put("routeExecutionSchemaVersion", arrange.getRouteExecutionSchemaVersion());
            row.put("predecessorNodeCodes", arrange.getPredecessorNodeCodes());
            row.put("blockingPredecessorNodes", blockingPredecessorNodes);
            row.put("routeType", arrange.getRouteType());
            row.put("reportMode", contract.getReportMode());
            row.put("executionKind", identifierPreview.getExecutionKind());
            row.put("routeReturnId", identifierPreview.getRouteReturnId());
            row.put("identifierRouteVersion", identifierPreview.getIdentifierRouteVersion());
            row.put("executionContract", contract);
            row.put("workerCode", arrange.getWorkerCode());
            row.put("workerName", arrange.getWorkerName());
            row.put("matCode", arrange.getMatCode());
            row.put("matName", arrange.getMatName());
            row.put("matSpec", arrange.getMatSpec());
            row.put("noticeNum", arrange.getNoticeNum());
            row.put("completeNum", arrange.getCompleteNum());
            row.put("passNum", defaultQuantity(arrange));
            row.put("worksiteCodes", arrange.getWorksiteCodes());
            row.put("worksiteCode", arrange.getActualWorksiteCode());
            row.put("isFinish", passed != null);
            row.put("reportTime", passed == null ? null : passed.getReportTime());
            boolean canReport = passed == null && predecessorsPassed && (!requireAssigned || assigned) && producing
                    && manualAllowed && identifierAllowed && routeReturnBlockReason == null;
            row.put("canReport", canReport);
            row.put("blockReason", nodeBlockReason != null ? nodeBlockReason
                    : identifierBlockReason != null ? identifierBlockReason
                    : passed != null ? "已过站" : routeReturnBlockReason != null ? routeReturnBlockReason
                    : !predecessorsPassed ? "等待实际前序节点：" + String.join("、", blockingPredecessorNodes)
                    : requireAssigned && !assigned ? "非本人派工" : !producing ? "任务未在生产中"
                    : !manualAllowed ? "执行模板未开放移动端人工报工"
                    : identifierBlockReason);
            if (canReport && issueOfflinePermit) {
                // 在线预览时同时下发短时最小范围授权；断网后只能暂存这次已确认的候选工序。
                row.put("offlinePermit", offlineReportPermitService.issue(arrange, identifier, contract));
            }
            processList.add(row);
        }
        boolean finished = !processList.isEmpty() && processList.stream()
                .anyMatch(row -> Boolean.TRUE.equals(row.get("isFinish")) && Boolean.TRUE.equals(
                        arrangements.stream().filter(item -> Objects.equals(item.getId(), row.get("id")))
                                .map(MesTaskArrangeEntity::getLastRoute).findFirst().orElse(false)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transferCode", identifier);
        result.put("submittedIdentifier", identifier);
        result.put("canonicalCandidates", canonicalCandidates);
        result.put("traveler", traveler);
        result.put("finishFlag", finished);
        result.put("showHekeReport", false);
        result.put("orderList", orders);
        result.put("allProcessList", processList);
        result.put("hekeArrangeList", List.of());
        result.put("message", processList.isEmpty() ? "未找到当前人员可执行的首工序或该标识的历史工序" : null);
        return result;
    }

    /**
     * 返回履历查询应覆盖的规范码。除当前有效映射外，同时从历史报工的原始扫码字段
     * 反查已经退役的映射，保证映射生命周期变化后仍能查到原过站证据。
     */
    public List<String> historyTransferCodes(String submittedIdentifier) {
        String identifier = StrUtil.blankToDefault(submittedIdentifier, "").trim();
        if (identifier.isEmpty()) {
            throw new CheckedException("生产标识不能为空");
        }
        String factory = factoryAccess.require("read");
        LinkedHashSet<String> codes = new LinkedHashSet<>(
                identifierMappingService.findCanonicalCandidates(identifier));
        codes.addAll(identifierBindings.historyCanonicalIdentifiers(identifier));
        reportService.list(new LambdaQueryWrapper<MesTaskReportEntity>()
                        .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesTaskReportEntity::getFactoryCode, factory)
                        .eq(MesTaskReportEntity::getSubmittedIdentifier, identifier))
                .stream().map(MesTaskReportEntity::getTransferCode)
                .filter(StrUtil::isNotBlank).forEach(codes::add);
        return List.copyOf(codes);
    }

    private BigDecimal defaultQuantity(MesTaskArrangeEntity arrange) {
        try {
            ReportModeEnum mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
            if (mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.PACKAGE_ONE_CODE) {
                return BigDecimal.ONE;
            }
        } catch (IllegalArgumentException ignored) {
            // 旧异常配置仍返回空数量，由正式报工接口明确拒绝，不在查询阶段伪造口径。
        }
        return null;
    }

    private boolean isScannableMode(MesTaskArrangeEntity arrange) {
        try {
            ReportModeEnum mode = ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType());
            return mode == ReportModeEnum.ONE_CODE || mode == ReportModeEnum.TRANSFER_CARD
                    || mode == ReportModeEnum.PACKAGE_ONE_CODE || mode == ReportModeEnum.PACKAGE_QUANTITY;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String nodeLabel(MesProduceTaskEntity task) {
        return (task.getSourceRouteNodeCode() == null ? "旧工序" : task.getSourceRouteNodeCode())
                + "/" + task.getRouteCode() + "[" + task.getCode() + "]";
    }

    private List<String> split(String csv) {
        if (StrUtil.isBlank(csv)) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }
}
