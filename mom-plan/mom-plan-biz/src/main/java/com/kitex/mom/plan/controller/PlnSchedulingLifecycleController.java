package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnSchedulingLifecycleCommand;
import com.kitex.mom.plan.service.impl.PlnSchedulingLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnScheduling/lifecycle")
public class PlnSchedulingLifecycleController {
    private final PlnSchedulingLifecycleService service;

    @GetMapping("/{taskId}")
    public R<Map<String, Object>> preview(@PathVariable Long taskId, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.preview(taskId, current));
    }

    @PostMapping("/{taskId}")
    @SysLog("排产任务取消或结果归档")
    public R<Map<String, Object>> apply(@PathVariable Long taskId, @RequestBody PlnSchedulingLifecycleCommand command) {
        return R.ok(service.apply(taskId, command));
    }

    @GetMapping("/operation/{id}")
    public R<?> operation(@PathVariable Long id) {
        return R.ok(service.operation(id));
    }
}
