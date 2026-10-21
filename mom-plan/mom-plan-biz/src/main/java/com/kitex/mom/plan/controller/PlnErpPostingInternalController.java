package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;
import com.kitex.mom.plan.service.PlnErpIntegrationOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MES向PLAN转交已提交执行事实的内部入口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/erpPosting/internal")
public class PlnErpPostingInternalController {
    private final PlnErpIntegrationOutboxService service;

    @Inner
    @PostMapping("/enqueue")
    public R<String> enqueue(@RequestBody PlnErpPostingEnvelope envelope) {
        PlnErpIntegrationOutboxEntity row = service.enqueue(envelope);
        return R.ok(row.getId().toString());
    }
}
