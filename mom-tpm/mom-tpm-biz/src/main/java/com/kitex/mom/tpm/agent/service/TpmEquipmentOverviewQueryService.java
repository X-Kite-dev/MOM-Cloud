package com.kitex.mom.tpm.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.DeviceUsableEnum;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.tpm.agent.dto.TpmEquipmentOverviewDTO;
import com.kitex.mom.tpm.entity.TpmCheckTaskEntity;
import com.kitex.mom.tpm.entity.TpmMaintenanceTaskEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.CheckTaskStateEnum;
import com.kitex.mom.tpm.enums.CheckTypeEnum;
import com.kitex.mom.tpm.enums.MaintenanceTaskStateEnum;
import com.kitex.mom.tpm.enums.RepairStateEnum;
import com.kitex.mom.tpm.service.TpmCheckTaskService;
import com.kitex.mom.tpm.service.TpmMaintenanceTaskService;
import com.kitex.mom.tpm.service.TpmRepairTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 汇总设备当前状态以及时间窗口内的维修、保养和点巡检事实。
 * 当前状态与历史活动分别定义，避免将台账快照误当作历史开动率或 OEE。
 */
@Service
@RequiredArgsConstructor
public class TpmEquipmentOverviewQueryService {

    public static final String TOOL_CODE = "TPM_EQUIPMENT_OVERVIEW_QUERY";
    private static final String BLOCKED = "BLOCKED";

    private final TpmAgentFactoryAccessService factoryAccessService;
    private final RemoteDeviceService remoteDeviceService;
    private final TpmRepairTaskService repairTaskService;
    private final TpmMaintenanceTaskService maintenanceTaskService;
    private final TpmCheckTaskService checkTaskService;

