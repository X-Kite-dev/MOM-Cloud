package com.kitex.mom.mes.enums;

import java.util.Locale;

/**
 * 设备事件换算为报工数量的方式。
 */
public enum AutoReportQuantityModeEnum {
    /**
     * 事件值本身就是本次新增产量。
     */
    DELTA,
    /**
     * 事件值是设备累计计数，使用与上一次有效事件的差值。
     */
    CUMULATIVE,
    /**
     * 每个脉冲按配置数量折算，事件值可表示一次消息内的脉冲数。
     */
    PULSE;

    public static AutoReportQuantityModeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("自动报工数量模式不能为空");
        }
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的自动报工数量模式: " + code);
        }
    }
}
