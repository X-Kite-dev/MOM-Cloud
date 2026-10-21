package com.kitex.mom.mdm.enums;

import java.util.Arrays;

/** 主替料正式决策的权责边界。 */
public enum SubstituteGovernanceModeEnum {
    ERP_FROZEN,
    ERP_ENVELOPE,
    MOM_APPROVAL;

    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(code));
    }
}
