package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实时库存按钮权限
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum ButtonLiveStockTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsLiveStock_view", "查看", 1),
    /**
     * 导入导出
     */
    EXPORT("wms_wmsLiveStock_export", "导入导出", 2);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonLiveStockTypeEnum getByCode(String code) {
        for (ButtonLiveStockTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
