package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsWarehouseLocationMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仓库库位图：位置配置与实时库存足迹。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseLocationMap")
@Tag(description = "wmsWarehouseLocationMap", name = "仓库库位图")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsWarehouseLocationMapController {
    private final WmsWarehouseLocationMapService maps;

    @Operation(summary = "读取仓库库位图和实时占用")
    @GetMapping
    public R<WmsWarehouseLocationMapService.MapView> load(@RequestParam String warehouseCode) {
        return R.ok(maps.load(warehouseCode));
    }

    @Operation(summary = "保存仓库库位图坐标")
    @PostMapping
    @HasPermission("wms_wmsWarehouseMenu_add")
    @SysLog("保存仓库库位图")
    public R<WmsWarehouseLocationMapService.Saved> save(
            @RequestBody WmsWarehouseLocationMapService.SaveCommand command) {
        return R.ok(maps.save(command));
    }
}
