package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 物料明细类型
 *
 * @author kitex
 * @since 2025-05-26
 */
@Getter
@AllArgsConstructor
public enum MesWarehouseMatDetailTypeEnum {

    RAW("原材料"),
    WIP("在制品"),
    SEMI_FINISHED("半成品"),
    PRODUCT("成品");


    private final String name;
}
