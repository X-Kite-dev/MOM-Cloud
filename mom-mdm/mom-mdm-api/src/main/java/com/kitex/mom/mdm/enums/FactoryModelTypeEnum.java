package com.kitex.mom.mdm.enums;

/**
 * 工厂建模节点类型枚举
 *
 * @author claude
 * @date 2025-07-30
 */
public enum FactoryModelTypeEnum {

    INDUSTRY("INDUSTRY", "产业", 1),
    FACTORY("FACTORY", "工厂", 2),
    WORKSHOP("WORKSHOP", "车间", 3),
    WAREHOUSE("WAREHOUSE", "仓库", 3),
    LINE("LINE", "线体", 4),
    STATION("STATION", "工位", 5);

    private final String code;
    private final String name;
    private final Integer level;

    FactoryModelTypeEnum(String code, String name, Integer level) {
        this.code = code;
        this.name = name;
        this.level = level;
    }

    /**
     * 根据代码获取枚举
     */
    public static FactoryModelTypeEnum getByCode(String code) {
        for (FactoryModelTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 根据级别获取允许的节点类型
     */
    public static FactoryModelTypeEnum[] getByLevel(Integer level) {
        switch (level) {
            case 1:
                return new FactoryModelTypeEnum[]{INDUSTRY};
            case 2:
                return new FactoryModelTypeEnum[]{FACTORY};
            case 3:
                return new FactoryModelTypeEnum[]{WORKSHOP, WAREHOUSE};
            case 4:
                return new FactoryModelTypeEnum[]{LINE};
            case 5:
                return new FactoryModelTypeEnum[]{STATION};
            default:
                return new FactoryModelTypeEnum[]{};
        }
    }

    /**
     * 根据名称获取代码
     */
    public static String getCodeByName(String name) {
        for (FactoryModelTypeEnum type : values()) {
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

    public Integer getLevel() {
        return level;
    }
}