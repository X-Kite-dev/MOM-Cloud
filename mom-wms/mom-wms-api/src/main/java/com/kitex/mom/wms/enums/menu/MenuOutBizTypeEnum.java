package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 出库业务菜单类型
 *
 * @author kitex
 * @date 2025-1-14 10:06:59
 **/
@Getter
@AllArgsConstructor
public enum MenuOutBizTypeEnum {

    /**
     * 生产领料
     */
    PRODUCE("produce", "生产领料", "/wms/outBiz/producePicking", "/pages-wms/order/outBillOrder/index", 5),
    /**
     * 销售出库
     */
    SALES("sales", "销售出库", "/wms/outBiz/sales", "/pages-wms/order/outBillOrder/index", 6),
    /**
     * 其他出库
     */
    OTHER_OUT("other_out", "其他出库", "/wms/outBiz/other", "/pages-wms/order/other/out/index", 11);

    private final String code;

    private final String label;

    private final String value;

    private final String appPath;

    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举，如果不存在则返回null
     */
    public static MenuOutBizTypeEnum getByCode(String code) {
        for (MenuOutBizTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据value获取枚举
     *
     * @param value 枚举值
     * @return 对应的枚举，如果不存在则返回null
     */
    public static MenuOutBizTypeEnum getByValue(String value) {
        for (MenuOutBizTypeEnum type : values()) {
            if (type.getValue().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
