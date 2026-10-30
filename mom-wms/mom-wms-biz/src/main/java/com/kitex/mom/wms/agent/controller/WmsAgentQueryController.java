package com.kitex.mom.wms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.wms.agent.dto.*;
import com.kitex.mom.wms.agent.service.*;
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
    private final WmsStockDispositionQueryService stockDispositionService;
    private final WmsStockDispositionReceiptService stockDispositionReceiptService;
    private final WmsPurchaseReceiptAvailabilityService purchaseReceiptAvailabilityService;

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

    /**
     * 精确库存行取证只按WMS实际库存查看权限开放，不复用工厂级问数权限别名。
     */
    @Inner
    @PostMapping("/internal/stock-disposition")
    @PreAuthorize("@pms.hasPermission('wms_wmsLiveStock_view')")
    public R<WmsStockDispositionDTO.Evidence> stockDisposition(
            @Valid @RequestBody WmsStockDispositionDTO.Query query) {
        return R.ok(stockDispositionService.query(query));
    }

    /**
     * 正式质量库存事务含来源案件和实物执行单，额外要求该岗位具备事务台账查看权。
     */
    @Inner
    @PostMapping("/internal/stock-disposition-receipts")
    @PreAuthorize("@pms.hasPermission('wms_wmsLiveStock_view') and @pms.hasPermission('wmsQualityStatus:show')")
    public R<WmsStockDispositionReceiptDTO.Evidence> stockDispositionReceipts(
            @Valid @RequestBody WmsStockDispositionReceiptDTO.Query query) {
        return R.ok(stockDispositionReceiptService.query(query));
    }

    /**
     * 来料可用Agent按采购收料来源主键读取通知、正式入库和当前库存证据。
     */
    @Inner
    @PostMapping("/internal/purchase-receipt-availability")
    @PreAuthorize("@pms.hasPermission('wms_wmsInNotice_view') and @pms.hasPermission('wms_wmsLiveStock_view')")
    public R<WmsPurchaseReceiptAvailabilityDTO.Evidence> purchaseReceiptAvailability(
            @Valid @RequestBody WmsPurchaseReceiptAvailabilityDTO.Query query) {
        return R.ok(purchaseReceiptAvailabilityService.query(query));
    }

}
