package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 不良品项管理状态枚举
 *
 * @author kitex
 * @since 2025-05-18
 */
@Getter
@AllArgsConstructor
public enum MdmBadConfigTypeEnum {

    CLASSIFY("分类"),
    ITEM("不良品项");

    private final String name;
}
