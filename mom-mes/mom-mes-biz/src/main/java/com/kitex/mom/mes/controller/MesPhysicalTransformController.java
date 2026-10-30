package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO;
import com.kitex.mom.mes.service.impl.MesPhysicalTransformSourceFreezeService;
import com.kitex.mom.mes.service.impl.MesPhysicalTransformSourceService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 实物转换第一段只读核对；不提供execute、通用CRUD、条码生成或强制放行。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPhysicalTransform")
@PreAuthorize("@pms.hasPermission('mes_mesWarehouseLiveStock_view') or @pms.hasPermission('mes_wipTransfer_dispatch')")
public class MesPhysicalTransformController {

    private final MesPhysicalTransformSourceService service;
    private final MesPhysicalTransformSourceFreezeService frozenSources;

    /**
     * 精确来源ID的完整同范围观察，所有ID及数量文本交付。
     */
    @Operation(summary = "实物转换来源完整核对")
    @GetMapping("/sources")
    public R<MesPhysicalTransformDTO.SourceView> sources(@RequestParam List<String> stockIds) {
        return R.ok(service.sources(stockIds));
    }

    /**
     * 写域/父行/全图保护内的新V2全文观察；不是持久冻结、claim或生产放行。
     */
    @Operation(summary = "实物转换原工单与工程完整来源V2核对")
    @GetMapping("/sources-v2")
    public R<MesPhysicalTransformSourceBundleDTO> sourcesV2(@RequestParam List<String> stockIds) {
        return R.ok(frozenSources.observe(stockIds));
    }

    /**
     * 严格完整JSON数学预检；任何结果都不能用作生产执行许可。
     */
    @Operation(summary = "实物转换投入产出守恒预检")
    @PostMapping(value = "/preview", consumes = "application/json")
    public R<MesPhysicalTransformDTO.Preview> preview(@RequestBody String rawJson) {
        return R.ok(service.preview(rawJson));
    }
}
