package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.MaintenancePlanTypeEnum;
import com.kitex.mom.tpm.enums.MaintenanceTaskStateEnum;
import com.kitex.mom.tpm.enums.PlanExecuteStateEnum;
import com.kitex.mom.tpm.enums.RepairStateEnum;
import com.kitex.mom.tpm.mapper.TpmCheckTaskMapper;
import com.kitex.mom.tpm.mapper.TpmMaintenancePlanMapper;
import com.kitex.mom.tpm.mapper.TpmMaintenanceTaskMapper;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import com.kitex.mom.tpm.service.TpmDeviceAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TpmDeviceAvailabilityServiceImpl implements TpmDeviceAvailabilityService {

    private static final int MAX_QUERY_DAYS = 800;
    private static final int MAX_DEVICE_COUNT = 500;
    private static final int MAX_OCCURRENCES_PER_PLAN = 20000;

    private final TpmMaintenanceTaskMapper maintenanceTaskMapper;
    private final TpmMaintenancePlanMapper maintenancePlanMapper;
    private final TpmRepairTaskMapper repairTaskMapper;
    private final TpmCheckTaskMapper checkTaskMapper;
    private final RemoteDeviceService remoteDeviceService;

    @Override
    public List<TpmDeviceUnavailableWindowDTO> unavailableWindows(TpmDeviceAvailabilityQueryDTO query) {
        QueryRange range = validate(query);
        List<TpmDeviceUnavailableWindowDTO> windows = new ArrayList<>();
        appendMaintenanceTasks(range, windows);
        appendMaintenancePlans(range, windows);
        appendRepairTasks(range, windows);
        appendCheckBlocks(range, windows);
        appendCurrentDeviceState(range, windows);
        windows.removeIf(item -> !overlaps(item, range.start, range.end));
        windows.sort(Comparator.comparing(TpmDeviceUnavailableWindowDTO::getDeviceCode)
                .thenComparing(TpmDeviceUnavailableWindowDTO::getStartTime)
                .thenComparing(TpmDeviceUnavailableWindowDTO::getSourceType));
        return windows;
    }

    private QueryRange validate(TpmDeviceAvailabilityQueryDTO query) {
        if (query == null || query.getStartTime() == null || query.getEndTime() == null
                || !query.getEndTime().isAfter(query.getStartTime())) {
            throw new CheckedException("设备可用性查询必须提供合法的开始、结束时间");
        }
        if (Duration.between(query.getStartTime(), query.getEndTime()).toDays() > MAX_QUERY_DAYS) {
            throw new CheckedException("设备可用性查询范围不能超过" + MAX_QUERY_DAYS + "天");
        }
        Set<String> codes = new LinkedHashSet<>();
        if (query.getDeviceCodes() != null) {
            query.getDeviceCodes().stream().filter(StrUtil::isNotBlank).map(String::trim).forEach(codes::add);
        }
        if (codes.isEmpty()) {
            throw new CheckedException("设备可用性查询的设备编码不能为空");
        }
        if (codes.size() > MAX_DEVICE_COUNT) {
            throw new CheckedException("单次设备可用性查询不能超过" + MAX_DEVICE_COUNT + "台设备");
        }
        return new QueryRange(List.copyOf(codes), query.getStartTime(), query.getEndTime());
    }

    private void appendMaintenanceTasks(QueryRange range, List<TpmDeviceUnavailableWindowDTO> windows) {
        List<TpmMaintenanceTaskEntity> tasks = maintenanceTaskMapper.selectList(
                new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                        .in(TpmMaintenanceTaskEntity::getDeviceCode, range.deviceCodes)
                        .in(TpmMaintenanceTaskEntity::getState,
                                MaintenanceTaskStateEnum.WAIT.getCode(), MaintenanceTaskStateEnum.ING.getCode()));
        for (TpmMaintenanceTaskEntity task : tasks) {
            LocalDateTime start = task.getMaintenanceStartTime() != null
                    ? task.getMaintenanceStartTime() : task.getCreateTime();
            if (start == null) {
                continue;
            }
            Duration duration = duration(task.getHandleTime(), task.getHandleTimeUnit());
            boolean processing = MaintenanceTaskStateEnum.ING.getCode().equals(task.getState());
            LocalDateTime end = processing ? task.getExpectedRecoveryTime()
                    : duration == null ? null : start.plus(duration);
            windows.add(window("MAINTENANCE_TASK", task.getId(), task.getTaskCode(), task.getDeviceCode(),
                    task.getDeviceName(), start, end, task.getState(),
                    processing ? end == null ? "保养任务正在执行且缺少恢复预测" : "保养任务正在执行，按有效预测窗口阻断" : duration == null
                            ? "待保养任务缺少处理时长，按开放窗口阻断" : "待执行保养任务", task.getFactoryCode()));
        }
    }

    private void appendMaintenancePlans(QueryRange range, List<TpmDeviceUnavailableWindowDTO> windows) {
        List<TpmMaintenancePlanEntity> plans = maintenancePlanMapper.selectList(
                new LambdaQueryWrapper<TpmMaintenancePlanEntity>()
                        .in(TpmMaintenancePlanEntity::getDeviceCode, range.deviceCodes)
                        .eq(TpmMaintenancePlanEntity::getExecuteState, PlanExecuteStateEnum.ING.getCode()));
        for (TpmMaintenancePlanEntity plan : plans) {
            if (plan.getStartTime() == null) {
                continue;
            }
            Duration handleDuration = duration(plan.getHandleTime(), plan.getHandleTimeUnit());
            if (MaintenancePlanTypeEnum.ONCE.getCode().equals(plan.getType())) {
                appendPlanOccurrence(plan, plan.getStartTime(), handleDuration, windows);
                continue;
            }
            Duration cycle = duration(plan.getCircleTime(), plan.getCircleTimeUnit());
            if (cycle == null || cycle.isZero() || cycle.isNegative()) {
                windows.add(window("MAINTENANCE_PLAN", plan.getId(), plan.getName(), plan.getDeviceCode(),
                        plan.getDeviceName(), plan.getStartTime(), null, plan.getExecuteState(),
                        "周期保养计划缺少合法周期，按开放窗口阻断", plan.getFactoryCode()));
                continue;
            }
            LocalDateTime occurrence = firstRelevantOccurrence(plan.getStartTime(), cycle,
                    handleDuration == null ? Duration.ZERO : handleDuration, range.start);
            for (int count = 0; occurrence.isBefore(range.end); count++, occurrence = occurrence.plus(cycle)) {
                if (count >= MAX_OCCURRENCES_PER_PLAN) {
                    throw new CheckedException("保养计划[" + plan.getName() + "]周期过密，无法生成设备可用窗口");
                }
                appendPlanOccurrence(plan, occurrence, handleDuration, windows);
                if (handleDuration == null) {
                    break;
                }
            }
        }
    }

    private LocalDateTime firstRelevantOccurrence(LocalDateTime origin, Duration cycle,
                                                  Duration handleDuration, LocalDateTime rangeStart) {
        LocalDateTime relevantStart = rangeStart.minus(handleDuration);
        if (!origin.isBefore(relevantStart)) {
            return origin;
        }
        long cycleMinutes = cycle.toMinutes();
        long elapsedMinutes = Duration.between(origin, relevantStart).toMinutes();
        long jumps = Math.max(0, elapsedMinutes / cycleMinutes);
        LocalDateTime occurrence = origin.plusMinutes(Math.multiplyExact(jumps, cycleMinutes));
        while (occurrence.plus(cycle).isBefore(relevantStart) || occurrence.plus(cycle).equals(relevantStart)) {
            occurrence = occurrence.plus(cycle);
        }
        return occurrence;
    }

    private void appendPlanOccurrence(TpmMaintenancePlanEntity plan, LocalDateTime start, Duration duration,
                                      List<TpmDeviceUnavailableWindowDTO> windows) {
        windows.add(window("MAINTENANCE_PLAN", plan.getId(), plan.getName(), plan.getDeviceCode(),
                plan.getDeviceName(), start, duration == null ? null : start.plus(duration), plan.getExecuteState(),
                duration == null ? "保养计划缺少处理时长，按开放窗口阻断" : "保养计划预占", plan.getFactoryCode()));
    }

    private void appendRepairTasks(QueryRange range, List<TpmDeviceUnavailableWindowDTO> windows) {
        List<TpmRepairTaskEntity> tasks = repairTaskMapper.selectList(
                new LambdaQueryWrapper<TpmRepairTaskEntity>()
                        .in(TpmRepairTaskEntity::getDeviceCode, range.deviceCodes)
                        .eq(TpmRepairTaskEntity::getState, RepairStateEnum.CREATE.getCode()));
        for (TpmRepairTaskEntity task : tasks) {
            LocalDateTime start = task.getSubmitTime() != null ? task.getSubmitTime() : task.getCreateTime();
            if (start != null) {
                windows.add(window("REPAIR_TASK", task.getId(), task.getTaskCode(), task.getDeviceCode(),
                        task.getDeviceName(), start, task.getExpectedRecoveryTime(), task.getState(),
                        task.getExpectedRecoveryTime() == null ? "维修任务未完成且缺少恢复预测"
                                : "维修任务未完成，按有效预测窗口阻断", task.getFactoryCode()));
            }
        }
    }

    private void appendCheckBlocks(QueryRange range, List<TpmDeviceUnavailableWindowDTO> windows) {
        List<TpmCheckTaskEntity> tasks = checkTaskMapper.selectList(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .in(TpmCheckTaskEntity::getDeviceCode, range.deviceCodes)
                .eq(TpmCheckTaskEntity::getBlockStatus, "BLOCKED"));
        for (TpmCheckTaskEntity task : tasks) {
            LocalDateTime start = task.getBlockTime() != null ? task.getBlockTime() : task.getHandleTime();
            if (start != null) {
                windows.add(window("CHECK_BLOCK", task.getId(), task.getTaskCode(), task.getDeviceCode(),
                        task.getDeviceName(), start, task.getExpectedRecoveryTime(), task.getBlockStatus(),
                        task.getExpectedRecoveryTime() == null ? "点检生产封锁缺少恢复预测：" + task.getBlockReason()
                                : "点检生产封锁，按有效预测窗口阻断：" + task.getBlockReason(), task.getFactoryCode()));
            }
        }
    }

    private void appendCurrentDeviceState(QueryRange range, List<TpmDeviceUnavailableWindowDTO> windows) {
        R<List<MdmDeviceEntity>> response = remoteDeviceService.getAllListWithScrap();
        if (response == null || !Objects.equals(CommonConstants.SUCCESS, response.getCode()) || response.getData() == null) {
            throw new CheckedException("无法读取设备实时状态");
        }
        Set<String> requested = Set.copyOf(range.deviceCodes);
        Set<String> returned = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (MdmDeviceEntity device : response.getData()) {
            if (!requested.contains(device.getDeviceCode())) {
                continue;
            }
            returned.add(device.getDeviceCode());
            boolean permanentlyUnavailable = Boolean.FALSE.equals(device.getStatus())
                    || DeviceStateEnum.SCRAP.getCode().equals(device.getDeviceState());
            boolean currentlyUnavailable = !"1".equals(device.getDeviceUsable())
                    || DeviceStateEnum.MAINTENANCE.getCode().equals(device.getDeviceState())
                    || DeviceStateEnum.REPAIR.getCode().equals(device.getDeviceState())
                    || DeviceStateEnum.PAUSE.getCode().equals(device.getDeviceState());
            if (permanentlyUnavailable || currentlyUnavailable) {
                String stateName = StrUtil.blankToDefault(DeviceStateEnum.getNameByCode(device.getDeviceState()), "未知状态");
                boolean coveredByActiveForecast = !permanentlyUnavailable && windows.stream().anyMatch(item ->
                        Objects.equals(item.getDeviceCode(), device.getDeviceCode()) && item.getEndTime() != null
                                && item.getEndTime().isAfter(now)
                                && ((DeviceStateEnum.REPAIR.getCode().equals(device.getDeviceState())
                                && "REPAIR_TASK".equals(item.getSourceType()))
                                || (DeviceStateEnum.MAINTENANCE.getCode().equals(device.getDeviceState())
                                && "MAINTENANCE_TASK".equals(item.getSourceType()))));
                if (!coveredByActiveForecast && DeviceStateEnum.PAUSE.getCode().equals(device.getDeviceState())) {
                    coveredByActiveForecast = windows.stream().anyMatch(item ->
                            Objects.equals(item.getDeviceCode(), device.getDeviceCode()) && item.getEndTime() != null
                                    && item.getEndTime().isAfter(now) && "CHECK_BLOCK".equals(item.getSourceType()));
                }
                if (!coveredByActiveForecast) {
                    windows.add(window("DEVICE_STATE", device.getId(), device.getDeviceCode(), device.getDeviceCode(),
                            device.getDeviceName(), permanentlyUnavailable ? range.start : later(range.start, now), null,
                            device.getDeviceState(), "设备当前不可用于生产：" + stateName, device.getFactoryCode()));
                }
            }
        }
        for (String missingCode : requested.stream().filter(code -> !returned.contains(code)).toList()) {
            windows.add(window("DEVICE_STATE", null, missingCode, missingCode, missingCode, range.start, null,
                    "MISSING", "设备编码在MDM中不存在，按不可用阻断", null));
        }
    }

    private TpmDeviceUnavailableWindowDTO window(String sourceType, Long sourceId, String sourceCode,
                                                 String deviceCode, String deviceName, LocalDateTime start,
                                                 LocalDateTime end, String state, String reason, String factoryCode) {
        return new TpmDeviceUnavailableWindowDTO().setSourceType(sourceType).setSourceId(sourceId)
                .setSourceCode(sourceCode).setDeviceCode(deviceCode).setDeviceName(deviceName)
                .setStartTime(start).setEndTime(end).setOpenEnded(end == null).setState(state)
                .setReason(reason).setFactoryCode(factoryCode);
    }

    private Duration duration(Integer value, String unit) {
        if (value == null || value <= 0 || StrUtil.isBlank(unit)) {
            return null;
        }
        return switch (unit.toLowerCase()) {
            case "minute" -> Duration.ofMinutes(value);
            case "hour" -> Duration.ofHours(value);
            case "day" -> Duration.ofDays(value);
            default -> null;
        };
    }

    private boolean overlaps(TpmDeviceUnavailableWindowDTO window, LocalDateTime start, LocalDateTime end) {
        return window.getStartTime() != null && window.getStartTime().isBefore(end)
                && (window.getEndTime() == null || window.getEndTime().isAfter(start));
    }

    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    private record QueryRange(List<String> deviceCodes, LocalDateTime start, LocalDateTime end) {
    }
}
