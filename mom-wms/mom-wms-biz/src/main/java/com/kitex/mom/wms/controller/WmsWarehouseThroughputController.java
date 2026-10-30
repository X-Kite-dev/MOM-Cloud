package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.service.WmsWarehouseThroughputService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅按有单据查看权限的方向提供可复算的单据审核时效。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseDashboard")
@Tag(name = "仓库运营看板")
public class WmsWarehouseThroughputController {
    private final WmsWarehouseThroughputService service;

    @GetMapping("/throughput")
    @Operation(summary = "按仓查询近期开单审核量与审核历时")
    public R<WmsWarehouseThroughputService.Throughput> throughput(@RequestParam String warehouseCode,
                                                                   @RequestParam(defaultValue = "7") int days) {
        return R.ok(service.query(warehouseCode, days));
    }
}
