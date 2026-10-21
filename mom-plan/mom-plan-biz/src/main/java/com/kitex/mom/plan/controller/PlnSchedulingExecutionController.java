package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnSchedulingExecutionCommand;
import com.kitex.mom.plan.service.impl.PlnSchedulingExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnScheduling/executions")
public class PlnSchedulingExecutionController {
    private final PlnSchedulingExecutionService service;

    @GetMapping("/solvers")
    public R<?> solvers() {
        return R.ok(service.solvers());
    }

    @GetMapping("/scenario-request")
    public R<?> scenarioRequest(@RequestParam String requestId) {
        return R.ok(service.lookupScenario(requestId));
    }

    @PostMapping("/scenario-preview")
    public R<?> scenarioPreview(@RequestBody com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO command) {
        return R.ok(service.previewScenario(command));
    }

    @GetMapping("/scenario/{id}/input")
    public R<?> scenarioInput(@PathVariable Long id) {
        return R.ok(service.scenarioInput(id));
    }

    @GetMapping("/task/{id}/sequence-proof")
    public R<?> proof(@PathVariable Long id) {
        return R.ok(service.sequenceProof(id));
    }

    @PostMapping("/preview")
    public R<?> preview(@RequestBody PlnSchedulingExecutionCommand command) {
        return R.ok(service.preview(command));
    }

    @PostMapping
    @SysLog("创建冻结排产运行版本")
    public R<?> submit(@RequestBody PlnSchedulingExecutionCommand command) {
        return R.ok(service.submit(command));
    }

    @GetMapping("/page")
    public R<?> page(@RequestParam(defaultValue = "1") long current, @RequestParam(required = false) String status) {
        return R.ok(service.page(current, status));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.detail(id, current));
    }

    @PostMapping("/{id}/execute")
    @SysLog("按冻结来源执行排产")
    public R<?> execute(@PathVariable Long id, @RequestBody PlnSchedulingExecutionCommand command) {
        return R.ok(service.execute(id, command));
    }

    @PostMapping("/{id}/cancel")
    @SysLog("请求取消排产运行")
    public R<?> cancel(@PathVariable Long id, @RequestBody PlnSchedulingExecutionCommand command) {
        return R.ok(service.cancel(id, command));
    }
}
