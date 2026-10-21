package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.plan.dto.PlnResourceLoadQueryDTO;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingOperationMapper;
import com.kitex.mom.plan.service.PlnResourceLoadService;
import com.kitex.mom.plan.vo.PlnResourceLoadOperationVO;
import com.kitex.mom.plan.vo.PlnResourceLoadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnResourceLoadServiceImpl implements PlnResourceLoadService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_RANGE_DAYS = 93;
    private static final LocalTime DEFAULT_SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_SHIFT_END = LocalTime.of(16, 0);

    private final PlnSchedulingOperationMapper operationMapper;
    private final RemoteClassesService remoteClassesService;
    private final RemoteCalendarService remoteCalendarService;

    @Override
    public List<PlnResourceLoadVO> queryDailyLoads(PlnResourceLoadQueryDTO query) {
        QueryRange range = normalizeRange(query);
        List<PlnSchedulingOperationEntity> operations = selectOperations(query.getTaskId(), range.start, range.end);
        WorkingCapacityCalendar calendar = loadCapacityCalendar(range.start, range.end);
        Map<String, AvailabilityEvidence> evidence = new LinkedHashMap<>();
        Map<String, LoadAccumulator> loads = new LinkedHashMap<>();

        for (PlnSchedulingOperationEntity operation : operations) {
            List<ResourceRef> resources = resourcesOf(operation, evidence);
            if (resources.isEmpty()) {
                continue;
            }
            LocalDate first = later(range.start, operation.getScheduledStartTime().toLocalDate());
            LocalDate last = earlier(range.end, operation.getScheduledEndTime().minusNanos(1).toLocalDate());
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                LocalDate loadDate = date;
                long plannedMinutes = operationWorkingMinutes(operation, loadDate, calendar);
                if (plannedMinutes <= 0) {
                    continue;
                }
                for (ResourceRef resource : resources) {
                    if (StrUtil.isNotBlank(query.getResourceType())
                            && !query.getResourceType().equalsIgnoreCase(resource.type)) {
                        continue;
                    }
                    if (StrUtil.isNotBlank(query.getResourceCode())
                            && !query.getResourceCode().trim().equals(resource.code)) {
                        continue;
                    }
                    String key = resource.type + "|" + resource.code + "|" + loadDate;
                    LoadAccumulator row = loads.computeIfAbsent(key,
                            ignored -> new LoadAccumulator(resource.type, resource.code, resource.name, loadDate));
                    row.add(operation, plannedMinutes, resource.capacityUnits, resource.capacityVerified);
                }
            }
        }

        List<PlnResourceLoadVO> result = new ArrayList<>();
        for (LoadAccumulator accumulator : loads.values()) {
            List<TimeWindow> baseWindows = calendar.windows(accumulator.loadDate);
            long baseMinutes = baseWindows.stream().mapToLong(TimeWindow::minutes).sum();
            long available = baseMinutes * accumulator.capacityUnits;
            boolean capacityVerified = calendar.verified && accumulator.capacityVerified;
            String explanation = accumulator.capacityUnits > 1
                    ? "工作日历可用分钟×" + accumulator.capacityUnits + "个并行槽位"
                    : "工作日历可用分钟×1个独占资源";

            AvailabilityEvidence resourceEvidence = evidence.get(resourceKey(accumulator.resourceType,
                    accumulator.resourceCode));
            if ("EMPLOYEE".equals(accumulator.resourceType)) {
                if (resourceEvidence != null && resourceEvidence.availableTouched) {
                    available = intersectionMinutes(baseWindows, resourceEvidence.availableWindows);
                    capacityVerified = capacityVerified && resourceEvidence.verified;
                    explanation = "工作日历与员工个人在岗窗口交集";
                } else {
                    capacityVerified = false;
                    explanation = "缺少人员在岗快照，暂按公共工作日历估算";
                }
            } else if ("DEVICE".equals(accumulator.resourceType) || "TOOLING".equals(accumulator.resourceType)) {
                if (resourceEvidence != null && resourceEvidence.unavailableTouched) {
                    long unavailable = intersectionMinutes(baseWindows, resourceEvidence.unavailableWindows);
                    available = Math.max(0, baseMinutes - unavailable);
                    capacityVerified = capacityVerified && resourceEvidence.verified;
                    explanation = "工作日历扣除" + ("DEVICE".equals(accumulator.resourceType) ? "设备" : "工装")
                            + "维护/停机不可用窗口";
                } else {
                    capacityVerified = false;
                    explanation = "缺少资源可用性快照，暂按公共工作日历估算";
                }
            }
            result.add(accumulator.toVO(available, capacityVerified, explanation));
        }

        if (StrUtil.isNotBlank(query.getLoadStatus())) {
            result.removeIf(item -> !query.getLoadStatus().equalsIgnoreCase(item.getLoadStatus()));
        }
        result.sort(Comparator.comparing(PlnResourceLoadVO::getLoadDate)
                .thenComparing(PlnResourceLoadVO::getResourceType)
                .thenComparing(PlnResourceLoadVO::getUtilizationRate, Comparator.reverseOrder())
                .thenComparing(PlnResourceLoadVO::getResourceCode));
        return result;
    }

    @Override
    public List<PlnResourceLoadOperationVO> queryLoadOperations(Long taskId, String resourceType,
                                                                String resourceCode, LocalDate loadDate) {
        if (StrUtil.isBlank(resourceType) || StrUtil.isBlank(resourceCode) || loadDate == null) {
            throw new CheckedException("资源类型、资源编码和负荷日期不能为空");
        }
        return selectOperations(taskId, loadDate, loadDate).stream()
                .filter(item -> matchesResource(item, resourceType.trim().toUpperCase(Locale.ROOT), resourceCode.trim()))
                .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getScheduledStartTime)
                        .thenComparing(PlnSchedulingOperationEntity::getOperationSeq,
                                Comparator.nullsLast(Integer::compareTo)))
                .map(this::toOperationVO).toList();
    }

    private List<PlnSchedulingOperationEntity> selectOperations(Long taskId, LocalDate start, LocalDate end) {
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<PlnSchedulingOperationEntity> wrapper = new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .isNotNull(PlnSchedulingOperationEntity::getScheduledStartTime)
                .isNotNull(PlnSchedulingOperationEntity::getScheduledEndTime)
                .eq(PlnSchedulingOperationEntity::getHardBlocked, false)
                .lt(PlnSchedulingOperationEntity::getScheduledStartTime, rangeEnd)
                .gt(PlnSchedulingOperationEntity::getScheduledEndTime, rangeStart)
                .orderByAsc(PlnSchedulingOperationEntity::getScheduledStartTime);
        if (taskId == null) {
            wrapper.eq(PlnSchedulingOperationEntity::getLockStatus, true);
        } else {
            wrapper.eq(PlnSchedulingOperationEntity::getTaskId, taskId);
        }
        return operationMapper.selectList(wrapper);
    }

    private QueryRange normalizeRange(PlnResourceLoadQueryDTO query) {
        if (query == null) {
            throw new CheckedException("资源负荷查询参数不能为空");
        }
        LocalDate start = Optional.ofNullable(query.getStartDate()).orElse(LocalDate.now());
        LocalDate end = Optional.ofNullable(query.getEndDate()).orElse(start.plusDays(6));
        if (end.isBefore(start)) {
            throw new CheckedException("资源负荷结束日期不能早于开始日期");
        }
        if (Duration.between(start.atStartOfDay(), end.plusDays(1).atStartOfDay()).toDays() > MAX_RANGE_DAYS) {
            throw new CheckedException("单次资源负荷查询最多支持" + MAX_RANGE_DAYS + "天");
        }
        return new QueryRange(start, end);
    }

    private WorkingCapacityCalendar loadCapacityCalendar(LocalDate start, LocalDate end) {
        List<MdmClassesEntity> shifts = new ArrayList<>();
        boolean shiftsVerified = false;
        try {
            R<List<MdmClassesEntity>> response = remoteClassesService.listActive();
            if (response != null && ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    && CollUtil.isNotEmpty(response.getData())) {
                shifts = response.getData().stream()
                        .filter(item -> item.getStartTime() != null && item.getEndTime() != null).toList();
                shiftsVerified = !shifts.isEmpty();
            }
        } catch (Exception e) {
            log.warn("[资源负荷] 读取班次失败，使用08:00-16:00估算，原因:{}", e.getMessage());
        }
        if (shifts.isEmpty()) {
            MdmClassesEntity fallback = new MdmClassesEntity();
            fallback.setShiftCode("APS_DEFAULT_SHIFT");
            fallback.setStartTime(DEFAULT_SHIFT_START);
            fallback.setEndTime(DEFAULT_SHIFT_END);
            shifts = List.of(fallback);
        }

        Map<LocalDate, Boolean> workDays = new LinkedHashMap<>();
        boolean calendarVerified = false;
        try {
            R<List<MdmCalendarEntity>> response = remoteCalendarService.listByRange(start.minusDays(1), end);
            if (response != null && ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    && response.getData() != null) {
                response.getData().stream().filter(item -> item.getTime() != null)
                        .forEach(item -> workDays.put(item.getTime(), "0".equals(item.getStatus())));
                calendarVerified = true;
            }
        } catch (Exception e) {
            log.warn("[资源负荷] 读取工作日历失败，使用周一至周五估算，原因:{}", e.getMessage());
        }
        return new WorkingCapacityCalendar(start, end, shifts, workDays,
                shiftsVerified && calendarVerified);
    }

    private List<ResourceRef> resourcesOf(PlnSchedulingOperationEntity operation,
                                          Map<String, AvailabilityEvidence> evidence) {
        List<ResourceRef> result = new ArrayList<>();
        if (StrUtil.isNotBlank(operation.getResourceType()) && StrUtil.isNotBlank(operation.getResourceCode())) {
            String type = operation.getResourceType().trim().toUpperCase(Locale.ROOT);
            CapacityInfo capacity = "STATION".equals(type) ? stationCapacity(operation) : new CapacityInfo(1, true);
            result.add(new ResourceRef(type, operation.getResourceCode(), operation.getResourceName(),
                    capacity.units, capacity.verified));
        }
        if (StrUtil.isNotBlank(operation.getDeviceCode())) {
            String code = operation.getDeviceCode().trim();
            result.add(new ResourceRef("DEVICE", code, StrUtil.blankToDefault(operation.getDeviceName(), code), 1, true));
            captureDeviceEvidence(operation.getDeviceAvailabilitySnapshot(), code,
                    evidence.computeIfAbsent(resourceKey("DEVICE", code), ignored -> new AvailabilityEvidence()));
        }
        if (StrUtil.isNotBlank(operation.getResourceGroupCode())) {
            String code = operation.getResourceGroupCode().trim();
            result.add(new ResourceRef("RESOURCE_GROUP", code,
                    StrUtil.blankToDefault(operation.getResourceGroupName(), code),
                    Math.max(1, Optional.ofNullable(operation.getResourceGroupCapacity()).orElse(1)),
                    operation.getResourceGroupCapacity() != null && operation.getResourceGroupSlotNo() != null));
        }
        Map<String, String> employeeNames = csvNameMap(operation.getEmployeeCode(), operation.getEmployeeName());
        for (String code : csvSet(operation.getEmployeeCode())) {
            result.add(new ResourceRef("EMPLOYEE", code, employeeNames.getOrDefault(code, code), 1, true));
            captureEmployeeEvidence(operation.getWorkforceAvailabilitySnapshot(), code,
                    evidence.computeIfAbsent(resourceKey("EMPLOYEE", code), ignored -> new AvailabilityEvidence()));
        }
        Map<String, String> toolingNames = csvNameMap(operation.getToolingCodes(), operation.getToolingNames());
        for (String code : csvSet(operation.getToolingCodes())) {
            result.add(new ResourceRef("TOOLING", code, toolingNames.getOrDefault(code, code), 1, true));
            captureToolingEvidence(operation.getToolingAllocationSnapshot(), code,
                    evidence.computeIfAbsent(resourceKey("TOOLING", code), ignored -> new AvailabilityEvidence()));
        }
        return result;
    }

    private CapacityInfo stationCapacity(PlnSchedulingOperationEntity operation) {
        int maxSlot = Optional.ofNullable(operation.getResourceSlotNo()).orElse(1);
        if (StrUtil.isBlank(operation.getCandidateResourceSnapshot())) {
            return new CapacityInfo(maxSlot, false);
        }
        try {
            JsonNode candidates = OBJECT_MAPPER.readTree(operation.getCandidateResourceSnapshot());
            boolean matched = false;
            if (candidates.isArray()) {
                for (JsonNode candidate : candidates) {
                    if ("STATION".equalsIgnoreCase(candidate.path("type").asText())
                            && operation.getResourceCode().equals(candidate.path("code").asText())
                            && candidate.hasNonNull("slotNo")) {
                        matched = true;
                        maxSlot = Math.max(maxSlot, candidate.path("slotNo").asInt(1));
                    }
                }
            }
            return new CapacityInfo(maxSlot, matched);
        } catch (Exception e) {
            return new CapacityInfo(maxSlot, false);
        }
    }

    private void captureDeviceEvidence(String snapshot, String deviceCode, AvailabilityEvidence evidence) {
        evidence.unavailableTouched = true;
        if (StrUtil.isBlank(snapshot)) {
            evidence.verified = false;
            return;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshot);
            evidence.verified &= root.path("verified").asBoolean(false);
            appendWindows(root.path("unavailableWindows"), evidence.unavailableWindows, deviceCode, "deviceCode");
            if (root.path("snapshotTruncated").asBoolean(false)) {
                evidence.verified = false;
            }
        } catch (Exception e) {
            evidence.verified = false;
        }
    }

    private void captureEmployeeEvidence(String snapshot, String employeeCode, AvailabilityEvidence evidence) {
        evidence.availableTouched = true;
        if (StrUtil.isBlank(snapshot)) {
            evidence.verified = false;
            return;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshot);
            evidence.verified &= root.path("verified").asBoolean(false);
            boolean found = false;
            for (JsonNode candidate : root.path("candidates")) {
                if (employeeCode.equals(candidate.path("employeeCode").asText())) {
                    found = true;
                    appendWindows(candidate.path("availableWindows"), evidence.availableWindows, null, null);
                }
            }
            evidence.verified &= found;
        } catch (Exception e) {
            evidence.verified = false;
        }
    }

    private void captureToolingEvidence(String snapshot, String toolingCode, AvailabilityEvidence evidence) {
        evidence.unavailableTouched = true;
        if (StrUtil.isBlank(snapshot)) {
            evidence.verified = false;
            return;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshot);
            evidence.verified &= root.path("verified").asBoolean(false);
            boolean found = false;
            for (JsonNode selected : root.path("selected")) {
                if (toolingCode.equals(selected.path("toolingCode").asText())) {
                    found = true;
                    appendWindows(selected.path("unavailableWindows"), evidence.unavailableWindows, null, null);
                }
            }
            evidence.verified &= found;
        } catch (Exception e) {
            evidence.verified = false;
        }
    }

    private void appendWindows(JsonNode rows, List<TimeWindow> target, String expectedCode, String codeField) {
        if (!rows.isArray()) {
            return;
        }
        for (JsonNode row : rows) {
            if (expectedCode != null && !expectedCode.equals(row.path(codeField).asText())) {
                continue;
            }
            LocalDateTime start = parseDateTime(row.path("startTime").asText(null));
            LocalDateTime end = parseDateTime(row.path("endTime").asText(null));
            if (start != null && end != null && end.isAfter(start)) {
                target.add(new TimeWindow(start, end));
            }
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (StrUtil.isBlank(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long intersectionMinutes(List<TimeWindow> base, List<TimeWindow> constraint) {
        List<TimeWindow> mergedBase = mergeWindows(base);
        List<TimeWindow> mergedConstraint = mergeWindows(constraint);
        long result = 0;
        for (TimeWindow left : mergedBase) {
            for (TimeWindow right : mergedConstraint) {
                LocalDateTime start = left.start.isAfter(right.start) ? left.start : right.start;
                LocalDateTime end = left.end.isBefore(right.end) ? left.end : right.end;
                if (end.isAfter(start)) {
                    result += Duration.between(start, end).toMinutes();
                }
            }
        }
        return result;
    }

    /**
     * 多人执行工序按成员共同在岗窗口分摊实际占用；否则按公共班次日历分摊。
     * 这样跨午休、换班或个人例外窗口的工序不会把非工作间隔误算成负荷。
     */
    private long operationWorkingMinutes(PlnSchedulingOperationEntity operation, LocalDate date,
                                         WorkingCapacityCalendar calendar) {
        List<TimeWindow> executionWindows = new ArrayList<>(calendar.windows(date));
        Set<String> employeeCodes = csvSet(operation.getEmployeeCode());
        if (!employeeCodes.isEmpty() && StrUtil.isNotBlank(operation.getWorkforceAvailabilitySnapshot())) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(operation.getWorkforceAvailabilitySnapshot());
                if (root.path("verified").asBoolean(false)) {
                    for (String employeeCode : employeeCodes) {
                        List<TimeWindow> employeeWindows = new ArrayList<>();
                        for (JsonNode candidate : root.path("candidates")) {
                            if (employeeCode.equals(candidate.path("employeeCode").asText())) {
                                appendWindows(candidate.path("availableWindows"), employeeWindows, null, null);
                            }
                        }
                        if (employeeWindows.isEmpty()) {
                            return calendar.workingMinutes(operation.getScheduledStartTime(),
                                    operation.getScheduledEndTime(), date);
                        }
                        executionWindows = intersectWindows(executionWindows, employeeWindows);
                    }
                }
            } catch (Exception ignored) {
                return calendar.workingMinutes(operation.getScheduledStartTime(),
                        operation.getScheduledEndTime(), date);
            }
        }
        return intersectionMinutes(List.of(new TimeWindow(operation.getScheduledStartTime(),
                operation.getScheduledEndTime())), executionWindows);
    }

    private List<TimeWindow> intersectWindows(List<TimeWindow> left, List<TimeWindow> right) {
        List<TimeWindow> result = new ArrayList<>();
        for (TimeWindow a : mergeWindows(left)) {
            for (TimeWindow b : mergeWindows(right)) {
                LocalDateTime start = a.start.isAfter(b.start) ? a.start : b.start;
                LocalDateTime end = a.end.isBefore(b.end) ? a.end : b.end;
                if (end.isAfter(start)) {
                    result.add(new TimeWindow(start, end));
                }
            }
        }
        return mergeWindows(result);
    }

    private List<TimeWindow> mergeWindows(List<TimeWindow> raw) {
        List<TimeWindow> sorted = raw.stream().filter(item -> item.start != null && item.end != null
                && item.end.isAfter(item.start)).sorted(Comparator.comparing(TimeWindow::start)).toList();
        List<TimeWindow> result = new ArrayList<>();
        for (TimeWindow current : sorted) {
            if (result.isEmpty()) {
                result.add(current);
                continue;
            }
            TimeWindow last = result.get(result.size() - 1);
            if (!current.start.isAfter(last.end)) {
                result.set(result.size() - 1, new TimeWindow(last.start,
                        current.end.isAfter(last.end) ? current.end : last.end));
            } else {
                result.add(current);
            }
        }
        return result;
    }

    private boolean matchesResource(PlnSchedulingOperationEntity operation, String type, String code) {
        return switch (type) {
            case "DEVICE" -> code.equals(operation.getDeviceCode());
            case "EMPLOYEE" -> csvSet(operation.getEmployeeCode()).contains(code);
            case "TOOLING" -> csvSet(operation.getToolingCodes()).contains(code);
            case "RESOURCE_GROUP" -> code.equals(operation.getResourceGroupCode());
            default -> type.equalsIgnoreCase(operation.getResourceType()) && code.equals(operation.getResourceCode());
        };
    }

    private PlnResourceLoadOperationVO toOperationVO(PlnSchedulingOperationEntity operation) {
        PlnResourceLoadOperationVO result = new PlnResourceLoadOperationVO();
        BeanUtils.copyProperties(operation, result);
        result.setOperationId(operation.getId());
        return result;
    }

    private Map<String, String> csvNameMap(String codes, String names) {
        List<String> codeList = new ArrayList<>(csvSet(codes));
        List<String> nameList = new ArrayList<>(csvSet(names));
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < codeList.size(); i++) {
            result.put(codeList.get(i), i < nameList.size() ? nameList.get(i) : codeList.get(i));
        }
        return result;
    }

    private Set<String> csvSet(String value) {
        if (StrUtil.isBlank(value)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).forEach(result::add);
        return result;
    }

    private String resourceKey(String type, String code) {
        return type + "|" + code;
    }

    private LocalDate later(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private LocalDate earlier(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private record QueryRange(LocalDate start, LocalDate end) {
    }

    private record ResourceRef(String type, String code, String name, int capacityUnits,
                               boolean capacityVerified) {
    }

    private record CapacityInfo(int units, boolean verified) {
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end) {
        private long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    private static final class AvailabilityEvidence {
        private final List<TimeWindow> availableWindows = new ArrayList<>();
        private final List<TimeWindow> unavailableWindows = new ArrayList<>();
        private boolean verified = true;
        private boolean availableTouched;
        private boolean unavailableTouched;
    }

    private static final class LoadAccumulator {
        private final String resourceType;
        private final String resourceCode;
        private final LocalDate loadDate;
        private final Set<String> operations = new LinkedHashSet<>();
        private final Set<Long> issues = new LinkedHashSet<>();
        private final Set<Long> tasks = new LinkedHashSet<>();
        private String resourceName;
        private int capacityUnits = 1;
        private boolean capacityVerified = true;
        private long plannedMinutes;
        private LocalDateTime firstStart;
        private LocalDateTime lastEnd;

        private LoadAccumulator(String resourceType, String resourceCode, String resourceName, LocalDate loadDate) {
            this.resourceType = resourceType;
            this.resourceCode = resourceCode;
            this.resourceName = StrUtil.blankToDefault(resourceName, resourceCode);
            this.loadDate = loadDate;
        }

        private void add(PlnSchedulingOperationEntity operation, long minutes, int units, boolean verified) {
            plannedMinutes += minutes;
            capacityUnits = Math.max(capacityUnits, Math.max(1, units));
            capacityVerified &= verified;
            if (StrUtil.isNotBlank(operation.getResourceName()) && resourceCode.equals(operation.getResourceCode())) {
                resourceName = operation.getResourceName();
            }
            operations.add(operation.getId() == null
                    ? operation.getTaskId() + "|" + operation.getIssueId() + "|" + operation.getOperationSeq()
                    : String.valueOf(operation.getId()));
            if (operation.getIssueId() != null) {
                issues.add(operation.getIssueId());
            }
            if (operation.getTaskId() != null) {
                tasks.add(operation.getTaskId());
            }
            if (firstStart == null || operation.getScheduledStartTime().isBefore(firstStart)) {
                firstStart = operation.getScheduledStartTime();
            }
            if (lastEnd == null || operation.getScheduledEndTime().isAfter(lastEnd)) {
                lastEnd = operation.getScheduledEndTime();
            }
        }

        private PlnResourceLoadVO toVO(long availableMinutes, boolean verified, String explanation) {
            PlnResourceLoadVO result = new PlnResourceLoadVO();
            result.setResourceType(resourceType);
            result.setResourceCode(resourceCode);
            result.setResourceName(resourceName);
            result.setLoadDate(loadDate);
            result.setCapacityUnits(capacityUnits);
            result.setAvailableMinutes(availableMinutes);
            result.setPlannedMinutes(plannedMinutes);
            result.setIdleMinutes(Math.max(0, availableMinutes - plannedMinutes));
            result.setOverloadMinutes(Math.max(0, plannedMinutes - availableMinutes));
            BigDecimal rate = availableMinutes <= 0
                    ? (plannedMinutes > 0 ? BigDecimal.valueOf(999.99) : BigDecimal.ZERO)
                    : BigDecimal.valueOf(plannedMinutes).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(availableMinutes), 2, RoundingMode.HALF_UP);
            result.setUtilizationRate(rate);
            result.setLoadStatus(rate.compareTo(BigDecimal.valueOf(100)) > 0 ? "OVERLOAD"
                    : rate.compareTo(BigDecimal.valueOf(100)) == 0 ? "SATURATED"
                    : rate.compareTo(BigDecimal.valueOf(85)) >= 0 ? "HIGH" : "NORMAL");
            result.setCapacityVerified(verified);
            result.setCapacityExplanation(explanation + (verified ? "（已核验）" : "（估算）"));
            result.setOperationCount(operations.size());
            result.setIssueCount(issues.size());
            result.setTaskCount(tasks.size());
            result.setFirstStartTime(firstStart);
            result.setLastEndTime(lastEnd);
            return result;
        }
    }

    private final class WorkingCapacityCalendar {
        private final Map<LocalDate, List<TimeWindow>> windowsByDate = new LinkedHashMap<>();
        private final boolean verified;

        private WorkingCapacityCalendar(LocalDate start, LocalDate end, List<MdmClassesEntity> shifts,
                                        Map<LocalDate, Boolean> workDays, boolean verified) {
            this.verified = verified;
            List<TimeWindow> raw = new ArrayList<>();
            for (LocalDate shiftDate = start.minusDays(1); !shiftDate.isAfter(end); shiftDate = shiftDate.plusDays(1)) {
                boolean workDay = workDays.containsKey(shiftDate) ? workDays.get(shiftDate)
                        : shiftDate.getDayOfWeek() != DayOfWeek.SATURDAY
                        && shiftDate.getDayOfWeek() != DayOfWeek.SUNDAY;
                if (!workDay) {
                    continue;
                }
                for (MdmClassesEntity shift : shifts) {
                    LocalDateTime shiftStart = shiftDate.atTime(shift.getStartTime());
                    LocalDateTime shiftEnd = shiftDate.atTime(shift.getEndTime());
                    if (!shiftEnd.isAfter(shiftStart)) {
                        shiftEnd = shiftEnd.plusDays(1);
                    }
                    raw.add(new TimeWindow(shiftStart, shiftEnd));
                }
            }
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                LocalDateTime dayStart = date.atStartOfDay();
                LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
                List<TimeWindow> daily = new ArrayList<>();
                for (TimeWindow window : raw) {
                    LocalDateTime overlapStart = window.start.isAfter(dayStart) ? window.start : dayStart;
                    LocalDateTime overlapEnd = window.end.isBefore(dayEnd) ? window.end : dayEnd;
                    if (overlapEnd.isAfter(overlapStart)) {
                        daily.add(new TimeWindow(overlapStart, overlapEnd));
                    }
                }
                windowsByDate.put(date, mergeWindows(daily));
            }
        }

        private List<TimeWindow> windows(LocalDate date) {
            return windowsByDate.getOrDefault(date, List.of());
        }

        private long workingMinutes(LocalDateTime start, LocalDateTime end, LocalDate date) {
            long result = 0;
            for (TimeWindow window : windows(date)) {
                LocalDateTime overlapStart = start.isAfter(window.start) ? start : window.start;
                LocalDateTime overlapEnd = end.isBefore(window.end) ? end : window.end;
                if (overlapEnd.isAfter(overlapStart)) {
                    result += Duration.between(overlapStart, overlapEnd).toMinutes();
                }
            }
            return result;
        }
    }
}
