package com.kitex.mom.pms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import com.kitex.mom.pms.agent.dto.PmsIncomingAvailabilityDTO;
import com.kitex.mom.pms.agent.dto.PmsSupplierQualitySourceDTO;
import com.kitex.mom.pms.agent.service.PmsIncomingAvailabilityQueryService;
import com.kitex.mom.pms.agent.service.PmsProcurementRiskQueryService;
import com.kitex.mom.pms.agent.service.PmsSupplierQualitySourceQueryService;
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
    private final PmsIncomingAvailabilityQueryService incomingAvailabilityService;
    private final PmsSupplierQualitySourceQueryService supplierQualitySourceService;

    @Inner
    @PostMapping("/internal/procurement-risk")
    @PreAuthorize("@pms.hasPermission('pms_pmsOrder_view')")
    public R<PmsProcurementRiskDTO.Evidence> procurementRisk(
            @Valid @RequestBody PmsProcurementRiskDTO.Query query) {
        return R.ok(procurementRiskService.query(query));
    }

    /**
     * 采购收料来源只按当前工厂的精确收料单主键读取，供跨域来料可用Agent取证。
     */
    @Inner
    @PostMapping("/internal/incoming-availability")
    @PreAuthorize("@pms.hasPermission('wms_wmsPurchaseReceipt_view')")
    public R<PmsIncomingAvailabilityDTO.Evidence> incomingAvailability(
            @Valid @RequestBody PmsIncomingAvailabilityDTO.Query query) {
        return R.ok(incomingAvailabilityService.query(query));
    }

    /**
     * 供应商质量改善只从PMS供货物料真实主键进入，并由MDM复核同工厂供应商资格。
     */
    @Inner
    @PostMapping("/internal/supplier-quality-source")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_view')")
    public R<PmsSupplierQualitySourceDTO.Evidence> supplierQualitySource(
            @Valid @RequestBody PmsSupplierQualitySourceDTO.Query query) {
        return R.ok(supplierQualitySourceService.query(query));
    }
}
