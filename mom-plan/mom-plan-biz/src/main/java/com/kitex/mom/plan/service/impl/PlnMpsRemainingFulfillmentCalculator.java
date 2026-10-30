package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 剩余责任的纯精确数量/日期计算，不访问数据库、不取用户上下文、不发布计划。
 *
 * <p>核验基线逐报工事实而非只比较累计。基线后正常推进允许兑现，原报工被纠正或
 * 重新归属必须重核；未知或补录不释放产能。FIFO仅分配逻辑责任，不改SN、实物或客户交期。</p>
 */
@Component
public class PlnMpsRemainingFulfillmentCalculator {
    private static final Set<String> MODES = Set.of("ONE_CODE", "QUANTITY", "TRANSFER_CARD",
            "PACKAGE_ONE_CODE", "PACKAGE_QUANTITY");

    /**
     * 调用方先校验已确认观察的组织、权限、独立核验与新鲜度，本方法不替代这些门禁。
     */
    public PlnMpsRemainingCommitmentDTO.Baseline baseline(PlnMpsExecutionDTO.Bucket bucket,
                                                          LocalDateTime observedAt) {
        require(observedAt != null, "基线须使用原观察真实采集时刻");
        var current = current(bucket);
        BigDecimal original = quantity(bucket.getPlanQuantity());
        require(Boolean.TRUE.equals(bucket.getStarted()) && current.total().compareTo(original) < 0,
                "剩余重承诺仅适用于已有执行事实且来源有效报工尚未满原量的活动桶");
        List<PlnMpsRemainingCommitmentDTO.SourceIdentity> sources = new ArrayList<>();
        for (var link : bucket.getLinks()) {
            LocalDateTime start = time(link.getProductionSnapshotStartedAt());
            LocalDateTime end = time(link.getProductionObservedAt());
            require(start != null && end != null && !end.isBefore(start) && !end.isAfter(observedAt),
                    "MES采集边界缺失、时钟不一致或不属于原观察，不能猜测补录窗口");
            sources.add(identity(link).setBaselineEventCutoff(start.toString()));
        }
        for (var event : current.events().values()) {
            LocalDateTime reportTime = time(event.getReportTime());
            require(reportTime == null || !reportTime.isAfter(observedAt), "基线报工时刻位于采集之后，须核清原事实");
        }
        return new PlnMpsRemainingCommitmentDTO.Baseline().setLineId(bucket.getLineId())
                .setBucketKey(bucket.getBucketKey()).setDemandNo(bucket.getDemandNo())
                .setRootIssueId(bucket.getRootIssueId()).setRootIssueNo(bucket.getRootIssueNo())
                .setProductCode(bucket.getProductCode()).setProductUnit(bucket.getProductUnit().trim())
                .setOriginalQuantity(decimal(original)).setBaselineQuantity(decimal(current.total()))
                .setRemainingQuantity(decimal(original.subtract(current.total())))
                .setPlannedDate(bucket.getPlannedDate()).setSourceDueDate(bucket.getSourceDueDate())
                .setCapacityResourceCode(bucket.getCapacityResourceCode()).setObservedAt(observedAt.toString())
                .setSources(sources).setEvents(current.events().values().stream()
                        .map(event -> BeanUtil.toBean(event, PlnMpsExecutionDTO.ProductionEvent.class)).toList());
    }

    /**
     * 失效返回明确缺口，不返回旧成功的剩余量；日期和客户交期从不回写原账。
     */
    public PlnMpsRemainingCommitmentDTO.Evaluation evaluate(PlnMpsRemainingCommitmentDTO.Baseline baseline,
                                                            PlnMpsExecutionDTO.Bucket bucket, List<PlnMpsRemainingCommitmentDTO.Schedule> schedules,
                                                            LocalDateTime now, int toleranceMinutes) {
        require(baseline != null && now != null && toleranceMinutes >= 0 && toleranceMinutes <= 1440,
                "原基线、计算时刻及0至1440分钟容差必填");
        List<PlnMpsRemainingCommitmentDTO.Schedule> ordered = schedules(schedules, quantity(baseline.getRemainingQuantity()));
        var progress = progress(baseline, bucket, now);
        if (progress.evaluation().getGaps().isEmpty()) {
            progress.evaluation().setBuckets(fulfillment(ordered, progress.fresh(), now, toleranceMinutes));
        }
        return progress.evaluation();
    }

