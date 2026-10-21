package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsSupplierReturnCommand;
import com.kitex.mom.wms.entity.WmsPmsSupplierReturnOutboxEntity;
import com.kitex.mom.wms.service.impl.WmsPmsSupplierReturnOutboxService;
import com.kitex.mom.wms.service.impl.WmsSupplierReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 采购到货后退供的 WMS 执行接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/supplierReturn")
@Tag(name = "采购退供WMS执行")
public class WmsSupplierReturnController {
    private final WmsSupplierReturnService service;
    private final WmsPmsSupplierReturnOutboxService outboxService;

    @PostMapping("/eligible-stocks")
    @Operation(summary = "查询可退供的精确库存行")
    public R<List<WmsSupplierReturnCommand.StockCandidate>> eligibleStocks(
            @RequestBody WmsSupplierReturnCommand.StockQuery query) {
        return R.ok(service.eligibleStocks(query));
    }

    @PostMapping("/notices")
    @Operation(summary = "幂等创建采购退供出库通知")
    public R<WmsSupplierReturnCommand.NoticeReceipt> createNotice(
            @RequestBody WmsSupplierReturnCommand.CreateNotice command) {
        return R.ok(service.createNotice(command));
    }

    @GetMapping("/outbox/page")
    @Operation(summary = "查询采购退供PMS回写事件")
    @PreAuthorize("@wms.hasPermission('wms_supplier_return_outbox_view')")
    public R<Page<WmsPmsSupplierReturnOutboxEntity>> outboxPage(
            @ParameterObject SearchPage page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventId) {
        return R.ok(outboxService.page(page, status, eventId));
    }

    @PostMapping("/outbox/{id}/retry")
    @Operation(summary = "人工续跑采购退供PMS回写")
    @PreAuthorize("@wms.hasPermission('wms_supplier_return_outbox_retry')")
    public R<WmsPmsSupplierReturnOutboxEntity> retry(
            @PathVariable Long id, @RequestParam String reason) {
        return R.ok(outboxService.retry(id, reason));
    }
}
