package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.Cancel;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.Command;
import com.kitex.mom.mes.service.impl.MesWipDeliverySimulationService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/deliverySimulation")
@PreAuthorize("@pms.hasPermission('mes_wipTransfer_dispatch')")
public class MesWipDeliverySimulationController {
    private final MesWipDeliverySimulationService service;

    @GetMapping("/transfer/{id}")
    public R overview(@PathVariable String id) {
        return R.ok(service.overview(id));
    }

    @GetMapping("/task/{id}")
    public R task(@PathVariable String id) {
        return R.ok(service.task(id));
    }

    @PostMapping("/receipt")
    public R receipt(@RequestBody Command c) {
        return R.ok(service.inspect(c));
    }

    @PostMapping("/execute")
    @SysLog("记录在制配送模拟事件")
    public R execute(@RequestBody Command c) {
        return R.ok(service.execute(c));
    }

    @PostMapping("/cancel")
    @SysLog("终止未执行的配送模拟原请求")
    public R cancel(@RequestBody Cancel c) {
        return R.ok(service.cancel(c));
    }
}
