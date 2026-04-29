package com.kitex.mom.common.flow.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 钉钉审批实例事件类型
 *
 * @author kitex
 * @date 2025-2-8 16:59:28
 **/
@Getter
@AllArgsConstructor
public enum DingTalkInstanceEventTypeEnum {

    /**
     * start开始
     */
    START("start", "开始"),
    /**
     * finish结束
     */
    FINISH("finish", "结束"),
    /**
     * terminate终止
     */
    TERMINATE("terminate", "终止"),
    /**
     * 未知
     */
    UNKNOWN("unknown", "未知");

    /**
     * 编码
     */
    private final String code;

    /**
     * 名称
     */
    private final String name;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static DingTalkInstanceEventTypeEnum getByCode(String code) {
        for (DingTalkInstanceEventTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return DingTalkInstanceEventTypeEnum.UNKNOWN;
    }

}
