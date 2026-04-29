package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 采购收料单按钮权限
 *
 * @author kitex
 * @date 2025-1-22 15:52:01
 **/
@Getter
@AllArgsConstructor
public enum ButtonPurchaseReceiptTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsPurchaseReceipt_view", "查看", 1),

    /**
     * 新增
     */
    ADD("wms_wmsPurchaseReceipt_add", "新增", 2),
    /**
     * 导入导出
     */
    EXPORT("wms_wmsPurchaseReceipt_export", "导入导出", 3);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonPurchaseReceiptTypeEnum getByCode(String code) {
        for (ButtonPurchaseReceiptTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
