package com.kitex.mom.iot.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.agent.dto.IotProcessTelemetryDTO;
import com.kitex.mom.iot.agent.dto.IotTelemetryAggregateRow;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotDevicePropertyMapper;
import com.kitex.mom.iot.mapper.IotProductModelMapper;
import com.kitex.mom.mes.agent.dto.MesProcessTelemetryScopeDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工单 IoT 工艺参数取证，供普通生产页面及智能体共同读取。
 *
 * <p>本服务先向 MES 获取实际设备连续生产区间，再在完全相同的租户和工厂范围内聚合
 * {@code scope_verified=true} 的设备属性历史。统计值不是由大模型计算，模型只能解释本服务
 * 返回的结构化证据。物模型上下限只表示设备数据包络，不冒充工艺卡参数标准。</p>
 */
@Service
@RequiredArgsConstructor
public class IotProcessTelemetryQueryService {

    public static final String TOOL_CODE = "IOT_PROCESS_TELEMETRY_QUERY@2";
    private static final int MAX_PARAMETERS = 100;
    private static final int MAX_EVIDENCE_REFS = 300;
    private static final int MAX_DATA_GAPS = 100;
    private static final String LEGACY_PROCESS_V1 = "LEGACY_PROCESS_V1";
    private static final String NODE_V2 = "NODE_V2";
    private static final String ROUTE_NODE_V2 = "ROUTE_NODE_V2";

    private final IotActualFactoryAccessService factoryAccess;
    private final RemoteMesTaskService remoteMesTaskService;
    private final IotDeviceMapper deviceMapper;
    private final IotDevicePropertyMapper propertyMapper;
    private final IotProductModelMapper productModelMapper;
    private final ObjectMapper objectMapper;

