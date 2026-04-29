package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class TpmCalendarDto {

    @Schema(description = "保养任务")
    private List<String> maintenance;

    @Schema(description = "维修任务")
    private List<String> repair;

    @Schema(description = "点检任务")
    private List<String> inspection;

    @Schema(description = "巡检任务")
    private List<String> patrol;

}
