package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "单条生产计划的物料可用性约束评估")
public class PlnMaterialConstraintEvaluationDTO {
    private Long issueId;
    private String issueNo;
    private String status;
    private Boolean hardBlocked;
    private LocalDate materialAvailableDate;
    private LocalDateTime materialAvailableTime;
    private String bomPathFingerprint;
    private Boolean bomLineageVerified;
    private String summary;
    private List<PlnMaterialConstraintLineDTO> lines;
    private List<PlnOperationMaterialConstraintDTO> operationConstraints;
    private LocalDateTime checkedTime;
}
