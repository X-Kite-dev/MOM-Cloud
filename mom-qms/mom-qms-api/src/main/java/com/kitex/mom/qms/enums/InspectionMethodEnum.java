package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 质检方式
 *
 * @author kitex
 * @since 2025/01/15
 */
@Getter
@AllArgsConstructor
public enum InspectionMethodEnum {

    /**
     * 抽检
     */
    RANDOM("抽检"),

    /**
     * 全检
     */
    FULL("全检"),

    /**
     * 固定数量
     */
    FIXED("固定数量"),

    /**
     * 发起质检时确定
     */
    PENDING("发起质检时确定");

    final String value;

    public static InspectionMethodEnum findByName(String name) {
        return Arrays.stream(InspectionMethodEnum.values()).filter(e -> Objects.equals(name.toUpperCase(), e.name()))
                .findFirst().orElseThrow(() -> new RuntimeException("不支持的质检方式"));
    }

    public static InspectionMethodEnum findByValue(String value) {
        return Arrays.stream(InspectionMethodEnum.values()).filter(e -> Objects.equals(value, e.value)).findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的质检方式"));
    }

}
