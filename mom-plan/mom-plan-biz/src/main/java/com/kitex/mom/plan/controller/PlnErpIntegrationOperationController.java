package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;
import com.kitex.mom.plan.dto.PlnErpReconciliationDTO;
import com.kitex.mom.plan.service.PlnErpIntegrationOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 计划员查看ERP投递、死信并执行受控人工重试。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/erpIntegrationOperation")
public class PlnErpIntegrationOperationController {
    private final PlnErpIntegrationOutboxService service;

    @GetMapping("/recent")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_view')")
    public R<List<PlnErpIntegrationOutboxEntity>> recent(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(service.recent(limit));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_view')")
    public R<PlnErpReconciliationDTO> reconciliation(@RequestParam(defaultValue = "200") int limit) {
        return R.ok(service.reconcile(limit));
    }

    @PostMapping("/retry")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_publish')")
    public R<PlnErpIntegrationOutboxEntity> retry(@RequestParam Long id, @RequestParam String reason) {
        return R.ok(service.retry(id, reason));
    }
}
