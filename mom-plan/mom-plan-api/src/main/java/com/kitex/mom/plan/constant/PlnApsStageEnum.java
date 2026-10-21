package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * 智能 APS 五阶段作业流程。
 */
@Getter
@AllArgsConstructor
public enum PlnApsStageEnum {

    PREPARATION("PREPARATION", "排产准备", 1),
    STRATEGY("STRATEGY", "策略设定", 2),
    CALCULATION("CALCULATION", "APS 计算", 3),
    DECISION("DECISION", "方案决策", 4),
    EXECUTION("EXECUTION", "执行跟踪", 5);

    private final String code;
    private final String description;
    private final int sequence;

    /**
     * 判断阶段代码是否有效。
     *
     * @param code 阶段代码
     * @return 是否有效
     */
    public static boolean isValid(String code) {
        return fromCode(code).isPresent();
    }

    /**
     * 按代码解析阶段，未知值不做隐式兜底。
     *
     * @param code 阶段代码
     * @return 匹配的枚举
     */
    public static Optional<PlnApsStageEnum> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
