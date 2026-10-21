package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 复测工作台当前权威关闭状态；不以MES链接缓存冒充QMS事实。
 */
@Data
@Accessors(chain = true)
public class MesChangePointEffectRetestContextDTO {
    @Schema(description = "当前租户和工厂的改善来源")
    private MesChangePointImprovementEntity source;

    @Schema(description = "QMS权威状态")
    private String capaStatus;

    @Schema(description = "QMS当前周期")
    private Integer cycleNo;

    @Schema(description = "QMS当前周期关闭时刻")
    private LocalDateTime closedTime;

    @Schema(description = "原CAPA有效性判定标准，独立复核须确认复测目标与之相符")
    private String effectivenessCriteria;
}
