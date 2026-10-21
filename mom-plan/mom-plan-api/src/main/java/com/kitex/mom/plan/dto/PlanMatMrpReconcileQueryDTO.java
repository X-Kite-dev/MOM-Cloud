package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Set;

@Data
@Accessors(chain = true)
public class PlanMatMrpReconcileQueryDTO {
    private Set<Long> sourceIds;
}
