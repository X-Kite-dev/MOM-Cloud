package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 封存未受理原号只关闭该完整原命令，不冲销已经成功的生产事实。
 */
@Data
@Schema(description = "报工未受理原号封存输入")
public class MesReportRequestCloseVO {

    /**
     * 必须原样携带持久保存的原公开输入，不重新解析当前外码。
     */
    @Schema(description = "原始完整报工输入")
    private MesTaskReportVO command;

    /**
     * 操作者说明重新采集的依据，永久保存，不静默换号。
     */
    @Schema(description = "封存原因，最长500字符")
    private String reason;
}
