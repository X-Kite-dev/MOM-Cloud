package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知单状态
 *
 * @author kitex
 * @date 2024年12月11日 11:23:47
 **/
@Getter
@AllArgsConstructor
public enum InOutNoticeStateEnum {

    /**
     * 待处理
     */
    READY("0", "待处理"),
    /**
     * 处理中
     */
    RUNNING("1", "处理中"),
    /**
     * 已完成
     */
    FINISHED("2", "已完成"),
    /**
     * 已作废
     */
    CANCELED("3", "已作废"),
    ;

    private final String code;

    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static InOutNoticeStateEnum getByCode(String code) {
        for (InOutNoticeStateEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
