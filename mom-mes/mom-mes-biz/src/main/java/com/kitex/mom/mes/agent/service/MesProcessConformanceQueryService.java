package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesProcessConformanceDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkCenterDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesApsOperationSnapshotDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 面向智能体的工单工艺执行一致性查询。
 *
 * <p>生产工单下达时冻结的工艺快照才是本次生产的执行合同。本服务不会把当前MDM主数据
 * 直接覆盖或冒充历史工单版本，而是核对冻结工序图与实际生成的工序任务是否一致。</p>
 */
@Service
@RequiredArgsConstructor
public class MesProcessConformanceQueryService {

    public static final String TOOL_CODE = "MES_PROCESS_CONFORMANCE_QUERY@1";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskService taskService;
    private final MesProduceTaskService produceTaskService;
    private final ObjectMapper objectMapper;

    /**
     * 按实际工厂和精确工单号返回工艺合同核对证据。
     */
    public MesProcessConformanceDTO.Evidence query(MesProcessConformanceDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())
                || !StringUtils.hasText(query.getTaskCode())) {
            throw new CheckedException("工艺执行一致性查询工厂或工单与当前范围不一致");
        }
        String taskCode = query.getTaskCode().trim();
        MesTaskEntity task = requireTask(actualFactory, taskCode);
        List<MesProduceTaskEntity> actualOperations = produceTaskService.list(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getFactoryCode, actualFactory)
                        .eq(MesProduceTaskEntity::getTaskCode, taskCode)
                        .orderByAsc(MesProduceTaskEntity::getRouteOrder)
                        .orderByAsc(MesProduceTaskEntity::getId));

        List<String> gaps = new ArrayList<>();
        RouteContract contract = readContract(task.getProcessRouteSnapshot(), gaps);
        List<MesProcessConformanceDTO.OperationConformance> operations = compare(
                task, contract, actualOperations, gaps);
        int critical = countLevel(operations, "CRITICAL");
        int warning = countLevel(operations, "WARNING");
        int consistent = countLevel(operations, "CONSISTENT");
        boolean issued = !"CREATE".equalsIgnoreCase(task.getStatus());
        Boolean contractConsistent = contract.valid() && issued
                ? critical == 0 && warning == 0 && contract.routes().size() == actualOperations.size()
                : null;
        if (!issued) {
            gaps.add("工单仍为新建状态，尚未下达生成工序任务；只能校验冻结工艺快照，不能判定执行一致性");
        }
        gaps.add("当前MES未形成统一的工艺参数实测时序账，本工具不判断温度、压力、速度等参数是否越界");
        gaps.add("当前证据只有APS计划工位和设备，没有派工接收后的完整实际工位、实际设备时序，不能证明全过程资源一致");

        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_task:" + task.getCode());
        actualOperations.forEach(operation -> refs.add("MES:mes_produce_task:" + operation.getCode()));
        return new MesProcessConformanceDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("以生产工单冻结工艺快照为执行合同，精确核对已生成工序的路线身份、快照、拓扑顺序、转序策略、计划工位和APS锁定证据；当前MDM主数据变化不自动判为本工单违规。")
                .setFactoryCode(actualFactory)
                .setObservedAt(LocalDateTime.now())
                .setWorkOrder(toWorkOrder(task))
                .setSummary(new MesProcessConformanceDTO.Summary()
                        .setExpectedOperationCount(contract.routes().size())
                        .setGeneratedOperationCount(actualOperations.size())
                        .setConsistentOperationCount(consistent)
                        .setWarningOperationCount(warning)
                        .setCriticalOperationCount(critical)
                        .setProcessContractConsistent(contractConsistent))
                .setOperations(operations)
                .setDataGaps(gaps.stream().distinct().toList())
                .setEvidenceRefs(refs)
                .setTruncated(Boolean.FALSE);
    }

    private MesTaskEntity requireTask(String factoryCode, String taskCode) {
        List<MesTaskEntity> rows = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factoryCode)
                .eq(MesTaskEntity::getCode, taskCode)
                .orderByAsc(MesTaskEntity::getId)
                .last("LIMIT 2"));
        if (rows.isEmpty()) {
            throw new CheckedException("当前工厂未找到生产工单：" + taskCode);
        }
        if (rows.size() > 1) {
            throw new CheckedException("当前工厂存在重复生产工单号，禁止生成模糊工艺结论：" + taskCode);
        }
        return rows.get(0);
    }

    private RouteContract readContract(String snapshot, List<String> gaps) {
        if (!StringUtils.hasText(snapshot)) {
            gaps.add("生产工单缺少冻结工艺快照，无法建立执行合同");
            return new RouteContract(Map.of(), false);
        }
        try {
            List<MdmProcessRouteWorkingDTO> routes = objectMapper.readValue(snapshot,
                    new TypeReference<List<MdmProcessRouteWorkingDTO>>() {
                    });
            return normalize(routes);
        } catch (Exception exception) {
            gaps.add("生产工单冻结工艺快照无法解析或拓扑无效：" + safeMessage(exception));
            return new RouteContract(Map.of(), false);
        }
    }

    /**
     * 与工单下达逻辑使用相同规则，把兼容旧数据的顺序路线规范化为唯一DAG合同。
     */
    private RouteContract normalize(List<MdmProcessRouteWorkingDTO> routeList) {
        if (routeList == null || routeList.isEmpty()) {
            throw new CheckedException("冻结工艺快照没有工序");
        }
        Map<String, MdmProcessRouteWorkingDTO> routes = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingDTO route : routeList) {
            String code = firstText(route.getProcessCode(), route.getRouteCode());
            if (!StringUtils.hasText(code) || routes.putIfAbsent(code, route) != null) {
                throw new CheckedException("冻结工艺存在空编码或重复工序：" + code);
            }
        }
        Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
        routes.keySet().forEach(code -> {
            predecessors.put(code, new LinkedHashSet<>());
            successors.put(code, new LinkedHashSet<>());
        });
        boolean explicit = routeList.stream().anyMatch(route ->
                route.getPreRouteCode() != null && route.getPreRouteCode().length > 0
                        || route.getNextRouteCode() != null && route.getNextRouteCode().length > 0);
        List<MdmProcessRouteWorkingDTO> ordered = routeList.stream().sorted(Comparator
                .comparingInt((MdmProcessRouteWorkingDTO route) -> expectedOrderOr(route, Integer.MAX_VALUE))
                .thenComparing(route -> firstText(route.getProcessCode(), route.getRouteCode()))).toList();
        if (!explicit && ordered.size() > 1) {
            for (int index = 1; index < ordered.size(); index++) {
                ordered.get(index).setPreRouteCode(new String[]{firstText(
                        ordered.get(index - 1).getProcessCode(), ordered.get(index - 1).getRouteCode())});
            }
        }
        routes.forEach((code, route) -> split(route.getPreRouteCode()).forEach(predecessor -> {
            if (!routes.containsKey(predecessor) || code.equals(predecessor)) {
                throw new CheckedException("工序[" + code + "]引用无效前置工序[" + predecessor + "]");
            }
            predecessors.get(code).add(predecessor);
            successors.get(predecessor).add(code);
        }));
        if (successors.values().stream().filter(Set::isEmpty).count() != 1) {
            throw new CheckedException("冻结工艺必须且只能有一道最终工序");
        }
        Map<String, Integer> indegrees = new LinkedHashMap<>();
        predecessors.forEach((code, values) -> indegrees.put(code, values.size()));
        Map<String, Integer> levels = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        indegrees.forEach((code, degree) -> {
            if (degree == 0) {
                ready.add(code);
                levels.put(code, 1);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String code = ready.removeFirst();
            visited++;
            for (String successor : successors.get(code)) {
                levels.merge(successor, levels.get(code) + 1, Math::max);
                int remaining = indegrees.computeIfPresent(successor, (key, value) -> value - 1);
                if (remaining == 0) ready.addLast(successor);
            }
        }
        if (visited != routes.size()) {
            throw new CheckedException("冻结工艺拓扑存在环路");
        }
        routes.forEach((code, route) -> {
            route.setPreRouteCode(predecessors.get(code).toArray(String[]::new));
            route.setNextRouteCode(successors.get(code).toArray(String[]::new));
            route.setOrderNum(levels.get(code));
            route.setRouteOrder(levels.get(code));
        });
        return new RouteContract(routes, true);
    }

    private List<MesProcessConformanceDTO.OperationConformance> compare(
            MesTaskEntity task, RouteContract contract, List<MesProduceTaskEntity> actualOperations,
            List<String> gaps) {
        if (!contract.valid()) {
            return actualOperations.stream().map(this::unknownWithoutContract).toList();
        }
        Map<String, MesProduceTaskEntity> actualByCode = new LinkedHashMap<>();
        Set<String> duplicateActualCodes = new LinkedHashSet<>();
        for (MesProduceTaskEntity operation : actualOperations) {
            if (actualByCode.putIfAbsent(operation.getRouteCode(), operation) != null) {
                duplicateActualCodes.add(operation.getRouteCode());
            }
        }
        if (!duplicateActualCodes.isEmpty()) {
            gaps.add("已生成工序任务存在重复工序编码：" + String.join(",", duplicateActualCodes));
        }
        List<MesProcessConformanceDTO.OperationConformance> results = new ArrayList<>();
        contract.routes().forEach((code, expected) -> results.add(compareExpected(
                task, code, expected, actualByCode.remove(code), duplicateActualCodes.contains(code))));
        actualByCode.values().forEach(actual -> results.add(unexpected(actual)));
        return results;
    }

    private MesProcessConformanceDTO.OperationConformance compareExpected(
            MesTaskEntity task, String code, MdmProcessRouteWorkingDTO expected,
            MesProduceTaskEntity actual, boolean duplicateActual) {
        MesProcessConformanceDTO.OperationConformance result = new MesProcessConformanceDTO.OperationConformance()
                .setProcessCode(code)
                .setProcessName(firstText(expected.getProcessName(), expected.getRouteName()))
                .setExpectedOrder(expectedOrder(expected));
        if (actual == null) {
            if ("CREATE".equalsIgnoreCase(task.getStatus())) {
                return result.setConformanceLevel("UNKNOWN")
                        .setIssues(List.of("工单尚未下达，未生成该工序任务"));
            }
            return result.setConformanceLevel("CRITICAL")
                    .setIssues(List.of("冻结工艺要求该工序，但MES未生成对应工序任务"));
        }
        fillActual(result, actual);
        List<String> issues = new ArrayList<>();
        boolean identity = Objects.equals(task.getProcessRouteId(), actual.getProcessRouteId())
                && Objects.equals(task.getProcessRouteCode(), actual.getProcessRouteCode());
        boolean snapshot = Objects.equals(task.getProcessRouteSnapshot(), actual.getProcessRouteSnapshot());
        boolean graph = Objects.equals(expectedOrder(expected), actual.getRouteOrder())
                && split(expected.getPreRouteCode()).equals(split(actual.getPredecessorCodes()))
                && split(expected.getNextRouteCode()).equals(split(actual.getSuccessorCodes()));
        boolean transfer = Boolean.TRUE.equals(expected.getOverlapEnabled())
                == Boolean.TRUE.equals(actual.getOverlapEnabled())
                && sameDecimal(Boolean.TRUE.equals(expected.getOverlapEnabled())
                ? expected.getTransferBatchQuantity() : null, actual.getTransferBatchQuantity())
                && Objects.equals(expectedTransferMinutes(expected), zero(actual.getTransferMinutes()));
        Boolean worksiteAllowed = plannedWorksiteAllowed(expected, actual.getPlannedWorksiteCode());
        Boolean apsLockValid = apsLockValid(actual, issues);
        if (!identity) issues.add("工序任务引用的工艺路线身份与生产工单不一致");
        if (!snapshot) issues.add("工序任务保存的冻结工艺快照与生产工单不一致");
        if (!graph) issues.add("工序顺序或前后置拓扑与冻结工艺不一致");
        if (!transfer) issues.add("重叠生产、转移批量或转序等待策略与冻结工艺不一致");
        if (Boolean.FALSE.equals(worksiteAllowed)) issues.add("APS计划工位不在冻结工序允许工位范围内");
        if (duplicateActual) issues.add("同一工序编码生成了多个工序任务，无法建立唯一执行关系");
        boolean critical = !identity || !snapshot || !graph || duplicateActual;
        boolean warning = !transfer || Boolean.FALSE.equals(worksiteAllowed) || Boolean.FALSE.equals(apsLockValid);
        return result.setRouteIdentityMatched(identity)
                .setProcessSnapshotMatched(snapshot)
                .setGraphMatched(graph)
                .setTransferPolicyMatched(transfer)
                .setPlannedWorksiteAllowed(worksiteAllowed)
                .setApsLockValid(apsLockValid)
                .setConformanceLevel(critical ? "CRITICAL" : warning ? "WARNING" : "CONSISTENT")
                .setIssues(issues);
    }

    private MesProcessConformanceDTO.OperationConformance unexpected(MesProduceTaskEntity actual) {
        MesProcessConformanceDTO.OperationConformance result = new MesProcessConformanceDTO.OperationConformance();
        fillActual(result, actual);
        return result.setProcessCode(actual.getRouteCode()).setProcessName(actual.getRouteName())
                .setConformanceLevel("CRITICAL")
                .setIssues(List.of("MES存在冻结工艺快照未声明的额外工序任务"));
    }

    private MesProcessConformanceDTO.OperationConformance unknownWithoutContract(MesProduceTaskEntity actual) {
        MesProcessConformanceDTO.OperationConformance result = new MesProcessConformanceDTO.OperationConformance();
        fillActual(result, actual);
        return result.setProcessCode(actual.getRouteCode()).setProcessName(actual.getRouteName())
                .setConformanceLevel("UNKNOWN")
                .setIssues(List.of("冻结工艺合同不可解析，无法核对该工序任务"));
    }

    private void fillActual(MesProcessConformanceDTO.OperationConformance result,
                            MesProduceTaskEntity actual) {
        result.setOperationId(actual.getId())
                .setOperationTaskCode(actual.getCode())
                .setOperationStatus(actual.getStatus())
                .setActualOrder(actual.getRouteOrder())
                .setPlannedWorksiteCode(actual.getPlannedWorksiteCode())
                .setPlannedDeviceCode(actual.getPlannedDeviceCode());
    }

    private Boolean plannedWorksiteAllowed(MdmProcessRouteWorkingDTO expected, String plannedWorksite) {
        if (!StringUtils.hasText(plannedWorksite)) return null;
        List<MdmProcessRouteWorkCenterDTO> works = expected.getMdmProcessProcedureWorkList();
        if (works == null || works.isEmpty()) return null;
        return works.stream().map(MdmProcessRouteWorkCenterDTO::getWorkCode)
                .filter(StringUtils::hasText).anyMatch(plannedWorksite::equals);
    }

    private Boolean apsLockValid(MesProduceTaskEntity actual, List<String> issues) {
        if (!StringUtils.hasText(actual.getApsOperationSnapshot())) return null;
        try {
            MesApsOperationSnapshotDTO aps = objectMapper.readValue(
                    actual.getApsOperationSnapshot(), MesApsOperationSnapshotDTO.class);
            boolean stationResourceMatched = !"STATION".equalsIgnoreCase(aps.getResourceType())
                    || Objects.equals(actual.getPlannedWorksiteCode(), aps.getResourceCode());
            boolean valid = Boolean.TRUE.equals(aps.getLockStatus())
                    && !Boolean.TRUE.equals(aps.getHardBlocked())
                    && Objects.equals(actual.getRouteCode(), aps.getProcessCode())
                    && Objects.equals(actual.getRouteOrder(), aps.getOperationSeq())
                    && split(actual.getPredecessorCodes()).equals(split(aps.getPredecessorCodes()))
                    && Boolean.TRUE.equals(actual.getOverlapEnabled()) == Boolean.TRUE.equals(aps.getOverlapEnabled())
                    && sameDecimal(actual.getTransferBatchQuantity(), aps.getTransferBatchQuantity())
                    && Objects.equals(actual.getPlannedStartTime(), aps.getScheduledStartTime())
                    && Objects.equals(actual.getPlannedEndTime(), aps.getScheduledEndTime())
                    && Objects.equals(actual.getPlannedDeviceCode(), aps.getDeviceCode())
                    && Objects.equals(actual.getPlannedWorkerCode(), aps.getEmployeeCode())
                    && split(actual.getPlannedToolingCodes()).equals(split(aps.getToolingCodes()))
                    && stationResourceMatched;
            if (!valid) issues.add("工序任务的APS锁定快照无效或与计划资源字段不一致");
            return valid;
        } catch (Exception exception) {
            issues.add("工序任务的APS锁定快照无法解析");
            return Boolean.FALSE;
        }
    }

    private MesProcessConformanceDTO.WorkOrder toWorkOrder(MesTaskEntity task) {
        return new MesProcessConformanceDTO.WorkOrder()
                .setId(task.getId()).setTaskCode(task.getCode()).setStatus(task.getStatus())
                .setMaterialCode(task.getMatCode()).setMaterialName(task.getMatName())
                .setProcessRouteId(task.getProcessRouteId())
                .setProcessRouteCode(task.getProcessRouteCode())
                .setProcessSnapshotFingerprint(fingerprint(task.getProcessRouteSnapshot()));
    }

    private int expectedOrderOr(MdmProcessRouteWorkingDTO route, int fallback) {
        return route.getOrderNum() != null ? route.getOrderNum()
                : route.getRouteOrder() != null ? route.getRouteOrder() : fallback;
    }

    private Integer expectedOrder(MdmProcessRouteWorkingDTO route) {
        return route.getOrderNum() != null ? route.getOrderNum() : route.getRouteOrder();
    }

    private long expectedTransferMinutes(MdmProcessRouteWorkingDTO expected) {
        return Boolean.TRUE.equals(expected.getTimeCheck()) && expected.getTimeVal() != null
                ? expected.getTimeVal().max(BigDecimal.ZERO).longValue() : 0L;
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private LinkedHashSet<String> split(String[] values) {
        if (values == null) return new LinkedHashSet<>();
        return Arrays.stream(values).filter(StringUtils::hasText).map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private LinkedHashSet<String> split(String values) {
        return !StringUtils.hasText(values) ? new LinkedHashSet<>()
                : Arrays.stream(values.split(",")).filter(StringUtils::hasText).map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private int countLevel(List<MesProcessConformanceDTO.OperationConformance> rows, String level) {
        return (int) rows.stream().filter(row -> level.equals(row.getConformanceLevel())).count();
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim()
                : StringUtils.hasText(fallback) ? fallback.trim() : null;
    }

    private String fingerprint(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return "SHA-256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("工艺快照指纹计算失败", exception);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? message : exception.getClass().getSimpleName();
    }

    private record RouteContract(Map<String, MdmProcessRouteWorkingDTO> routes, boolean valid) {
    }
}
