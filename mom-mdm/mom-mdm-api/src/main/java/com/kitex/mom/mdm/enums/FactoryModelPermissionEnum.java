package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工厂建模权限类型枚举
 *
 * @author claude
 * @date 2025-07-30
 */
@Getter
@AllArgsConstructor
public enum FactoryModelPermissionEnum {

    /**
     * 只读权限
     */
    READ("read", "只读"),

    /**
     * 读写权限
     */
    WRITE("write", "读写"),

    /**
     * 管理员权限
     */
    ADMIN("admin", "管理");

    private final String code;
    private final String desc;

    /**
     * 根据code获取枚举
     */
    public static FactoryModelPermissionEnum getByCode(String code) {
        for (FactoryModelPermissionEnum permission : values()) {
            if (permission.getCode().equals(code)) {
                return permission;
            }
        }
        return null;
    }

    /**
     * 检查是否有指定权限
     * 权限等级：READ < WRITE < ADMIN
     */
    public boolean hasPermission(FactoryModelPermissionEnum requiredPermission) {
        if (requiredPermission == null) {
            return true;
        }

        switch (this) {
            case ADMIN:
                return true; // 管理员权限包含所有权限
            case WRITE:
                return requiredPermission != ADMIN; // 写权限包含读权限，但不包含管理权限
            case READ:
                return requiredPermission == READ; // 读权限只包含读权限
            default:
                return false;
        }
    }
}