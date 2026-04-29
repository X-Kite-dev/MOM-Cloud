package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生产报工-不合格品填报
 *
 * @author kitex
 * @since 2025-05-13
 */
@Data
public class MesTaskReportUnqualifiedVO {

    @Schema(description = "流转码/条码")
    private String transferCode;

    @Schema(description = "不合格数量")
    private BigDecimal unPassNum;

    @Schema(description = "不合格类型")
    private String unqualifiedType;

    @Schema(description = "派工单号")
    private String arrangeCode;

    @Schema(description = "任务单号")
    private String taskCode;

    @Schema(description = "工序任务单号")
    private String produceTaskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料规格")
    private String matSpec;

    @Schema(description = "类型 手动 自动")
    private String type;

}
