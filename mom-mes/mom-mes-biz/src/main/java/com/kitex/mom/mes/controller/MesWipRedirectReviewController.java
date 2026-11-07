package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWipRedirectReviewDTO.*;
import com.kitex.mom.mes.service.impl.MesWipRedirectReviewService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/redirectReview")
@PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
public class MesWipRedirectReviewController {
    private final MesWipRedirectReviewService service;
    private final com.kitex.mom.mes.service.impl.MesWipRedirectActivationService activation;

    @GetMapping("/{transfer}/overview")
    public R overview(@PathVariable String transfer) {
        return R.ok(service.overview(transfer));
    }

    @GetMapping("/proposal/{id}")
    public R proposal(@PathVariable String id) {
        return R.ok(service.proposal(id));
    }

    @GetMapping("/proposal/{id}/preview")
    public R preview(@PathVariable String id) {
        return R.ok(service.preview(id));
    }

    @GetMapping("/proposal/{id}/activationPreview")
    public R activationPreview(@PathVariable String id) {
        return R.ok(activation.preview(id));
    }

    @PostMapping("/inspect")
    public R inspect(@RequestBody Command c) {
        return R.ok("ACTIVATE".equals(c.action()) ? activation.inspect(c) : service.inspect(c));
    }

    @PostMapping("/execute")
    public R execute(@RequestBody Command c) {
        return R.ok("ACTIVATE".equals(c.action()) ? activation.execute(c) : service.execute(c));
    }

    @PostMapping("/cancel")
    public R cancel(@RequestBody Cancel c) {
        return R.ok(
                c.original() != null && "ACTIVATE".equals(c.original().action())
                        ? activation.cancel(c)
                        : service.cancel(c));
    }
}
