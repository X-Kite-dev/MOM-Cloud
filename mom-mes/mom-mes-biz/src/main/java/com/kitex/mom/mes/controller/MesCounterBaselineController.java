package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesCounterBaselineCommand;
import com.kitex.mom.mes.service.impl.MesCounterBaselineService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAutoReport/counter-baseline")
public class MesCounterBaselineController {
    private final MesCounterBaselineService service;

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:show')")
    public R<?> context(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.context(id, page));
    }

    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:show')")
    public R<?> preview(@RequestBody MesCounterBaselineCommand c) {
        return R.ok(service.preview(c));
    }

    @PostMapping("/propose")
    @SysLog("申请累计计数基线交接")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:baselinePropose')")
    public R<?> propose(@RequestBody MesCounterBaselineCommand c) {
        c.setOperation("PROPOSE");
        return R.ok(service.execute(c));
    }

    @PostMapping("/approve")
    @SysLog("独立批准累计计数基线")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:baselineReview')")
    public R<?> approve(@RequestBody MesCounterBaselineCommand c) {
        c.setOperation("APPROVE");
        return R.ok(service.execute(c));
    }

    @PostMapping("/reject")
    @SysLog("驳回累计计数基线")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:baselineReview')")
    public R<?> reject(@RequestBody MesCounterBaselineCommand c) {
        c.setOperation("REJECT");
        return R.ok(service.execute(c));
    }

    @PostMapping("/withdraw")
    @SysLog("撤回累计计数基线")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:baselinePropose')")
    public R<?> withdraw(@RequestBody MesCounterBaselineCommand c) {
        c.setOperation("WITHDRAW");
        return R.ok(service.execute(c));
    }
}
