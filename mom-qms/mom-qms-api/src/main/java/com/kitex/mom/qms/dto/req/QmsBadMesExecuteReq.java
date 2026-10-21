package com.kitex.mom.qms.dto.req;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QmsBadMesExecuteReq {
    private Long detailId;
    private String decisionKey;
    private String requestId;
    private String evidence;
    private String locationCode;
    private String barcode;
    private BigDecimal actualQuantity;
    private Boolean confirmed;
}
