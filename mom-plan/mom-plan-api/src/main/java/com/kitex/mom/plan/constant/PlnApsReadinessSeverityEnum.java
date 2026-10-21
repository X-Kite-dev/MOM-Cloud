package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * APS 数据预检问题严重度。
 */
@Getter
@AllArgsConstructor
public enum PlnApsReadinessSeverityEnum {

    INFO("INFO", "提示", false),
    WARNING("WARNING", "警告", false),
    BLOCKER("BLOCKER", "阻断", true);

    private final String code;
    private final String description;
    private final boolean blocking;

    /**
     * 判断严重度代码是否有效。
     *
     * @param code 严重度代码
     * @return 是否有效
     */
    public static boolean isValid(String code) {
        return fromCode(code).isPresent();
    }

    /**
     * 按代码解析严重度，未知值不做隐式兜底。
     *
     * @param code 严重度代码
     * @return 匹配的枚举
     */
    public static Optional<PlnApsReadinessSeverityEnum> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
