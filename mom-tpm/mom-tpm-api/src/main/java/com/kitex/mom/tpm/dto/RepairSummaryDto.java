package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class RepairSummaryDto {

    @Schema(description = "维修提报")
    private Integer totalRepairs;

    @Schema(description = "待派工")
    private Integer pendingAssignment;

    @Schema(description = "已派工")
    private Integer assigned;

    @Schema(description = "内部维修")
    private Integer internalTasks;

    @Schema(description = "委外维修任务")
    private Integer externalTasks;

    @Schema(description = "内部已处理")
    private Integer internalCompleted;

    @Schema(description = "外部已处理")
    private Integer externalCompleted;


}
