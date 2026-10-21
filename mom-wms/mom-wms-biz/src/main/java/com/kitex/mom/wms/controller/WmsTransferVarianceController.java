package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.dto.WmsTransferVarianceDTO;
import com.kitex.mom.wms.entity.WmsTransferVarianceCaseEntity;
import com.kitex.mom.wms.service.WmsTransferVarianceService;
import com.kitex.mom.wms.vo.WmsTransferVarianceCaseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsTransferVariance")
@Tag(name = "调拨分批接收与短溢差异治理")
public class WmsTransferVarianceController {
    private final WmsTransferVarianceService service;

    @PostMapping("/receive")
    @SysLog("调拨分批接收")
    @Operation(summary = "调拨分批或最终接收")
    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationReceive_rec_*','wms_wmsAllocationReceive_rec')")
    public R<WmsTransferReceiptDTO.Result> receive(@RequestBody WmsTransferReceiptDTO.Command command) {
        return R.ok(service.receive(command));
    }

    @GetMapping("/page")
    @Operation(summary = "查询调拨短溢差异单")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_view')")
    public R<Page<WmsTransferVarianceCaseEntity>> page(@ParameterObject SearchPage page,
                                                       @ParameterObject WmsTransferVarianceDTO.CaseQuery query) {
        return R.ok(service.queryCases(page.toMpPage(), query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取差异单、逐行容差、接收批次和操作履历")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_view')")
    public R<WmsTransferVarianceCaseVO> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/review/warehouse")
    @SysLog("调拨差异仓库审核")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_warehouseReview')")
    public R<Boolean> warehouseReview(@RequestBody WmsTransferVarianceDTO.ReviewCommand command) {
        service.warehouseReview(command);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/review/plan")
    @SysLog("调拨差异计划审核")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_planReview')")
    public R<Boolean> planReview(@RequestBody WmsTransferVarianceDTO.ReviewCommand command) {
        service.planReview(command);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/review/quality")
    @SysLog("调拨差异质量审核")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_qualityReview')")
    public R<Boolean> qualityReview(@RequestBody WmsTransferVarianceDTO.ReviewCommand command) {
        service.qualityReview(command);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/resubmit")
    @SysLog("调拨差异补充后重提")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_resubmit')")
    public R<Boolean> resubmit(@RequestBody WmsTransferVarianceDTO.ResubmitCommand command) {
        service.resubmit(command);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/dispose")
    @SysLog("调拨差异最终处置")
    @PreAuthorize("@pms.hasPermission('wms_transferVariance_dispose')")
    public R<Boolean> dispose(@RequestBody WmsTransferVarianceDTO.DispositionCommand command) {
        service.dispose(command);
        return R.ok(Boolean.TRUE);
    }
}
