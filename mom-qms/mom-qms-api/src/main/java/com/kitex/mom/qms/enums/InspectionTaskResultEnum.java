package com.kitex.mom.qms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质检任务状态
 *
 * @author kitex
 * @since 2025/02/25
 */
@Getter
@AllArgsConstructor
public enum InspectionTaskResultEnum {

    /**
     * 合格
     */
    QUALIFIED,
    /**
     * 不合格
     */
    UN_QUALIFIED,
    /**
     * 作废
     */
    CANCELED,
    /**
     * 免检
     */
    EXEMPTION,
    /**
     * 紧急放行
     */
    RELEASE,
    /**
     * 让步接收
     */
    RECEIVE;
}
