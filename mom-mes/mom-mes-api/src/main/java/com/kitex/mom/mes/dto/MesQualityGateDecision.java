package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "工序质量门判定结果")
public class MesQualityGateDecision {

    @Schema(description = "是否允许继续业务")
    private Boolean allowed = true;

    @Schema(description = "参与判定的全部质检任务是否已正式放行")
    private Boolean passed = true;

    @Schema(description = "本工序累计报工数量")
    private java.math.BigDecimal reportedQuantity;

    @Schema(description = "本工序累计质量放行数量")
    private java.math.BigDecimal releasedQuantity;

    @Schema(description = "本工序累计待判数量")
    private java.math.BigDecimal pendingQuantity;

    @Schema(description = "本工序累计质量拒绝数量")
    private java.math.BigDecimal rejectedQuantity;

    @Schema(description = "控制模式 NONE/WARN/BLOCK")
    private String gateMode;

    @Schema(description = "判定阶段 REPORT/FINISH")
    private String phase;

    @Schema(description = "判定说明")
    private String message;

    @Schema(description = "参与判定的质检任务")
    private List<MesQualityGateItemDTO> items = new ArrayList<>();
}
