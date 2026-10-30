package com.kitex.mom.wms.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.enums.menu.MenuOutBizTypeEnum;

import java.util.Map;
import java.util.Set;

/**
 * 其他出库的业务类型范围。标记和值存于仓库业务配置，不生成独立菜单。
 * 未设置标记的历史仓库继续按原“其他出库”配置放行子类型。
 */
public final class WmsOtherOutboundScope {
    public static final String MARKER = "wms:other_out:types";

    private static final String PARENT = MenuOutBizTypeEnum.OTHER_OUT.getValue();
    private static final Map<String, String> SUBTYPES = Map.of(
            "other_retail", "wms:other_out:other_retail",
            "other_outsource", "wms:other_out:other_outsource",
            "other_pms", "wms:other_out:other_pms");

    private WmsOtherOutboundScope() {
    }

    public static boolean isConfigurationValue(String value) {
        return MARKER.equals(value) || SUBTYPES.containsValue(value);
    }

    public static String selectionFor(String billType) {
        return SUBTYPES.get(billType);
    }

    public static void validateSelection(Set<String> selections) {
        boolean marker = selections.contains(MARKER);
        boolean hasSubtype = selections.stream().anyMatch(SUBTYPES::containsValue);
        if ((marker && !selections.contains(PARENT)) || (hasSubtype && !marker)) {
            throw new CheckedException("其他出库细分类型须与其他出库一起配置");
        }
    }
}
