package com.kitex.mom.mes.enums;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;

/**
 * 工序质量门控制模式。
 */
public enum QualityGateModeEnum {
    NONE,
    WARN,
    BLOCK;

    public static QualityGateModeEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return NONE;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new CheckedException("不支持的质量门模式：" + code);
        }
    }
}
