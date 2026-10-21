package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "变化点现场处置与在制损失核清命令")
public class ChangePointHandleCommand {

    @Schema(description = "变化点事件主键", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "客户端生成的幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "根本原因", requiredMode = Schema.RequiredMode.REQUIRED)
    private String rootCause;

    @Schema(description = "纠正措施", requiredMode = Schema.RequiredMode.REQUIRED)
    private String correctiveAction;

    @Schema(description = "预防措施")
    private String preventiveAction;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "预计关闭时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime expectedCloseTime;

    @Schema(description = "现场处置说明", requiredMode = Schema.RequiredMode.REQUIRED)
    private String handleDesc;

    @Schema(description = "现场确认的受影响在制数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal affectedWipQty;

    @Schema(description = "受影响在制中的放行数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal releasedWipQty;

    @Schema(description = "受影响在制中的返工数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal reworkWipQty;

    @Schema(description = "受影响在制中的报废数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal scrapWipQty;

    @Schema(description = "本变化点确认的损失金额", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal lossAmount;

    @Schema(description = "主损失类型：SCRAP/REWORK/DOWNTIME/MATERIAL/QUALITY/OTHER")
    private String lossCategory;

    @Schema(description = "损失处置及金额测算说明")
    private String lossRemark;
}
