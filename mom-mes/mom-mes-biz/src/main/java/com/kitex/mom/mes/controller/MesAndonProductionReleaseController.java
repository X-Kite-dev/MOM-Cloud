package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.AndonProductionReleaseCommand;
import com.kitex.mom.mes.service.impl.MesAndonPauseCoordinator;
import com.kitex.mom.mes.service.impl.MesAndonProductionReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAndonRecord/productionRelease")
public class MesAndonProductionReleaseController {
    private final MesAndonProductionReleaseService service;
    private final MesAndonPauseCoordinator pauseCoordinator;

    @GetMapping("/{id}")
    public R<?> view(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.view(id, current));
    }

    @PostMapping("/{id}/request")
    @SysLog("申请安灯复产")
    public R<?> request(@PathVariable Long id, @RequestBody AndonProductionReleaseCommand command) {
        return R.ok(service.request(id, command));
    }

    @PostMapping("/{id}/decide")
    @SysLog("审批或撤回安灯复产放行")
    public R<?> decide(@PathVariable Long id, @RequestBody AndonProductionReleaseCommand command) {
        return R.ok(service.decide(id, command));
    }

    @PostMapping("/{id}/pause")
    @SysLog("重试安灯派工暂停")
    public R<?> pause(@PathVariable Long id) {
        pauseCoordinator.retry(id);
        return R.ok();
    }

    @GetMapping("/{id}/pause-history")
    public R<?> pauseHistory(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(pauseCoordinator.history(id, page));
    }
}
