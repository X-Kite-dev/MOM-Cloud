package com.kitex.mom.common.message.constant.enums;

import lombok.Getter;

/**
 * 消息推送类型
 *
 * @author kitex
 * @date 2025-01-14 10:39:26
 */
@Getter
public enum MessageTypeEnum {

    TEXT("text", "文本"),
    LINK("link", "链接");

    private String type;

    private String desc;

    MessageTypeEnum(String type, String desc) {
        this.type = type;
        this.desc = desc;
    }

}
