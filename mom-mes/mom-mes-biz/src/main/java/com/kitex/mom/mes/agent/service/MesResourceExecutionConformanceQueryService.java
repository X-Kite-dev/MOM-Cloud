package com.kitex.mom.mes.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesResourceExecutionConformanceDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesTaskExecutionSegmentMapper;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskArrangeService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 汇总工单工序、派工接收、连续生产区间和工装执行记录，形成计划资源与现场事实的核对证据。
 *
 * <p>派工的workerCode只表示获派人员；只有连续生产区间中的workerCodes才进入实际执行人员。
 * 同理，计划设备字段不能冒充实际设备，实际设备只取已开始的生产区间。</p>
 */
@Service
@RequiredArgsConstructor
public class MesResourceExecutionConformanceQueryService {

    public static final String TOOL_CODE = "MES_RESOURCE_EXECUTION_CONFORMANCE_QUERY@1";
    private static final int MAX_OPERATIONS = 50;
    private static final int MAX_DISPATCHES_PER_OPERATION = 20;
    private static final int MAX_SEGMENTS_PER_DISPATCH = 50;
    private static final int MAX_TOOLINGS_PER_DISPATCH = 30;

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskService taskService;
    private final MesProduceTaskService produceTaskService;
    private final MesTaskArrangeService arrangeService;
    private final MesTaskExecutionSegmentMapper segmentMapper;
    private final MesToolingExecutionMapper toolingMapper;
    private final ObjectMapper objectMapper;

    /**
     * 按当前登录人的实际工厂范围和精确工单号生成资源执行证据。
     */
    public MesResourceExecutionConformanceDTO.Evidence query(
            MesResourceExecutionConformanceDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !Objects.equals(actualFactory, query.getFactoryCode())
                || StrUtil.isBlank(query.getTaskCode())) {
            throw new CheckedException("资源执行一致性查询工厂或工单与当前范围不一致");
        }
        String taskCode = query.getTaskCode().trim();
        requireUniqueTask(actualFactory, taskCode);

