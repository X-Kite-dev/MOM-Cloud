package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 工序进程
 *
 * @author kitex
 * @since 2025-04-14
 */
@Data
public class RouteProcessDTO {

    @Schema(description = "工序任务编码")
    private String produceTaskCode;

    @Schema(description = "工单编码")
    private String taskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料规格")
    private String matSpec;

    @Schema(description = "工作中心编码")
    private String worksiteCodes;

    @Schema(description = "工作中心")
    private String worksiteNames;

    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "百分比")
    private BigDecimal percent;

}
