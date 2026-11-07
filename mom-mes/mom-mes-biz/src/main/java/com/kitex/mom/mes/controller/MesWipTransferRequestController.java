package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesWipTransferRequestDTO.*;
import com.kitex.mom.mes.service.impl.MesWipTransferRequestService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/request")
public class MesWipTransferRequestController {
    private final MesWipTransferRequestService service;

    @GetMapping("/context")
    @PreAuthorize(
            "@pms.hasPermission('mes_wipTransfer_dispatch') or"
                    + " @pms.hasPermission('mes_wipTransfer_receive') or"
                    + " @pms.hasPermission('mes_wipTransfer_request_return') or"
                    + " @pms.hasPermission('mes_wipTransfer_receive_return') or"
                    + " @pms.hasPermission('mes_wipTransfer_receive_quarantine') or"
                    + " @pms.hasPermission('mes_wipTransfer_hold') or"
                    + " @pms.hasPermission('mes_wipTransfer_resume')")
    public R context() {
        return R.ok(service.context());
    }

    @PostMapping("/{operation}/receipt")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_' + #operation)")
    public R receipt(@PathVariable String operation, @RequestBody Envelope original) {
        return R.ok(service.inspect(operation, original));
    }

    @PostMapping("/{operation}/execute")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_' + #operation)")
    @SysLog("原号执行在制实物转运")
    public R execute(@PathVariable String operation, @RequestBody Envelope original) {
        return R.ok(service.execute(operation, original));
    }

    @PostMapping("/{operation}/cancel")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_' + #operation)")
    @SysLog("终止未执行的转运原请求")
    public R cancel(@PathVariable String operation, @RequestBody Cancel cancel) {
        return R.ok(service.cancel(operation, cancel));
    }
}
