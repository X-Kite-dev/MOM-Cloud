package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class MesMaterialReceiptCommand {
    private String requestId;
    private Long noticeId;
    /**
     * 本批实际签收量，不是累计量。
     */
    private BigDecimal quantity;
    /**
     * 页面读取时的累计已收量，防止陈旧操作。
     */
    private BigDecimal expectedReceivedQuantity;
    private String siteCode;
    private String reason;
    /**
     * 冲销时指定原签收操作。
     */
    private Long sourceReceiptId;
}
