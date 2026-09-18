package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 菜单类型
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum LiveStockMenuTypeEnum {

    /**
     * 物料级
     */
    BY_MATERIAL("by_material", "物料级", "/byMaterial/index", 1),
    /**
     * 供应商级
     */
    BY_SUPPLIER("by_supplier", "供应商级", "/bySupplier/index", 2),
    /**
     * 库存明细
     */
    STOCK_DETAIL("stock_detail", "库存明细", "/stockDetail/index", 3),
    /**
     * 拣货区
     */
    PICKING_AREA("picking_area", "拣货区", "/pickingArea/index", 4),
    /** 质量隔离与实物处置事务 */
    QUALITY_STATUS("quality_status", "质量库存处置", "/qualityStatus/index", 5);

    private final String code;

    private final String name;

    private final String url;

    private final Integer sort;

    /**
     * 根据url获取枚举
     *
     * @param url 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static LiveStockMenuTypeEnum getByUrl(String url) {
        for (LiveStockMenuTypeEnum type : values()) {
            if (type.getUrl().equals(url)) {
                return type;
            }
        }
        return null;
    }
}