    /**
     * 尚未编制新日桶时只核清当前推进，不虚构日期或替用户创建分期。
     */
    public PlnMpsRemainingCommitmentDTO.Evaluation verifyProgress(PlnMpsRemainingCommitmentDTO.Baseline baseline,
                                                                  PlnMpsExecutionDTO.Bucket bucket, LocalDateTime now) {
        require(baseline != null && now != null, "原基线与核清时刻必填");
        return progress(baseline, bucket, now).evaluation();
    }

    /**
     * 原桶逾期负荷只取当前可信未完工量；已完工历史不再占未来产能。
     * 此处只证明完整来源有效数量，不把登记时间解释为物理完工时间。
     * 未下达的零事实根计划须同样经过完整组织、来源及数量核查。
     */
    public BigDecimal currentRemainingQuantity(PlnMpsExecutionDTO.Bucket bucket) {
        if (bucket != null && Boolean.TRUE.equals(bucket.getActive()) && "0".equals(bucket.getRootStatus())
                && Boolean.FALSE.equals(bucket.getStarted()) && bucket.getLinks() != null && bucket.getLinks().isEmpty()
                && bucket.getGaps() != null && bucket.getGaps().isEmpty()
                && bucket.getQuantityGaps() != null && bucket.getQuantityGaps().isEmpty()) {
            id(bucket.getLineId());
            id(bucket.getRootIssueId());
            date(bucket.getPlannedDate());
            date(bucket.getSourceDueDate());
            require(StrUtil.isNotBlank(bucket.getBucketKey()) && StrUtil.isNotBlank(bucket.getRootIssueNo())
                            && StrUtil.isNotBlank(bucket.getProductCode()) && StrUtil.isNotBlank(bucket.getProductUnit())
                            && quantity(bucket.getCompletedQuantity()).signum() == 0,
                    "未下达原桶缺完整身份、单位或零产出依据");
            BigDecimal original = quantity(bucket.getPlanQuantity());
            require(original.signum() > 0, "原计划量必须为正");
            return original;
        }
        var current = current(bucket);
        return quantity(bucket.getPlanQuantity()).subtract(current.total());
    }

