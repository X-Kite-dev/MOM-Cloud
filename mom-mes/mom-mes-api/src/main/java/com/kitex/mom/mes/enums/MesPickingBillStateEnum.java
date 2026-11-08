package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 生产领料申请单的本域状态。
 * 0新建、1已提交、2待接收、3已完成；不等同于V1来源阶段或WMS实物出库状态。
 *
 * @author kitex
 * @date 2025-1-2 16:02:49
 */
@Getter
@AllArgsConstructor
public enum MesPickingBillStateEnum {

    CREATED("0", "新建"),
    COMMITTED("1", "已提交"),
    PENDING("2", "待接收"),
    FINISHED("3", "已完成");

    /**
     * 编码
     */
    private final String code;

    /**
     * 描述
     */
    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static MesPickingBillStateEnum getByCode(String code) {
        for (MesPickingBillStateEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
