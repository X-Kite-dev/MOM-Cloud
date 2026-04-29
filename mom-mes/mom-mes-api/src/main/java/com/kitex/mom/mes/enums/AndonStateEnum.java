package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 安灯呼叫配置级别
 *
 * @author nave
 * @since 2021年12月06日 17:14
 */
@Getter
@AllArgsConstructor
public enum AndonStateEnum {

    /**
     * 未接收
     */
    UN_RECEIVE("un_receive", "未接收"),

    UN_HANDLE("un_handle", "未处理"),
    UP_UN_RECEIVE("up_un_receive", "升级未接收"),
    UP_UN_HANDLE("up_un_handle", "升级未处理"),
    HANDLED("handled", "已处理"),
    CLOSED("closed", "已关闭"),
    /**
     * 已撤销
     */
    CANCEL("cancel", "已撤销");

    private String code;

    private String name;

}