    public TpmEquipmentOverviewDTO.Evidence query(TpmEquipmentOverviewDTO.Query query) {
        TimeRange range = validate(query);
        String factoryCode = factoryAccessService.requireRead(query.getFactoryCode());
        LocalDateTime observedAt = LocalDateTime.now();
        int limit = query.getLimit() == null ? 20 : query.getLimit();

        List<MdmDeviceEntity> devices = loadDevices(factoryCode);
        List<TpmRepairTaskEntity> repairActivity = loadRepairActivity(factoryCode, range);
        List<TpmRepairTaskEntity> openRepairs = repairTaskService.list(
                new LambdaQueryWrapper<TpmRepairTaskEntity>()
                        .eq(TpmRepairTaskEntity::getFactoryCode, factoryCode)
                        .ne(TpmRepairTaskEntity::getState, RepairStateEnum.COMPLETE.getCode()));
        List<TpmMaintenanceTaskEntity> maintenanceActivity = loadMaintenanceActivity(factoryCode, range);
        List<TpmMaintenanceTaskEntity> openMaintenance = maintenanceTaskService.list(
                new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                        .eq(TpmMaintenanceTaskEntity::getFactoryCode, factoryCode)
                        .in(TpmMaintenanceTaskEntity::getState,
                                MaintenanceTaskStateEnum.WAIT.getCode(), MaintenanceTaskStateEnum.ING.getCode()));
        List<TpmCheckTaskEntity> checkActivity = loadCheckActivity(factoryCode, range);
        List<TpmCheckTaskEntity> openOrBlockedChecks = checkTaskService.list(
                new LambdaQueryWrapper<TpmCheckTaskEntity>()
                        .eq(TpmCheckTaskEntity::getFactoryCode, factoryCode)
                        .and(wrapper -> wrapper.in(TpmCheckTaskEntity::getState,
                                        CheckTaskStateEnum.WAIT.getCode(), CheckTaskStateEnum.ASSIGNED.getCode(),
                                        CheckTaskStateEnum.ING.getCode())
                                .or().eq(TpmCheckTaskEntity::getBlockStatus, BLOCKED)));

        TpmEquipmentOverviewDTO.Summary summary = summarize(devices, repairActivity, openRepairs,
                maintenanceActivity, openMaintenance, checkActivity, openOrBlockedChecks, observedAt);
        List<TpmEquipmentOverviewDTO.ExceptionItem> collected = collectExceptions(devices, openRepairs,
                openMaintenance, checkActivity, openOrBlockedChecks, observedAt);
        collected.sort(Comparator
                .comparing(TpmEquipmentOverviewDTO.ExceptionItem::getOverdue,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TpmEquipmentOverviewDTO.ExceptionItem::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        boolean truncated = collected.size() > limit;
        List<TpmEquipmentOverviewDTO.ExceptionItem> exceptions = collected.stream().limit(limit).toList();

        return new TpmEquipmentOverviewDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("设备状态为查询时刻的MDM快照；任务活动指创建、计划/开始、完成、封锁或解除时间落入查询窗口的记录；未闭环数量按查询时刻状态统计")
                .setFactoryCode(factoryCode)
                .setRangeStart(range.start()).setRangeEnd(range.end()).setObservedAt(observedAt)
                .setSummary(summary).setExceptionItems(exceptions).setTruncated(truncated)
                .setDataGaps(List.of(
                        "当前设备台账状态不能反推出历史运行时长，本场景不计算OEE、MTBF和MTTR",
                        "保养任务缺少独立完成时间字段，历史窗口只使用创建、计划保养和实际开始时间",
                        "设备实时能耗、振动和温度等信号需接入IoT时序数据后再进行趋势和预测性维护分析"))
                .setEvidenceRefs(List.of(
                        "mom_mdm.mdm_device(factory_code,device_state,device_usable,status)",
                        "mom_tpm.tpm_repair_task(factory_code,state,submit_time,repair_time)",
                        "mom_tpm.tpm_maintenance_task(factory_code,state,maintenance_time,maintenance_start_time)",
                        "mom_tpm.tpm_check_task(factory_code,state,judge_result,block_status,handle_time)"));
    }

    private TimeRange validate(TpmEquipmentOverviewDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) {
            throw new CheckedException("TPM设备概览必须指定工厂");
        }
        LocalDateTime end = query.getEndTime() == null ? LocalDateTime.now() : query.getEndTime();
        LocalDateTime start = query.getStartTime() == null ? end.toLocalDate().atStartOfDay() : query.getStartTime();
        if (!start.isBefore(end)) {
            throw new CheckedException("TPM设备概览开始时间必须早于结束时间");
        }
        if (Duration.between(start, end).toDays() > 31) {
            throw new CheckedException("TPM设备概览单次查询时间范围不能超过31天");
        }
        return new TimeRange(start, end);
    }

    private List<MdmDeviceEntity> loadDevices(String factoryCode) {
        R<List<MdmDeviceEntity>> response = remoteDeviceService.getAllListWithScrap();
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MDM未返回当前工厂设备台账");
        }
        return response.getData().stream()
                .filter(device -> Objects.equals(factoryCode, device.getFactoryCode()))
                .toList();
    }

    private List<TpmRepairTaskEntity> loadRepairActivity(String factoryCode, TimeRange range) {
        return repairTaskService.list(new LambdaQueryWrapper<TpmRepairTaskEntity>()
                .eq(TpmRepairTaskEntity::getFactoryCode, factoryCode)
                .and(wrapper -> inRange(wrapper, TpmRepairTaskEntity::getCreateTime, range)
                        .or().ge(TpmRepairTaskEntity::getSubmitTime, range.start())
                        .lt(TpmRepairTaskEntity::getSubmitTime, range.end())
                        .or().ge(TpmRepairTaskEntity::getRepairTime, range.start())
                        .lt(TpmRepairTaskEntity::getRepairTime, range.end())));
    }

