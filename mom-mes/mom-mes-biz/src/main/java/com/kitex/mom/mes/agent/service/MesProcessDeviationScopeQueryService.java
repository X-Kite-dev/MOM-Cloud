package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesProcessDeviationScopeDTO;
import com.kitex.mom.mes.agent.dto.MesProcessTelemetryScopeDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesTaskExecutionSegmentEntity;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 将 IoT 告警或生产任务归一到唯一的实际运行节点和下达冻结参数。
 */
@Service
@RequiredArgsConstructor
public class MesProcessDeviationScopeQueryService {

    public static final String TOOL_CODE = "MES_PROCESS_DEVIATION_SCOPE@1";
    private static final String NODE_V2 = "ROUTE_NODE_V2";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskExecutionSegmentMapper segmentMapper;
    private final MesTaskService taskService;
    private final MesProcessTelemetryScopeQueryService telemetryScopeService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesProcessDeviationScopeDTO.Evidence query(MesProcessDeviationScopeDTO.Query query) {
        String factory = factoryAccess.require("read");
        if (query == null || !factory.equals(query.getFactoryCode())
                || !StringUtils.hasText(query.getParameterIdentifier())) {
            throw new CheckedException("参数异常调查必须携带当前工厂和精确参数标识符");
        }
        String sourceType = query.getSourceType();
        if (!"MES_TASK".equals(sourceType) && !"IOT_ALARM".equals(sourceType)) {
            throw new CheckedException("参数异常调查只接受生产任务或已核验IoT告警来源");
        }
        String taskCode = "IOT_ALARM".equals(sourceType) ? resolveAlarmTask(factory, query) : query.getTaskCode();
        if (!StringUtils.hasText(taskCode)) {
            throw new CheckedException("请选择真实生产任务，不能按设备当前计划猜测工单");
        }
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
        if (tasks.size() != 1 || !StringUtils.hasText(tasks.get(0).getMatCode())) {
            throw new CheckedException("生产工单不存在、不唯一或缺少产品编码，不能建立跨工单可比范围");
        }
        MesProcessTelemetryScopeDTO.Evidence scope = telemetryScopeService.query(
                new MesProcessTelemetryScopeDTO.Query().setFactoryCode(factory).setTaskCode(taskCode)
                        .setAcceptedSnapshotSchemaVersion(NODE_V2));
        if (!NODE_V2.equals(scope.getSnapshotSchemaVersion())
                || !StringUtils.hasText(scope.getProcessParameterSnapshotFingerprint())
                || Boolean.TRUE.equals(scope.getTruncated())) {
            throw new CheckedException("工单节点冻结标准不存在或生产段已截断，不能形成精确参数异常范围");
        }
        List<MesProcessTelemetryScopeDTO.ExecutionWindow> windows = scope.getWindows().stream()
                .filter(item -> matches(query.getDeviceCode(), item.getDeviceCode()))
                .filter(item -> matches(query.getRouteNodeCode(), item.getRouteNodeCode()))
                .filter(item -> query.getEventTime() == null || inWindow(query.getEventTime(), item))
                .sorted(Comparator.comparing(MesProcessTelemetryScopeDTO.ExecutionWindow::getStartTime)
                        .thenComparing(MesProcessTelemetryScopeDTO.ExecutionWindow::getExecutionSegmentId))
                .toList();
        if (windows.isEmpty()) {
            throw new CheckedException("指定设备、节点和时间未匹配实际RUN生产段");
        }
        List<String> devices = windows.stream().map(MesProcessTelemetryScopeDTO.ExecutionWindow::getDeviceCode)
                .distinct().toList();
        List<String> nodes = windows.stream().map(MesProcessTelemetryScopeDTO.ExecutionWindow::getRouteNodeCode)
                .distinct().toList();
        List<String> processes = windows.stream().map(MesProcessTelemetryScopeDTO.ExecutionWindow::getProcessCode)
                .distinct().toList();
        if (devices.size() != 1 || nodes.size() != 1 || processes.size() != 1
                || !StringUtils.hasText(nodes.get(0))) {
            throw new CheckedException("设备或路线节点对应多个实际范围，请先选择精确工序，不能自动归因");
        }
        List<MesProcessTelemetryScopeDTO.FrozenParameterStandard> standards = scope.getFrozenParameterStandards().stream()
                .filter(item -> Objects.equals(item.getRouteNodeCode(), nodes.get(0))
                        && Objects.equals(item.getProcessCode(), processes.get(0))
                        && Objects.equals(item.getIdentifier(), query.getParameterIdentifier()))
                .toList();
        if (standards.size() != 1) {
            throw new CheckedException(standards.isEmpty()
                    ? "该节点没有下达冻结的参数标准，不能按现行MDM标准补判历史异常"
                    : "该节点存在多条同标识符冻结标准，需先处理标准冲突");
        }
        String fingerprint = sha256(String.join("|", factory, taskCode, tasks.get(0).getMatCode(),
                devices.get(0), nodes.get(0),
                query.getParameterIdentifier(), scope.getProcessParameterSnapshotFingerprint(),
                windows.stream().map(item -> item.getExecutionSegmentId() + ":" + item.getStartTime()
                        + ":" + item.getEndTime()).toList().toString()));
        return new MesProcessDeviationScopeDTO.Evidence()
                .setToolCode(TOOL_CODE).setFactoryCode(factory).setSourceType(sourceType)
                .setTaskCode(taskCode).setDeviceCode(devices.get(0)).setRouteNodeCode(nodes.get(0))
                .setProductCode(tasks.get(0).getMatCode())
                .setProcessCode(processes.get(0)).setParameterIdentifier(query.getParameterIdentifier())
                .setProcessParameterStandardNo(scope.getProcessParameterStandardNo())
                .setProcessParameterStandardVersion(scope.getProcessParameterStandardVersion())
                .setProcessParameterSnapshotFingerprint(scope.getProcessParameterSnapshotFingerprint())
                .setFrozenStandard(standards.get(0)).setWindows(windows)
                .setDataGaps(scope.getDataGaps()).setEvidenceRefs(scope.getEvidenceRefs())
                .setFactFingerprint(fingerprint).setTruncated(false);
    }

