package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsAllocationReturnExceptionDTO;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionEntity;
import com.kitex.mom.wms.entity.WmsAllocationReturnExceptionLineEntity;
import com.kitex.mom.wms.service.WmsAllocationReturnExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsAllocationReturnException")
@Tag(name = "调拨退回异常")
public class WmsAllocationReturnExceptionController {
    private final WmsAllocationReturnExceptionService service;

    @GetMapping("/page")
    @Operation(summary = "查询当前工厂退回异常审核任务")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_dispose')")
    public R<Page<WmsAllocationReturnExceptionEntity>> page(@ParameterObject SearchPage page,
            @ParameterObject WmsAllocationReturnExceptionDTO.CaseQuery query) {
        return R.ok(service.page(page.toMpPage(), query));
    }

    @PostMapping("/report")
    @SysLog("申报调拨退回异常")
    @Operation(summary = "冻结待退实物的异常数量，不恢复库存")
    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_cancel_*','wms_wmsAllocationBill_cancel')")
    public R<WmsAllocationReturnExceptionEntity> report(@RequestBody WmsAllocationReturnExceptionDTO.Report command) {
        return R.ok(service.report(command));
    }

    @PostMapping("/review")
    @SysLog("审核调拨退回异常")
    @Operation(summary = "异人审核保管异常；批准后按处置单号关闭WMS实物责任")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_dispose')")
    public R<WmsAllocationReturnExceptionEntity> review(@RequestBody WmsAllocationReturnExceptionDTO.Review command) {
        return R.ok(service.review(command));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询退回异常申报及审核")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_dispose','wms_wmsAllocationBill_cancel_*','wms_wmsAllocationBill_cancel')")
    public R<WmsAllocationReturnExceptionEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/lines")
    @Operation(summary = "查询退回异常冻结明细")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_dispose','wms_wmsAllocationBill_cancel_*','wms_wmsAllocationBill_cancel')")
    public R<List<WmsAllocationReturnExceptionLineEntity>> lines(@PathVariable Long id) {
        return R.ok(service.lines(id));
    }
}