    /** 查询指定工单实际生产区间中的设备属性采样及物模型包络偏差。 */
    public IotProcessTelemetryDTO.Evidence query(IotProcessTelemetryDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        Long tenantId = factoryAccess.tenantId();
        if (query == null || !actualFactory.equals(query.getFactoryCode())
                || !StringUtils.hasText(query.getTaskCode())) {
            throw new CheckedException("IoT工艺参数查询工厂或工单与当前范围不一致");
        }
        String taskCode = query.getTaskCode().trim();
        requireAcceptedSchemaVersion(query.getAcceptedSnapshotSchemaVersion());
        MesProcessTelemetryScopeDTO.Evidence mesScope = loadMesScope(actualFactory, taskCode);
        boolean nodeProtocol = validateMesSchema(mesScope, query.getAcceptedSnapshotSchemaVersion());
        List<MergedWindow> mergedWindows = mergeWindows(mesScope.getWindows(), nodeProtocol);
        if (nodeProtocol) {
            rejectAmbiguousNodeWindows(mergedWindows);
        }
        LinkedHashSet<String> gaps = new LinkedHashSet<>();
        if (mesScope.getDataGaps() != null) {
            gaps.addAll(mesScope.getDataGaps());
        }
        List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> frozenStandards =
                mesScope.getFrozenParameterStandards() == null
                        ? List.of() : mesScope.getFrozenParameterStandards();
        if (frozenStandards.isEmpty()) {
            gaps.add("工单没有可用的下达冻结工艺参数标准；设备物模型上下限只能判断数据包络，不得表述为工艺参数合格或不合格");
        }

        Map<String, IotDeviceEntity> devices = resolveDevices(
                tenantId, actualFactory, mergedWindows, gaps);
        Map<Long, Map<String, ModelEnvelope>> modelCache = new HashMap<>();
        Map<ParameterKey, ParameterAccumulator> accumulators = new LinkedHashMap<>();
        Map<DeviceNodeKey, Set<String>> sampledIdentifiers = new HashMap<>();
        List<IotProcessTelemetryDTO.DeviceWindow> outputWindows = new ArrayList<>();
        LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>();
        if (mesScope.getEvidenceRefs() != null) {
            evidenceRefs.addAll(mesScope.getEvidenceRefs());
        }

        for (MergedWindow window : mergedWindows) {
            IotDeviceEntity device = devices.get(window.deviceCode());
            if (device == null) {
                continue;
            }
            outputWindows.add(toDeviceWindow(window, device));
            List<IotTelemetryAggregateRow> rows = propertyMapper.aggregateProcessTelemetry(
                    tenantId, actualFactory, device.getId(), window.startTime(), window.endTime());
            if (rows.isEmpty()) {
                gaps.add(nodeLabel(window.routeNodeCode()) + "设备" + window.deviceCode() + "在实际生产区间"
                        + window.startTime() + "至" + window.endTime()
                        + "没有归属已核验的属性采样");
                continue;
            }
            for (IotTelemetryAggregateRow row : rows) {
                if (!StringUtils.hasText(row.getIdentifier())) {
                    gaps.add(nodeLabel(window.routeNodeCode()) + "设备" + window.deviceCode()
                            + "存在无属性标识符采样，未纳入参数统计");
                    continue;
                }
                ParameterKey key = new ParameterKey(device.getId(), window.routeNodeCode(), row.getIdentifier());
                accumulators.computeIfAbsent(key,
                                ignored -> new ParameterAccumulator(device, window.routeNodeCode(), row.getIdentifier()))
                        .add(row, window.processCodes());
                sampledIdentifiers.computeIfAbsent(window.deviceNodeKey(), ignored -> new LinkedHashSet<>())
                        .add(row.getIdentifier());
                addEvidenceRef(evidenceRefs, "IOT:iot_device_property:"
                        + row.getFirstPropertyId() + ".." + row.getLastPropertyId());
            }
        }

        int modelRequiredGapCount = inspectRequiredParameters(actualFactory, devices,
                mergedWindows, sampledIdentifiers, modelCache, gaps);
        int missingRequiredStandardCount = inspectRequiredStandards(
                frozenStandards, accumulators.values(), nodeProtocol, gaps);
        List<IotProcessTelemetryDTO.ParameterEvidence> allParameters = accumulators.values().stream()
                .sorted(Comparator.comparing((ParameterAccumulator item) -> item.device.getDeviceCode())
                        .thenComparing(item -> item.routeNodeCode, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(item -> item.identifier))
                .map(item -> item.toEvidence(models(actualFactory, item.device, modelCache)
                                .get(item.identifier), resolveStandard(item, frozenStandards, nodeProtocol)))
                .toList();
        long totalSamples = allParameters.stream().mapToLong(item -> value(item.getSampleCount())).sum();
        long goodSamples = allParameters.stream().mapToLong(item -> value(item.getGoodSampleCount())).sum();
        long nonGoodSamples = allParameters.stream().mapToLong(item -> value(item.getNonGoodSampleCount())).sum();
        long numericGoodSamples = allParameters.stream()
                .mapToLong(item -> value(item.getNumericGoodSampleCount())).sum();
        if (nonGoodSamples > 0) {
            gaps.add("共有" + nonGoodSamples + "条采样质量不是GOOD，已从数值最小值、最大值和平均值统计中排除");
        }
        int outOfEnvelope = (int) allParameters.stream()
                .filter(item -> Boolean.FALSE.equals(item.getWithinModelEnvelope())).count();
        int unknownEnvelope = (int) allParameters.stream()
                .filter(item -> item.getWithinModelEnvelope() == null).count();
        int conformant = countStatus(allParameters, "CONFORMANT");
        int nonConformant = countStatus(allParameters, "NON_CONFORMANT");
        int insufficient = countStatus(allParameters, "INSUFFICIENT_EVIDENCE")
                + countStatus(allParameters, "AMBIGUOUS_STANDARD");
        int undefined = countStatus(allParameters, "NOT_DEFINED");
        String complianceStatus = complianceStatus(frozenStandards.size(), conformant,
                nonConformant, insufficient, missingRequiredStandardCount);
        if (allParameters.size() > MAX_PARAMETERS) {
            gaps.add("工艺参数超过" + MAX_PARAMETERS + "项，本次只返回前"
                    + MAX_PARAMETERS + "项明细，汇总仍按全部聚合结果计算");
        }
        if (evidenceRefs.size() > MAX_EVIDENCE_REFS) {
            gaps.add("证据引用超过" + MAX_EVIDENCE_REFS + "项，响应仅保留前"
                    + MAX_EVIDENCE_REFS + "项引用");
        }
        if (mergedWindows.isEmpty()) {
            gaps.add("MES未返回可用的实际设备连续生产区间，无法读取工单IoT时序证据");
        }
        if (accumulators.isEmpty() && !mergedWindows.isEmpty()) {
            gaps.add("实际生产区间内没有可用于工单参数分析的IoT属性证据");
        }
        boolean truncated = Boolean.TRUE.equals(mesScope.getTruncated())
                || allParameters.size() > MAX_PARAMETERS
                || evidenceRefs.size() > MAX_EVIDENCE_REFS
                || gaps.size() > MAX_DATA_GAPS;
        if (truncated && "CONFORMANT".equals(complianceStatus)) {
            complianceStatus = "PARTIAL";
        }
        return new IotProcessTelemetryDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition(nodeProtocol
                        ? "按MES已核验的实际执行节点及设备合并生产时间窗，再按设备、节点和参数标识符聚合当前租户、当前工厂且scope_verified=true的IoT属性；同设备跨节点重叠时间窗拒绝取证。只有quality_code=GOOD的十进制采样进入数值统计。工艺合规按冻结节点、主工序和参数标识符匹配工单下达时冻结的MDM标准，设备物模型上下限仅作为数据包络。"
                        : "先按MES实际设备连续生产区间限定时间窗，再聚合当前租户、当前工厂且scope_verified=true的IoT属性；只有quality_code=GOOD的十进制采样进入数值统计。工艺合规只使用工单下达时冻结的MDM参数标准，设备物模型上下限仅作为数据包络。")
                .setFactoryCode(actualFactory)
                .setTaskCode(taskCode)
                .setObservedAt(LocalDateTime.now())
                .setProcessParameterStandardNo(mesScope.getProcessParameterStandardNo())
                .setProcessParameterStandardVersion(mesScope.getProcessParameterStandardVersion())
                .setProcessParameterSnapshotFingerprint(mesScope.getProcessParameterSnapshotFingerprint())
                .setSnapshotSchemaVersion(query.getAcceptedSnapshotSchemaVersion() == null ? null
                        : nodeProtocol ? ROUTE_NODE_V2 : LEGACY_PROCESS_V1)
                .setContentSchemaVersion(query.getAcceptedSnapshotSchemaVersion() == null ? null
                        : nodeProtocol ? mesScope.getContentSchemaVersion()
                        : schemaVersion(mesScope.getContentSchemaVersion()))
                .setProcessComplianceStatus(complianceStatus)
                .setSummary(new IotProcessTelemetryDTO.Summary()
                        .setExecutionSegmentCount(sizeOf(mesScope.getWindows()))
                        .setMergedDeviceWindowCount(mergedWindows.size())
                        .setDeviceCount(devices.size())
                        .setParameterCount(allParameters.size())
                        .setTotalSampleCount(totalSamples)
                        .setGoodSampleCount(goodSamples)
                        .setNonGoodSampleCount(nonGoodSamples)
                        .setNumericGoodSampleCount(numericGoodSamples)
                        .setOutOfModelEnvelopeParameterCount(outOfEnvelope)
                        .setUnknownModelEnvelopeParameterCount(unknownEnvelope)
                        .setRequiredParameterGapCount(modelRequiredGapCount)
                        .setFrozenStandardParameterCount(frozenStandards.size())
                        .setConformantParameterCount(conformant)
                        .setNonConformantParameterCount(nonConformant)
                        .setInsufficientEvidenceParameterCount(insufficient)
                        .setUndefinedStandardParameterCount(undefined)
                        .setMissingRequiredStandardParameterCount(missingRequiredStandardCount))
                .setDeviceWindows(outputWindows)
                .setParameters(allParameters.stream().limit(MAX_PARAMETERS).toList())
                .setDataGaps(limit(gaps, MAX_DATA_GAPS, "其余数据缺口已截断"))
                .setEvidenceRefs(limit(evidenceRefs, MAX_EVIDENCE_REFS, null))
                .setTruncated(truncated);
    }

    private int inspectRequiredStandards(
            List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> standards,
            java.util.Collection<ParameterAccumulator> accumulators,
            boolean nodeProtocol,
            Set<String> gaps) {
        int missing = 0;
        for (MesProcessTelemetryScopeDTO.FrozenParameterStandard standard : standards) {
            if (!Boolean.TRUE.equals(standard.getRequired())) {
                continue;
            }
            boolean sampled = accumulators.stream().anyMatch(item ->
                    Objects.equals(item.identifier, standard.getIdentifier())
                            && item.processCodes.contains(standard.getProcessCode())
                            && (!nodeProtocol || Objects.equals(item.routeNodeCode, standard.getRouteNodeCode())));
            if (!sampled) {
                missing++;
                gaps.add("冻结标准要求" + nodeLabel(nodeProtocol ? standard.getRouteNodeCode() : null)
                        + "工序" + standard.getProcessCode() + "采集参数"
                        + standard.getIdentifier() + "，但实际生产区间没有对应采样");
            }
        }
        return missing;
    }

    private StandardResolution resolveStandard(
            ParameterAccumulator accumulator,
            List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> standards,
            boolean nodeProtocol) {
        List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> matches = standards.stream()
                .filter(item -> Objects.equals(item.getIdentifier(), accumulator.identifier)
                        && accumulator.processCodes.contains(item.getProcessCode())
                        && (!nodeProtocol || Objects.equals(item.getRouteNodeCode(), accumulator.routeNodeCode)))
                .sorted(Comparator.comparing(MesProcessTelemetryScopeDTO.FrozenParameterStandard::getProcessCode)
                        .thenComparing(item -> item.getSourceItemId() == null
                                ? Long.MAX_VALUE : item.getSourceItemId()))
                .toList();
        if (matches.isEmpty()) {
            return StandardResolution.undefined();
        }
        Map<String, List<MesProcessTelemetryScopeDTO.FrozenParameterStandard>> definitions =
                matches.stream().collect(java.util.stream.Collectors.groupingBy(
                        this::standardDefinitionKey, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        return new StandardResolution(matches.get(0),
                matches.stream().map(MesProcessTelemetryScopeDTO.FrozenParameterStandard::getProcessCode)
                        .distinct().toList(),
                matches.stream().map(MesProcessTelemetryScopeDTO.FrozenParameterStandard::getSourceItemId)
                        .filter(Objects::nonNull).distinct().toList(), definitions.size() > 1);
    }

    private String standardDefinitionKey(MesProcessTelemetryScopeDTO.FrozenParameterStandard item) {
        return String.join("|", textOf(item.getDataType()), textOf(item.getUnit()),
                decimalText(item.getTargetValue()), decimalText(item.getLowerLimit()),
                decimalText(item.getUpperLimit()), decimalText(item.getWarningLowerLimit()),
                decimalText(item.getWarningUpperLimit()), textOf(item.getAggregationMode()),
                Objects.toString(item.getMinimumSampleCount(), ""),
                decimalText(item.getMaximumNonGoodRatePct()));
    }

    private String complianceStatus(int standardCount, int conformant, int nonConformant,
                                    int insufficient, int missingRequired) {
        if (standardCount == 0) return "NO_STANDARD";
        if (nonConformant > 0) return "NON_CONFORMANT";
        if (insufficient > 0 || missingRequired > 0) return "PARTIAL";
        // 有冻结标准但本观察窗没有命中任何受控参数时，不能把“没有证据”判成合规。
        if (conformant == 0) return "PARTIAL";
        return "CONFORMANT";
    }

    private int countStatus(List<IotProcessTelemetryDTO.ParameterEvidence> parameters, String status) {
        return (int) parameters.stream()
                .filter(item -> Objects.equals(status, item.getProcessComplianceStatus())).count();
    }

    private String textOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private void requireAcceptedSchemaVersion(String acceptedVersion) {
        String version = schemaVersion(acceptedVersion);
        if (!LEGACY_PROCESS_V1.equals(version) && !ROUTE_NODE_V2.equals(version)) {
            throw new CheckedException("IoT工艺参数查询不支持acceptedSnapshotSchemaVersion=" + version);
        }
    }

    private boolean validateMesSchema(MesProcessTelemetryScopeDTO.Evidence scope, String acceptedVersion) {
        String snapshotVersion = schemaVersion(scope.getSnapshotSchemaVersion());
        boolean nodeProtocol = ROUTE_NODE_V2.equals(snapshotVersion);
        // V2明确无源标准时content为空；不能把它改写成存在某版来源内容。
        String contentVersion = nodeProtocol ? scope.getContentSchemaVersion()
                : schemaVersion(scope.getContentSchemaVersion());
        if (!LEGACY_PROCESS_V1.equals(snapshotVersion) && !ROUTE_NODE_V2.equals(snapshotVersion)) {
            throw new CheckedException("MES返回未知工艺参数冻结协议：" + snapshotVersion);
        }
        if (contentVersion != null && !LEGACY_PROCESS_V1.equals(contentVersion) && !NODE_V2.equals(contentVersion)) {
            throw new CheckedException("MES返回未知工艺参数内容协议：" + contentVersion);
        }
        if (nodeProtocol && contentVersion == null
                && (StringUtils.hasText(scope.getProcessParameterStandardNo())
                || (scope.getFrozenParameterStandards() != null && !scope.getFrozenParameterStandards().isEmpty()))) {
            throw new CheckedException("MES节点冻结已声明标准却缺少来源内容协议");
        }
        if (nodeProtocol && !ROUTE_NODE_V2.equals(acceptedVersion)) {
            throw new CheckedException("工单使用ROUTE_NODE_V2节点冻结；请升级IoT取证消费者并显式声明"
                    + "acceptedSnapshotSchemaVersion=ROUTE_NODE_V2后重试");
        }
        if (!nodeProtocol && NODE_V2.equals(contentVersion)) {
            throw new CheckedException("MES节点参数内容不得使用历史V1冻结协议返回");
        }
        Map<String, String> processByNode = new HashMap<>();
        Set<NodeParameterKey> standardKeys = new LinkedHashSet<>();
        if (scope.getFrozenParameterStandards() != null) {
            for (MesProcessTelemetryScopeDTO.FrozenParameterStandard standard : scope.getFrozenParameterStandards()) {
                if (standard == null) {
                    throw new CheckedException("MES返回空冻结参数标准，不能执行IoT参数取证");
                }
                if (nodeProtocol) {
                    requireNodeProcess(processByNode, standard.getRouteNodeCode(), standard.getProcessCode());
                    if (!StringUtils.hasText(standard.getIdentifier())
                            || !standardKeys.add(new NodeParameterKey(standard.getRouteNodeCode(), standard.getIdentifier()))) {
                        throw new CheckedException("MES节点冻结标准的参数标识为空或同节点重复："
                                + standard.getRouteNodeCode() + "/" + standard.getIdentifier());
                    }
                } else if (standard.getRouteNodeCode() != null) {
                    throw new CheckedException("MES历史V1参数标准携带节点身份，缺少明确ROUTE_NODE_V2协议");
                }
            }
        }
        if (scope.getWindows() != null) {
            for (MesProcessTelemetryScopeDTO.ExecutionWindow window : scope.getWindows()) {
                if (nodeProtocol) {
                    if (window == null) {
                        throw new CheckedException("MES节点冻结返回空执行时间窗，不能执行IoT参数取证");
                    }
                    requireNodeProcess(processByNode, window.getRouteNodeCode(), window.getProcessCode());
                    if (!StringUtils.hasText(window.getDeviceCode()) || window.getStartTime() == null
                            || window.getEndTime() == null || !window.getEndTime().isAfter(window.getStartTime())) {
                        throw new CheckedException("MES节点" + window.getRouteNodeCode() + "的执行设备或时间窗无效");
                    }
                } else if (window != null && window.getRouteNodeCode() != null) {
                    throw new CheckedException("MES历史V1执行时间窗携带节点身份，缺少明确ROUTE_NODE_V2协议");
                }
            }
        }
        return nodeProtocol;
    }

    private void requireNodeProcess(Map<String, String> processByNode, String routeNodeCode, String processCode) {
        if (routeNodeCode == null || !routeNodeCode.matches("RN-[1-9][0-9]{0,18}")
                || !StringUtils.hasText(processCode)) {
            throw new CheckedException("MES节点冻结缺少routeNodeCode或processCode，禁止按主工序回落取证");
        }
        String knownProcess = processByNode.putIfAbsent(routeNodeCode, processCode);
        if (knownProcess != null && !knownProcess.equals(processCode)) {
            throw new CheckedException("MES节点" + routeNodeCode + "的执行窗口与冻结标准绑定了不同主工序");
        }
    }

    private String schemaVersion(String version) {
        return version == null ? LEGACY_PROCESS_V1 : version;
    }

    private String nodeLabel(String routeNodeCode) {
        return routeNodeCode == null ? "" : "节点" + routeNodeCode + "的";
    }

    private MesProcessTelemetryScopeDTO.Evidence loadMesScope(String factoryCode, String taskCode) {
        R<MesProcessTelemetryScopeDTO.Evidence> response = remoteMesTaskService.processTelemetryScope(
                new MesProcessTelemetryScopeDTO.Query()
                        .setFactoryCode(factoryCode).setTaskCode(taskCode)
                        .setAcceptedSnapshotSchemaVersion(ROUTE_NODE_V2));
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MES未返回工单实际设备连续生产区间，不能执行IoT参数取证");
        }
        MesProcessTelemetryScopeDTO.Evidence evidence = response.getData();
        if (!factoryCode.equals(evidence.getFactoryCode()) || !taskCode.equals(evidence.getTaskCode())) {
            throw new CheckedException("MES返回的IoT取证范围与当前工厂或工单不一致");
        }
        return evidence;
    }

    private Map<String, IotDeviceEntity> resolveDevices(Long tenantId, String factoryCode,
                                                         List<MergedWindow> windows,
                                                         Set<String> gaps) {
        Map<String, IotDeviceEntity> devices = new LinkedHashMap<>();
        windows.stream().map(MergedWindow::deviceCode).distinct().forEach(deviceCode -> {
            List<IotDeviceEntity> rows = deviceMapper.selectList(
                    new LambdaQueryWrapper<IotDeviceEntity>()
                            .eq(IotDeviceEntity::getTenantId, tenantId)
                            .eq(IotDeviceEntity::getFactoryCode, factoryCode)
                            .eq(IotDeviceEntity::getDeviceCode, deviceCode)
                            .orderByAsc(IotDeviceEntity::getId)
                            .last("LIMIT 2"));
            if (rows.size() != 1) {
                gaps.add("MES实际设备编码" + deviceCode
                        + (rows.isEmpty() ? "在当前IoT工厂中不存在" : "在当前IoT工厂中不唯一")
                        + "，未关联属性历史");
            } else {
                devices.put(deviceCode, rows.get(0));
            }
        });
        return devices;
    }

    private List<MergedWindow> mergeWindows(List<MesProcessTelemetryScopeDTO.ExecutionWindow> source,
                                            boolean nodeProtocol) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Map<DeviceNodeKey, List<MesProcessTelemetryScopeDTO.ExecutionWindow>> byDeviceNode = source.stream()
                .filter(item -> item != null && StringUtils.hasText(item.getDeviceCode())
                        && item.getStartTime() != null && item.getEndTime() != null
                        && item.getEndTime().isAfter(item.getStartTime()))
                .collect(java.util.stream.Collectors.groupingBy(
                        item -> new DeviceNodeKey(item.getDeviceCode().trim(),
                                nodeProtocol ? item.getRouteNodeCode() : null), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        List<MergedWindow> result = new ArrayList<>();
        byDeviceNode.forEach((deviceNode, windows) -> {
            windows.sort(Comparator.comparing(MesProcessTelemetryScopeDTO.ExecutionWindow::getStartTime)
                    .thenComparing(item -> item.getExecutionSegmentId() == null
                            ? Long.MAX_VALUE : item.getExecutionSegmentId()));
            MergedWindow current = null;
            for (MesProcessTelemetryScopeDTO.ExecutionWindow item : windows) {
                if (current == null || item.getStartTime().isAfter(current.endTime())) {
                    current = MergedWindow.from(deviceNode, item);
                    result.add(current);
                } else {
                    current.merge(item);
                }
            }
        });
        return result.stream().sorted(Comparator.comparing(MergedWindow::deviceCode)
                .thenComparing(MergedWindow::routeNodeCode, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(MergedWindow::startTime)).toList();
    }

    /** IoT历史只有设备与时间，跨节点重叠无法证明采样归属，必须阻断重复计数。 */
    private void rejectAmbiguousNodeWindows(List<MergedWindow> windows) {
        List<MergedWindow> chronological = windows.stream()
                .sorted(Comparator.comparing(MergedWindow::deviceCode).thenComparing(MergedWindow::startTime))
                .toList();
        for (int i = 0; i < chronological.size(); i++) {
            MergedWindow current = chronological.get(i);
            for (int j = i + 1; j < chronological.size(); j++) {
                MergedWindow candidate = chronological.get(j);
                if (!current.deviceCode().equals(candidate.deviceCode())
                        || !candidate.startTime().isBefore(current.endTime())) {
                    break;
                }
                if (!Objects.equals(current.routeNodeCode(), candidate.routeNodeCode())) {
                    throw new CheckedException("设备" + current.deviceCode() + "在节点"
                            + current.routeNodeCode() + "与" + candidate.routeNodeCode()
                            + "的实际执行时间窗重叠，IoT采样节点归属存在歧义，不能重复用于工艺合规判定");
                }
            }
        }
    }

    private int inspectRequiredParameters(String factoryCode,
                                          Map<String, IotDeviceEntity> devices,
                                          List<MergedWindow> windows,
                                          Map<DeviceNodeKey, Set<String>> sampledIdentifiers,
                                          Map<Long, Map<String, ModelEnvelope>> modelCache,
                                          Set<String> gaps) {
        int count = 0;
        for (DeviceNodeKey deviceNode : windows.stream().map(MergedWindow::deviceNodeKey).distinct().toList()) {
            IotDeviceEntity device = devices.get(deviceNode.deviceCode());
            if (device == null) {
                continue;
            }
            Set<String> sampled = sampledIdentifiers.getOrDefault(deviceNode, Set.of());
            for (Map.Entry<String, ModelEnvelope> entry
                    : models(factoryCode, device, modelCache).entrySet()) {
                if (entry.getValue().required() && !sampled.contains(entry.getKey())) {
                    count++;
                    gaps.add(nodeLabel(deviceNode.routeNodeCode()) + "设备" + device.getDeviceCode() + "的必采属性"
                            + entry.getKey() + "在工单实际生产区间内没有采样");
                }
            }
        }
        return count;
    }

    private Map<String, ModelEnvelope> models(String factoryCode, IotDeviceEntity device,
                                               Map<Long, Map<String, ModelEnvelope>> cache) {
        if (device.getProductId() == null) {
            return Map.of();
        }
        return cache.computeIfAbsent(device.getProductId(), productId -> {
            List<IotProductModelEntity> rows = productModelMapper.selectList(
                    new LambdaQueryWrapper<IotProductModelEntity>()
                            .eq(IotProductModelEntity::getFactoryCode, factoryCode)
                            .eq(IotProductModelEntity::getProductId, productId)
                            .eq(IotProductModelEntity::getFuncType, "property")
                            .orderByAsc(IotProductModelEntity::getId));
            Map<String, ModelEnvelope> result = new LinkedHashMap<>();
            for (IotProductModelEntity row : rows) {
                if (!StringUtils.hasText(row.getIdentifier())) {
                    continue;
                }
                BigDecimal minimum = null;
                BigDecimal maximum = null;
                String unit = null;
                if (StringUtils.hasText(row.getDataSpecs())) {
                    try {
                        JsonNode specs = objectMapper.readTree(row.getDataSpecs());
                        minimum = decimal(specs.get("min"));
                        maximum = decimal(specs.get("max"));
                        unit = text(specs.get("unit"));
                    } catch (Exception ignored) {
                        // 无法解析的物模型不会转成虚假上下限，最终以未知包络呈现。
                    }
                }
                result.putIfAbsent(row.getIdentifier(), new ModelEnvelope(
                        row.getName(), row.getDataType(), unit, minimum, maximum,
                        Boolean.TRUE.equals(row.getRequired())));
            }
            return result;
        });
    }

    private IotProcessTelemetryDTO.DeviceWindow toDeviceWindow(MergedWindow window,
                                                                 IotDeviceEntity device) {
        return new IotProcessTelemetryDTO.DeviceWindow()
                .setDeviceCode(device.getDeviceCode())
                .setDeviceName(device.getDeviceName())
                .setProductCode(device.getProductCode())
                .setRouteNodeCode(window.routeNodeCode())
                .setStartTime(window.startTime())
                .setEndTime(window.endTime())
                .setProcessCodes(List.copyOf(window.processCodes()))
                .setExecutionSegmentIds(List.copyOf(window.executionSegmentIds()));
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() || !StringUtils.hasText(node.asText())
                ? null : node.asText();
    }

    private void addEvidenceRef(Set<String> refs, String ref) {
        if (refs.size() <= MAX_EVIDENCE_REFS && StringUtils.hasText(ref)) {
            refs.add(ref);
        }
    }

    private <T> List<T> limit(Set<T> source, int maximum, T overflowMessage) {
        List<T> values = source.stream().limit(maximum).toList();
        if (source.size() <= maximum || overflowMessage == null || maximum == 0) {
            return values;
        }
        List<T> result = new ArrayList<>(values.subList(0, maximum - 1));
        result.add(overflowMessage);
        return result;
    }

    private int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private long value(Long number) {
        return number == null ? 0L : number;
    }

    private record DeviceNodeKey(String deviceCode, String routeNodeCode) {
    }

    private record ParameterKey(Long deviceId, String routeNodeCode, String identifier) {
    }

    private record NodeParameterKey(String routeNodeCode, String identifier) {
    }

    private record ModelEnvelope(String name, String dataType, String unit,
                                 BigDecimal minimum, BigDecimal maximum, boolean required) {
    }

    private static final class MergedWindow {
        private final String deviceCode;
        private final String routeNodeCode;
        private final LocalDateTime startTime;
        private LocalDateTime endTime;
        private final LinkedHashSet<String> processCodes = new LinkedHashSet<>();
        private final LinkedHashSet<Long> executionSegmentIds = new LinkedHashSet<>();

        private MergedWindow(DeviceNodeKey deviceNode, LocalDateTime startTime, LocalDateTime endTime) {
            this.deviceCode = deviceNode.deviceCode();
            this.routeNodeCode = deviceNode.routeNodeCode();
            this.startTime = startTime;
            this.endTime = endTime;
        }

        private static MergedWindow from(DeviceNodeKey deviceNode,
                                         MesProcessTelemetryScopeDTO.ExecutionWindow item) {
            MergedWindow result = new MergedWindow(deviceNode, item.getStartTime(), item.getEndTime());
            result.include(item);
            return result;
        }

        private void merge(MesProcessTelemetryScopeDTO.ExecutionWindow item) {
            if (item.getEndTime().isAfter(endTime)) {
                endTime = item.getEndTime();
            }
            include(item);
        }

        private void include(MesProcessTelemetryScopeDTO.ExecutionWindow item) {
            if (StringUtils.hasText(item.getProcessCode())) {
                processCodes.add(item.getProcessCode());
            }
            if (item.getExecutionSegmentId() != null) {
                executionSegmentIds.add(item.getExecutionSegmentId());
            }
        }

        private String deviceCode() {
            return deviceCode;
        }

        private String routeNodeCode() {
            return routeNodeCode;
        }

        private DeviceNodeKey deviceNodeKey() {
            return new DeviceNodeKey(deviceCode, routeNodeCode);
        }

        private LocalDateTime startTime() {
            return startTime;
        }

        private LocalDateTime endTime() {
            return endTime;
        }

        private Set<String> processCodes() {
            return processCodes;
        }

        private Set<Long> executionSegmentIds() {
            return executionSegmentIds;
        }
    }

    private static final class ParameterAccumulator {
        private final IotDeviceEntity device;
        private final String routeNodeCode;
        private final String identifier;
        private final LinkedHashSet<String> processCodes = new LinkedHashSet<>();
        private String parameterName;
        private String dataType;
        private String unit;
        private final Set<String> numericUnits = new LinkedHashSet<>();
        private boolean mixedUnits;
        private boolean unknownUnitMetadata;
        private long missingNumericUnits;
        private long sampleCount;
        private long goodSampleCount;
        private long nonGoodSampleCount;
        private long numericGoodSampleCount;
        private BigDecimal minimumValue;
        private BigDecimal maximumValue;
        private BigDecimal weightedValueSum = BigDecimal.ZERO;
        private LocalDateTime firstCollectTime;
        private LocalDateTime lastCollectTime;

        private ParameterAccumulator(IotDeviceEntity device, String routeNodeCode, String identifier) {
            this.device = device;
            this.routeNodeCode = routeNodeCode;
            this.identifier = identifier;
        }

        private void add(IotTelemetryAggregateRow row, Set<String> windowProcessCodes) {
            parameterName = firstText(parameterName, row.getParameterName());
            dataType = firstText(dataType, row.getDataType());
            unit = firstText(unit, row.getUnit());
            if (valueOf(row.getNumericGoodSampleCount()) > 0) {
                if (row.getNumericUnitCount() == null || row.getMissingNumericUnitCount() == null
                        || row.getNumericUnitCount() < 0 || row.getMissingNumericUnitCount() < 0) {
                    unknownUnitMetadata = true;
                } else {
                    mixedUnits |= row.getNumericUnitCount() > 1;
                    missingNumericUnits += row.getMissingNumericUnitCount();
                    if (row.getNumericUnitCount() > 0 && StringUtils.hasText(row.getUnit())) numericUnits.add(row.getUnit().trim());
                    else if (row.getNumericUnitCount() > 0) unknownUnitMetadata = true;
                }
            }
            sampleCount += valueOf(row.getSampleCount());
            goodSampleCount += valueOf(row.getGoodSampleCount());
            nonGoodSampleCount += valueOf(row.getNonGoodSampleCount());
            long numericCount = valueOf(row.getNumericGoodSampleCount());
            numericGoodSampleCount += numericCount;
            if (row.getAverageValue() != null && numericCount > 0) {
                weightedValueSum =
                        weightedValueSum.add(
                                row.getAverageValue().multiply(BigDecimal.valueOf(numericCount)));
            }
            minimumValue = minimum(minimumValue, row.getMinimumValue());
            maximumValue = maximum(maximumValue, row.getMaximumValue());
            firstCollectTime = earlier(firstCollectTime, row.getFirstCollectTime());
            lastCollectTime = later(lastCollectTime, row.getLastCollectTime());
            processCodes.addAll(windowProcessCodes);
        }

        private IotProcessTelemetryDTO.ParameterEvidence toEvidence(
                ModelEnvelope model, StandardResolution resolution) {
            BigDecimal modelMinimum = model == null ? null : model.minimum();
            BigDecimal modelMaximum = model == null ? null : model.maximum();
            Boolean within = null;
            List<String> deviations = new ArrayList<>();
            String unitStatus = sampleUnitStatus();
            String sampleUnit = "KNOWN".equals(unitStatus) ? numericUnits.iterator().next() : null;
            boolean modelUnitsMatch =
                    sampleUnit != null
                            && model != null
                            && StringUtils.hasText(model.unit())
                            && sampleUnit.equals(model.unit().trim());
            if (numericGoodSampleCount > 0
                    && modelUnitsMatch
                    && (modelMinimum != null || modelMaximum != null)) {
                within = Boolean.TRUE;
                if (modelMinimum != null
                        && minimumValue != null
                        && minimumValue.compareTo(modelMinimum) < 0) {
                    within = Boolean.FALSE;
                    deviations.add("BELOW_MODEL_MIN");
                }
                if (modelMaximum != null
                        && maximumValue != null
                        && maximumValue.compareTo(modelMaximum) > 0) {
                    within = Boolean.FALSE;
                    deviations.add("ABOVE_MODEL_MAX");
                }
            }
            BigDecimal average =
                    !"KNOWN".equals(unitStatus)
                            ? null
                            : weightedValueSum.divide(
                                    BigDecimal.valueOf(numericGoodSampleCount),
                                    MathContext.DECIMAL64);
            StandardAssessment assessment = assessStandard(resolution, average, deviations);
            MesProcessTelemetryScopeDTO.FrozenParameterStandard standard = resolution.standard();
            return new IotProcessTelemetryDTO.ParameterEvidence()
                    .setDeviceCode(device.getDeviceCode())
                    .setDeviceName(device.getDeviceName())
                    .setProductCode(device.getProductCode())
                    .setRouteNodeCode(routeNodeCode)
                    .setProcessCodes(List.copyOf(processCodes))
                    .setIdentifier(identifier)
                    .setParameterName(
                            model != null && StringUtils.hasText(model.name())
                                    ? model.name()
                                    : parameterName)
                    .setDataType(
                            model != null && StringUtils.hasText(model.dataType())
                                    ? model.dataType()
                                    : dataType)
                    .setUnit(
                            model != null && StringUtils.hasText(model.unit())
                                    ? model.unit()
                                    : unit)
                    .setSampleUnit(sampleUnit)
                    .setSampleUnitStatus(unitStatus)
                    .setStandardUnit(standard == null ? null : standard.getUnit())
                    .setFirstCollectTime(firstCollectTime)
                    .setLastCollectTime(lastCollectTime)
                    .setSampleCount(sampleCount)
                    .setGoodSampleCount(goodSampleCount)
                    .setNonGoodSampleCount(nonGoodSampleCount)
                    .setNumericGoodSampleCount(numericGoodSampleCount)
                    .setMinimumValue("KNOWN".equals(unitStatus) ? minimumValue : null)
                    .setMaximumValue("KNOWN".equals(unitStatus) ? maximumValue : null)
                    .setAverageValue(average)
                    .setModelMinimum(modelMinimum)
                    .setModelMaximum(modelMaximum)
                    .setEnvelopeSource(
                            modelMinimum == null && modelMaximum == null
                                    ? null
                                    : "IOT_PRODUCT_MODEL")
                    .setWithinModelEnvelope(within)
                    .setStandardProcessCodes(resolution.processCodes())
                    .setStandardSourceItemIds(resolution.sourceItemIds())
                    .setStandardTargetValue(standard == null ? null : standard.getTargetValue())
                    .setStandardLowerLimit(standard == null ? null : standard.getLowerLimit())
                    .setStandardUpperLimit(standard == null ? null : standard.getUpperLimit())
                    .setStandardWarningLowerLimit(
                            standard == null ? null : standard.getWarningLowerLimit())
                    .setStandardWarningUpperLimit(
                            standard == null ? null : standard.getWarningUpperLimit())
                    .setStandardAggregationMode(
                            standard == null ? null : standard.getAggregationMode())
                    .setStandardMinimumSampleCount(
                            standard == null ? null : standard.getMinimumSampleCount())
                    .setStandardMaximumNonGoodRatePct(
                            standard == null ? null : standard.getMaximumNonGoodRatePct())
                    .setProcessStandardSource(standard == null ? null : "MES_FROZEN_MDM")
                    .setProcessComplianceStatus(assessment.status())
                    .setDeviations(deviations);
        }

        private String sampleUnitStatus() {
            if (numericGoodSampleCount == 0) return "NO_SAMPLES";
            if (mixedUnits || numericUnits.size() > 1) return "MIXED";
            if (unknownUnitMetadata) return "UNKNOWN";
            if (missingNumericUnits > 0 || numericUnits.isEmpty()) return "MISSING";
            return "KNOWN";
        }

        private StandardAssessment assessStandard(
                StandardResolution resolution, BigDecimal average, List<String> deviations) {
            MesProcessTelemetryScopeDTO.FrozenParameterStandard standard = resolution.standard();
            if (standard == null) {
                return new StandardAssessment("NOT_DEFINED");
            }
            if (resolution.ambiguous()) {
                deviations.add("AMBIGUOUS_FROZEN_PROCESS_STANDARD");
                return new StandardAssessment("AMBIGUOUS_STANDARD");
            }
            if (!"KNOWN".equals(sampleUnitStatus())) {
                deviations.add("SAMPLE_UNIT_" + sampleUnitStatus());
                return new StandardAssessment("INSUFFICIENT_EVIDENCE");
            }
            if (!StringUtils.hasText(standard.getUnit())) {
                deviations.add("STANDARD_UNIT_MISSING");
                return new StandardAssessment("INSUFFICIENT_EVIDENCE");
            }
            if (!standard.getUnit().trim().equals(numericUnits.iterator().next())) {
                deviations.add("STANDARD_UNIT_MISMATCH");
                return new StandardAssessment("INSUFFICIENT_EVIDENCE");
            }
            int minimumSamples =
                    standard.getMinimumSampleCount() == null ? 1 : standard.getMinimumSampleCount();
            if (numericGoodSampleCount < minimumSamples) {
                deviations.add("GOOD_NUMERIC_SAMPLE_COUNT_BELOW_STANDARD_MINIMUM");
                return new StandardAssessment("INSUFFICIENT_EVIDENCE");
            }
            BigDecimal nonGoodRate =
                    sampleCount == 0
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf(nonGoodSampleCount)
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(
                                            BigDecimal.valueOf(sampleCount),
                                            6,
                                            RoundingMode.HALF_UP);
            BigDecimal maximumNonGood =
                    standard.getMaximumNonGoodRatePct() == null
                            ? BigDecimal.ZERO
                            : standard.getMaximumNonGoodRatePct();
            boolean nonConformant = false;
            if (nonGoodRate.compareTo(maximumNonGood) > 0) {
                deviations.add("NON_GOOD_SAMPLE_RATE_EXCEEDS_STANDARD");
                nonConformant = true;
            }
            BigDecimal valueMinimum =
                    "AVERAGE".equalsIgnoreCase(standard.getAggregationMode())
                            ? average
                            : minimumValue;
            BigDecimal valueMaximum =
                    "AVERAGE".equalsIgnoreCase(standard.getAggregationMode())
                            ? average
                            : maximumValue;
            if (standard.getLowerLimit() != null
                    && valueMinimum != null
                    && valueMinimum.compareTo(standard.getLowerLimit()) < 0) {
                deviations.add("BELOW_FROZEN_PROCESS_LOWER_LIMIT");
                nonConformant = true;
            }
            if (standard.getUpperLimit() != null
                    && valueMaximum != null
                    && valueMaximum.compareTo(standard.getUpperLimit()) > 0) {
                deviations.add("ABOVE_FROZEN_PROCESS_UPPER_LIMIT");
                nonConformant = true;
            }
            if (standard.getWarningLowerLimit() != null
                    && valueMinimum != null
                    && valueMinimum.compareTo(standard.getWarningLowerLimit()) < 0
                    && !deviations.contains("BELOW_FROZEN_PROCESS_LOWER_LIMIT")) {
                deviations.add("BELOW_FROZEN_PROCESS_WARNING_LOWER_LIMIT");
            }
            if (standard.getWarningUpperLimit() != null
                    && valueMaximum != null
                    && valueMaximum.compareTo(standard.getWarningUpperLimit()) > 0
                    && !deviations.contains("ABOVE_FROZEN_PROCESS_UPPER_LIMIT")) {
                deviations.add("ABOVE_FROZEN_PROCESS_WARNING_UPPER_LIMIT");
            }
            return new StandardAssessment(nonConformant ? "NON_CONFORMANT" : "CONFORMANT");
        }

        private static String firstText(String current, String candidate) {
            return StringUtils.hasText(current) ? current : candidate;
        }

        private static long valueOf(Long number) {
            return number == null ? 0L : number;
        }

        private static BigDecimal minimum(BigDecimal left, BigDecimal right) {
            if (left == null) return right;
            if (right == null) return left;
            return left.min(right);
        }

        private static BigDecimal maximum(BigDecimal left, BigDecimal right) {
            if (left == null) return right;
            if (right == null) return left;
            return left.max(right);
        }

        private static LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
            if (left == null) return right;
            if (right == null) return left;
            return left.isBefore(right) ? left : right;
        }

        private static LocalDateTime later(LocalDateTime left, LocalDateTime right) {
            if (left == null) return right;
            if (right == null) return left;
            return left.isAfter(right) ? left : right;
        }
    }

    private record StandardResolution(
            MesProcessTelemetryScopeDTO.FrozenParameterStandard standard,
            List<String> processCodes,
            List<Long> sourceItemIds,
            boolean ambiguous) {
        private static StandardResolution undefined() {
            return new StandardResolution(null, List.of(), List.of(), false);
        }
    }

    private record StandardAssessment(String status) {}
}
