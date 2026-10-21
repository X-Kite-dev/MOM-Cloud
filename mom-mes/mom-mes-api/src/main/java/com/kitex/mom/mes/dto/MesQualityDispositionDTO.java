package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * QMS生产质量隔离、放行、让步和报废事实向MES幂等落账。
 */
@Data
@Accessors(chain = true)
public class MesQualityDispositionDTO {
    private String requestId;
    private String dispositionKind;
    private String inspectionType;
    private Long inspectionTaskId;
    private Long handleRecordId;
    private String sourceTaskCode;
    private String sourceProduceTaskCode;
    private String matCode;
    private String matName;
    private String matSpec;
    private String batchNo;
    private BigDecimal quantity;
    private BigDecimal taskQuantity;
    private BigDecimal usableQuantity;
    private BigDecimal handledQuantity;
    private String taskResult;
    private String reason;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime occurredTime;
    private String factoryCode;
}