    private String resolveAlarmTask(String factory, MesProcessDeviationScopeDTO.Query query) {
        if (!StringUtils.hasText(query.getDeviceCode()) || query.getEventTime() == null) {
            throw new CheckedException("IoT告警入口必须由IoT提供已核验的设备和告警时间");
        }
        LocalDateTime eventTime = query.getEventTime();
        List<MesTaskExecutionSegmentEntity> segments = segmentMapper.selectList(
                new LambdaQueryWrapper<MesTaskExecutionSegmentEntity>()
                        .eq(MesTaskExecutionSegmentEntity::getFactoryCode, factory)
                        .eq(MesTaskExecutionSegmentEntity::getDeviceCode, query.getDeviceCode())
                        .eq(MesTaskExecutionSegmentEntity::getSegmentType, "RUN")
                        .eq(MesTaskExecutionSegmentEntity::getSampleEligible, true)
                        .ne(MesTaskExecutionSegmentEntity::getStatus, "CANCELLED")
                        .le(MesTaskExecutionSegmentEntity::getStartTime, eventTime)
                        .and(wrapper -> wrapper.isNull(MesTaskExecutionSegmentEntity::getEndTime)
                                .or().gt(MesTaskExecutionSegmentEntity::getEndTime, eventTime))
                        .orderByAsc(MesTaskExecutionSegmentEntity::getId)
                        .last("LIMIT 3"));
        if (segments.size() != 1 || !StringUtils.hasText(segments.get(0).getTaskCode())) {
            throw new CheckedException("告警发生时设备未唯一匹配实际生产段，请人工核对工单与节点");
        }
        return segments.get(0).getTaskCode();
    }

    private boolean matches(String expected, String actual) {
        return !StringUtils.hasText(expected) || Objects.equals(expected.trim(), actual);
    }

    private boolean inWindow(LocalDateTime time, MesProcessTelemetryScopeDTO.ExecutionWindow window) {
        return !time.isBefore(window.getStartTime()) && time.isBefore(window.getEndTime());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成生产参数范围指纹", exception);
        }
    }
}
