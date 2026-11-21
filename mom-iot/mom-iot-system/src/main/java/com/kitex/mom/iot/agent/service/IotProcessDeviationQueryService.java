package com.kitex.mom.iot.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.iot.agent.dto.IotProcessDeviationDTO;
import com.kitex.mom.iot.entity.IotAlarmEpisode;
import com.kitex.mom.iot.entity.IotAlarmRule;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.mapper.IotAlarmEpisodeMapper;
import com.kitex.mom.iot.mapper.IotAlarmRuleMapper;
import com.kitex.mom.iot.mapper.IotDeviceMapper;
import com.kitex.mom.iot.mapper.IotDevicePropertyMapper;
import com.kitex.mom.mes.agent.dto.MesProcessDeviationScopeDTO;
import com.kitex.mom.mes.agent.dto.MesProcessTelemetryScopeDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;

/** 以 MES 实际运行段和冻结标准为边界，逐点计算 IoT 工艺参数异常。 */
@Service
@RequiredArgsConstructor
public class IotProcessDeviationQueryService {

    public static final String TOOL_CODE = "IOT_PROCESS_DEVIATION_QUERY@1";
    private static final int MAX_WINDOWS = 40;
    private static final int MAX_SAMPLES = 3000;
    private static final int MAX_TREND_POINTS = 240;
    private static final int MAX_EPISODES = 100;

    private final IotActualFactoryAccessService factoryAccess;
    private final RemoteMesTaskService mesTaskService;
    private final IotAlarmEpisodeMapper alarmMapper;
    private final IotAlarmRuleMapper ruleMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDevicePropertyMapper propertyMapper;

    public IotProcessDeviationDTO.Evidence query(IotProcessDeviationDTO.Query query) {
        String factory = factoryAccess.require("read");
        Long tenantId = factoryAccess.tenantId();
        if (query == null || !factory.equals(query.getFactoryCode())) {
            throw new CheckedException("参数异常取证工厂与当前登录范围不一致");
        }
        IotAlarmEpisode alarm = resolveAlarm(query, tenantId, factory);
        String identifier = alarm == null ? query.getParameterIdentifier() : alarm.getIdentifier();
        String deviceCode = alarm == null ? query.getDeviceCode() : alarm.getDeviceCode();
        if (!StringUtils.hasText(identifier)) {
            throw new CheckedException("请选择实际采集参数，不能按模型猜测属性标识符");
        }
        LocalDateTime alarmTime = alarm == null ? null
                : alarm.getFirstBreachTime() == null ? alarm.getTriggeredTime() : alarm.getFirstBreachTime();
        R<MesProcessDeviationScopeDTO.Evidence> response = mesTaskService.processDeviationScope(
                new MesProcessDeviationScopeDTO.Query().setFactoryCode(factory)
                        .setSourceType(query.getSourceType()).setTaskCode(query.getTaskCode())
                        .setDeviceCode(deviceCode).setRouteNodeCode(query.getRouteNodeCode())
                        .setParameterIdentifier(identifier).setEventTime(alarmTime));
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MES未返回唯一生产节点和冻结标准，不能计算参数异常");
        }
        MesProcessDeviationScopeDTO.Evidence scope = response.getData();
        verifyScope(scope, factory, identifier, deviceCode, alarmTime);
        if ("MES_TASK".equals(query.getSourceType())
                && (!Objects.equals(query.getTaskCode(), scope.getTaskCode())
                || (StringUtils.hasText(query.getRouteNodeCode())
                && !query.getRouteNodeCode().equals(scope.getRouteNodeCode())))) {
            throw new CheckedException("返回的工单或工序节点与本次取证范围不一致");
        }
        List<IotDeviceEntity> devices = deviceMapper.selectList(new LambdaQueryWrapper<IotDeviceEntity>()
                .eq(IotDeviceEntity::getTenantId, tenantId).eq(IotDeviceEntity::getFactoryCode, factory)
                .eq(IotDeviceEntity::getDeviceCode, scope.getDeviceCode()).last("LIMIT 2"));
        if (devices.size() != 1) {
            throw new CheckedException("MES实际设备在当前IoT工厂不存在或不唯一");
        }
        IotDeviceEntity device = devices.get(0);
        if (alarm != null && !Objects.equals(device.getId(), alarm.getDeviceId())) {
            throw new CheckedException("告警设备主键与当前工厂的MES实际设备不一致");
        }

