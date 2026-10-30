package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 库存盘点按钮权限
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum ButtonInventoryTypeEnum {

    /**
     * 查看
     */
    VIEW("wms_wmsInsideInventoryPlan_view", "查看", 1),

    /**
     * 新增
     */
    ADD("wms_wmsInsideInventoryPlan_add", "新增", 2),

    /**
     * 删除
     */
    DELETE("wms_wmsInsideInventoryPlan_del", "删除", 3),

    /**
     * 盘点录入
     */
    ENTRY("wms_wmsInsideInventoryPlan_entry", "盘点录入", 4),

    /**
     * 损益处理
     */
    HAND("wms_wmsInsideInventoryPlan_hand", "损益处理", 5),
    /**
     * 导入导出
     */
    EXPORT("wms_wmsInsideInventoryPlan_export", "导入导出", 6),

    /** 保留原盘点记录后作废。 */
    CANCEL("wms_wmsInsideInventoryPlan_cancel", "作废", 7);

    private final String code;
    private final String name;
    private final Integer sort;

    /**
     * 根据code获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static ButtonInventoryTypeEnum getByCode(String code) {
        for (ButtonInventoryTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
