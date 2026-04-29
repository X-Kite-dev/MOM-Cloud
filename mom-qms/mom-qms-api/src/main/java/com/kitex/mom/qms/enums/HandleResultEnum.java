package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 处置结果
 *
 * @author kitex
 * @since 2025-03-19
 */
@Getter
@AllArgsConstructor
public enum HandleResultEnum {


    SMASH("报废"),
    RETURN("退货"),
    RELEASE("紧急放行"),
    UNQUALIFIED("入不合格品库"),
    REWORK("返工返修"),
    CONCESSION("让步接收");

    private final String name;
}
