package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MesCaseDispositionCommand {
    private Long detailId;
    private String decisionKey;
    private String requestId;
    private String stage;
    private String evidence;
    private String locationCode;
    private String barcode;
    private BigDecimal actualQuantity;
    private Boolean confirmed;
}
