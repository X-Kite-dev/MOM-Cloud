package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * 智能 APS 案件生命周期状态。
 */
@Getter
@AllArgsConstructor
public enum PlnApsCaseStatusEnum {

    DRAFT("DRAFT", "草稿"),
    PREPARING("PREPARING", "排产准备中"),
    BLOCKED("BLOCKED", "存在阻断"),
    READY("READY", "可计算"),
    CALCULATING("CALCULATING", "计算中"),
    EVALUATED("EVALUATED", "候选已评估"),
    SELECTED("SELECTED", "候选已选择"),
    PUBLISHING("PUBLISHING", "发布中"),
    PUBLISHED("PUBLISHED", "已发布"),
    DISPATCHING("DISPATCHING", "MES 下达中"),
    EXECUTING("EXECUTING", "执行中"),
    COMPLETED("COMPLETED", "已完成"),
    FAILED("FAILED", "处理失败"),
    CANCELLED("CANCELLED", "已取消"),
    SUPERSEDED("SUPERSEDED", "已被新版本替代");

    private final String code;
    private final String description;

    /**
     * 判断状态代码是否有效。
     *
     * @param code 状态代码
     * @return 是否有效
     */
    public static boolean isValid(String code) {
        return fromCode(code).isPresent();
    }

    /**
     * 按代码解析案件状态，未知值不做隐式兜底。
     *
     * @param code 状态代码
     * @return 匹配的枚举
     */
    public static Optional<PlnApsCaseStatusEnum> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }

    /**
     * 判断案件是否已经进入不可继续推进的终态。
     *
     * @return 是否终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == SUPERSEDED;
    }
}
