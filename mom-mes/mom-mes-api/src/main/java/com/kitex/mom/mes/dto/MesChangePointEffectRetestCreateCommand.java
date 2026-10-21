package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冻结既有CAPA来源的复测窗口与目标，所有事实由服务端重采。
 */
@Data
public class MesChangePointEffectRetestCreateCommand {
    @Schema(description = "永久请求号，最多100字")
    private String requestId;

    @Schema(description = "改善来源单主键")
    private Long improvementId;

    @Schema(description = "已结束的复测窗口开始，包含")
    private LocalDateTime windowStartTime;

    @Schema(description = "复测窗口结束，不包含")
    private LocalDateTime windowEndTime;

    @Schema(description = "EVENT_DAILY/LOSS_DAILY/PAUSE_FAILURE_RATE")
    private String metric;

    @Schema(description = "要求下降百分比，0至100，最多2位小数；须与CAPA原验证标准相符")
    private BigDecimal requiredReductionPercent;

    @Schema(description = "冻结基线每个产品最少有效报工事实数，1至10000")
    private Long minimumReportSamples;

    @Schema(description = "未达标复开新周期的关闭期限，1至365天")
    private Integer reopenDueDays;
}
