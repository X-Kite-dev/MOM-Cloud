package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "在制超时处置验证命令")
public class WipTimeoutVerifyCommand {
    private Long id;
    private String requestId;
    private String verificationResult;
    private String verificationRemark;
}
