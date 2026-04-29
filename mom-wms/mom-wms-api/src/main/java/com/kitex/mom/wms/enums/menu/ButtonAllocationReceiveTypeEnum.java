package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 调拨接收按钮权限
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum ButtonAllocationReceiveTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsAllocationReceive_view", "查看", 1),

    /**
     * 接收
     */
    RECEIVE("wms_wmsAllocationReceive_rec", "接收", 2),

    /**
     * 导入导出
     */
    EXPORT("wms_wmsAllocationReceive_export", "导入导出", 3);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonAllocationReceiveTypeEnum getByCode(String code) {
        for (ButtonAllocationReceiveTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
