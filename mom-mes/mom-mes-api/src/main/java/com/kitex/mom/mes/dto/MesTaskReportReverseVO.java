package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 报工冲销命令。
 */
@Data
@JsonIgnoreProperties(value = {"reverseRequestBusinessId", "evidenceFingerprint"}, allowGetters = true)
@Schema(description = "报工冲销命令")
public class MesTaskReportReverseVO {

    @Schema(description = "原报工记录ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long reportId;

    @Schema(description = "冲销请求唯一号，用于网络重试幂等", maxLength = 64)
    private String requestId;

    @Schema(description = "冲销原因", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500)
    private String reason;

    @Schema(description = "本次冲减数量；为空表示冲销当前全部有效数量")
    private BigDecimal correctionQuantity;

    @Schema(hidden = true)
    private Long reverseRequestBusinessId;

    @Schema(hidden = true)
    private String evidenceFingerprint;
}
