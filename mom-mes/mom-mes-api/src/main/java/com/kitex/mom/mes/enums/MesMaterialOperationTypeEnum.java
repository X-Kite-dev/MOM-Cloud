package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 领退料操作类型枚举
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Getter
@AllArgsConstructor
public enum MesMaterialOperationTypeEnum {

    PICK("PICK", "领料"),
    RETURN("RETURN", "退料");

    private final String code;
    private final String message;

    public static MesMaterialOperationTypeEnum getByCode(String code) {
        for (MesMaterialOperationTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return PICK;
    }
}