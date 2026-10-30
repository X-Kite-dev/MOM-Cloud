package com.kitex.mom.plan.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.oms.agent.dto.OmsDeliveryPromiseDTO;
import com.kitex.mom.oms.agent.dto.OmsProductionScopeDTO;
import com.kitex.mom.plan.agent.service.OmsDeliveryPromiseQueryService;
import com.kitex.mom.plan.agent.service.OmsProductionScopeQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OMS/PLAN供智能体调用的受控只读查询入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/omsAgentQuery")
public class OmsAgentQueryController {
    private final OmsDeliveryPromiseQueryService service;
    private final OmsProductionScopeQueryService productionScopeService;

    /**
     * 订单生产范围同时受OMS查询权限及MES工单权限约束。
     */
    @Inner(value = false)
    @PostMapping("/internal/production-scope")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsProductionScopeDTO.Evidence> productionScope(
            @Valid @RequestBody OmsProductionScopeDTO.Query query) {
        return R.ok(productionScopeService.query(query));
    }

    @Inner(value = false)
    @PostMapping("/internal/delivery-promise")
    @PreAuthorize("@pms.hasPermission('oms_omsOrderBaseTrack_view')")
    public R<OmsDeliveryPromiseDTO.Evidence> deliveryPromise(
            @Valid @RequestBody OmsDeliveryPromiseDTO.Query query) {
        return R.ok(service.query(query));
    }

}
