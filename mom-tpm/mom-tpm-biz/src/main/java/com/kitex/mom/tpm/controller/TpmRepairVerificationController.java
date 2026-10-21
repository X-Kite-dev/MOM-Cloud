package com.kitex.mom.tpm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.TpmRepairVerificationCommand;
import com.kitex.mom.tpm.service.impl.TpmRepairVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/repair-verification")
public class TpmRepairVerificationController {
    private final TpmRepairVerificationService service;

    @GetMapping("/context/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_internal_view','tpm_tpmRepairTask_outsourcing_view')")
    public R<?> context(@PathVariable Long id) {
        return R.ok(service.context(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_internal_view','tpm_tpmRepairTask_outsourcing_view')")
    public R<?> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    @GetMapping("/recovery-evidence/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmRepairTask_internal_view','tpm_tpmRepairTask_outsourcing_view')")
    public R<?> recoveryEvidence(@PathVariable Long id) {
        return R.ok(service.recoveryEvidence(id));
    }

    @PostMapping("/{action}")
    @PreAuthorize("@pms.hasPermission(#action == 'SUBMIT' || #action == 'WITHDRAW' ? 'tpm_repairVerification_submit' : 'tpm_repairVerification_verify')")
    public R<?> execute(@PathVariable String action, @RequestBody TpmRepairVerificationCommand c) {
        return R.ok(service.execute(action, c));
    }
}
