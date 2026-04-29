package com.kitex.mom.mdm.enums;

/**
 * 工厂建模节点类型枚举
 *
 * @author claude
 * @date 2025-07-30
 */
public enum WarehouseModelTypeEnum {

    INDUSTRY("INDUSTRY", "产业"),
    FACTORY("FACTORY", "工厂"),
    WAREHOUSE("0", "仓库"),
    RESERVOIR("1", "库区"),
    SHELF("2", "货架"),
    LOCATION("3", "库位");

    private final String code;
    private final String name;

    WarehouseModelTypeEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * 根据代码获取枚举
     */
    public static WarehouseModelTypeEnum getByCode(String code) {
        for (WarehouseModelTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据名称获取代码
     */
    public static String getCodeByName(String name) {
        for (WarehouseModelTypeEnum type : values()) {
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