    private Progress progress(PlnMpsRemainingCommitmentDTO.Baseline baseline,
                              PlnMpsExecutionDTO.Bucket bucket, LocalDateTime now) {
        var result = new PlnMpsRemainingCommitmentDTO.Evaluation();
        if (!sameBucket(baseline, bucket))
            return failed(result, "SOURCE_STALE", "原桶、根计划、数量、单位或原交期身份已变化");
        Current current;
        try {
            current = current(bucket);
        } catch (CheckedException exception) {
            return failed(result, "DATA_GAP", exception.getMessage());
        }
        result.setCurrentQuantity(decimal(current.total()));
        if (baseline.getSources() == null || baseline.getSources().size() != bucket.getLinks().size()) {
            return failed(result, "SOURCE_STALE", "原基线缺完整来源身份或来源数量已变化");
        }
        Map<String, PlnMpsRemainingCommitmentDTO.SourceIdentity> frozenSources = new HashMap<>();
        for (var source : baseline.getSources()) {
            if (source == null || frozenSources.put(sourceKey(source.getTaskId(), source.getAllocationId()), source) != null) {
                return failed(result, "DATA_GAP", "原基线来源身份缺失或重复");
            }
        }
        for (var link : bucket.getLinks()) {
            var frozen = frozenSources.get(sourceKey(link.getTaskId(), link.getAllocationId()));
            if (!sameSource(frozen, identity(link)))
                return failed(result, "SOURCE_STALE", "原物理来源或唯一末序映射已变化");
        }
        Map<String, PlnMpsExecutionDTO.ProductionEvent> frozenEvents = new HashMap<>();
        BigDecimal frozenTotal = BigDecimal.ZERO;
        require(baseline.getEvents() != null, "原基线缺少完整事件证明，不得从累计补造");
        for (var frozen : baseline.getEvents()) {
            String key = eventKey(frozen);
            require(frozenEvents.put(key, frozen) == null, "原基线存在重复报工贡献");
            frozenTotal = frozenTotal.add(quantity(frozen.getQuantity()));
            var actual = current.events().get(key);
            if (actual == null || !sameEvent(frozen, actual)) {
                return failed(result, "BASELINE_STALE", "原基线报工" + frozen.getReportId() + "被冲销、纠正或其来源/标识/时间依据已变化");
            }
        }
        BigDecimal base = quantity(baseline.getBaselineQuantity());
        BigDecimal original = quantity(baseline.getOriginalQuantity());
        require(frozenTotal.compareTo(base) == 0 && base.add(quantity(baseline.getRemainingQuantity())).compareTo(original) == 0,
                "原基线Q/B/R或完整事件贡献不守恒，禁止以当前数覆盖历史");
        if (current.total().compareTo(base) < 0 || current.total().compareTo(original) > 0) {
            return failed(result, "DATA_GAP", "当前来源有效报工低于基线或超过原计划量");
        }
        List<PlnMpsExecutionDTO.ProductionEvent> fresh = new ArrayList<>();
        for (var event : current.events().values()) {
            if (frozenEvents.containsKey(eventKey(event))) continue;
            var source = frozenSources.get(sourceKey(event.getTaskId(), event.getAllocationId()));
            LocalDateTime cutoff = source == null ? null : time(source.getBaselineEventCutoff());
            LocalDateTime occurred = eventTime(event);
            if (cutoff == null || occurred == null || occurred.isAfter(now)) {
                return failed(result, "DATA_GAP", "新增报工" + event.getReportId() + "缺可信时间/弱网现场依据，不能释放旧责任");
            }
            if (!occurred.isAfter(cutoff)) {
                return failed(result, "BACKFILLED_BASELINE", "新增报工" + event.getReportId() + "早于或等于原MES采集边界，须重采基线");
            }
            fresh.add(event);
        }
        BigDecimal freshTotal = fresh.stream().map(event -> quantity(event.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(freshTotal.compareTo(current.total().subtract(base)) == 0, "基线后完整贡献与当前推进量不守恒");
        fresh.sort(Comparator.comparing(this::eventTime).thenComparing(event -> Long.parseLong(event.getReportId()))
                .thenComparing(event -> sourceKey(event.getTaskId(), event.getAllocationId())));
        result.setRemainingQuantity(decimal(original.subtract(current.total())))
                .setResult(current.total().compareTo(original) == 0 ? "FULFILLED" : "ACTIVE");
        return new Progress(result, fresh);
    }

    /**
     * 完整来源范围、每来源贡献及原量分别核对，合批物理量不参与累计。
     */
    private Current current(PlnMpsExecutionDTO.Bucket bucket) {
        require(bucket != null && Boolean.TRUE.equals(bucket.getActive()) && bucket.getGaps() != null
                        && bucket.getGaps().isEmpty() && bucket.getLinks() != null && !bucket.getLinks().isEmpty()
                        && bucket.getQuantityGaps() != null && bucket.getQuantityGaps().isEmpty()
                        && bucket.getLinks().size() <= 5000 && StrUtil.isNotBlank(bucket.getBucketKey())
                        && StrUtil.isNotBlank(bucket.getRootIssueNo()) && StrUtil.isNotBlank(bucket.getProductCode())
                        && StrUtil.isNotBlank(bucket.getProductUnit()),
                "原桶缺活动身份、完整根计划/MES来源或权威单位，不能猜测来源与单位");
        id(bucket.getLineId());
        id(bucket.getRootIssueId());
        date(bucket.getPlannedDate());
        date(bucket.getSourceDueDate());
        BigDecimal original = quantity(bucket.getPlanQuantity());
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        Set<String> sourceKeys = new HashSet<>();
        Map<String, PlnMpsExecutionDTO.ProductionEvent> events = new LinkedHashMap<>();
        for (var link : bucket.getLinks()) {
            require(link != null && Boolean.TRUE.equals(link.getProductionEventsComplete())
                            && link.getProductionEventGaps() != null && link.getProductionEventGaps().isEmpty()
                            && link.getProductionEvents() != null && Objects.equals(link.getProductCode(), bucket.getProductCode())
                            && hashValue(link.getSourceFingerprint()) && link.getProductionEvents().size() <= 20000,
                    "完整类型化产出依据缺失或超限；旧观察不能补造新版事件");
            id(link.getTaskId());
            require(Set.of("DIRECT_PLAN", "APS_BATCH_ALLOCATION").contains(StrUtil.blankToDefault(link.getRelationType(), "UNKNOWN")),
                    "来源类型不可核验");
            if ("APS_BATCH_ALLOCATION".equals(link.getRelationType())) {
                id(link.getAllocationId());
                require(Objects.equals(link.getSourceIssueId(), bucket.getRootIssueId()), "APS来源根计划与原桶不一致");
            } else {
                require(link.getAllocationId() == null && link.getSourceIssueId() == null, "直接来源不能附带另一APS根计划");
            }
            require(sourceKeys.add(sourceKey(link.getTaskId(), link.getAllocationId())), "同一物理来源重复，禁止重复累计");
            BigDecimal sourceTotal = BigDecimal.ZERO;
            for (var event : link.getProductionEvents()) {
                require(event != null && Objects.equals(event.getTaskId(), link.getTaskId())
                                && Objects.equals(event.getAllocationId(), link.getAllocationId())
                                && Objects.equals(event.getSourceFingerprint(), link.getSourceFingerprint())
                                && hashValue(event.getFactFingerprint()) && hashValue(event.getProvenanceFingerprint())
                                && MODES.contains(StrUtil.blankToDefault(event.getReportMode(), "UNKNOWN"))
                                && StrUtil.isNotBlank(event.getReportUnit())
                                && Objects.equals(event.getReportUnit().trim(), bucket.getProductUnit().trim()),
                        "产出事件来源/模式/单位/指纹不一致，不能做无依据换算");
                id(event.getReportId());
                BigDecimal contribution = quantity(event.getQuantity());
                require(contribution.signum() > 0 && contribution.compareTo(quantity(event.getPhysicalEffectiveQuantity())) <= 0,
                        "来源事件须为正贡献且不得超过原报工物理有效量");
                require(events.put(eventKey(event), event) == null && events.size() <= 100000,
                        "完整事件重复或超过100000条，禁止截断");
                sourceTotal = sourceTotal.add(contribution);
            }
            BigDecimal sourceQuantity = quantity(link.getSourceQuantity());
            require(sourceQuantity.signum() > 0 && sourceTotal.compareTo(quantity(link.getCompletedQuantity())) == 0
                            && sourceTotal.compareTo(sourceQuantity) <= 0,
                    "逐来源事件/完成量/原分配量不守恒");
            allocated = allocated.add(sourceQuantity);
            total = total.add(sourceTotal);
        }
        require(original.signum() > 0 && allocated.compareTo(original) == 0
                        && total.compareTo(quantity(bucket.getCompletedQuantity())) == 0 && total.compareTo(original) <= 0,
                "原MPS量、全部来源分配及当前有效报工量不守恒");
        return new Current(events, total);
    }

    private List<PlnMpsRemainingCommitmentDTO.Schedule> schedules(
            List<PlnMpsRemainingCommitmentDTO.Schedule> schedules, BigDecimal expected) {
        require(schedules != null && !schedules.isEmpty() && schedules.size() <= 100 && expected.signum() > 0,
                "须提供1至100个完整正量日桶");
        Set<LocalDate> dates = new HashSet<>();
        Set<Integer> numbers = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var item : schedules) {
            require(item != null && item.getBucketNo() != null && item.getBucketNo() > 0
                            && item.getBucketNo() <= schedules.size() && numbers.add(item.getBucketNo())
                            && dates.add(date(item.getPromisedDate())) && quantity(item.getQuantity()).signum() > 0,
                    "日桶日期/连续序号/正数量缺失或重复");
            total = total.add(quantity(item.getQuantity()));
        }
        require(total.compareTo(expected) == 0, "全部内部日桶正数量必须严格等于原R，不舍入或补量");
        var ordered = schedules.stream().sorted(Comparator.comparing(PlnMpsRemainingCommitmentDTO.Schedule::getPromisedDate)).toList();
        for (int index = 0; index < ordered.size(); index++) {
            require(ordered.get(index).getBucketNo() == index + 1, "日桶须按承诺日期从1连续编号");
        }
        return ordered;
    }

    private List<PlnMpsRemainingCommitmentDTO.Fulfillment> fulfillment(
            List<PlnMpsRemainingCommitmentDTO.Schedule> schedules,
            List<PlnMpsExecutionDTO.ProductionEvent> events, LocalDateTime now, int tolerance) {
        List<PlnMpsRemainingCommitmentDTO.Fulfillment> result = new ArrayList<>();
        for (var schedule : schedules) {
            result.add(new PlnMpsRemainingCommitmentDTO.Fulfillment().setBucketNo(schedule.getBucketNo())
                    .setPromisedDate(schedule.getPromisedDate()).setPromisedQuantity(decimal(quantity(schedule.getQuantity())))
                    .setFulfilledQuantity("0").setRemainingQuantity(decimal(quantity(schedule.getQuantity())))
                    .setTimeBasis("NONE"));
        }
        int index = 0;
        for (var event : events) {
            BigDecimal quantityLeft = quantity(event.getQuantity());
            while (quantityLeft.signum() > 0) {
                require(index < result.size(), "新增贡献超过原R，不允许填充虚构日桶");
                var row = result.get(index);
                BigDecimal remaining = quantity(row.getRemainingQuantity());
                BigDecimal share = remaining.min(quantityLeft);
                row.getShares().add(new PlnMpsRemainingCommitmentDTO.Share().setReportId(event.getReportId())
                        .setTaskId(event.getTaskId()).setAllocationId(event.getAllocationId()).setQuantity(decimal(share)));
                row.setFulfilledQuantity(decimal(quantity(row.getFulfilledQuantity()).add(share)))
                        .setRemainingQuantity(decimal(remaining.subtract(share)));
                row.setTimeBasis("REPORT_REGISTERED".equals(row.getTimeBasis()) || "REPORT_REGISTERED".equals(event.getTimeBasis())
                        ? "REPORT_REGISTERED" : "DEVICE_EVENT");
                quantityLeft = quantityLeft.subtract(share);
                if (quantity(row.getRemainingQuantity()).signum() == 0) {
                    row.setReachedAt(eventTime(event).toString());
                    index++;
                }
            }
        }
        for (var row : result) {
            LocalDateTime deadline = date(row.getPromisedDate()).plusDays(1).atStartOfDay().plusMinutes(tolerance);
            if (row.getReachedAt() != null) {
                boolean late = !time(row.getReachedAt()).isBefore(deadline);
                row.setStatus("REPORT_REGISTERED".equals(row.getTimeBasis())
                        ? late ? "REGISTERED_LATE" : "REGISTERED_ON_TIME" : late ? "LATE" : "ON_TIME");
            } else {
                row.setStatus(now.isBefore(deadline) ? "PENDING" : "OVERDUE");
            }
        }
        return result;
    }

    private PlnMpsRemainingCommitmentDTO.SourceIdentity identity(PlnMpsExecutionDTO.SourceLink link) {
        return new PlnMpsRemainingCommitmentDTO.SourceIdentity().setTaskId(link.getTaskId())
                .setRelationType(link.getRelationType()).setAllocationId(link.getAllocationId())
                .setSourceIssueId(link.getSourceIssueId()).setProductCode(link.getProductCode())
                .setSourceQuantity(decimal(quantity(link.getSourceQuantity()))).setSourceFingerprint(link.getSourceFingerprint());
    }

    private boolean sameBucket(PlnMpsRemainingCommitmentDTO.Baseline baseline, PlnMpsExecutionDTO.Bucket bucket) {
        return bucket != null && Boolean.TRUE.equals(bucket.getActive())
                && Objects.equals(baseline.getLineId(), bucket.getLineId()) && Objects.equals(baseline.getBucketKey(), bucket.getBucketKey())
                && Objects.equals(baseline.getDemandNo(), bucket.getDemandNo())
                && Objects.equals(baseline.getRootIssueId(), bucket.getRootIssueId()) && Objects.equals(baseline.getRootIssueNo(), bucket.getRootIssueNo())
                && Objects.equals(baseline.getProductCode(), bucket.getProductCode())
                && Objects.equals(baseline.getProductUnit(), StrUtil.trim(bucket.getProductUnit()))
                && Objects.equals(baseline.getOriginalQuantity(), bucket.getPlanQuantity())
                && Objects.equals(baseline.getPlannedDate(), bucket.getPlannedDate())
                && Objects.equals(baseline.getSourceDueDate(), bucket.getSourceDueDate())
                && Objects.equals(baseline.getCapacityResourceCode(), bucket.getCapacityResourceCode());
    }

    private boolean sameSource(PlnMpsRemainingCommitmentDTO.SourceIdentity frozen,
                               PlnMpsRemainingCommitmentDTO.SourceIdentity current) {
        return frozen != null && Objects.equals(frozen.getTaskId(), current.getTaskId())
                && Objects.equals(frozen.getRelationType(), current.getRelationType())
                && Objects.equals(frozen.getAllocationId(), current.getAllocationId())
                && Objects.equals(frozen.getSourceIssueId(), current.getSourceIssueId())
                && Objects.equals(frozen.getProductCode(), current.getProductCode())
                && Objects.equals(frozen.getSourceQuantity(), current.getSourceQuantity())
                && Objects.equals(frozen.getSourceFingerprint(), current.getSourceFingerprint());
    }

    private boolean sameEvent(PlnMpsExecutionDTO.ProductionEvent frozen, PlnMpsExecutionDTO.ProductionEvent current) {
        return Objects.equals(frozen.getQuantity(), current.getQuantity())
                && Objects.equals(frozen.getFactFingerprint(), current.getFactFingerprint())
                && Objects.equals(frozen.getSourceFingerprint(), current.getSourceFingerprint())
                && Objects.equals(frozen.getProvenanceFingerprint(), current.getProvenanceFingerprint())
                && Objects.equals(frozen.getTimeBasis(), current.getTimeBasis())
                && Objects.equals(frozen.getOfflineCapturedAt(), current.getOfflineCapturedAt());
    }

    private LocalDateTime eventTime(PlnMpsExecutionDTO.ProductionEvent event) {
        if ("DEVICE_EVENT".equals(event.getTimeBasis())) {
            var device = time(event.getSourceEventTime());
            return StrUtil.isNotBlank(event.getSourceEventId()) && Objects.equals(device, time(event.getReportTime())) ? device : null;
        }
        return "REPORT_REGISTERED".equals(event.getTimeBasis()) ? time(event.getReportTime()) : null;
    }

    private String eventKey(PlnMpsExecutionDTO.ProductionEvent event) {
        require(event != null, "基线事件缺失");
        id(event.getReportId());
        return sourceKey(event.getTaskId(), event.getAllocationId()) + ":" + event.getReportId();
    }

    private String sourceKey(String taskId, String allocationId) {
        id(taskId);
        if (allocationId != null) id(allocationId);
        return taskId + ":" + Objects.toString(allocationId, "DIRECT");
    }

    private void id(String value) {
        try {
            require(value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0, "主键须为精确有效bigint文本");
        } catch (NumberFormatException exception) {
            throw new CheckedException("主键超出bigint范围，不能转浮点或截断");
        }
    }

    private BigDecimal quantity(String value) {
        require(value != null && value.matches("[0-9]{1,22}(\\.[0-9]{1,8})?"), "数量须为非负精确十进制文本且最多8位小数");
        return new BigDecimal(value);
    }

    private LocalDate date(String value) {
        try {
            require(value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "承诺日期须为ISO日期");
            return LocalDate.parse(value);
        } catch (java.time.DateTimeException exception) {
            throw new CheckedException("承诺日期不可解析");
        }
    }

    private LocalDateTime time(String value) {
        try {
            return value == null ? null : LocalDateTime.parse(value);
        } catch (java.time.DateTimeException exception) {
            return null;
        }
    }

    private boolean hashValue(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private Progress failed(PlnMpsRemainingCommitmentDTO.Evaluation result, String status, String message) {
        result.setResult(status).setRemainingQuantity(null).setBuckets(List.of());
        result.getGaps().add(message);
        return new Progress(result, List.of());
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Current(Map<String, PlnMpsExecutionDTO.ProductionEvent> events, BigDecimal total) {
    }

    private record Progress(PlnMpsRemainingCommitmentDTO.Evaluation evaluation,
                            List<PlnMpsExecutionDTO.ProductionEvent> fresh) {
    }
}
