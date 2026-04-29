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
public enum ButtonOutNoticeBatchTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsOutNoticeBatch_view", "查看", 1),

    /**
     * 新增
     */
    ADD("wms_wmsOutNoticeBatch_add", "新增", 2),

    /**
     * 编辑
     */
    EDIT("wms_wmsOutNoticeBatch_edit", "编辑", 3),

    /**
     * 删除
     */
    DELETE("wms_wmsOutNoticeBatch_del", "删除", 4),

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
    public static ButtonOutNoticeBatchTypeEnum getByCode(String code) {
        for (ButtonOutNoticeBatchTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
