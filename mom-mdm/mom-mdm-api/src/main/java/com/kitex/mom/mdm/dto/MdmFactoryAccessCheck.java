package com.kitex.mom.mdm.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 内部工厂访问核验：调用方从已认证身份取userId，不能信任浏览器传入的工厂上下文。
 */
@Data
@Accessors(chain = true)
public class MdmFactoryAccessCheck {
    private Long userId;
    private String factoryCode;
    /**
     * read/write/admin
     */
    private String requiredPermission;
}
