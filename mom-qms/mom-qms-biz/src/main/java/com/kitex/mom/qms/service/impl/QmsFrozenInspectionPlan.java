package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.kitex.mom.qms.entity.Frequency;
import com.kitex.mom.qms.entity.QmsQualityStandardItemEntity;

import java.util.*;

/** 完整冻结抽样政策。批量变化只重新求值，不读取现行配置，不认证按件首检资格。 */
public record QmsFrozenInspectionPlan(
        Long standardId,
        String standardName,
        String method,
        Integer fixedCount,
        Integer requestedCount,
        List<Item> items) {
    public record Band(Integer start, Integer end, Integer count, Integer ac, Integer re) {
        Frequency frequency() {
            return new Frequency().setS(start).setE(end).setN(count).setAc(ac).setRe(re);
        }
    }

    public record Item(
            Long standardItemId,
            String name,
            String demand,
            String testMethod,
            String position,
            String checkType,
            String checkClassify,
            Double upperLimit,
            Double lowerLimit,
            Long samplingStrategyId,
            List<Band> bands) {
        public Item {
            bands = bands == null ? null : List.copyOf(bands);
        }

        static Item of(QmsQualityStandardItemEntity v, Long strategy, List<Frequency> frequencies) {
            return new Item(
                    v.getId(),
                    v.getName(),
                    v.getDemand(),
                    v.getMethod(),
                    v.getPosition(),
                    v.getCheckType(),
                    v.getCheckClassify(),
                    v.getUpperLimit(),
                    v.getLowerLimit(),
                    strategy,
                    frequencies == null
                            ? List.of()
                            : frequencies.stream()
                                    .map(
                                            f ->
                                                    new Band(
                                                            f.getS(), f.getE(), f.getN(), f.getAc(),
                                                            f.getRe()))
                                    .toList());
        }
    }

    public record Target(Item item, int count, Integer ac, Integer re) {}

    public QmsFrozenInspectionPlan {
        items = items == null ? null : List.copyOf(items);
    }

    public List<Target> targets(int batch) {
        require(
                standardId != null
                        && standardId > 0
                        && items != null
                        && !items.isEmpty()
                        && items.size() <= 5000,
                "冻结检验方案缺少标准或有界项目");
        var ids = new HashSet<Long>();
        var result = new ArrayList<Target>();
        for (var item : items) {
            require(
                    item != null
                            && item.standardItemId() != null
                            && item.standardItemId() > 0
                            && ids.add(item.standardItemId()),
                    "冻结检验项目缺失或重复");
            QmsInspectionEvidenceChecks.criteria(
                    new QmsInspectionEvidenceChecks.Item(
                            null,
                            null,
                            null,
                            null,
                            item.checkType(),
                            item.upperLimit(),
                            item.lowerLimit(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
            Frequency f = null;
            if ("RANDOM".equals(method)) {
                require(
                        item.samplingStrategyId() != null
                                && item.samplingStrategyId() > 0
                                && item.bands() != null,
                        "冻结抽样策略或完整区间缺失");
                validateBands(item.bands());
                f =
                        QmsInspectionSamplingRules.frequency(
                                batch, item.bands().stream().map(Band::frequency).toList());
            } else
                require(
                        item.samplingStrategyId() == null
                                && item.bands() != null
                                && item.bands().isEmpty(),
                        "非随机检验不能夹带抽样区间");
            int count =
                    QmsInspectionSamplingRules.sampleSize(
                            batch, method, fixedCount, requestedCount, f);
            result.add(
                    new Target(
                            item,
                            count,
                            f == null ? null : f.getAc(),
                            f == null ? null : f.getRe()));
        }
        return List.copyOf(result);
    }

    public int maximum(int batch) {
        return targets(batch).stream().mapToInt(Target::count).max().orElseThrow();
    }

    private static void validateBands(List<Band> bands) {
        require(bands != null && !bands.isEmpty() && bands.size() <= 1000, "冻结抽样区间缺失或超过上限");
        for (var b : bands)
            require(
                    b != null
                            && b.start() != null
                            && b.start() > 0
                            && (b.end() == null || b.end() >= b.start())
                            && b.count() != null
                            && b.count() >= 0,
                    "冻结抽样区间或样本数无效");
        var ordered = bands.stream().sorted(Comparator.comparing(Band::start)).toList();
        Band previous = null;
        for (var b : ordered) {
            require(
                    previous == null || (previous.end() != null && previous.end() < b.start()),
                    "冻结抽样区间存在重叠");
            int minimum = b.start() == 1 && (b.end() == null || b.end() >= 2) ? 2 : b.start();
            QmsInspectionSamplingRules.sampleSize(minimum, "RANDOM", null, null, b.frequency());
            previous = b;
        }
    }
}
