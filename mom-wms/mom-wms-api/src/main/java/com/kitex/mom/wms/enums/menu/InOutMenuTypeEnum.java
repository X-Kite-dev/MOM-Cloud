package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 入库出库业务菜单
 * 通知单和单
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum InOutMenuTypeEnum {

    /**
     * 入库通知单
     */
    IN_NOTICE(1, "入库通知单", "/inNotice/index", "in"),
    /**
     * 入库单
     */
    IN_BILL(2, "入库单", "/inBill/index", "in"),
    /**
     * 出库通知单
     */
    OUT_NOTICE(1, "出库通知单", "/outNotice/index", "out"),
    /**
     * 出库单
     */
    OUT_BILL(2, "出库单", "/outBill/index", "out");

    private final Integer sort;

    private final String name;

    /**
     * 相对路径（字典值拼上这个就是组件实际位置）
     */
    private final String url;

    private final String type;

    /**
     * 根据url获取枚举
     *
     * @param url 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static InOutMenuTypeEnum getByUrl(String url) {
        for (InOutMenuTypeEnum type : values()) {
            if (type.getUrl().equals(url)) {
                return type;
            }
        }
        return null;
    }
}
