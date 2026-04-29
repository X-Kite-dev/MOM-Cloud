package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 点检类型
 *
 * @author kitex
 * @date 2022/11/29
 */
@Getter
@AllArgsConstructor
public enum CheckTypeEnum {

    /**
     * 是否合格
     */
    BOOL("是否合格"),
    /**
     * 范围
     */
    RANG("范围"),
    /**
     * 上限
     */
    UPPER_LIMIT("上限"),
    /**
     * 下限
     */
    LOWER_LIMIT("下限");

    private final String value;

    public static CheckTypeEnum findByName(String name) {
        return Arrays.stream(CheckTypeEnum.values()).filter(e -> Objects.equals(name.toUpperCase(), e.name()))
                .findFirst().orElseThrow(() -> new RuntimeException("不支持的加严等级"));
    }

    public static CheckTypeEnum findByValue(String value) {
        return Arrays.stream(CheckTypeEnum.values()).filter(e -> Objects.equals(value, e.value)).findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的加严等级"));
    }

}
