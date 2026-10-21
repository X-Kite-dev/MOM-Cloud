package com.kitex.mom.tpm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.dto.TpmRepairNoticeCommand;
import com.kitex.mom.tpm.service.impl.TpmRepairNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 维修提醒来源、投递回执与结果不明的人工核对。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/repair-notice")
public class TpmRepairNoticeController {
    private final TpmRepairNoticeService notices;

    @GetMapping("/task/{taskId}")
    @PreAuthorize("@pms.hasPermission('tpm_repairNotice_view')")
    public R<Object> task(@PathVariable Long taskId) {
        return R.ok(notices.ledger(taskId));
    }

    @PostMapping("/resolve")
    @PreAuthorize("@pms.hasPermission('tpm_repairNotice_resolve')")
    public R<Object> resolve(@RequestBody TpmRepairNoticeCommand command) {
        return R.ok(notices.resolve(command));
    }
}
