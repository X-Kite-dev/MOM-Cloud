package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/**
 * 质检任务类型
 *
 * @author kitex
 * @date 2022/11/30
 */
@Getter
@AllArgsConstructor
public enum InspectionTaskTypeEnum {

    /**
     * 来料质检（采购质检）
     */
    PURCHASE("采购质检"),

    /**
     * 过程检（工序质检）
     */
    PROCEDURE("工序质检"),

    /**
     * 成品质检
     */
    PRODUCT("成品质检");

    final String value;

    public static InspectionTaskTypeEnum findByName(String name) {
        return Arrays.stream(InspectionTaskTypeEnum.values()).filter(e -> Objects.equals(name.toUpperCase(), e.name()))
                .findFirst().orElseThrow(() -> new RuntimeException("质检任务类型"));
    }

    public static InspectionTaskTypeEnum findByValue(String value) {
        return Arrays.stream(InspectionTaskTypeEnum.values()).filter(e -> Objects.equals(value, e.value)).findFirst()
                .orElseThrow(() -> new RuntimeException("不支持的加严等级"));
    }

}
