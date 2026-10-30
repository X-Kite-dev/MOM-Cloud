package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工单派工工作台的工单摘要。数量均来自当前租户、工厂的真实工序任务与派工记录。
 */
@Data
@Accessors(chain = true)
@Schema(description = "工单派工工作台行")
public class MesDispatchWorkbenchDTO {

    @Schema(description = "生产工单编码")
    private String taskCode;

    @Schema(description = "生产工单状态")
    private String status;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "工单需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "工单已完成数量")
    private BigDecimal completeNum;

    @Schema(description = "计划开始时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planStartDate;

    @Schema(description = "计划完成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime planEndDate;

    @Schema(description = "实际工序任务数")
    private Long processTaskCount;

    @Schema(description = "当前仍有活动派工的工序任务数")
    private Long activeProcessCount;

    @Schema(description = "全部派工记录数，包含完工和关闭记录")
    private Long dispatchCount;

    @Schema(description = "活动派工记录数")
    private Long activeDispatchCount;

    @Schema(description = "最近派工时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime latestDispatchTime;

    /**
     * 按工单聚合的真实工序任务数量。
     */
    @Data
    public static class ProcessCount {
        private String taskCode;
        private Long processTaskCount;
    }

    /**
     * 按工单聚合的派工履历及当前活动派工。
     */
    @Data
    public static class DispatchSummary {
        private String taskCode;
        private Long activeProcessCount;
        private Long dispatchCount;
        private Long activeDispatchCount;
        private LocalDateTime latestDispatchTime;
    }
}
