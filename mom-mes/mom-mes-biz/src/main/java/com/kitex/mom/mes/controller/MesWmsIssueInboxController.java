package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesWmsIssueEvent;
import com.kitex.mom.mes.service.impl.MesWmsIssueInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWmsIssue/internal")
public class MesWmsIssueInboxController {
    private final MesWmsIssueInboxService service;

    @Inner
    @PostMapping("/receive")
    public R<MesWmsIssueEvent.Receipt> receive(@RequestBody MesWmsIssueEvent event) {
        return R.ok(service.receive(event));
    }

    @Inner
    @GetMapping("/receipt")
    public R<MesWmsIssueEvent.Receipt> receipt(@RequestParam String eventId) {
        return R.ok(service.receipt(eventId));
    }
}
