package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 变化点暂停与在制损失分析。
 */
@Data
@Accessors(chain = true)
@Schema(description = "变化点暂停与在制损失分析")
public class MesChangePointLossAnalysisDTO {

    @Schema(description = "统计开始时间，包含")
    private LocalDateTime startTime;

    @Schema(description = "统计结束时间，不包含")
    private LocalDateTime endTime;

    @Schema(description = "统计范围总计")
    private MesChangePointLossGroupDTO total;

    @Schema(description = "按4M1E类别聚合")
    private List<MesChangePointLossGroupDTO> byCategory;

    @Schema(description = "按变化点配置聚合")
    private List<MesChangePointLossGroupDTO> byChangePoint;

    @Schema(description = "按损失类型聚合")
    private List<MesChangePointLossGroupDTO> byLossCategory;
}
