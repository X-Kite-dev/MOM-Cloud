package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlnObjectiveTemplateCommand;
import com.kitex.mom.plan.service.impl.PlnObjectiveTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/objective-template")
public class PlnObjectiveTemplateController {
    private final PlnObjectiveTemplateService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<?> page(@RequestParam(defaultValue = "1") long page, @RequestParam(required = false) String code, @RequestParam(defaultValue = "false") boolean activeOnly) {
        return R.ok(service.page(page, code, activeOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<?> history(@PathVariable Long id) {
        return R.ok(service.history(id));
    }

    @PostMapping("/{action}")
    @PreAuthorize("@pms.hasPermission(#action == 'PROPOSE' || #action == 'WITHDRAW' ? 'plan_objectiveTemplate_propose' : 'plan_objectiveTemplate_review')")
    public R<?> execute(@PathVariable String action, @RequestBody PlnObjectiveTemplateCommand c) {
        return R.ok(service.execute(action, c));
    }
}
