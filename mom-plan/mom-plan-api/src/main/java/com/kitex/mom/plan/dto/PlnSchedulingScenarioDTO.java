package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "APS多候选情景及完整候选任务")
public class PlnSchedulingScenarioDTO {
    private PlnSchedulingScenarioEntity scenario;
    private List<PlnSchedulingTaskEntity> candidates;
}
