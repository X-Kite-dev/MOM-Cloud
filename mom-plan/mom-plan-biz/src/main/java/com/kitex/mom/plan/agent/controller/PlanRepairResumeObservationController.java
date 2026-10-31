package com.kitex.mom.plan.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.agent.dto.PlanRepairResumeObservationDTO;
import com.kitex.mom.plan.agent.service.PlanRepairResumeObservationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN 维修复工事件处理及观察账的受控只读 Agent 取证入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnAgentQuery")
public class PlanRepairResumeObservationController {

    private final PlanRepairResumeObservationQueryService service;

    @Inner
    @PostMapping("/internal/repair-resume-observations")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<PlanRepairResumeObservationDTO.Evidence> repairResumeObservations(
            @Valid @RequestBody PlanRepairResumeObservationDTO.Query query) {
        return R.ok(service.query(query));
    }
}
