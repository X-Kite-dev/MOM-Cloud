package com.kitex.mom.qms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.entity.Frequency;

import java.util.List;

/** QMS工序与成品检验共用的抽样规则；不承担跨领域单位换算。 */
final class QmsInspectionSamplingRules {
    private QmsInspectionSamplingRules() {}

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    static Frequency frequency(int batch, List<Frequency> frequencies) {
        require(batch > 0 && frequencies != null && !frequencies.isEmpty(), "抽样策略缺少正数批量或配置");
        for (var f : frequencies)
            require(
                    f != null
                            && f.getS() != null
                            && f.getS() > 0
                            && (f.getE() == null || f.getE() >= f.getS())
                            && f.getN() != null
                            && f.getN() >= 0,
                    "抽样范围或样本数量配置无效");
        if (batch == 1) return new Frequency().setS(1).setE(1).setN(1).setAc(0).setRe(1);
        var matching =
                frequencies.stream()
                        .filter(f -> f.getS() <= batch && (f.getE() == null || batch <= f.getE()))
                        .toList();
        require(matching.size() == 1, "抽样范围没有唯一匹配，请检查缺口或重叠配置");
        return matching.get(0);
    }

    static int sampleSize(
            int batch, String method, Integer fixed, Integer requested, Frequency frequency) {
        require(batch > 0 && method != null, "受检批量或检验方式无效");
        int count;
        switch (method) {
            case "FULL" -> count = batch;
            case "FIXED" -> {
                require(fixed != null && fixed > 0, "固定抽样数量必须大于0");
                count = Math.min(batch, fixed);
            }
            case "PENDING" -> {
                require(requested != null && requested > 0, "发起时确定的抽样数量必须大于0");
                count = Math.min(batch, requested);
            }
            case "RANDOM" -> {
                require(
                        frequency != null && frequency.getN() != null && frequency.getN() >= 0,
                        "抽样数量无效，0表示全检");
                count = frequency.getN() == 0 ? batch : Math.min(batch, frequency.getN());
                require(
                        frequency.getAc() != null
                                && frequency.getRe() != null
                                && frequency.getAc() >= 0
                                && frequency.getAc() <= count
                                && frequency.getRe() > frequency.getAc()
                                && (long) frequency.getRe() <= (long) count + 1,
                        "抽样接收/拒收阈值与实际应检量不一致");
            }
            default -> throw new CheckedException("不支持的检验方式：" + method);
        }
        return count;
    }
}
