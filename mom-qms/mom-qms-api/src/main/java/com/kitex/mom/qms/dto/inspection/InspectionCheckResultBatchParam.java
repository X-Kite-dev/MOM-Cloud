package com.kitex.mom.qms.dto.inspection;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class InspectionCheckResultBatchParam {

    @Schema(description = "质检任务id")
    private Long taskId;

    @Schema(description = "附件")
    private String annex;

    @Schema(description = "质检项结果")
    private List<InspectionCheckResultParam> results;


}
