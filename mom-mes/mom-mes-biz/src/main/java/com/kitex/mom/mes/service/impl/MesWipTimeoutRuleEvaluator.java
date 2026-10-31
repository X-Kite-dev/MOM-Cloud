package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** WIP超时本域规则；试算和正式扫描使用同一范围、优先级及阈值语义。 */
public final class MesWipTimeoutRuleEvaluator {
    private MesWipTimeoutRuleEvaluator() {}

    public record Candidate(String lineCode, String routeCode, String productCode) {}

    public record Match(MesWipTimeoutConfigEntity rule, int specificity) {}

    public record Selection(Candidate candidate, List<Match> matches, List<Match> preferred) {
        public MesWipTimeoutConfigEntity selected() {
            return preferred.size() == 1 ? preferred.get(0).rule() : null;
        }
    }

    public static Selection select(
            List<MesWipTimeoutConfigEntity> rules,
            MesWarehouseLiveStockEntity stock,
            MesTaskArrangeEntity arrange) {
        require(
                stock != null
                        && stock.getId() != null
                        && stock.getId() > 0
                        && stock.getTenantId() != null
                        && stock.getTenantId() > 0
                        && StrUtil.isNotBlank(stock.getFactoryCode()),
                "在制来源缺少明确租户/工厂/主键");
        require(
                arrange == null
                        || Objects.equals(stock.getTenantId(), arrange.getTenantId())
                                && Objects.equals(stock.getFactoryCode(), arrange.getFactoryCode())
                                && Objects.equals(stock.getBillNo(), arrange.getCode()),
                "派工与在制来源组织或编码不一致");
        require(rules != null, "规则集合不可读取");
        var candidate =
                new Candidate(
                        first(
                                stock.getWarehouseCode(),
                                arrange == null ? null : arrange.getRouteWorkCenter()),
                        first(
                                stock.getRouteCode(),
                                stock.getSiteCode(),
                                arrange == null ? null : arrange.getRouteCode()),
                        stock.getMatCode());
        var matches =
                rules.stream()
                        .filter(
                                r ->
                                        r != null
                                                && Boolean.TRUE.equals(r.getEnabled())
                                                && Objects.equals(
                                                        stock.getTenantId(), r.getTenantId())
                                                && Objects.equals(
                                                        stock.getFactoryCode(), r.getFactoryCode()))
                        .filter(
                                r ->
                                        matches(r.getLineCode(), candidate.lineCode())
                                                && matches(r.getRouteCode(), candidate.routeCode())
                                                && matches(
                                                        r.getProductCode(),
                                                        candidate.productCode()))
                        .map(r -> new Match(r, score(r)))
                        .sorted(
                                Comparator.comparingInt(Match::specificity)
                                        .reversed()
                                        .thenComparing(
                                                m -> m.rule().getCode(),
                                                Comparator.nullsLast(String::compareTo)))
                        .toList();
        for (var m : matches) require(StrUtil.isNotBlank(m.rule().getCode()), "命中规则缺少配置编码");
        var preferred =
                matches.isEmpty()
                        ? List.<Match>of()
                        : matches.stream()
                                .filter(m -> m.specificity() == matches.get(0).specificity())
                                .toList();
        return new Selection(candidate, matches, preferred);
    }

    public static Integer level(MesWipTimeoutConfigEntity rule, long elapsedSeconds) {
        require(
                rule != null && rule.getTimeoutSec() != null && rule.getTimeoutSec() > 0,
                "L1阈值必须大于0秒");
        require(
                rule.getLevel2Sec() == null || rule.getLevel2Sec() > rule.getTimeoutSec(),
                "L2阈值必须大于L1阈值");
        require(
                rule.getLevel3Sec() == null
                        || rule.getLevel2Sec() != null && rule.getLevel3Sec() > rule.getLevel2Sec(),
                "L3阈值须在L2之后且大于L2");
        if (elapsedSeconds < rule.getTimeoutSec()) return null;
        if (rule.getLevel3Sec() != null && elapsedSeconds >= rule.getLevel3Sec()) return 3;
        if (rule.getLevel2Sec() != null && elapsedSeconds >= rule.getLevel2Sec()) return 2;
        return 1;
    }

    private static int score(MesWipTimeoutConfigEntity r) {
        return (StrUtil.isNotBlank(r.getLineCode()) ? 1 : 0)
                + (StrUtil.isNotBlank(r.getRouteCode()) ? 2 : 0)
                + (StrUtil.isNotBlank(r.getProductCode()) ? 4 : 0);
    }

    private static boolean matches(String scope, String actual) {
        return StrUtil.isBlank(scope) || Objects.equals(scope, actual);
    }

    private static String first(String... values) {
        return Arrays.stream(values).filter(StrUtil::isNotBlank).findFirst().orElse(null);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }
}
