package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 出库通知单据类型枚举
 *
 * @author Cascade
 * @since 2024-12-16
 */
@Getter
@AllArgsConstructor
public enum OutBillTypeEnum {

    /**
     * 销售出库
     */
    SALES_OUT("sales", "销售出库"),

    /**
     * 生产领料出库
     */
    PRODUCE_OUT("produce", "领料出库"),

    /**
     * 调拨出库
     */
    ALLOCATION_OUT("allocation", "调拨出库"),

    /**
     * 其他出库
     */
    OTHER_OUT("other_out", "其他出库");

    /**
     * 类型编码
     */
    private final String code;

    /**
     * 类型描述
     */
    private final String desc;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static OutBillTypeEnum getByCode(String code) {
        for (OutBillTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 判断是否是有效的单据类型
     *
     * @param code 类型编码
     * @return true-有效，false-无效
     */
    public static boolean isValid(String code) {
        return getByCode(code) != null;
    }

}
