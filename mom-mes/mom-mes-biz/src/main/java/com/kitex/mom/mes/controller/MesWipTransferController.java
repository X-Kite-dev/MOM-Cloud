package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesWipTransferCommand;
import com.kitex.mom.mes.service.impl.MesWipTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer")
public class MesWipTransferController {
    private final MesWipTransferService service;

    @GetMapping("/source/{id}")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_dispatch')")
    public R source(@PathVariable Long id) {
        return R.ok(service.source(id));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
    public R page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "OPEN") String status,
                  @RequestParam(required = false) Long sourceReportId, @RequestParam(required = false) String matCode) {
        return R.ok(service.page(current, status, sourceReportId, matCode));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
    public R detail(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.detail(id, current));
    }

    @PostMapping("/command/{operation}")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_' + #operation)")
    @SysLog("在制实物转运交接")
    public R command(@PathVariable String operation, @RequestBody MesWipTransferCommand command) {
        command.setOperationType(operation.toUpperCase(Locale.ROOT));
        return R.ok(service.execute(command));
    }
}
