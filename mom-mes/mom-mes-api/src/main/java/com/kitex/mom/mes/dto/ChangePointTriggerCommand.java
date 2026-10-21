package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "变化点触发命令")
public class ChangePointTriggerCommand {
    private String requestId;
    private String configCode;
    private String taskCode;
    private String produceTaskCode;
    private String arrangeCode;
    private String transferCode;
    private String lineCode;
    private String routeCode;
    private String productCode;
    private String deviceCode;
    private String worksiteCode;
    private BigDecimal actualValue;
    private String actualSituation;
    @Schema(description = "MANUAL/IOT/MES/MDM/QMS/TPM/INTEGRATION")
    private String triggerSource;
}
