package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import lombok.Data;

import java.util.List;

@Data
public class PlnSchedulingRunInput {
    private PlnSchedulingRunDTO run;
    private java.time.LocalDate capturedDate;
    private List<PlnIssueEntity> sources;
    private PlnSchedulingRuleSetEntity rule;
    private String sequenceSolverSpec;
}
