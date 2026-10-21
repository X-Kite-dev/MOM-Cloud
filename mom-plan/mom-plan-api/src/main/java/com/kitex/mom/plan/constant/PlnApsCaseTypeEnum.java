package com.kitex.mom.plan.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * 智能 APS 案件类型。
 *
 * <p>案件类型只决定启动来源、默认策略和影响范围，计算、选案、发布与回评仍使用统一流程。</p>
 */
@Getter
@AllArgsConstructor
public enum PlnApsCaseTypeEnum {

    COMPREHENSIVE_SCHEDULING("COMPREHENSIVE_SCHEDULING", "综合周期排产"),
    ORDER_COMMITMENT("ORDER_COMMITMENT", "订单交期承诺"),
    ORDER_CHANGE_REPLAN("ORDER_CHANGE_REPLAN", "订单变更重排"),
    INSERTION_REPLAN("INSERTION_REPLAN", "紧急插单重排"),
    SHORTAGE_REPLAN("SHORTAGE_REPLAN", "缺料重排"),
    RESOURCE_EXCEPTION_REPLAN("RESOURCE_EXCEPTION_REPLAN", "资源异常重排"),
    QUALITY_EXCEPTION_REPLAN("QUALITY_EXCEPTION_REPLAN", "质量异常重排"),
    EXECUTION_DEVIATION_REPLAN("EXECUTION_DEVIATION_REPLAN", "执行偏差重排");

    private final String code;
    private final String description;

    /**
     * 判断代码是否属于受支持的 APS 案件类型。
     *
     * @param code 类型代码
     * @return 是否有效
     */
    public static boolean isValid(String code) {
        return fromCode(code).isPresent();
    }

    /**
     * 按代码解析案件类型，未知值不做隐式兜底。
     *
     * @param code 类型代码
     * @return 匹配的枚举
     */
    public static Optional<PlnApsCaseTypeEnum> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
