package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Schema(description = "排产甘特明细")
public class PlnSchedulingGanttDetailVO {

    @Schema(description = "计划ID")
    private Long issueId;

    @Schema(description = "计划号")
    private String issueNo;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "规格型号")
    private String specificationModel;

    @Schema(description = "数量")
    private BigDecimal num;

    @Schema(description = "需求时间")
    private LocalDate demandTime;

    @Schema(description = "分配日期")
    private LocalDate scheduledDate;

    @Schema(description = "完工日期")
    private LocalDate scheduledEndDate;

    @Schema(description = "下达时间")
    private LocalDateTime issueTime;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "是否锁定")
    private Boolean lockStatus;

    @Schema(description = "计划类型")
    private String planType;

    @Schema(description = "任务编号")
    private String taskCode;

    @Schema(description = "工艺路线编号")
    private String routeCode;
}
