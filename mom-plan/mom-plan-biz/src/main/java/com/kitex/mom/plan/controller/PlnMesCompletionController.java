package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlanMesCompletionDTO;
import com.kitex.mom.plan.service.impl.PlnMesCompletionService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mes-completion/internal")
public class PlnMesCompletionController {
    private final PlnMesCompletionService completion;

    @Inner
    @PostMapping("/reconcile")
    public R<PlanMesCompletionDTO.Receipt> reconcile(
            @RequestBody PlanMesCompletionDTO.Command command) {
        return R.ok(completion.reconcile(command));
    }
}
