package com.kitex.mom.iot.parameter;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 普通业务入口；没有可由调用方伪造的真实设备回执写口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/process-parameters/commands")
@PreAuthorize(IotParameterCommandService.READ)
public class IotParameterCommandController {
    private final IotParameterCommandService commands;
    private final IotParameterCommandCoordinator coordinator;
    private final IotParameterPreparationService preparation;

    @GetMapping
    public R<?> list(@RequestParam String taskCode, @RequestParam(required = false) String before) {
        return R.ok(commands.list(taskCode, before));
    }

    @GetMapping("/preparation-options")
    public R<IotParameterPreparationService.Options> options(
            @RequestParam String taskCode,
            @RequestParam(required = false) String arrangeCode,
            @RequestParam(required = false) String deviceCode) {
        return R.ok(preparation.query(taskCode, arrangeCode, deviceCode));
    }

    @GetMapping("/{requestId}")
    public R<IotParameterCommandDTO.Detail> detail(@PathVariable String requestId) {
        return R.ok(commands.detail(requestId));
    }

    @PostMapping
    @PreAuthorize(IotParameterCommandService.WRITE)
    @SysLog("准备模拟参数命令")
    public R<IotParameterCommand> prepare(@RequestBody IotParameterCommandDTO.Prepare body) {
        return R.ok(commands.prepare(body));
    }

    @PostMapping("/{requestId}/request")
    @PreAuthorize(IotParameterCommandService.WRITE)
    @SysLog("登记模拟参数下发请求")
    public R<IotParameterCommand> request(@PathVariable String requestId) {
        return R.ok(commands.request(requestId));
    }

    @PostMapping("/{requestId}/simulate")
    @PreAuthorize(IotParameterCommandService.WRITE)
    @SysLog("模拟设备处理原参数命令")
    public R<IotParameterCommandDTO.Detail> simulate(@PathVariable String requestId) {
        return R.ok(coordinator.simulate(requestId));
    }

    @PostMapping("/{requestId}/recover")
    @PreAuthorize(IotParameterCommandService.WRITE)
    @SysLog("按原号核对模拟参数设备日志")
    public R<IotParameterCommandDTO.Detail> recover(@PathVariable String requestId) {
        return R.ok(coordinator.recover(requestId));
    }
}
