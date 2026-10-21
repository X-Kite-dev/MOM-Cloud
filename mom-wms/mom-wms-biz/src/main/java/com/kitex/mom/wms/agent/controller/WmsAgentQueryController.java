package com.kitex.mom.wms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.agent.dto.WmsBatchStockTraceDTO;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import com.kitex.mom.wms.agent.service.WmsBatchStockTraceQueryService;
import com.kitex.mom.wms.agent.service.WmsInventoryHealthQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WMS 面向智能体的受控只读语义查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsAgentQuery")
public class WmsAgentQueryController {

    private final WmsInventoryHealthQueryService inventoryHealthService;
    private final WmsBatchStockTraceQueryService batchStockTraceService;

    @Inner
    @PostMapping("/internal/inventory-health")
    @PreAuthorize("@pms.hasPermission('wms_wmsLiveStock_view')")
    public R<WmsInventoryHealthDTO.Evidence> inventoryHealth(
            @Valid @RequestBody WmsInventoryHealthDTO.Query query) {
        return R.ok(inventoryHealthService.query(query));
    }

    @Inner
    @PostMapping("/internal/batch-stock-trace")
    @PreAuthorize("@pms.hasPermission('wms_wmsLiveStock_view')")
    public R<WmsBatchStockTraceDTO.Evidence> batchStockTrace(
            @Valid @RequestBody WmsBatchStockTraceDTO.Query query) {
        return R.ok(batchStockTraceService.query(query));
    }

}