        List<MesProduceTaskEntity> sourceOperations = produceTaskService.list(
                new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .eq(MesProduceTaskEntity::getFactoryCode, actualFactory)
                        .eq(MesProduceTaskEntity::getTaskCode, taskCode)
                        .orderByAsc(MesProduceTaskEntity::getRouteOrder)
                        .orderByAsc(MesProduceTaskEntity::getId));
        List<MesTaskArrangeEntity> sourceArranges = arrangeService.list(
                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getFactoryCode, actualFactory)
                        .eq(MesTaskArrangeEntity::getTaskCode, taskCode)
                        .orderByAsc(MesTaskArrangeEntity::getRouteOrder)
                        .orderByAsc(MesTaskArrangeEntity::getDispatcherTime)
                        .orderByAsc(MesTaskArrangeEntity::getId));

        List<Long> arrangeIds = sourceArranges.stream().map(MesTaskArrangeEntity::getId)
                .filter(Objects::nonNull).distinct().toList();
        List<String> arrangeCodes = sourceArranges.stream().map(MesTaskArrangeEntity::getCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        List<MesTaskExecutionSegmentEntity> sourceSegments = arrangeIds.isEmpty() ? List.of()
                : segmentMapper.selectList(new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                .eq(MesTaskExecutionSegmentEntity::getFactoryCode, actualFactory)
                .in(MesTaskExecutionSegmentEntity::getArrangeId, arrangeIds)
                .orderByAsc(MesTaskExecutionSegmentEntity::getArrangeId)
                .orderByAsc(MesTaskExecutionSegmentEntity::getSegmentNo));
        List<MesToolingExecutionEntity> sourceToolings = arrangeCodes.isEmpty() ? List.of()
                : toolingMapper.selectList(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                .eq(MesToolingExecutionEntity::getFactoryCode, actualFactory)
                .in(MesToolingExecutionEntity::getArrangeCode, arrangeCodes)
                .orderByAsc(MesToolingExecutionEntity::getArrangeCode)
                .orderByAsc(MesToolingExecutionEntity::getToolingCode));

        Map<String, List<MesTaskArrangeEntity>> arrangesByOperation = sourceArranges.stream()
                .filter(item -> StrUtil.isNotBlank(item.getProduceTaskCode()))
                .collect(Collectors.groupingBy(MesTaskArrangeEntity::getProduceTaskCode,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<MesTaskExecutionSegmentEntity>> segmentsByArrange = sourceSegments.stream()
                .filter(item -> item.getArrangeId() != null)
                .collect(Collectors.groupingBy(MesTaskExecutionSegmentEntity::getArrangeId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<MesToolingExecutionEntity>> toolingsByArrange = sourceToolings.stream()
                .filter(item -> StrUtil.isNotBlank(item.getArrangeCode()))
                .collect(Collectors.groupingBy(MesToolingExecutionEntity::getArrangeCode,
                        LinkedHashMap::new, Collectors.toList()));

        List<String> gaps = new ArrayList<>();
        if (sourceOperations.isEmpty()) {
            gaps.add("MES工单尚未生成工序任务，无法核对资源执行一致性");
        }
        List<MesResourceExecutionConformanceDTO.OperationExecution> operations = sourceOperations.stream()
                .limit(MAX_OPERATIONS)
                .map(operation -> operation(operation,
                        arrangesByOperation.getOrDefault(operation.getCode(), List.of()),
                        segmentsByArrange, toolingsByArrange, gaps))
                .toList();
        if (sourceOperations.size() > MAX_OPERATIONS) {
            gaps.add("工序数量超过" + MAX_OPERATIONS + "条，本次资源执行证据已截断");
        }
        Set<String> knownOperationCodes = sourceOperations.stream().map(MesProduceTaskEntity::getCode)
                .collect(Collectors.toSet());
        sourceArranges.stream().filter(item -> !knownOperationCodes.contains(item.getProduceTaskCode()))
                .map(MesTaskArrangeEntity::getCode).forEach(code ->
                        gaps.add("派工单未匹配到当前工单工序任务：" + code));

        int started = (int) sourceArranges.stream().filter(item -> item.getStartTime() != null
                || !segmentsByArrange.getOrDefault(item.getId(), List.of()).isEmpty()).count();
        int conformant = countStatus(operations, "CONFORMANT");
        int deviation = countStatus(operations, "DEVIATION");
        int unknown = countStatus(operations, "UNKNOWN") + countStatus(operations, "NOT_STARTED");
        String overall = started == 0 ? "NO_EXECUTION"
                : deviation > 0 ? "NON_CONFORMANT"
                : unknown > 0 ? "PARTIAL" : "CONFORMANT";

        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_task:" + taskCode);
        sourceOperations.forEach(item -> refs.add("MES:mes_produce_task:" + item.getCode()));
        sourceArranges.forEach(item -> refs.add("MES:mes_task_arrange:" + item.getCode()));
        sourceSegments.forEach(item -> refs.add("MES:mes_task_execution_segment:" + item.getId()));
        sourceToolings.forEach(item -> refs.add("MES:mes_tooling_execution:" + item.getId()));

        MesResourceExecutionConformanceDTO.Evidence evidence =
                new MesResourceExecutionConformanceDTO.Evidence()
                        .setToolCode(TOOL_CODE)
                        .setMetricDefinition("计划资源取工序任务冻结的APS字段；实际工位取已开工派工的接收确认或连续RUN区间，实际设备和人员取连续RUN区间，实际工装取已开始的扫码执行记录。未开工派工、获派人员和计划设备不冒充实际执行。")
                        .setFactoryCode(actualFactory).setTaskCode(taskCode)
                        .setObservedAt(LocalDateTime.now())
                        .setSummary(new MesResourceExecutionConformanceDTO.Summary()
                                .setOperationCount(sourceOperations.size())
                                .setDispatchCount(sourceArranges.size())
                                .setStartedDispatchCount(started)
                                .setExecutionSegmentCount(sourceSegments.size())
                                .setToolingExecutionCount(sourceToolings.size())
                                .setConformantOperationCount(conformant)
                                .setDeviationOperationCount(deviation)
                                .setUnknownOperationCount(unknown)
                                .setOverallStatus(overall))
                        .setOperations(operations)
                        .setDataGaps(gaps.stream().distinct().limit(50).toList())
                        .setEvidenceRefs(refs.stream().distinct().toList())
                        .setTruncated(sourceOperations.size() > MAX_OPERATIONS);
        evidence.setEvidenceFingerprint(fingerprint(evidence));
        return evidence;
    }

    private MesResourceExecutionConformanceDTO.OperationExecution operation(
            MesProduceTaskEntity operation, List<MesTaskArrangeEntity> arranges,
            Map<Long, List<MesTaskExecutionSegmentEntity>> segmentsByArrange,
            Map<String, List<MesToolingExecutionEntity>> toolingsByArrange,
            List<String> evidenceGaps) {
        List<MesTaskArrangeEntity> returnedArranges = arranges.stream()
                .limit(MAX_DISPATCHES_PER_OPERATION).toList();
        List<MesTaskExecutionSegmentEntity> allSegments = returnedArranges.stream()
                .flatMap(item -> segmentsByArrange.getOrDefault(item.getId(), List.of()).stream())
                .filter(item -> "RUN".equals(item.getSegmentType()))
                .toList();
        List<MesToolingExecutionEntity> allToolings = returnedArranges.stream()
                .flatMap(item -> toolingsByArrange.getOrDefault(item.getCode(), List.of()).stream())
                .toList();
        Set<String> actualWorksites = allSegments.stream()
                .map(MesTaskExecutionSegmentEntity::getStationCode).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        returnedArranges.stream().filter(item -> item.getStartTime() != null)
                .map(MesTaskArrangeEntity::getActualWorksiteCode).filter(StrUtil::isNotBlank)
                .forEach(actualWorksites::add);
        Set<String> actualDevices = allSegments.stream().map(MesTaskExecutionSegmentEntity::getDeviceCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualWorkers = allSegments.stream()
                .flatMap(item -> csv(item.getWorkerCodes()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualToolings = allToolings.stream().filter(item -> item.getStartTime() != null)
                .map(MesToolingExecutionEntity::getToolingCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> plannedToolings = new LinkedHashSet<>(csv(operation.getPlannedToolingCodes()));
        LocalDateTime actualStart = allSegments.stream().map(MesTaskExecutionSegmentEntity::getStartTime)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime actualEnd = allSegments.stream().filter(item -> !"CANCELLED".equals(item.getStatus()))
                .map(MesTaskExecutionSegmentEntity::getEndTime).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);

        Boolean worksiteMatched = matchOne(operation.getPlannedWorksiteCode(), actualWorksites);
        Boolean deviceMatched = matchOne(operation.getPlannedDeviceCode(), actualDevices);
        Boolean workerMatched = matchOne(operation.getPlannedWorkerCode(), actualWorkers);
        Boolean toolingMatched = matchSet(plannedToolings, actualToolings, !allSegments.isEmpty());
        List<String> issues = new ArrayList<>();
        appendIssue(issues, worksiteMatched, "实际工位与APS冻结工位不一致", "已接收派工缺少实际工位证据");
        appendIssue(issues, deviceMatched, "实际设备与APS冻结设备不一致", "生产区间缺少实际设备证据");
        appendIssue(issues, workerMatched, "实际执行人员不包含APS冻结人员", "生产区间缺少实际人员证据");
        appendIssue(issues, toolingMatched, "实际工装集合与APS冻结工装不一致", "工装扫码执行证据不完整");
        if (actualStart != null && operation.getPlannedStartTime() == null) {
            issues.add("已实际开工但工序缺少APS计划开始时间");
        }
        if (actualEnd != null && operation.getPlannedEndTime() == null) {
            issues.add("已实际完工但工序缺少APS计划结束时间");
        }
        boolean started = actualStart != null;
        boolean deviation = Stream.of(worksiteMatched, deviceMatched, workerMatched, toolingMatched)
                .anyMatch(Boolean.FALSE::equals);
        boolean unknown = started && Stream.of(worksiteMatched, deviceMatched, workerMatched, toolingMatched)
                .anyMatch(Objects::isNull);
        String status = !started ? "NOT_STARTED" : deviation ? "DEVIATION" : unknown ? "UNKNOWN" : "CONFORMANT";
        if (unknown) {
            evidenceGaps.add("工序" + operation.getCode() + "已开工但部分计划或实际资源证据不足");
        }
        if (arranges.size() > MAX_DISPATCHES_PER_OPERATION) {
            issues.add("工序派工数量超过" + MAX_DISPATCHES_PER_OPERATION + "条，明细已截断");
            evidenceGaps.add("工序" + operation.getCode() + "派工明细已截断");
        }

        return new MesResourceExecutionConformanceDTO.OperationExecution()
                .setOperationId(operation.getId()).setOperationTaskCode(operation.getCode())
                .setProcessCode(operation.getRouteCode()).setProcessName(operation.getRouteName())
                .setOperationSeq(operation.getRouteOrder()).setOperationStatus(operation.getStatus())
                .setPlannedWorksiteCode(operation.getPlannedWorksiteCode())
                .setPlannedDeviceCode(operation.getPlannedDeviceCode())
                .setPlannedWorkerCode(operation.getPlannedWorkerCode())
                .setPlannedToolingCodes(new ArrayList<>(plannedToolings))
                .setPlannedStartTime(operation.getPlannedStartTime())
                .setPlannedEndTime(operation.getPlannedEndTime())
                .setActualWorksiteCodes(new ArrayList<>(actualWorksites))
                .setActualDeviceCodes(new ArrayList<>(actualDevices))
                .setActualWorkerCodes(new ArrayList<>(actualWorkers))
                .setActualToolingCodes(new ArrayList<>(actualToolings))
                .setActualStartTime(actualStart).setActualEndTime(actualEnd)
                .setStartDeviationMinutes(minutes(operation.getPlannedStartTime(), actualStart))
                .setFinishDeviationMinutes(minutes(operation.getPlannedEndTime(), actualEnd))
                .setWorksiteMatched(worksiteMatched).setDeviceMatched(deviceMatched)
                .setWorkerMatched(workerMatched).setToolingMatched(toolingMatched)
                .setConformanceStatus(status)
                .setDispatches(returnedArranges.stream().map(item -> dispatch(item,
                        segmentsByArrange.getOrDefault(item.getId(), List.of()),
                        toolingsByArrange.getOrDefault(item.getCode(), List.of()))).toList())
                .setIssues(issues).setTruncated(arranges.size() > MAX_DISPATCHES_PER_OPERATION);
    }

    private MesResourceExecutionConformanceDTO.DispatchExecution dispatch(
            MesTaskArrangeEntity arrange, List<MesTaskExecutionSegmentEntity> segments,
            List<MesToolingExecutionEntity> toolings) {
        return new MesResourceExecutionConformanceDTO.DispatchExecution()
                .setArrangeId(arrange.getId()).setArrangeCode(arrange.getCode())
                .setArrangeStatus(arrange.getStatus()).setAssignedWorkerCodes(arrange.getWorkerCode())
                .setActualWorksiteCode(arrange.getActualWorksiteCode())
                .setDispatchTime(arrange.getDispatcherTime())
                .setArrangeStartTime(arrange.getStartTime()).setArrangeFinishTime(arrange.getFinishTime())
                .setSegments(segments.stream().limit(MAX_SEGMENTS_PER_DISPATCH).map(this::segment).toList())
                .setToolings(toolings.stream().limit(MAX_TOOLINGS_PER_DISPATCH).map(this::tooling).toList())
                .setSegmentsTruncated(segments.size() > MAX_SEGMENTS_PER_DISPATCH)
                .setToolingsTruncated(toolings.size() > MAX_TOOLINGS_PER_DISPATCH);
    }

    private MesResourceExecutionConformanceDTO.ExecutionSegment segment(
            MesTaskExecutionSegmentEntity source) {
        return new MesResourceExecutionConformanceDTO.ExecutionSegment()
                .setSegmentId(source.getId()).setSegmentNo(source.getSegmentNo())
                .setStatus(source.getStatus()).setCloseReason(source.getCloseReason())
                .setStartTime(source.getStartTime()).setEndTime(source.getEndTime())
                .setDurationSeconds(source.getDurationSeconds()).setWorksiteCode(source.getStationCode())
                .setDeviceCode(source.getDeviceCode()).setWorkerCodes(csv(source.getWorkerCodes()))
                .setCalibrationEligible(source.getSampleEligible()).setExclusionReason(source.getExclusionReason());
    }

    private MesResourceExecutionConformanceDTO.ToolingExecution tooling(
            MesToolingExecutionEntity source) {
        return new MesResourceExecutionConformanceDTO.ToolingExecution()
                .setToolingExecutionId(source.getId()).setToolingCode(source.getToolingCode())
                .setToolingName(source.getToolingName()).setStatus(source.getStatus())
                .setCheckInWorkerCode(source.getCheckInWorkerCode()).setCheckInTime(source.getCheckInTime())
                .setStartTime(source.getStartTime()).setReleaseTime(source.getReleaseTime());
    }

    private void requireUniqueTask(String factoryCode, String taskCode) {
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factoryCode)
                .eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
        if (tasks.isEmpty()) throw new CheckedException("当前工厂未找到生产工单：" + taskCode);
        if (tasks.size() > 1) throw new CheckedException("当前工厂生产工单号重复，禁止生成资源一致性结论：" + taskCode);
    }

    private Boolean matchOne(String planned, Set<String> actual) {
        if (StrUtil.isBlank(planned)) return null;
        if (actual.isEmpty()) return null;
        return actual.size() == 1 && actual.contains(planned.trim());
    }

    private Boolean matchSet(Set<String> planned, Set<String> actual, boolean started) {
        if (planned.isEmpty()) return actual.isEmpty() ? Boolean.TRUE : Boolean.FALSE;
        if (actual.isEmpty()) return started ? Boolean.FALSE : null;
        return planned.equals(actual);
    }

    private void appendIssue(List<String> issues, Boolean matched, String mismatch, String missing) {
        if (Boolean.FALSE.equals(matched)) issues.add(mismatch);
        else if (matched == null) issues.add(missing);
    }

    private Long minutes(LocalDateTime planned, LocalDateTime actual) {
        return planned == null || actual == null ? null : Duration.between(planned, actual).toMinutes();
    }

    private int countStatus(List<MesResourceExecutionConformanceDTO.OperationExecution> operations,
                            String status) {
        return (int) operations.stream().filter(item -> status.equals(item.getConformanceStatus())).count();
    }

    private List<String> csv(String value) {
        if (StrUtil.isBlank(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                .distinct().toList();
    }

    private String fingerprint(MesResourceExecutionConformanceDTO.Evidence evidence) {
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("factoryCode", evidence.getFactoryCode());
            source.put("taskCode", evidence.getTaskCode());
            source.put("summary", evidence.getSummary());
            source.put("operations", evidence.getOperations());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("资源执行证据指纹生成失败", exception);
        }
    }
}
