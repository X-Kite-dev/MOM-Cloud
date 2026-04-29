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
public enum ButtonBillTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_bill_view", "查看", 1),

    /**
     * 新增
     */
    ADD("wms_bill_add", "新增", 2),

    /**
     * 编辑
     */
    EDIT("wms_bill_edit", "编辑", 3),

    /**
     * 删除
     */
    DELETE("wms_bill_del", "删除", 4),

    /**
     * 提交
     */
    COMMIT("wms_bill_commit", "提交", 5),

    /**
     * 审核
     */
    REVIEW("wms_bill_review", "审核", 6),

    /**
     * 新增并通过
     */
    ADD_REVIEW("wms_bill_add_review", "新增并通过", 7),

    /**
     * 导入导出
     */
    EXPORT("wms_bill_export", "导入导出", 8);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonBillTypeEnum getByCode(String code) {
        for (ButtonBillTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
