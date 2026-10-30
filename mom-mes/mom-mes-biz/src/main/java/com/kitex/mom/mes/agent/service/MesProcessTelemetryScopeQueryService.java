package com.kitex.mom.mes.agent.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mes.agent.dto.MesProcessTelemetryScopeDTO;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 为 IoT 工艺参数取证冻结工单的实际设备运行范围。
 *
 * <p>只接受 MES 派工开始、暂停、复工和结束形成的连续生产段。计划设备、设备当前状态
 * 或报工时间都不能替代该范围，避免把非生产时段的设备数据算入工单。</p>
 */
@Service
@RequiredArgsConstructor
public class MesProcessTelemetryScopeQueryService {

    public static final String TOOL_CODE = "MES_PROCESS_TELEMETRY_SCOPE@1";
    private static final int MAX_SEGMENTS = 200;

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskService taskService;
    private final MesTaskExecutionSegmentMapper segmentMapper;
    private final MesProduceTaskMapper produceTaskMapper;

    /**
     * 按当前租户、实际工厂和精确工单返回可取证的连续生产区间。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesProcessTelemetryScopeDTO.Evidence query(MesProcessTelemetryScopeDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())
                || !StringUtils.hasText(query.getTaskCode())) {
            throw new CheckedException("IoT取证范围的工厂或工单与当前范围不一致");
        }
        if (query.getAcceptedSnapshotSchemaVersion() != null
                && !MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1.equals(query.getAcceptedSnapshotSchemaVersion())
                && !MdmProcessParameterStandardDTO.ROUTE_NODE_V2.equals(query.getAcceptedSnapshotSchemaVersion())) {
            throw new CheckedException("IoT取证消费者声明了不支持的参数冻结协议");
        }
        String taskCode = query.getTaskCode().trim();
        MesTaskEntity task = requireTask(actualFactory, taskCode);
        FrozenStandard frozenStandard = frozenStandard(task);
        if (frozenStandard.nodeProtocol()
                && !MdmProcessParameterStandardDTO.ROUTE_NODE_V2.equals(query.getAcceptedSnapshotSchemaVersion())) {
            throw new CheckedException("该工单使用ROUTE_NODE_V2节点冻结协议，请升级IoT取证消费者并显式声明节点协议能力");
        }
        LocalDateTime observedAt = LocalDateTime.now();
        List<MesTaskExecutionSegmentEntity> rows = segmentMapper.selectList(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .eq(MesTaskExecutionSegmentEntity::getFactoryCode, actualFactory)
                        .eq(MesTaskExecutionSegmentEntity::getTaskCode, taskCode)
                        .eq(MesTaskExecutionSegmentEntity::getSegmentType, "RUN")
                        .orderByAsc(MesTaskExecutionSegmentEntity::getStartTime)
                        .orderByAsc(MesTaskExecutionSegmentEntity::getId)
                        .last("LIMIT " + (MAX_SEGMENTS + 1)));
        boolean truncated = rows.size() > MAX_SEGMENTS;
        List<MesTaskExecutionSegmentEntity> inspected = truncated
                ? rows.subList(0, MAX_SEGMENTS) : rows;
        // 同一只读事务内读取真实工序任务。历史V1不补查当前MDM，也不猜测节点。
        Map<String, List<MesProduceTaskEntity>> operations = frozenStandard.nodeProtocol()
                ? loadOperations(task, inspected) : Map.of();
        List<String> gaps = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_task:" + taskCode);
        if (frozenStandard.catalog() != null) {
            if (Boolean.TRUE.equals(frozenStandard.catalog().getAvailable())) {
                refs.add("MDM:mdm_process_parameter_standard:"
                        + frozenStandard.catalog().getStandardId() + "@"
                        + frozenStandard.catalog().getContentFingerprint());
            }
            refs.add("MES:mes_task:" + taskCode + ":process_parameter_snapshot@"
                    + task.getProcessParameterSnapshotFingerprint());
            if (frozenStandard.catalog().getDataGaps() != null) {
                gaps.addAll(frozenStandard.catalog().getDataGaps());
            }
        } else {
            gaps.add("该工单在参数标准冻结能力上线前已下达，不能套用当前MDM标准形成历史合规结论");
        }
        List<MesProcessTelemetryScopeDTO.ExecutionWindow> windows = new ArrayList<>();
        for (MesTaskExecutionSegmentEntity row : inspected) {
            if ("CANCELLED".equalsIgnoreCase(row.getStatus())) {
                continue;
            }
            if (!"RUN".equalsIgnoreCase(row.getSegmentType())) {
                gaps.add("连续生产段" + row.getId() + "不是RUN类型，未纳入设备参数取证");
                continue;
            }
            if (!Boolean.TRUE.equals(row.getSampleEligible())) {
                gaps.add("连续生产段" + row.getId() + "被标记为不可校准样本："
                        + textOr(row.getExclusionReason(), "原因未记录"));
                continue;
            }
            if (!StringUtils.hasText(row.getDeviceCode())) {
                gaps.add("连续生产段" + row.getId() + "未绑定实际设备，无法关联IoT采样");
                continue;
            }
            LocalDateTime endTime = "OPEN".equalsIgnoreCase(row.getStatus())
                    ? observedAt : row.getEndTime();
            if (row.getStartTime() == null || endTime == null
                    || !endTime.isAfter(row.getStartTime())) {
                gaps.add("连续生产段" + row.getId() + "起止时间无效，未纳入设备参数取证");
                continue;
            }
            windows.add(new MesProcessTelemetryScopeDTO.ExecutionWindow()
                    .setExecutionSegmentId(row.getId())
                    .setArrangeCode(row.getArrangeCode())
                    .setOperationTaskCode(row.getProduceTaskCode())
                    .setProcessCode(row.getProcessCode())
                    .setRouteNodeCode(frozenStandard.nodeProtocol()
                            ? verifiedNode(task, row, frozenStandard, operations) : null)
                    .setStationCode(row.getStationCode())
                    .setDeviceCode(row.getDeviceCode().trim())
                    .setStartTime(row.getStartTime())
                    .setEndTime(endTime)
                    .setSourceStatus(row.getStatus()));
            refs.add("MES:mes_task_execution_segment:" + row.getId());
        }
        if (rows.isEmpty()) {
            gaps.add("工单没有派工连续生产段，尚不能建立设备参数取证时间窗");
        } else if (windows.isEmpty()) {
            gaps.add("工单现有连续生产段均不满足实际设备参数取证条件");
        }
        if (truncated) {
            gaps.add("连续生产段超过" + MAX_SEGMENTS + "条，本次仅返回前"
                    + MAX_SEGMENTS + "条，结论不得视为完整");
        }
        return new MesProcessTelemetryScopeDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("以MES派工开始、暂停、复工和结束形成的RUN连续生产段为唯一取证范围；OPEN段截止本次查询时刻，计划设备和报工时间不代替实际运行区间。")
                .setFactoryCode(actualFactory)
                .setTaskCode(taskCode)
                .setObservedAt(observedAt)
                .setSourceSegmentCount(inspected.size())
                .setProcessParameterStandardNo(task.getProcessParameterStandardNo())
                .setProcessParameterStandardVersion(task.getProcessParameterStandardVersion())
                .setProcessParameterSnapshotFingerprint(task.getProcessParameterSnapshotFingerprint())
                .setSnapshotSchemaVersion(query.getAcceptedSnapshotSchemaVersion() == null ? null
                        : frozenStandard.nodeProtocol() ? MdmProcessParameterStandardDTO.ROUTE_NODE_V2
                        : MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1)
                .setContentSchemaVersion(query.getAcceptedSnapshotSchemaVersion() == null || frozenStandard.catalog() == null ? null
                        : frozenStandard.catalog().getContentSchemaVersion())
                .setWindows(windows)
                .setFrozenParameterStandards(frozenStandard.parameters())
                .setDataGaps(gaps.stream().distinct().toList())
                .setEvidenceRefs(refs.stream().distinct().toList())
                .setTruncated(truncated);
    }

    private MesTaskEntity requireTask(String factoryCode, String taskCode) {
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factoryCode)
                .eq(MesTaskEntity::getCode, taskCode)
                .orderByAsc(MesTaskEntity::getId)
                .last("LIMIT 2"));
        if (tasks.isEmpty()) {
            throw new CheckedException("当前工厂未找到生产工单：" + taskCode);
        }
        if (tasks.size() > 1) {
            throw new CheckedException("当前工厂存在重复生产工单号，禁止生成模糊IoT取证范围：" + taskCode);
        }
        return tasks.get(0);
    }

    private FrozenStandard frozenStandard(MesTaskEntity task) {
        if (!StringUtils.hasText(task.getProcessParameterStandardSnapshot())
                || !StringUtils.hasText(task.getProcessParameterSnapshotFingerprint())) {
            if (StringUtils.hasText(task.getProcessParameterStandardSnapshot())
                    || StringUtils.hasText(task.getProcessParameterSnapshotFingerprint())) {
                throw new CheckedException("生产工单参数冻结原文与保存指纹不完整，禁止形成参数合规结论");
            }
            return new FrozenStandard(null, List.of(), false, Map.of());
        }
        String actualFingerprint = sha256(task.getProcessParameterStandardSnapshot());
        if (!Objects.equals(actualFingerprint, task.getProcessParameterSnapshotFingerprint())) {
            throw new CheckedException("生产工单冻结工艺参数标准指纹不一致，禁止形成参数合规结论");
        }
        MdmProcessParameterStandardDTO.FrozenCatalog catalog;
        try {
            catalog = JSONUtil.toBean(task.getProcessParameterStandardSnapshot(),
                    MdmProcessParameterStandardDTO.FrozenCatalog.class);
        } catch (RuntimeException exception) {
            throw new CheckedException("生产工单冻结工艺参数标准JSON损坏，禁止形成参数合规结论");
        }
        if (catalog == null || !Objects.equals(catalog.getProcessRouteId(), task.getProcessRouteId())
                || !Objects.equals(catalog.getRouteCode(), task.getProcessRouteCode())
                || !Objects.equals(catalog.getStandardNo(), task.getProcessParameterStandardNo())
                || !Objects.equals(catalog.getVersionNo(), task.getProcessParameterStandardVersion())) {
            throw new CheckedException("生产工单冻结工艺参数标准身份与工单不一致，禁止形成参数合规结论");
        }
        boolean nodeProtocol = nodeProtocol(catalog);
        Map<String, String> nodeProcesses = nodeProtocol ? frozenNodes(task) : Map.of();
        validateParameters(catalog, nodeProtocol, nodeProcesses);
        List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> parameters =
                catalog.getParameters() == null ? List.of() : catalog.getParameters().stream()
                        .map(this::mapStandard).toList();
        return new FrozenStandard(catalog, parameters, nodeProtocol, nodeProcesses);
    }

    private MesProcessTelemetryScopeDTO.FrozenParameterStandard mapStandard(
            MdmProcessParameterStandardDTO.FrozenParameter source) {
        return new MesProcessTelemetryScopeDTO.FrozenParameterStandard()
                .setSourceItemId(source.getSourceItemId())
                .setProcessCode(source.getProcessCode())
                .setRouteNodeCode(source.getRouteNodeCode())
                .setProcessName(source.getProcessName())
                .setIdentifier(source.getIdentifier())
                .setParameterName(source.getParameterName())
                .setDataType(source.getDataType())
                .setUnit(source.getUnit())
                .setTargetValue(source.getTargetValue())
                .setLowerLimit(source.getLowerLimit())
                .setUpperLimit(source.getUpperLimit())
                .setWarningLowerLimit(source.getWarningLowerLimit())
                .setWarningUpperLimit(source.getWarningUpperLimit())
                .setRequired(source.getRequired())
                .setAggregationMode(source.getAggregationMode())
                .setMinimumSampleCount(source.getMinimumSampleCount())
                .setMaximumNonGoodRatePct(source.getMaximumNonGoodRatePct())
                .setContinuousDeviationSeconds(source.getContinuousDeviationSeconds());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法校验MES工艺参数冻结指纹", exception);
        }
    }

    private String textOr(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    /**
     * 节点协议严格分支；V1只能解释历史按工序冻结的参数，不能混入节点内容。
     */
    private boolean nodeProtocol(MdmProcessParameterStandardDTO.FrozenCatalog catalog) {
        String schema = catalog.getSnapshotSchemaVersion();
        boolean node = MdmProcessParameterStandardDTO.ROUTE_NODE_V2.equals(schema);
        if (!node && schema != null && !MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1.equals(schema)) {
            throw new CheckedException("不支持的MES参数冻结协议：" + schema);
        }
        String content = catalog.getContentSchemaVersion();
        if (content != null && !MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1.equals(content)
                && !(node && MdmProcessParameterStandardDTO.NODE_V2.equals(content))) {
            throw new CheckedException("MES参数冻结的内容协议与节点协议不一致");
        }
        if (node && Boolean.TRUE.equals(catalog.getAvailable()) && content == null) {
            throw new CheckedException("节点冻结缺少来源内容协议，禁止套用历史解释");
        }
        return node;
    }

