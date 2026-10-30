package com.kitex.mom.mdm.enums;

import java.util.Arrays;

/**
 * 生产订单独立执行或与ERP协作的正式边界。
 */
public enum ProductionOrderSyncModeEnum {
    MOM_ORDER_ONLY,
    ERP_ORDER_REQUIRED,
    MOM_ORDER_WITH_POSTING;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
    }

    public static boolean isLocalOnly(String code) {
        return MOM_ORDER_ONLY.name().equals(code);
    }
}
