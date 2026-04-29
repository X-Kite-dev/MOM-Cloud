package com.kitex.mom.common.message.constant.enums;

import lombok.Getter;

/**
 * 第三方消息推送类型
 *
 * @author kitex
 * @date 2025-01-14 15:27:52
 */
@Getter
public enum MessageSocialTypeEnum {

    DINGDING("DINGDING", "钉钉"),
    WECHAT("WECHAT", "企业微信");

    private String type;

    private String desc;

    MessageSocialTypeEnum(String type, String desc) {
        this.type = type;
        this.desc = desc;
    }

}
