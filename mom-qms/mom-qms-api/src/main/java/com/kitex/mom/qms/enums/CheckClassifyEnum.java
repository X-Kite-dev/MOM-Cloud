package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 质检方式
 *
 * @author kitex
 * @since 2025-01-15
 */
@Getter
@AllArgsConstructor
public enum CheckClassifyEnum {

    SIZE("尺寸"),
    APPEARANCE("外观"),
    PERFORMANCE("性能");

    private final String value;

    public static CheckClassifyEnum findByName(String name) {
        return Arrays.stream(CheckClassifyEnum.values()).filter(e -> Objects.equals(name.toUpperCase(), e.name()))
                .findFirst().orElseThrow(() -> new RuntimeException("不支持的质检方式"));
    }

    public static CheckClassifyEnum findByValue(String value) {
        return Arrays.stream(CheckClassifyEnum.values()).filter(e -> Objects.equals(value, e.value)).findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的质检方式"));
    }

}