        List<MesProcessTelemetryScopeDTO.ExecutionWindow> windows = scope.getWindows().stream()
                .sorted(Comparator.comparing(MesProcessTelemetryScopeDTO.ExecutionWindow::getStartTime)).toList();
        if (windows.isEmpty() || windows.size() > MAX_WINDOWS) {
            throw new CheckedException("实际生产时间窗为空或超过单次逐点取证上限，请缩小范围");
        }
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            if (window.getStartTime() == null || window.getEndTime() == null
                    || Duration.between(window.getStartTime(), window.getEndTime()).toHours() > 24
                    || !window.getEndTime().isAfter(window.getStartTime())) {
                throw new CheckedException("生产段超过24小时或时间无效，不能产生不完整的逐点结论");
            }
            if (i > 0 && windows.get(i - 1).getEndTime().isAfter(window.getStartTime())) {
                throw new CheckedException("同设备生产段重叠，采样归属不唯一，需先修正执行时间");
            }
        }
        LocalDateTime observationStart = query.getObservationStartTime();
        if (observationStart != null && observationStart.isAfter(LocalDateTime.now().plusSeconds(5))) {
            throw new CheckedException("效果观察起点不能晚于当前时间");
        }
        List<SampleWindow> measuredWindows = windows.stream()
                .map(window -> new SampleWindow(observationStart == null
                        || window.getStartTime().isAfter(observationStart)
                        ? window.getStartTime() : observationStart, window.getEndTime(),
                        !"OPEN".equalsIgnoreCase(window.getSourceStatus())))
                .filter(window -> window.endTime().isAfter(window.startTime()))
                .toList();
        List<IotDevicePropertyEntity> rows = new ArrayList<>();
        Set<Long> firstSampleIdsByWindow = new HashSet<>();
        List<String> windowGaps = new ArrayList<>();
        if (measuredWindows.isEmpty()) {
            windowGaps.add("处置后没有新的实际RUN生产段，不能验证参数恢复效果");
        }
        boolean truncated = false;
        for (SampleWindow window : measuredWindows) {
            int remaining = MAX_SAMPLES + 1 - rows.size();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            List<IotDevicePropertyEntity> windowRows = propertyMapper.selectProcessDeviationSamples(
                    tenantId, factory, device.getId(), identifier,
                    window.startTime(), window.endTime(), remaining);
            if (windowRows.isEmpty()) {
                windowGaps.add("生产段" + window.startTime() + "至" + window.endTime() + "没有参数采样");
            } else {
                firstSampleIdsByWindow.add(windowRows.get(0).getId());
                if (Duration.between(window.startTime(), windowRows.get(0).getCollectTime()).getSeconds() > 300
                        || Duration.between(windowRows.get(windowRows.size() - 1).getCollectTime(),
                                window.endTime()).getSeconds() > 300) {
                    windowGaps.add("生产段" + window.startTime() + "至" + window.endTime()
                            + "的首尾采样覆盖不足，不能判断整段参数合格");
                }
                rows.addAll(windowRows);
            }
            if (rows.size() > MAX_SAMPLES) {
                truncated = true;
                rows = new ArrayList<>(rows.subList(0, MAX_SAMPLES));
                break;
            }
        }
        return calculate(query, scope, alarm, rows, firstSampleIdsByWindow,
                measuredWindows, windowGaps, truncated);
    }

    private IotAlarmEpisode resolveAlarm(IotProcessDeviationDTO.Query query, Long tenantId, String factory) {
        if ("MES_TASK".equals(query.getSourceType())) {
            if (!StringUtils.hasText(query.getTaskCode())) {
                throw new CheckedException("生产任务入口必须选择真实工单");
            }
            return null;
        }
        if (!"IOT_ALARM".equals(query.getSourceType()) || query.getAlarmEpisodeId() == null) {
            throw new CheckedException("告警入口必须提供真实告警记录主键");
        }
        IotAlarmEpisode alarm = alarmMapper.selectById(query.getAlarmEpisodeId());
        if (alarm == null || !Objects.equals(alarm.getTenantId(), tenantId)
                || !factory.equals(alarm.getFactoryCode()) || !StringUtils.hasText(alarm.getDeviceCode())
                || !StringUtils.hasText(alarm.getIdentifier())
                || (alarm.getFirstBreachTime() == null && alarm.getTriggeredTime() == null)) {
            throw new CheckedException("告警不存在、未绑定当前工厂或缺少可信设备采样来源");
        }
        if (StringUtils.hasText(query.getDeviceCode()) && !query.getDeviceCode().equals(alarm.getDeviceCode())) {
            throw new CheckedException("请求设备与原IoT告警不一致");
        }
        if (StringUtils.hasText(query.getParameterIdentifier())
                && !query.getParameterIdentifier().equals(alarm.getIdentifier())) {
            throw new CheckedException("请求参数与原IoT告警不一致");
        }
        IotAlarmRule rule = alarm.getRuleId() == null ? null : ruleMapper.selectById(alarm.getRuleId());
        if (rule == null || !Objects.equals(rule.getTenantId(), tenantId)
                || !factory.equals(rule.getFactoryCode())
                || !Objects.equals(rule.getDeviceId(), alarm.getDeviceId())
                || !Objects.equals(rule.getIdentifier(), alarm.getIdentifier())) {
            throw new CheckedException("原IoT告警规则与当前工厂、设备或参数不一致");
        }
        return alarm;
    }

    private void verifyScope(MesProcessDeviationScopeDTO.Evidence scope, String factory,
                             String identifier, String requestedDevice, LocalDateTime alarmTime) {
        if (!factory.equals(scope.getFactoryCode()) || !identifier.equals(scope.getParameterIdentifier())
                || !StringUtils.hasText(scope.getTaskCode()) || !StringUtils.hasText(scope.getProductCode())
                || !StringUtils.hasText(scope.getRouteNodeCode())
                || !StringUtils.hasText(scope.getDeviceCode())
                || !StringUtils.hasText(scope.getProcessParameterSnapshotFingerprint())
                || scope.getFrozenStandard() == null || Boolean.TRUE.equals(scope.getTruncated())
                || !StringUtils.hasText(scope.getFactFingerprint())
                || (StringUtils.hasText(requestedDevice) && !requestedDevice.equals(scope.getDeviceCode()))
                || (alarmTime != null && scope.getWindows().stream().noneMatch(window ->
                        !alarmTime.isBefore(window.getStartTime()) && alarmTime.isBefore(window.getEndTime())))) {
            throw new CheckedException("MES返回的生产范围与告警来源、冻结参数或当前工厂不一致");
        }
    }

    private IotProcessDeviationDTO.Evidence calculate(IotProcessDeviationDTO.Query query,
            MesProcessDeviationScopeDTO.Evidence scope, IotAlarmEpisode alarm,
            List<IotDevicePropertyEntity> rows, Set<Long> firstSampleIdsByWindow,
            List<SampleWindow> measuredWindows, List<String> windowGaps, boolean truncated) {
        var standard = scope.getFrozenStandard();
        List<String> gaps = new ArrayList<>(scope.getDataGaps() == null ? List.of() : scope.getDataGaps());
        gaps.addAll(windowGaps);
        if (rows.isEmpty()) gaps.add("实际生产段内没有归属已核验的参数采样");
        if (truncated) gaps.add("逐点样本超过" + MAX_SAMPLES + "条，指标仅为前段样本，禁止判断参数合格或效果已验证");
        BigDecimal lower = standard.getLowerLimit();
        BigDecimal upper = standard.getUpperLimit();
        if (lower == null && upper == null) gaps.add("冻结标准未定义数值上下限，只能展示采样，不计算越界");
        long good = 0, nonGood = 0, invalid = 0, outside = 0, totalSeconds = 0, longest = 0, maxGap = 0;
        BigDecimal totalArea = BigDecimal.ZERO;
        List<IotProcessDeviationDTO.Sample> samples = new ArrayList<>();
        List<IotProcessDeviationDTO.DeviationEpisode> episodes = new ArrayList<>();
        IotProcessDeviationDTO.Sample previous = null;
        IotProcessDeviationDTO.DeviationEpisode active = null;
        List<IotProcessDeviationDTO.Sample> numeric = new ArrayList<>();
        for (IotDevicePropertyEntity row : rows) {
            boolean segmentStart = firstSampleIdsByWindow.contains(row.getId());
            // 相邻 RUN 生产段即使只间隔数秒，也不能把暂停前后样本连成一次异常。
            if (segmentStart && previous != null) {
                if (active != null) episodes.add(active);
                active = null;
                previous = null;
            }
            boolean qualityGood = "GOOD".equals(row.getQualityCode());
            BigDecimal value = qualityGood ? decimal(row.getValue()) : null;
            if (qualityGood) good++; else nonGood++;
            if (qualityGood && value == null) invalid++;
            boolean out = value != null && excess(value, lower, upper).signum() > 0;
            if (out) outside++;
            var sample = new IotProcessDeviationDTO.Sample().setPropertyId(row.getId())
                    .setCollectTime(row.getCollectTime()).setNumericValue(value)
                    .setQualityCode(row.getQualityCode()).setOutOfLimit(value == null ? null : out)
                    .setSegmentStart(segmentStart);
            samples.add(sample);
            if (value != null) numeric.add(sample);
            if (previous != null) {
                long seconds = Math.max(0, Duration.between(previous.getCollectTime(), sample.getCollectTime()).getSeconds());
                maxGap = Math.max(maxGap, seconds);
                // 采样间隔超过五分钟不连续；两端必须都是 GOOD 且越界，才计入持续时间和面积。
                if (seconds > 0 && seconds <= 300 && Boolean.TRUE.equals(previous.getOutOfLimit()) && out) {
                    if (active == null) {
                        active = new IotProcessDeviationDTO.DeviationEpisode()
                                .setStartTime(previous.getCollectTime()).setFirstPropertyId(previous.getPropertyId())
                                .setDurationSeconds(0L).setArea(BigDecimal.ZERO).setSampleCount(1);
                        recordPeak(active, previous, lower, upper);
                    }
                    recordPeak(active, sample, lower, upper);
                    BigDecimal segmentArea = excess(previous.getNumericValue(), lower, upper)
                            .add(excess(value, lower, upper))
                            .multiply(BigDecimal.valueOf(seconds))
                            .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                    active.setEndTime(sample.getCollectTime()).setLastPropertyId(sample.getPropertyId())
                            .setDurationSeconds(active.getDurationSeconds() + seconds)
                            .setArea(active.getArea().add(segmentArea))
                            .setSampleCount(active.getSampleCount() + 1);
                } else if (active != null) {
                    episodes.add(active);
                    active = null;
                }
            }
            previous = sample;
        }
        if (active != null) episodes.add(active);
        for (var episode : episodes) {
            totalSeconds += episode.getDurationSeconds();
            longest = Math.max(longest, episode.getDurationSeconds());
            totalArea = totalArea.add(episode.getArea());
        }
        if (maxGap > 300) gaps.add("最大采样间隔" + maxGap + "秒超过五分钟；跨缺口不计连续异常时长");
        if (nonGood > 0 || invalid > 0) gaps.add("存在非GOOD或不可解析数值采样，已排除出连续越界计算");
        if (standard.getMinimumSampleCount() != null
                && good - invalid < standard.getMinimumSampleCount()) {
            gaps.add("有效数值采样" + (good - invalid) + "条，未达到冻结标准最少"
                    + standard.getMinimumSampleCount() + "条");
        }
        if (standard.getMaximumNonGoodRatePct() != null && !rows.isEmpty()
                && BigDecimal.valueOf(nonGood + invalid).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(rows.size()), 4, RoundingMode.HALF_UP)
                        .compareTo(standard.getMaximumNonGoodRatePct()) > 0) {
            gaps.add("非GOOD或不可解析采样比例超过冻结标准允许上限");
        }
        if (episodes.size() > MAX_EPISODES) gaps.add("异常区段超过" + MAX_EPISODES + "段，仅展示前段明细");
        List<String> refs = new ArrayList<>(scope.getEvidenceRefs() == null ? List.of() : scope.getEvidenceRefs());
        if (alarm != null) refs.add("IOT:iot_alarm_episode:" + alarm.getId());
        if (!rows.isEmpty()) refs.add("IOT:iot_device_property:" + rows.get(0).getId()
                + ".." + rows.get(rows.size() - 1).getId());
        String fingerprint = fingerprint(scope.getFactFingerprint(), alarm, rows, query.getObservationStartTime());
        long measuredSeconds = measuredWindows.stream()
                .mapToLong(window -> Duration.between(window.startTime(), window.endTime()).getSeconds()).sum();
        LocalDateTime measurementStart = measuredWindows.isEmpty()
                ? query.getObservationStartTime() : measuredWindows.get(0).startTime();
        LocalDateTime measurementEnd = measuredWindows.isEmpty()
                ? null : measuredWindows.get(measuredWindows.size() - 1).endTime();
        boolean windowsClosed = !measuredWindows.isEmpty()
                && measuredWindows.stream().allMatch(SampleWindow::closed);
        return new IotProcessDeviationDTO.Evidence()
                .setToolCode(TOOL_CODE).setFactoryCode(scope.getFactoryCode()).setSourceType(query.getSourceType())
                .setAlarmEpisodeId(alarm == null ? null : alarm.getId())
                .setAlarmStatus(alarm == null ? null : alarm.getStatus())
                .setTaskCode(scope.getTaskCode()).setProductCode(scope.getProductCode())
                .setRouteNodeCode(scope.getRouteNodeCode())
                .setProcessCode(scope.getProcessCode()).setDeviceCode(scope.getDeviceCode())
                .setParameterIdentifier(scope.getParameterIdentifier()).setParameterName(standard.getParameterName())
                .setUnit(standard.getUnit()).setLowerLimit(lower).setUpperLimit(upper)
                .setContinuousDeviationSeconds(standard.getContinuousDeviationSeconds())
                .setProcessParameterStandardNo(scope.getProcessParameterStandardNo())
                .setProcessParameterStandardVersion(scope.getProcessParameterStandardVersion())
                .setProcessParameterSnapshotFingerprint(scope.getProcessParameterSnapshotFingerprint())
                .setMesScopeFingerprint(scope.getFactFingerprint()).setObservedAt(LocalDateTime.now())
                .setMeasurementStartTime(measurementStart).setMeasurementEndTime(measurementEnd)
                .setMeasurementWindowSeconds(measuredSeconds).setMeasurementWindowsClosed(windowsClosed)
                .setTotalSampleCount((long) rows.size()).setGoodSampleCount(good)
                .setNonGoodSampleCount(nonGood).setInvalidNumericCount(invalid)
                .setOutOfLimitSampleCount(outside).setCumulativeOutOfLimitSeconds(totalSeconds)
                .setLongestOutOfLimitSeconds(longest).setDeviationEpisodeCount(episodes.size())
                .setDeviationArea(totalArea).setTrendSlopePerHour(slope(numeric))
                .setMaximumSampleGapSeconds(maxGap)
                .setEvidenceStatus(gaps.isEmpty() && lower != null && upper != null && !rows.isEmpty()
                        ? "COMPLETE" : "PARTIAL")
                .setTrend(thin(samples)).setEpisodes(episodes.stream().limit(MAX_EPISODES).toList())
                .setDataGaps(gaps.stream().distinct().limit(100).toList())
                .setEvidenceRefs(refs.stream().distinct().limit(300).toList())
                .setFactFingerprint(fingerprint).setTruncated(truncated || episodes.size() > MAX_EPISODES);
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.length() > 64 || !raw.trim().matches("[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)")) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal excess(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        if (value == null) return BigDecimal.ZERO;
        if (lower != null && value.compareTo(lower) < 0) return lower.subtract(value);
        if (upper != null && value.compareTo(upper) > 0) return value.subtract(upper);
        return BigDecimal.ZERO;
    }

    private BigDecimal slope(List<IotProcessDeviationDTO.Sample> samples) {
        if (samples.size() < 2) return null;
        LocalDateTime first = samples.get(0).getCollectTime();
        BigDecimal sx = BigDecimal.ZERO, sy = BigDecimal.ZERO, sxx = BigDecimal.ZERO, sxy = BigDecimal.ZERO;
        for (var sample : samples) {
            BigDecimal x = BigDecimal.valueOf(Duration.between(first, sample.getCollectTime()).getSeconds())
                    .divide(BigDecimal.valueOf(3600), MathContext.DECIMAL64);
            sx = sx.add(x); sy = sy.add(sample.getNumericValue());
            sxx = sxx.add(x.multiply(x)); sxy = sxy.add(x.multiply(sample.getNumericValue()));
        }
        BigDecimal n = BigDecimal.valueOf(samples.size());
        BigDecimal denominator = n.multiply(sxx).subtract(sx.multiply(sx));
        return denominator.signum() == 0 ? null
                : n.multiply(sxy).subtract(sx.multiply(sy))
                .divide(denominator, 6, RoundingMode.HALF_UP);
    }

    /** 逐点更新区段最大越界，偏差相同时保留最早的实测时间。 */
    private void recordPeak(IotProcessDeviationDTO.DeviationEpisode episode,
                            IotProcessDeviationDTO.Sample sample, BigDecimal lower, BigDecimal upper) {
        BigDecimal value = sample.getNumericValue();
        BigDecimal distance = excess(value, lower, upper);
        if (episode.getPeakExcess() == null || distance.compareTo(episode.getPeakExcess()) > 0) {
            episode.setPeakValue(value)
                    .setPeakTime(sample.getCollectTime())
                    .setPeakExcess(distance)
                    .setPeakSide(lower != null && value.compareTo(lower) < 0 ? "LOW" : "HIGH");
        }
    }

    /** 趋势摘要保留首尾及每组的最低、最高和一个无效点。指标与区段明细始终用分组前的采样计算。 */
    private List<IotProcessDeviationDTO.Sample> thin(List<IotProcessDeviationDTO.Sample> samples) {
        int size = samples.size();
        if (size <= MAX_TREND_POINTS) {
            return samples;
        }
        int groups = (MAX_TREND_POINTS - 2) / 3;
        Set<Integer> selected = new TreeSet<>();
        selected.add(0);
        selected.add(size - 1);
        for (int group = 0; group < groups; group++) {
            int start = group * size / groups;
            int end = (group + 1) * size / groups;
            int lowest = -1;
            int highest = -1;
            int invalid = -1;
            for (int i = start; i < end; i++) {
                BigDecimal value = samples.get(i).getNumericValue();
                if (value == null) {
                    if (invalid < 0) {
                        invalid = i;
                    }
                    continue;
                }
                if (lowest < 0 || value.compareTo(samples.get(lowest).getNumericValue()) < 0) {
                    lowest = i;
                }
                if (highest < 0 || value.compareTo(samples.get(highest).getNumericValue()) >= 0) {
                    highest = i;
                }
            }
            if (lowest >= 0) {
                selected.add(lowest);
                selected.add(highest);
            }
            if (invalid >= 0) {
                selected.add(invalid);
            }
        }
        return selected.stream().map(samples::get).toList();
    }

    private String fingerprint(String scopeFingerprint, IotAlarmEpisode alarm,
                               List<IotDevicePropertyEntity> rows, LocalDateTime observationStart) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 不改变既有整段取证的指纹口径；只为处置后裁剪窗口增加起点维度。
            String source = scopeFingerprint + "|" + (alarm == null ? "" : alarm.getId());
            digest.update((observationStart == null ? source : source + "|" + observationStart)
                    .getBytes(StandardCharsets.UTF_8));
            for (var row : rows) {
                digest.update(("|" + row.getId() + ":" + row.getCollectTime() + ":" + row.getQualityCode()
                        + ":" + row.getValue()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算IoT参数证据指纹", exception);
        }
    }

    /** 处置后观察仅裁剪 MES 已核验的实际生产段，不创造新的生产时间窗。 */
    private record SampleWindow(LocalDateTime startTime, LocalDateTime endTime, boolean closed) {
    }
}
