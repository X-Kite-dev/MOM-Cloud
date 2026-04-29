package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 出入库流水按钮权限
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum ButtonInoutFlowTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsInoutFlow_view", "查看", 1),
    /**
     * 导入导出
     */
    EXPORT("wms_wmsInoutFlow_export", "导入导出", 2);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonInoutFlowTypeEnum getByCode(String code) {
        for (ButtonInoutFlowTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
