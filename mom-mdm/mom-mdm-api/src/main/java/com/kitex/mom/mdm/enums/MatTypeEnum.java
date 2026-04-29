package com.kitex.mom.mdm.enums;

/**
 * 物料型态
 *
 * @author kitex
 * @date 2025-09-04 09:48:05
 */
public enum MatTypeEnum {

    PRODUCT("product", "产品"),

    MAT("mat", "原材料"),

    SEMI("semi", "半成品"),
    SECONDARY("secondary", "副产品");

    private final String code;
    private final String label;

    MatTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 根据编码获取名称
     */
    public static String getLabelByCode(String code) {
        for (MatTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type.getLabel();
            }
        }
        return null;
    }

    /**
     * 根据名称获取代码
     */
    public static String getCodeByLabel(String label) {
        for (MatTypeEnum type : values()) {
            if (type.getLabel().equals(label)) {
                return type.getCode();
            }
        }
        return null;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}