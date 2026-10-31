package com.kitex.mom.plan.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.agent.dto.PlanIncomingAvailabilityImpactDTO;
import com.kitex.mom.plan.agent.service.PlanIncomingAvailabilityImpactQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN供智能体调用的受控只读查询入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/planAgentQuery")
public class PlanAgentQueryController {

    private final PlanIncomingAvailabilityImpactQueryService incomingImpactService;

    @Inner(value = false)
    @PostMapping("/internal/incoming-availability-impact")
    @PreAuthorize("@pms.hasPermission('agent:shortage:run')")
    public R<PlanIncomingAvailabilityImpactDTO.Evidence> incomingAvailabilityImpact(
            @Valid @RequestBody PlanIncomingAvailabilityImpactDTO.Query query) {
        return R.ok(incomingImpactService.query(query));
    }
}