    /**
     * 仅从工单原路线读取节点，不查询现行MDM或依据主工序补造身份。
     */
    private Map<String, String> frozenNodes(MesTaskEntity task) {
        Map<String, String> result = new LinkedHashMap<>();
        List<MdmProcessRouteWorkingDTO> routes;
        try {
            routes = JSONUtil.toList(task.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
        } catch (RuntimeException invalid) {
            throw new CheckedException("节点冻结的原路线JSON损坏");
        }
        if (routes == null || routes.isEmpty() || routes.size() > 500) {
            throw new CheckedException("节点冻结缺少有界原路线");
        }
        for (MdmProcessRouteWorkingDTO route : routes) {
            String node = route == null ? null : route.getRouteNodeCode();
            String process = route == null ? null : StringUtils.hasText(route.getProcessCode())
                    ? route.getProcessCode() : route.getRouteCode();
            if (node == null || !node.matches("RN-[1-9][0-9]{0,18}") || !StringUtils.hasText(process)
                    || result.putIfAbsent(node, process) != null
                    || (StringUtils.hasText(route.getFactoryCode())
                    && !Objects.equals(task.getFactoryCode(), route.getFactoryCode()))) {
                throw new CheckedException("工单原路线节点缺失、重复或工厂不一致，禁止按当前MDM补齐");
            }
        }
        return result;
    }

    private void validateParameters(MdmProcessParameterStandardDTO.FrozenCatalog catalog,
                                    boolean nodeProtocol, Map<String, String> nodes) {
        if (nodeProtocol ? !nodes.equals(catalog.getRouteNodeProcesses())
                : catalog.getRouteNodeProcesses() != null && !catalog.getRouteNodeProcesses().isEmpty()) {
            throw new CheckedException("参数冻结的真实节点映射与原路线或协议不一致");
        }
        if (catalog.getAvailable() == null || catalog.getParameters() == null
                || (!catalog.getAvailable() && !catalog.getParameters().isEmpty())) {
            throw new CheckedException("原参数冻结可用状态与正文不一致");
        }
        Set<String> keys = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (MdmProcessParameterStandardDTO.FrozenParameter parameter : catalog.getParameters()) {
            if (parameter == null || !StringUtils.hasText(parameter.getProcessCode())
                    || !StringUtils.hasText(parameter.getIdentifier()) || parameter.getSourceItemId() == null
                    || !ids.add(parameter.getSourceItemId())) {
                throw new CheckedException("原参数冻结身份缺失或重复");
            }
            String identity = nodeProtocol ? parameter.getRouteNodeCode() : parameter.getProcessCode();
            if ((nodeProtocol && !Objects.equals(nodes.get(identity), parameter.getProcessCode()))
                    || (!nodeProtocol && parameter.getRouteNodeCode() != null)
                    || !keys.add(identity + "\u0000" + parameter.getIdentifier())) {
                throw new CheckedException("原参数冻结节点与主工序错配、协议混用或参数重复");
            }
        }
    }

    private Map<String, List<MesProduceTaskEntity>> loadOperations(MesTaskEntity task,
                                                                   List<MesTaskExecutionSegmentEntity> segments) {
        Set<String> codes = segments.stream().map(MesTaskExecutionSegmentEntity::getProduceTaskCode)
                .filter(StringUtils::hasText).collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Map.of();
        }
        return produceTaskMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getFactoryCode, task.getFactoryCode())
                        .eq(MesProduceTaskEntity::getTaskCode, task.getCode())
                        .in(MesProduceTaskEntity::getCode, codes))
                .stream().collect(Collectors.groupingBy(MesProduceTaskEntity::getCode));
    }

    /**
     * 实际段→唯一工序任务→原冻结节点三者必须一致，任务参数原字节SHA独立校验。
     */
    private String verifiedNode(MesTaskEntity task, MesTaskExecutionSegmentEntity segment,
                                FrozenStandard standard, Map<String, List<MesProduceTaskEntity>> operations) {
        List<MesProduceTaskEntity> matches = operations.getOrDefault(segment.getProduceTaskCode(), List.of());
        if (matches.size() != 1) {
            throw new CheckedException("连续生产段" + segment.getId() + "缺少唯一原工序任务，不能推断路线节点");
        }
        MesProduceTaskEntity operation = matches.get(0);
        String node = operation.getSourceRouteNodeCode();
        if (!StringUtils.hasText(node) || !Objects.equals(standard.nodeProcesses().get(node), operation.getRouteCode())
                || !Objects.equals(operation.getRouteCode(), segment.getProcessCode())
                || !Objects.equals(operation.getProcessRouteId(), task.getProcessRouteId())
                || !Objects.equals(operation.getProcessRouteCode(), task.getProcessRouteCode())
                || !Objects.equals(operation.getProcessRouteSnapshot(), task.getProcessRouteSnapshot())) {
            throw new CheckedException("连续生产段" + segment.getId() + "的实际工序任务与原路线节点不一致");
        }
        String raw = operation.getProcessParameterStandardSnapshot();
        if (!StringUtils.hasText(raw) || !Objects.equals(sha256(raw), operation.getProcessParameterSnapshotFingerprint())) {
            throw new CheckedException("工序任务" + operation.getCode() + "的原参数冻结指纹不一致");
        }
        MdmProcessParameterStandardDTO.FrozenCatalog catalog;
        try {
            catalog = JSONUtil.toBean(raw, MdmProcessParameterStandardDTO.FrozenCatalog.class);
        } catch (RuntimeException invalid) {
            throw new CheckedException("工序任务原节点参数冻结JSON损坏");
        }
        MdmProcessParameterStandardDTO.FrozenCatalog source = standard.catalog();
        if (catalog == null || !nodeProtocol(catalog)
                || !Objects.equals(catalog.getProcessRouteId(), source.getProcessRouteId())
                || !Objects.equals(catalog.getRouteCode(), source.getRouteCode())
                || !Objects.equals(catalog.getAvailable(), source.getAvailable())
                || !Objects.equals(catalog.getStandardId(), source.getStandardId())
                || !Objects.equals(catalog.getStandardNo(), source.getStandardNo())
                || !Objects.equals(catalog.getVersionNo(), source.getVersionNo())
                || !Objects.equals(catalog.getContentSchemaVersion(), source.getContentSchemaVersion())
                || !Objects.equals(catalog.getContentFingerprint(), source.getContentFingerprint())
                || !Map.of(node, operation.getRouteCode()).equals(catalog.getRouteNodeProcesses())
                || !Objects.equals(operation.getProcessParameterStandardNo(), source.getStandardNo())
                || !Objects.equals(operation.getProcessParameterStandardVersion(), source.getVersionNo())
                || !Objects.equals(catalog.getParameters(), source.getParameters().stream()
                .filter(parameter -> node.equals(parameter.getRouteNodeCode())).toList())) {
            throw new CheckedException("工序任务原参数冻结不属于工单的精确路线节点");
        }
        return node;
    }

    private record FrozenStandard(MdmProcessParameterStandardDTO.FrozenCatalog catalog,
                                  List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> parameters,
                                  boolean nodeProtocol, Map<String, String> nodeProcesses) {
    }
}
