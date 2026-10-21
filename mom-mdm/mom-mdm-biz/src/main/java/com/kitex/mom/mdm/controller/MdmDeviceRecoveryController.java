package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmDeviceRecoveryCommand;
import com.kitex.mom.mdm.service.impl.MdmDeviceRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmDevice/controlled-recovery")
public class MdmDeviceRecoveryController {
    private final MdmDeviceRecoveryService service;

    @GetMapping("/preview/{verificationId}")
    @PreAuthorize("@pms.hasPermission('mdm_deviceRecovery_release')")
    public R<?> preview(@PathVariable Long verificationId) {
        return R.ok(service.preview(verificationId));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_deviceRecovery_release')")
    public R<?> release(@RequestBody MdmDeviceRecoveryCommand command) {
        return R.ok(service.release(command));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_deviceRecovery_release')")
    public R<?> receipt(@PathVariable Long id) {
        return R.ok(service.receipt(id));
    }

    @GetMapping("/by-verification/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_deviceRecovery_release')")
    public R<?> byVerification(@PathVariable Long id) {
        return R.ok(service.byVerification(id));
    }

    @Inner
    @GetMapping("/inner-event/{receiptId}")
    public R<com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence> eventEvidence(@PathVariable Long receiptId,
                                                                                 @RequestParam String factoryCode) {
        return R.ok(service.eventEvidence(receiptId, factoryCode));
    }

    @Inner
    @GetMapping("/inner-latest")
    public R<com.kitex.mom.mdm.dto.MdmDeviceRecoveryEventEvidence> latestEvidence(@RequestParam String deviceCode,
                                                                                  @RequestParam String factoryCode) {
        return R.ok(service.latestEvidence(deviceCode, factoryCode));
    }
}
