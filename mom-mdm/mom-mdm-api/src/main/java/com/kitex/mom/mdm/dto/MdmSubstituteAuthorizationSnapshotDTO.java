package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Schema(description = "替代料权威授权冻结快照")
public class MdmSubstituteAuthorizationSnapshotDTO {
    private Long authorizationId;
    private String authorizationType;
    private String partnerCode;
    private String partnerName;
    private String authorizationNo;
    private LocalDate validStart;
    private LocalDate validEnd;
    private String inspectionLevel;
    private Long qualityStandardId;
    private String qualityStandardName;
    private BigDecimal referenceUnitPrice;
    private String currency;
}
