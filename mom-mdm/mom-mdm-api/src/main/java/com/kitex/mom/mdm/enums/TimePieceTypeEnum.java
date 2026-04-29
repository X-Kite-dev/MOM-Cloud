package com.kitex.mom.mdm.enums;

/**
 * 计时计件枚举
 *
 * @author kitex
 * @date 2025-09-04 09:10:56
 */
public enum TimePieceTypeEnum {

    TIME("0", "计时"),
    PIECE("1", "计件");

    private final String code;
    private final String name;

    TimePieceTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 根据编码获取名称
     */
    public static String getNameByCode(String code) {
        for (TimePieceTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type.getName();
            }
        }
        return null;
    }

    /**
     * 根据名称获取代码
     */
    public static String getCodeByName(String name) {
        for (TimePieceTypeEnum type : values()) {
            if (type.getName().equals(name)) {
                return type.getCode();
            }
        }
        return null;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}