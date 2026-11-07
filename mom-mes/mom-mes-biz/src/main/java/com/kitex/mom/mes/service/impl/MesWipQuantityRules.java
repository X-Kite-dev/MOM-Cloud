package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.need;

import java.math.BigDecimal;

/** MES在途实物与冻结派工策略数量的共同精度规则。 */
final class MesWipQuantityRules {
    private MesWipQuantityRules() {}

    static BigDecimal number(BigDecimal value, boolean zero) {
        need(value != null && value.signum() >= (zero ? 0 : 1), "派工数量未知或不正确");
        var normalized = value.stripTrailingZeros();
        need(
                plainPrecision(normalized) <= 28 && Math.max(normalized.scale(), 0) <= 16,
                "派工策略数量超出精确范围");
        return normalized;
    }

    static BigDecimal number(String value, boolean zero) {
        need(
                value != null
                        && value.length() <= 64
                        && value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,16})?"),
                "数量须为精确十进制文本");
        return number(new BigDecimal(value), zero);
    }

    static BigDecimal delivery(BigDecimal value) {
        var n = number(value, false);
        need(plainPrecision(n) <= 24 && Math.max(value.scale(), 0) <= 8, "配送数量超出原在途精度");
        return n;
    }

    static long plainPrecision(BigDecimal value) {
        return (long) value.precision() - Math.min((long) value.scale(), 0L);
    }

    static BigDecimal delivery(String value) {
        need(
                value != null
                        && value.length() <= 64
                        && value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8})?"),
                "配送数量须为原24位/8小数精度文本");
        return delivery(new BigDecimal(value));
    }

    static boolean same(BigDecimal a, BigDecimal b) {
        return b != null && a.compareTo(b) == 0;
    }
}
