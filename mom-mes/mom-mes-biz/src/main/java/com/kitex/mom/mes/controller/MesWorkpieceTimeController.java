package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWorkpieceTimeDTO;
import com.kitex.mom.mes.service.impl.MesWorkpieceTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 只登记现场计时动作，生产开工和数量仍由原派工/报工入口办理。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/workpiece-time")
public class MesWorkpieceTimeController {

    private final MesWorkpieceTimeService service;

    @PostMapping("/commands")
    @PreAuthorize("((#p0.action == 'START' or #p0.action == 'RESUME') and @pms.hasPermission('mesProduceTask:start'))"
            + " or (#p0.action == 'PAUSE' and @pms.hasPermission('mesProduceTask:pause'))"
            + " or (#p0.action == 'FINISH' and @pms.hasPermission('mesProduceTask:finish'))")
    public R<?> execute(@RequestBody MesWorkpieceTimeDTO.Command command) {
        return R.ok(service.execute(command));
    }

    @PostMapping("/cancel")
    @PreAuthorize("((#p0.action == 'START' or #p0.action == 'RESUME') and @pms.hasPermission('mesProduceTask:start'))"
            + " or (#p0.action == 'PAUSE' and @pms.hasPermission('mesProduceTask:pause'))"
            + " or (#p0.action == 'FINISH' and @pms.hasPermission('mesProduceTask:finish'))")
    public R<?> cancel(@RequestBody MesWorkpieceTimeDTO.Command command) {
        return R.ok(service.cancel(command));
    }

    @GetMapping("/{arrangeId:[0-9]+}")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show','mesTaskArrange:show','mesProduceTask:start','mesProduceTask:pause','mesProduceTask:finish')")
    public R<?> view(@PathVariable Long arrangeId, @RequestParam(defaultValue = "1") int sessionPage,
                     @RequestParam(defaultValue = "1") int intervalPage) {
        return R.ok(service.view(arrangeId, sessionPage, intervalPage));
    }

    @GetMapping("/receipt/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesProduceTask:show','mesTaskArrange:show','mesProduceTask:start','mesProduceTask:pause','mesProduceTask:finish')")
    public R<?> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }
}
