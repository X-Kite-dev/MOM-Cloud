package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 变化点损失聚合项。
 */
@Data
@Accessors(chain = true)
@Schema(description = "变化点损失聚合项")
public class MesChangePointLossGroupDTO {

    @Schema(description = "分组编码；总计时为TOTAL")
    private String groupCode;

    @Schema(description = "分组名称")
    private String groupName;

    @Schema(description = "已完成在制核清的变化点事件数")
    private Long eventCount;

    @Schema(description = "配置自动暂停的事件数")
    private Long autoPauseRequiredCount;

    @Schema(description = "暂停结果不明或设备已暂停但MES落账失败的事件数")
    private Long pauseFailureCount;

    @Schema(description = "受影响在制数量合计")
    private BigDecimal affectedWipQty;

    @Schema(description = "放行数量合计")
    private BigDecimal releasedWipQty;

    @Schema(description = "返工数量合计")
    private BigDecimal reworkWipQty;

    @Schema(description = "报废数量合计")
    private BigDecimal scrapWipQty;

    @Schema(description = "确认损失金额合计")
    private BigDecimal lossAmount;

    @Schema(description = "已关闭事件的平均闭环分钟数")
    private BigDecimal averageCloseMinutes;
}
