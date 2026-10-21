package com.kitex.mom.mdm.enums;

import java.util.Arrays;

/** MES 生产订单与 ERP 的正式同步边界。 */
public enum ProductionOrderSyncModeEnum {
    ERP_ORDER_REQUIRED,
    MOM_ORDER_WITH_POSTING;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
    }
}
