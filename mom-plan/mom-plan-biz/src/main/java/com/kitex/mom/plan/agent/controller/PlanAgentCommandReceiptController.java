package com.kitex.mom.plan.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.agent.service.PlanAgentCommandCodec;
import com.kitex.mom.plan.agent.service.PlanAgentCommandReceiptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 固定只读永久回执核查入口；UNKNOWN不能作为自动重试写入的依据。
 */
@RestController
@RequestMapping("/planAgentCommand")
public class PlanAgentCommandReceiptController {
    private final PlanAgentCommandReceiptService receipts;

    public PlanAgentCommandReceiptController(PlanAgentCommandReceiptService receipts) {
        this.receipts = receipts;
    }

    @PostMapping("/receipt")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute') or @pms.hasPermission('agent:scheduling:run')")
    public R<Map<String, Object>> receipt(@RequestBody PlanAgentCommandCodec.LookupQuery query) throws Exception {
        return R.ok(receipts.lookup(query));
    }
}
