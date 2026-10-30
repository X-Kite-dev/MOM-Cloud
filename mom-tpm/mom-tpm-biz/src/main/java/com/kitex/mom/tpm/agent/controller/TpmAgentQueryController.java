package com.kitex.mom.tpm.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.tpm.agent.dto.TpmEquipmentOverviewDTO;
import com.kitex.mom.tpm.agent.dto.TpmRepairInvestigationDTO;
import com.kitex.mom.tpm.agent.service.TpmEquipmentOverviewQueryService;
import com.kitex.mom.tpm.agent.service.TpmRepairInvestigationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TPM 面向智能体的受控只读语义查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmAgentQuery")
public class TpmAgentQueryController {

    private final TpmEquipmentOverviewQueryService equipmentOverviewService;
    private final TpmRepairInvestigationQueryService repairInvestigationService;

    @Inner
    @PostMapping("/internal/equipment-overview")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_view') and "
            + "@pms.hasPermission('tpm_tpmMaintenanceTask_view') and "
            + "@pms.hasPermission('tpm_tpmCheckTask_view') and "
            + "(@pms.hasPermission('tpm_tpmRepairTask_internal_view') or "
            + "@pms.hasPermission('tpm_tpmRepairTask_outsourcing_view'))")
    public R<TpmEquipmentOverviewDTO.Evidence> equipmentOverview(
            @Valid @RequestBody TpmEquipmentOverviewDTO.Query query) {
        return R.ok(equipmentOverviewService.query(query));
    }

    /** 单任务取证只复用原TPM查看权；不开放维修提交、验证或设备恢复写动作。 */
    @Inner
    @PostMapping("/internal/repair-investigation")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_internal_view','tpm_tpmRepairTask_outsourcing_view')")
    public R<TpmRepairInvestigationDTO.Evidence> repairInvestigation(
            @Valid @RequestBody TpmRepairInvestigationDTO.Query query) {
        return R.ok(repairInvestigationService.query(query));
    }
}
