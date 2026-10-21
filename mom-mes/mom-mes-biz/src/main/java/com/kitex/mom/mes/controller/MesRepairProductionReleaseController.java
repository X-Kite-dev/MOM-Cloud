package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesRepairProductionReleaseCommand;
import com.kitex.mom.mes.service.impl.MesRepairProductionReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/repair-production-release")
public class MesRepairProductionReleaseController {
    private final MesRepairProductionReleaseService service;

    /** 只编码原服务事务代理已回滚的专用拒绝，不把通用code=1解释成未执行。 */
    @ExceptionHandler(MesRepairProductionReleaseService.CommandNotAcceptedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<?> commandNotAccepted(MesRepairProductionReleaseService.CommandNotAcceptedException failure) {
        return R.failed(failure.getOutcome(), failure.getMessage());
    }

    @GetMapping("/page")
    public R<?> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String arrangeCode, @RequestParam(required = false) String deviceCode,
                     @RequestParam(defaultValue = "false") boolean exactArrange) {
        return R.ok(service.page(current, size, arrangeCode, deviceCode, exactArrange));
    }

    @GetMapping("/{stopId}")
    public R<?> view(@PathVariable Long stopId, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.view(stopId, current));
    }

    @PostMapping("/{stopId}/request")
    @SysLog("申请维修停工派工复产")
    public R<?> request(@PathVariable Long stopId, @RequestBody MesRepairProductionReleaseCommand command) {
        return R.ok(service.request(stopId, command));
    }

    @PostMapping("/{stopId}/decide")
    @SysLog("审批或撤回维修停工派工复产")
    public R<?> decide(@PathVariable Long stopId, @RequestBody MesRepairProductionReleaseCommand command) {
        return R.ok(service.decide(stopId, command));
    }
}
