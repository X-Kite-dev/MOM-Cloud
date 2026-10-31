package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesPlanCompletionDTO;
import com.kitex.mom.mes.service.impl.MesPlanCompletionOutboxService;
import com.kitex.mom.mes.service.impl.MesPlanCompletionProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/plan-completion/internal")
public class MesPlanCompletionController {
    private final MesPlanCompletionOutboxService outboxes;
    private final MesPlanCompletionProgressService progress;

    @Inner
    @GetMapping("/evidence")
    public R<MesPlanCompletionDTO.Evidence> evidence(
            @RequestParam Long outboxId, @RequestParam String factoryCode) {
        return R.ok(outboxes.evidence(outboxId, factoryCode));
    }

    @Inner
    @GetMapping("/progress")
    public R<MesPlanCompletionDTO.Progress> progress(
            @RequestParam Long outboxId,
            @RequestParam Long issueId,
            @RequestParam String issueNo,
            @RequestParam String factoryCode) {
        return R.ok(progress.progress(outboxId, issueId, issueNo, factoryCode));
    }
}
