package com.kitex.mom.mdm.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * 工厂正式 MRP 权威；不提供运行时自动猜测模式。
 */
@Getter
@RequiredArgsConstructor
public enum MrpAuthorityEnum {
    ERP_MRP("ERP主导MRP"),
    MOM_MRP("MOM主导MRP");

    private final String description;

    public static Optional<MrpAuthorityEnum> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
    }

    public static boolean isValid(String code) {
        return fromCode(code).isPresent();
    }
}
