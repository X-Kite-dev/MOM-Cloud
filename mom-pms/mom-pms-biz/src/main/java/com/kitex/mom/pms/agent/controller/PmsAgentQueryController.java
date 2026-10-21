package com.kitex.mom.pms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import com.kitex.mom.pms.agent.service.PmsProcurementRiskQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PMS 面向智能体的受控只读语义查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsAgentQuery")
public class PmsAgentQueryController {

    private final PmsProcurementRiskQueryService procurementRiskService;

    @Inner
    @PostMapping("/internal/procurement-risk")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    public R<PmsProcurementRiskDTO.Evidence> procurementRisk(
            @Valid @RequestBody PmsProcurementRiskDTO.Query query) {
        return R.ok(procurementRiskService.query(query));
    }
}
