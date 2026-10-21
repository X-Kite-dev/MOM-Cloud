package com.kitex.mom.tpm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CheckFailureActionEnum {
    WARN("WARN", "仅告警"),
    BLOCK("BLOCK", "封锁生产"),
    REPAIR("REPAIR", "转维修并封锁");

    private final String code;
    private final String name;

    public static boolean supports(String code) {
        for (CheckFailureActionEnum value : values()) {
            if (value.code.equals(code)) return true;
        }
        return false;
    }

    public static int priority(String code) {
        if (REPAIR.code.equals(code)) return 3;
        if (BLOCK.code.equals(code)) return 2;
        return 1;
    }
}
