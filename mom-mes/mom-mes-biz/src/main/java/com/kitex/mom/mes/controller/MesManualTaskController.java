package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesManualTaskCommand;
import com.kitex.mom.mes.dto.MesManualTaskView;
import com.kitex.mom.mes.service.impl.MesManualTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/manual")
@PreAuthorize("@pms.hasPermission('mesTask:add')")
public class MesManualTaskController {
    private final MesManualTaskService service;

    @GetMapping("/engineering-candidates")
    public R<MesManualTaskView.Candidates> candidates(@RequestParam String productCode) {
        return R.ok(service.candidates(productCode));
    }

    @PostMapping
    public R<MesManualTaskView.Receipt> create(@RequestBody MesManualTaskCommand command) {
        return R.ok(service.create(command));
    }

    @PostMapping("/resolve")
    public R<MesManualTaskView.Receipt> resolve(@RequestBody MesManualTaskCommand command) {
        return R.ok(service.resolve(command));
    }

    @PostMapping("/cancel")
    public R<MesManualTaskView.Receipt> cancel(@RequestBody MesManualTaskCommand command) {
        return R.ok(service.cancel(command));
    }
}
