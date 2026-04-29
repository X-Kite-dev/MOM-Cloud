package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 安灯呼叫配置班次
 *
 * @author nave
 * @since 2021年12月06日 17:14
 */
@Getter
@AllArgsConstructor
public enum AndonNoticeTypeEnum {

    /**
     * 处理人通知
     */
    HANDLE("handle", "处理人"),
    /**
     * 通知人通知
     */
    NOTICE("notice", "通知人");

    private String code;

    private String name;

}
