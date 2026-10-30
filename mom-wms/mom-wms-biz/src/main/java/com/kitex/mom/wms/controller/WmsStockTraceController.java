package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsStockTraceDTO;
import com.kitex.mom.wms.service.impl.WmsStockTraceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/material/wmsStockTrace")
@Tag(description = "wmsStockTrace", name = "库存实物追溯")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsStockTraceController {
    private final WmsStockTraceQueryService service;

    @Operation(summary = "查询库存位置和出入库流水证据")
    @GetMapping
    @PreAuthorize("@pms.hasPermission('wms_stockTrace_view')")
    public R<WmsStockTraceDTO.Evidence> query(@ParameterObject WmsStockTraceDTO.Query query) {
        return R.ok(service.query(query));
    }
}
