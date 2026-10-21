package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@Schema(description = "生产计划工序级有限产能排程结果")
public class PlnOperationScheduleEvaluationDTO {
    private Long issueId;
    private String status;
    private Boolean hardBlocked;
    private String summary;
    private Integer operationCount;
    private LocalDateTime firstStartTime;
    private LocalDateTime lastEndTime;
}