    private List<TpmMaintenanceTaskEntity> loadMaintenanceActivity(String factoryCode, TimeRange range) {
        return maintenanceTaskService.list(new LambdaQueryWrapper<TpmMaintenanceTaskEntity>()
                .eq(TpmMaintenanceTaskEntity::getFactoryCode, factoryCode)
                .and(wrapper -> inRange(wrapper, TpmMaintenanceTaskEntity::getCreateTime, range)
                        .or().ge(TpmMaintenanceTaskEntity::getMaintenanceTime, range.start())
                        .lt(TpmMaintenanceTaskEntity::getMaintenanceTime, range.end())
                        .or().ge(TpmMaintenanceTaskEntity::getMaintenanceStartTime, range.start())
                        .lt(TpmMaintenanceTaskEntity::getMaintenanceStartTime, range.end())));
    }

    private List<TpmCheckTaskEntity> loadCheckActivity(String factoryCode, TimeRange range) {
        return checkTaskService.list(new LambdaQueryWrapper<TpmCheckTaskEntity>()
                .eq(TpmCheckTaskEntity::getFactoryCode, factoryCode)
                .and(wrapper -> inRange(wrapper, TpmCheckTaskEntity::getCreateTime, range)
                        .or().ge(TpmCheckTaskEntity::getHandleTime, range.start())
                        .lt(TpmCheckTaskEntity::getHandleTime, range.end())
                        .or().ge(TpmCheckTaskEntity::getBlockTime, range.start())
                        .lt(TpmCheckTaskEntity::getBlockTime, range.end())
                        .or().ge(TpmCheckTaskEntity::getReleasedTime, range.start())
                        .lt(TpmCheckTaskEntity::getReleasedTime, range.end())));
    }

    private <T> LambdaQueryWrapper<T> inRange(LambdaQueryWrapper<T> wrapper,
                                              com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, LocalDateTime> field,
                                              TimeRange range) {
        return wrapper.ge(field, range.start()).lt(field, range.end());
    }

    private TpmEquipmentOverviewDTO.Summary summarize(
            List<MdmDeviceEntity> devices,
            List<TpmRepairTaskEntity> repairActivity,
            List<TpmRepairTaskEntity> openRepairs,
            List<TpmMaintenanceTaskEntity> maintenanceActivity,
            List<TpmMaintenanceTaskEntity> openMaintenance,
            List<TpmCheckTaskEntity> checkActivity,
            List<TpmCheckTaskEntity> openOrBlockedChecks,
            LocalDateTime observedAt) {
        List<MdmDeviceEntity> active = devices.stream()
                .filter(device -> Boolean.TRUE.equals(device.getStatus()))
                .filter(device -> !DeviceStateEnum.SCRAP.getCode().equals(device.getDeviceState()))
                .toList();
        long available = active.stream()
                .filter(device -> DeviceUsableEnum.YES.getCode().equals(device.getDeviceUsable())).count();
        BigDecimal availabilityRate = active.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(available * 100L)
                .divide(BigDecimal.valueOf(active.size()), 2, RoundingMode.HALF_UP);
        return new TpmEquipmentOverviewDTO.Summary()
                .setRegisteredDeviceCount((long) devices.size())
                .setActiveDeviceCount((long) active.size())
                .setRunningDeviceCount(countState(active, DeviceStateEnum.RUNNING.getCode()))
                .setIdleDeviceCount(countState(active, DeviceStateEnum.IDLE.getCode()))
                .setMaintenanceDeviceCount(countState(active, DeviceStateEnum.MAINTENANCE.getCode()))
                .setRepairingDeviceCount(countState(active, DeviceStateEnum.REPAIR.getCode()))
                .setPausedDeviceCount(countState(active, DeviceStateEnum.PAUSE.getCode()))
                .setUnavailableDeviceCount(active.stream()
                        .filter(device -> !DeviceUsableEnum.YES.getCode().equals(device.getDeviceUsable())).count())
                .setCurrentAvailabilityRate(availabilityRate)
                .setRepairActivityCount((long) repairActivity.size())
                .setOpenRepairCount((long) openRepairs.size())
                .setMaintenanceActivityCount((long) maintenanceActivity.size())
                .setPendingMaintenanceCount(openMaintenance.stream()
                        .filter(task -> MaintenanceTaskStateEnum.WAIT.getCode().equals(task.getState())).count())
                .setProcessingMaintenanceCount(openMaintenance.stream()
                        .filter(task -> MaintenanceTaskStateEnum.ING.getCode().equals(task.getState())).count())
                .setOverdueMaintenanceCount(openMaintenance.stream()
                        .filter(task -> task.getMaintenanceTime() != null && task.getMaintenanceTime().isBefore(observedAt)).count())
                .setCheckActivityCount((long) checkActivity.size())
                .setPendingCheckCount(openOrBlockedChecks.stream()
                        .filter(task -> CheckTaskStateEnum.WAIT.getCode().equals(task.getState())
                                || CheckTaskStateEnum.ASSIGNED.getCode().equals(task.getState())).count())
                .setProcessingCheckCount(openOrBlockedChecks.stream()
                        .filter(task -> CheckTaskStateEnum.ING.getCode().equals(task.getState())).count())
                .setFailedCheckCount(checkActivity.stream().filter(task -> "1".equals(task.getJudgeResult())).count())
                .setBlockedCheckCount(openOrBlockedChecks.stream()
                        .filter(task -> BLOCKED.equals(task.getBlockStatus())).count());
    }

