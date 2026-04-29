package com.kitex.mom.qms.dto.req;

import lombok.Data;

/**
 * 不良判定
 *
 * @author kitex
 * @since 2025-01-21
 */
@Data
public class QmsBadJudgeReq {

    private Long id;

    /**
     * 不良类型
     */
    private String badType;

    /**
     * 不良类型
     */
    private String badTypeName;

    /**
     * 不良原因
     */
    private String badReason;

    /**
     * 判定数量
     */
    private Integer num;

}
