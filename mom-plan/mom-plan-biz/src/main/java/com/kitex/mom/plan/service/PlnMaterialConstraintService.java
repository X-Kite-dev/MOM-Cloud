package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlnMaterialConstraintEvaluationDTO;
import com.kitex.mom.plan.entity.PlnIssueEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PlnMaterialConstraintService {
    /**
     * 批量评估生产计划的物料可用日期与硬阻断状态，结果按计划ID返回。
     */
    Map<Long, PlnMaterialConstraintEvaluationDTO> evaluate(List<PlnIssueEntity> issues, LocalDate baselineDate);
}