    private long countState(List<MdmDeviceEntity> devices, String state) {
        return devices.stream().filter(device -> state.equals(device.getDeviceState())).count();
    }

    private List<TpmEquipmentOverviewDTO.ExceptionItem> collectExceptions(
            List<MdmDeviceEntity> devices,
            List<TpmRepairTaskEntity> openRepairs,
            List<TpmMaintenanceTaskEntity> openMaintenance,
            List<TpmCheckTaskEntity> checkActivity,
            List<TpmCheckTaskEntity> openOrBlockedChecks,
            LocalDateTime observedAt) {
        Map<String, TpmEquipmentOverviewDTO.ExceptionItem> result = new LinkedHashMap<>();
        devices.stream()
                .filter(device -> Boolean.TRUE.equals(device.getStatus()))
                .filter(device -> !DeviceStateEnum.SCRAP.getCode().equals(device.getDeviceState()))
                .filter(device -> !DeviceUsableEnum.YES.getCode().equals(device.getDeviceUsable()))
                .forEach(device -> result.put("DEVICE:" + device.getDeviceCode(),
                        new TpmEquipmentOverviewDTO.ExceptionItem()
                                .setSourceType("DEVICE_STATE").setDeviceCode(device.getDeviceCode())
                                .setDeviceName(device.getDeviceName()).setStatus(device.getDeviceState())
                                .setStatusName(DeviceStateEnum.getNameByCode(device.getDeviceState()))
                                .setExceptionType("CURRENT_UNAVAILABLE")
                                .setDescription("设备当前不可用于生产，位置：" + StrUtil.blankToDefault(device.getLocation(), "未维护"))
                                .setOccurredAt(device.getUpdateTime()).setOverdue(Boolean.FALSE)));
        openRepairs.forEach(task -> result.put("REPAIR:" + task.getId(), mapRepair(task)));
        openMaintenance.stream()
                .filter(task -> MaintenanceTaskStateEnum.ING.getCode().equals(task.getState())
                        || (task.getMaintenanceTime() != null && task.getMaintenanceTime().isBefore(observedAt)))
                .forEach(task -> result.put("MAINTENANCE:" + task.getId(), mapMaintenance(task, observedAt)));
        checkActivity.stream().filter(task -> "1".equals(task.getJudgeResult()))
                .forEach(task -> result.put("CHECK:" + task.getId(), mapCheck(task)));
        openOrBlockedChecks.stream().filter(task -> BLOCKED.equals(task.getBlockStatus()))
                .forEach(task -> result.put("CHECK:" + task.getId(), mapCheck(task)));
        return new ArrayList<>(result.values());
    }

