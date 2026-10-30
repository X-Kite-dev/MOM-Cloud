package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 变化点改善的确定性原始证据读取器。
 *
 * <p>包含尚未核清的变化点，不能用“已核清损失为零”假装异常消失。生产样本是当前工厂
 * 冻结产品范围内的有效报工事实，不合计不同产品/工序的产量，也不宣称证明产线适用性。
 * 产线、工况、采集启用及原CAPA判定标准仍须由独立人员依据现场证据核验。</p>
 */
@Component
@RequiredArgsConstructor
public class MesChangePointEffectEvidenceReader {

    private static final int LIMIT = 10000;
    private final MesChangePointHandleMapper handleMapper;
    private final MesTaskReportMapper reportMapper;

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("变化点复测证据指纹生成失败", exception);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String time(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    /**
     * 新来源单发起时冻结完整原始范围；不对缺失该快照的历史单据补造依据。
     */
    public String captureBaseline(String factory, String dimension, String group,
                                  LocalDateTime start, LocalDateTime end) {
        List<MesChangePointHandleEntity> events = events(factory, dimension, group, start, end);
        List<String> products = events.stream().map(MesChangePointHandleEntity::getProductCode)
                .filter(StrUtil::isNotBlank).distinct().sorted().toList();
        Map<String, Object> snapshot = eventSnapshot(events);
        snapshot.put("schemaVersion", "MES_CP_EFFECT_V1");
        snapshot.put("products", products);
        snapshot.put("productScopeComplete", !events.isEmpty()
                && events.stream().allMatch(event -> StrUtil.isNotBlank(event.getProductCode())));
        if ("LOSS".equals(dimension)) {
            snapshot.put("unclassifiedEvents", unclassified(factory, start, end));
        }
        return JSONUtil.toJsonStr(snapshot);
    }

    public Observation observe(MesChangePointImprovementEntity source, LocalDateTime start,
                               LocalDateTime end, long minimumSamples) {
        var baseline = JSONUtil.parseObj(source.getBaselineEventSnapshot());
        if (!"MES_CP_EFFECT_V1".equals(baseline.getStr("schemaVersion")) || baseline.getJSONArray("products") == null) {
            throw new CheckedException("基线原始证据版本或产品范围不完整，禁止推断补齐");
        }
        List<String> products = baseline.getJSONArray("products").toList(String.class);
        List<String> gaps = new ArrayList<>();
        if (!Boolean.TRUE.equals(baseline.getBool("productScopeComplete")) || products.isEmpty()) {
            gaps.add("基线变化点缺少完整产品范围，无法证明生产样本覆盖");
        }
        List<MesChangePointHandleEntity> events = events(source.getFactoryCode(), source.getDimension(),
                source.getGroupCode(), start, end);
        Map<String, Object> snapshot = eventSnapshot(events);
        if (events.stream().anyMatch(event -> StrUtil.isBlank(event.getProductCode())
                || !products.contains(event.getProductCode()))) {
            gaps.add("复测事件出现基线外产品或缺少产品编码，产品范围不可比");
        }
        List<MesTaskReportEntity> reports = products.isEmpty() ? List.of() : reportMapper.selectList(
                new LambdaQueryWrapper<MesTaskReportEntity>()
                        .eq(MesTaskReportEntity::getTenantId, tenant())
                        .eq(MesTaskReportEntity::getFactoryCode, source.getFactoryCode())
                        .in(MesTaskReportEntity::getMatCode, products)
                        .eq(MesTaskReportEntity::getStatus, "EFFECTIVE")
                        .ge(MesTaskReportEntity::getReportTime, start).lt(MesTaskReportEntity::getReportTime, end)
                        .select(MesTaskReportEntity::getId, MesTaskReportEntity::getMatCode,
                                MesTaskReportEntity::getStatus, MesTaskReportEntity::getReportTime,
                                MesTaskReportEntity::getReportNum, MesTaskReportEntity::getEffectiveReportNum,
                                MesTaskReportEntity::getReportMode, MesTaskReportEntity::getReportUnit,
                                MesTaskReportEntity::getTaskCode, MesTaskReportEntity::getProduceTaskCode,
                                MesTaskReportEntity::getArrangeCode, MesTaskReportEntity::getLastCorrectionTime)
                        .orderByAsc(MesTaskReportEntity::getId).last("LIMIT " + (LIMIT + 1)));
        requireWithinLimit(reports.size(), "有效报工");
        Map<String, Long> coverage = new LinkedHashMap<>();
        products.forEach(product -> coverage.put(product, 0L));
        for (MesTaskReportEntity report : reports) {
            if (report.getEffectiveReportNum() == null) {
                gaps.add("报工" + report.getId() + "缺少当前有效数量，不用原报工量补造");
            } else if (report.getEffectiveReportNum().signum() > 0) {
                coverage.computeIfPresent(report.getMatCode(), (product, count) -> count + 1);
            }
        }
        coverage.forEach((product, count) -> {
            if (count < minimumSamples) {
                gaps.add("产品" + product + "有效报工事实仅" + count + "条，少于要求" + minimumSamples);
            }
        });
        // LOSS分组在核清前可能尚无损失分类，不能将这部分潜在同类损失排除后宣称改善。
        if ("LOSS".equals(source.getDimension())) {
            List<List<Object>> unclassified = unclassified(source.getFactoryCode(), start, end);
            snapshot.put("unclassifiedEvents", unclassified);
            if (!unclassified.isEmpty()) {
                gaps.add("复测窗口存在" + unclassified.size() + "条未核清且未分类变化点，不能排除潜在同类损失");
            }
        }
        snapshot.put("reports", reports.stream().map(report -> Arrays.asList(report.getId(),
                report.getMatCode(), report.getStatus(), time(report.getReportTime()),
                decimal(report.getReportNum()), decimal(report.getEffectiveReportNum()), report.getReportMode(),
                report.getReportUnit(), report.getTaskCode(), report.getProduceTaskCode(), report.getArrangeCode(),
                time(report.getLastCorrectionTime()))).toList());
        snapshot.put("productCoverage", coverage);
        snapshot.put("gaps", gaps);
        String json = JSONUtil.toJsonStr(snapshot);
        return new Observation(json, hash(json), List.copyOf(gaps), coverage.values().stream()
                .mapToLong(Long::longValue).sum(), events.size(), (Long) snapshot.get("unclearedEventCount"));
    }

    private List<List<Object>> unclassified(String factory, LocalDateTime start, LocalDateTime end) {
        List<MesChangePointHandleEntity> records = handleMapper.selectList(eventScope(factory, start, end)
                .and(query -> query.isNull(MesChangePointHandleEntity::getLossCategory)
                        .or().eq(MesChangePointHandleEntity::getLossCategory, ""))
                .isNull(MesChangePointHandleEntity::getAffectedWipQty)
                .ne(MesChangePointHandleEntity::getStatus, "9")
                .select(MesChangePointHandleEntity::getId, MesChangePointHandleEntity::getStatus,
                        MesChangePointHandleEntity::getProductCode, MesChangePointHandleEntity::getTriggerTime)
                .orderByAsc(MesChangePointHandleEntity::getId).last("LIMIT " + (LIMIT + 1)));
        requireWithinLimit(records.size(), "未分类变化点");
        return records.stream().map(event -> Arrays.<Object>asList(event.getId(), event.getStatus(),
                event.getProductCode(), time(event.getTriggerTime()))).toList();
    }

    private List<MesChangePointHandleEntity> events(String factory, String dimension, String group,
                                                    LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<MesChangePointHandleEntity> query = eventScope(factory, start, end);
        switch (dimension) {
            case "CATEGORY" -> query.eq(MesChangePointHandleEntity::getCategory, group);
            case "CONFIG" -> query.eq(MesChangePointHandleEntity::getConfigCode, group);
            case "LOSS" -> {
                if ("NONE".equals(group)) {
                    query.and(part -> part.isNull(MesChangePointHandleEntity::getLossCategory)
                            .or().eq(MesChangePointHandleEntity::getLossCategory, "NONE"));
                } else {
                    query.eq(MesChangePointHandleEntity::getLossCategory, group);
                }
            }
            default -> throw new CheckedException("变化点复测分组维度无效");
        }
        query.select(MesChangePointHandleEntity::getId, MesChangePointHandleEntity::getEventCode,
                        MesChangePointHandleEntity::getProductCode, MesChangePointHandleEntity::getLineCode,
                        MesChangePointHandleEntity::getConfigCode, MesChangePointHandleEntity::getCategory,
                        MesChangePointHandleEntity::getLossCategory, MesChangePointHandleEntity::getStatus,
                        MesChangePointHandleEntity::getTriggerTime, MesChangePointHandleEntity::getAffectedWipQty,
                        MesChangePointHandleEntity::getReworkWipQty, MesChangePointHandleEntity::getScrapWipQty,
                        MesChangePointHandleEntity::getLossAmount, MesChangePointHandleEntity::getAutoPauseRequired,
                        MesChangePointHandleEntity::getProductionPaused, MesChangePointHandleEntity::getPauseOutcome)
                .orderByAsc(MesChangePointHandleEntity::getId).last("LIMIT " + (LIMIT + 1));
        List<MesChangePointHandleEntity> events = handleMapper.selectList(query);
        requireWithinLimit(events.size(), "变化点");
        return events;
    }

    private LambdaQueryWrapper<MesChangePointHandleEntity> eventScope(String factory, LocalDateTime start,
                                                                      LocalDateTime end) {
        return new LambdaQueryWrapper<MesChangePointHandleEntity>()
                .eq(MesChangePointHandleEntity::getTenantId, tenant())
                .eq(MesChangePointHandleEntity::getFactoryCode, factory)
                .ge(MesChangePointHandleEntity::getTriggerTime, start).lt(MesChangePointHandleEntity::getTriggerTime, end);
    }

    private Map<String, Object> eventSnapshot(List<MesChangePointHandleEntity> events) {
        // 作废事件不计指标，但仍保留原始行以识别申请后撤销或改写事实。
        List<MesChangePointHandleEntity> active = events.stream()
                .filter(event -> !"9".equals(event.getStatus())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventCount", (long) active.size());
        result.put("unclearedEventCount", active.stream().filter(event -> event.getAffectedWipQty() == null
                && !"9".equals(event.getStatus())).count());
        result.put("autoPauseCount", active.stream().filter(event -> Boolean.TRUE.equals(event.getAutoPauseRequired())).count());
        result.put("pauseFailureCount", active.stream().filter(event -> Boolean.TRUE.equals(event.getAutoPauseRequired())
                && !Boolean.TRUE.equals(event.getProductionPaused())
                && ("FAILED_UNKNOWN".equals(event.getPauseOutcome())
                || "MDM_CONFIRMED_MES_FAILED".equals(event.getPauseOutcome()))).count());
        result.put("pauseUnknownCount", active.stream().filter(event -> Boolean.TRUE.equals(event.getAutoPauseRequired())
                && !Boolean.TRUE.equals(event.getProductionPaused())
                && !"FAILED_UNKNOWN".equals(event.getPauseOutcome())
                && !"MDM_CONFIRMED_MES_FAILED".equals(event.getPauseOutcome())).count());
        result.put("missingLossCount", active.stream().filter(event -> event.getLossAmount() == null).count());
        result.put("lossAmount", active.stream().map(MesChangePointHandleEntity::getLossAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString());
        result.put("events", events.stream().map(event -> Arrays.asList(event.getId(), event.getEventCode(),
                event.getProductCode(), event.getLineCode(), event.getConfigCode(), event.getCategory(),
                event.getLossCategory(), event.getStatus(), time(event.getTriggerTime()),
                decimal(event.getAffectedWipQty()), decimal(event.getReworkWipQty()), decimal(event.getScrapWipQty()),
                decimal(event.getLossAmount()), event.getAutoPauseRequired(), event.getProductionPaused(),
                event.getPauseOutcome())).toList());
        return result;
    }

    private Long tenant() {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || TenantContextHolder.getTenantSkip()) {
            throw new CheckedException("变化点复测须提供实际租户，不允许跳过隔离");
        }
        return tenant;
    }

    private void requireWithinLimit(int size, String label) {
        if (size > LIMIT) {
            throw new CheckedException(label + "证据超过10000条，禁止截断后复测，请缩小时间窗或按更细分组建立基线");
        }
    }

    public record Observation(String snapshot, String fingerprint, List<String> gaps,
                              long reportSamples, long events, long unclearedEvents) {
    }
}
