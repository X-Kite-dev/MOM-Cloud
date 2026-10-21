package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesWipLossCommand;
import com.kitex.mom.mes.service.impl.MesWipLossService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/loss")
public class MesWipLossController {
    private final MesWipLossService service;

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
    public R context(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.context(id, page));
    }

    @PostMapping("/command/{operation}")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_loss_' + #operation)")
    @SysLog("在途丢失核销")
    public R execute(@PathVariable String operation, @RequestBody MesWipLossCommand command) {
        command.setOperation(operation.toUpperCase(Locale.ROOT));
        return R.ok(service.execute(command));
    }
}