    private TpmEquipmentOverviewDTO.ExceptionItem mapRepair(TpmRepairTaskEntity task) {
        String description = String.join("；", List.of(
                StrUtil.blankToDefault(task.getFaultClassName(), "故障类型未维护"),
                StrUtil.blankToDefault(task.getFaultDesc(), "故障描述未维护")));
        return new TpmEquipmentOverviewDTO.ExceptionItem()
                .setSourceType("REPAIR").setRepairType(task.getType())
                .setTaskId(task.getId()).setTaskCode(task.getTaskCode())
                .setDeviceCode(task.getDeviceCode()).setDeviceName(task.getDeviceName())
                .setStatus(task.getState()).setStatusName(RepairStateEnum.getNameByCode(task.getState()))
                .setExceptionType("OPEN_REPAIR").setDescription(description).setOwnerName(task.getRepairByName())
                .setOccurredAt(firstNonNull(task.getSubmitTime(), task.getCreateTime()))
                .setExpectedRecoveryTime(task.getExpectedRecoveryTime()).setOverdue(Boolean.FALSE);
    }

    private TpmEquipmentOverviewDTO.ExceptionItem mapMaintenance(TpmMaintenanceTaskEntity task,
                                                                 LocalDateTime observedAt) {
        boolean overdue = task.getMaintenanceTime() != null && task.getMaintenanceTime().isBefore(observedAt);
        return new TpmEquipmentOverviewDTO.ExceptionItem()
                .setSourceType("MAINTENANCE").setTaskId(task.getId()).setTaskCode(task.getTaskCode())
                .setDeviceCode(task.getDeviceCode()).setDeviceName(task.getDeviceName())
                .setStatus(task.getState()).setStatusName(MaintenanceTaskStateEnum.getNameByCode(task.getState()))
                .setExceptionType(overdue ? "OVERDUE_MAINTENANCE" : "MAINTENANCE_IN_PROGRESS")
                .setDescription(StrUtil.blankToDefault(task.getPlanName(), "保养计划未维护"))
                .setOwnerName(task.getHandlePersonName()).setOccurredAt(firstNonNull(
                        task.getMaintenanceStartTime(), task.getCreateTime()))
                .setPlannedAt(task.getMaintenanceTime()).setExpectedRecoveryTime(task.getExpectedRecoveryTime())
                .setOverdue(overdue);
    }

    private TpmEquipmentOverviewDTO.ExceptionItem mapCheck(TpmCheckTaskEntity task) {
        boolean blocked = BLOCKED.equals(task.getBlockStatus());
        return new TpmEquipmentOverviewDTO.ExceptionItem()
                .setSourceType("CHECK").setTaskId(task.getId()).setTaskCode(task.getTaskCode())
                .setDeviceCode(task.getDeviceCode()).setDeviceName(task.getDeviceName())
                .setStatus(task.getState()).setStatusName(CheckTaskStateEnum.getNameByCode(task.getState()))
                .setExceptionType(blocked ? "PRODUCTION_BLOCKED" : "CHECK_FAILED")
                .setDescription(StrUtil.blankToDefault(CheckTypeEnum.getNameByCode(task.getType()), "点巡检") + "不通过："
                        + StrUtil.blankToDefault(task.getBlockReason(), "未维护异常原因"))
                .setOwnerName(task.getHandlePersonName())
                .setOccurredAt(firstNonNull(task.getBlockTime(), task.getHandleTime(), task.getCreateTime()))
                .setExpectedRecoveryTime(task.getExpectedRecoveryTime()).setOverdue(Boolean.FALSE)
                .setBlockStatus(task.getBlockStatus());
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
    }
}
