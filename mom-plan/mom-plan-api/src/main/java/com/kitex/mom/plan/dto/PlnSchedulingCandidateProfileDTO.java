package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "APS多候选目标配置")
public class PlnSchedulingCandidateProfileDTO {
    private String candidateCode;
    private String candidateName;
    @Schema(description = "DELIVERY_FIRST、BALANCED_GLOBAL或CHANGEOVER_FIRST")
    private String sequenceStrategy;
    @Schema(description = "排序求解器；省略保持历史GREEDY_WINDOW口径")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String sequenceSolverCode;
    @Schema(description = "允许跨原优先顺序择优的交期窗口天数")
    private Integer crossPriorityWindowDays;
    private BigDecimal deliveryWeight;
    private BigDecimal changeoverWeight;
    private BigDecimal cycleWeight;
    private BigDecimal waitingWeight;
    @Schema(description = "资源日负荷均衡权重")
    private BigDecimal loadBalanceWeight;
    @Schema(description = "在制时间面积权重")
    private BigDecimal wipWeight;
    @Schema(description = "超出资源可用产能所需加班权重")
    private BigDecimal overtimeWeight;
    @Schema(description = "相对来源计划原定日期的排程扰动权重")
    private BigDecimal disturbanceWeight;
}
