package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsWarehouseRulePreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仓库业务规则的统一只读试算入口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseRulePreview")
@Tag(name = "仓库业务规则试算")
public class WmsWarehouseRulePreviewController {
    private final WmsWarehouseRulePreviewService service;

    @GetMapping
    @Operation(summary = "组合试算仓库作业、标识和预留规则")
    @HasPermission("wms_wmsWarehouseMenu_view")
    public R<WmsWarehouseRulePreviewService.Preview> preview(@RequestParam String warehouseCode,
                                                               @RequestParam String billType,
                                                               @RequestParam boolean inbound,
                                                               @RequestParam(required = false) String materialCode) {
        return R.ok(service.preview(warehouseCode, billType, inbound, materialCode));
    }
}
