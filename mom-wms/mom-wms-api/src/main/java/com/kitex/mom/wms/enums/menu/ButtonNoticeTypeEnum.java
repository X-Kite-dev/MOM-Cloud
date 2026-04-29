package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知单按钮权限
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum ButtonNoticeTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_notice_view", "查看", 1),

    /**
     * 新增
     */
    ADD("wms_notice_add", "新增", 2),

    /**
     * 出入库
     */
    INOUT("wms_notice_inout", "出入库", 3),

    /**
     * 新增并通过
     */
    ADD_REVIEW("wms_bill_add_review", "直接通过", 4),

    /**
     * 作废
     */
    DELETE("wms_notice_del", "作废", 5),

    /**
     * 导入导出
     */
    EXPORT("wms_notice_export", "导入导出", 6);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonNoticeTypeEnum getByCode(String code) {
        for (ButtonNoticeTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
