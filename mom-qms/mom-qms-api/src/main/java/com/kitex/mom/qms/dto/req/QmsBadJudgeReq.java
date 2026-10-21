package com.kitex.mom.qms.dto.req;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 不良判定
 *
 * @author kitex
 * @since 2025-01-21
 */
@Data
public class QmsBadJudgeReq {

    private String requestId;

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
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal num;

}
