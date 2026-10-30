package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.service.WmsWarehouseAgingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前仓库库龄分布，沿用库存明细的按仓查看权限。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseDashboard")
@Tag(name = "仓库运营看板")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsWarehouseAgingController {
    private final WmsWarehouseAgingService service;

    @GetMapping("/aging")
    @Operation(summary = "按仓查询当前库存库龄分布")
    public R<WmsWarehouseAgingService.Aging> aging(@RequestParam String warehouseCode) {
        return R.ok(service.query(warehouseCode));
    }
}
