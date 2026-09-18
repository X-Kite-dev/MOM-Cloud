package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class MesWarehouseFeedLeftDTO {

    private String taskCode;

    private String arrangeCode;

    private String produceTaskCode;

    private String productCode;

    private String productName;

    private String batchNo;

    private String routeCode;

    private String routeName;

    private String matCode;

    private String reservationNo;

    private String requirementMatCode;

    private Boolean substituteFlag;

    private BigDecimal substituteRatio;

    private String matName;

    private String matSpec;

    private String matUnit;

    private String status;

    private BigDecimal leftNum;

    private String judgeStatus;

    private Date lastUsedTime;

    private Boolean isLock;

    private String reason;

    private BigDecimal backNum;

    private String siteCode;

    private String factoryCode;

}
