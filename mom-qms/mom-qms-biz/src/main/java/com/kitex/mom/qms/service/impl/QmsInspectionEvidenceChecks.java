package com.kitex.mom.qms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.dto.QmsInspectionSamples;
import com.kitex.mom.qms.dto.inspection.InspectionItemResultRecord;

import java.util.*;

/** 生产检验的应检量、冻结判定及持久样本守恒；不认证按件首检合格量。 */
final class QmsInspectionEvidenceChecks {
    private QmsInspectionEvidenceChecks() {}

    record Task(Long id, Long tenantId, String factoryCode, Integer num, Integer inspectionNum) {}

    record Item(
            Long id,
            Long taskId,
            Long tenantId,
            String factoryCode,
            String checkType,
            Double upperLimit,
            Double lowerLimit,
            Integer totalNum,
            Integer finishNum,
            Integer waitNum,
            Integer unqualifiedNum,
            Integer ac,
            Boolean qualifiedStatus) {}

    record Completion(boolean ready, boolean qualified) {}

    static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    static void criteria(Item detail) {
        require(detail != null && detail.checkType() != null, "检验项缺少已保存的判定要求");
        switch (detail.checkType().toUpperCase(Locale.ROOT)) {
            case "BOOL" -> {}
            case "RANG" ->
                    require(
                            finite(detail.lowerLimit())
                                    && finite(detail.upperLimit())
                                    && detail.lowerLimit() <= detail.upperLimit(),
                            "检验项范围上下限无效");
            case "UPPER_LIMIT" -> require(finite(detail.upperLimit()), "检验项上限无效");
            case "LOWER_LIMIT" -> require(finite(detail.lowerLimit()), "检验项下限无效");
            default -> throw new CheckedException("检验项判定类型无效");
        }
    }

    static boolean judge(Item detail, InspectionItemResultRecord input) {
        criteria(detail);
        require(input != null, "请填写检验结果");
        if ("BOOL".equalsIgnoreCase(detail.checkType())) {
            require(input.getQualified() != null, "请明确选择是否合格");
            return input.getQualified();
        }
        require(finite(input.getDoubleValue()), "请填写有效的检验数值");
        double value = input.getDoubleValue();
        return switch (detail.checkType().toUpperCase(Locale.ROOT)) {
            case "RANG" -> value >= detail.lowerLimit() && value <= detail.upperLimit();
            case "UPPER_LIMIT" -> value <= detail.upperLimit();
            case "LOWER_LIMIT" -> value >= detail.lowerLimit();
            default -> throw new CheckedException("检验项判定类型无效");
        };
    }

    static void counters(Item d) {
        require(
                d != null
                        && d.totalNum() != null
                        && d.totalNum() > 0
                        && d.finishNum() != null
                        && d.finishNum() >= 0
                        && d.waitNum() != null
                        && d.waitNum() >= 0
                        && (long) d.finishNum() + d.waitNum() == d.totalNum()
                        && d.unqualifiedNum() != null
                        && d.unqualifiedNum() >= 0
                        && d.unqualifiedNum() <= d.finishNum(),
                "检验项应检、已检、待检或不合格数量不守恒，请先核对原记录");
        int ac = d.ac() == null ? 0 : d.ac();
        require(ac >= 0 && ac <= d.totalNum(), "检验项允收阈值无效");
        if (d.finishNum() > 0)
            require(Objects.equals(d.qualifiedStatus(), d.unqualifiedNum() <= ac), "检验项累计判定与数量不一致");
    }

    static Completion completion(Task task, List<Item> details, List<QmsInspectionSamples> stored) {
        require(
                task != null && task.id() != null && task.num() != null && task.num() > 0,
                "实际检验父任务或批量无效");
        require(
                details != null && !details.isEmpty() && details.size() <= 5000,
                "没有完整有界的检验项目，不能形成正式判定");
        require(stored != null && stored.size() <= 5000, "实际检验样本不可读取或超过治理上限");
        Map<Long, QmsInspectionSamples> facts = new HashMap<>();
        for (var sample : stored)
            require(
                    sample != null
                            && sample.detailId() != null
                            && sample.total() > 0
                            && sample.unqualified() >= 0
                            && sample.unqualified() <= sample.total()
                            && sample.undetermined() == 0
                            && facts.put(sample.detailId(), sample) == null,
                    "实际检验样本有缺判定、重复或无效归属");
        Set<Long> ids = new HashSet<>();
        boolean ready = true, qualified = true;
        int sampleCount = 0;
        for (var d : details) {
            require(
                    d != null
                            && d.id() != null
                            && d.id() > 0
                            && ids.add(d.id())
                            && Objects.equals(d.taskId(), task.id())
                            && Objects.equals(d.tenantId(), task.tenantId())
                            && Objects.equals(d.factoryCode(), task.factoryCode()),
                    "检验项目缺少实际父任务/组织或发生重复");
            criteria(d);
            counters(d);
            sampleCount = Math.max(sampleCount, d.totalNum());
            require(d.totalNum() <= task.num(), "检验项目应检量超过受检批量");
            var sample = facts.remove(d.id());
            long total = sample == null ? 0 : sample.total(),
                    ng = sample == null ? 0 : sample.unqualified();
            require(total == d.finishNum() && ng == d.unqualifiedNum(), "实际保存样本与项目累计数量不一致，禁止按缓存判定");
            ready &= d.waitNum() == 0;
            qualified &= Boolean.TRUE.equals(d.qualifiedStatus());
        }
        require(facts.isEmpty(), "存在不属于当前检验项目的样本，不能形成正式判定");
        require(Objects.equals(task.inspectionNum(), sampleCount), "检验任务与项目的应检数量不一致，请先核对原记录");
        return new Completion(ready, qualified);
    }

    /** 追加不能把已变化的标准项目集解释为与原任务兼容。 */
    static void sameItems(List<Long> saved, List<Long> current) {
        require(
                saved != null
                        && current != null
                        && !saved.isEmpty()
                        && saved.size() <= 5000
                        && current.size() == saved.size(),
                "当前标准项目集与原检验任务不一致，请新建受控检验任务");
        var original = new HashSet<Long>();
        for (var id : saved)
            require(id != null && id > 0 && original.add(id), "原检验项目缺少唯一标准项，不能直接追加");
        var configured = new HashSet<Long>();
        for (var id : current)
            require(id != null && id > 0 && configured.add(id), "当前标准项目缺失或重复，不能直接追加");
        require(original.equals(configured), "当前标准项目集与原检验任务不一致，请新建受控检验任务");
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }
}
