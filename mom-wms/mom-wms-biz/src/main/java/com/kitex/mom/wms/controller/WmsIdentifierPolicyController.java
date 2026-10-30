package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import com.kitex.mom.wms.service.WmsIdentifierPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsIdentifierPolicy")
@Tag(description = "wmsIdentifierPolicy", name = "WMS批次条码规则")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsIdentifierPolicyController {
    private final WmsIdentifierPolicyService service;

    @Operation(summary = "查询当前工厂规则")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('wms_identifierPolicy_view')")
    public R<List<WmsIdentifierPolicyEntity>> list() {
        return R.ok(service.listPolicies());
    }

    @Operation(summary = "预览物料与入库类型命中的规则")
    @GetMapping("/resolve")
    public R<WmsIdentifierPolicyEntity> resolve(@RequestParam String billType,
                                                @RequestParam String materialCode) {
        return R.ok(service.resolveEffective(billType, materialCode));
    }

    @Operation(summary = "保存或停用批次条码规则")
    @PostMapping
    @SysLog("保存WMS批次条码规则")
    @PreAuthorize("@pms.hasPermission('wms_identifierPolicy_edit')")
    public R<WmsIdentifierPolicyEntity> save(@RequestBody WmsIdentifierPolicyEntity policy) {
        return R.ok(service.savePolicy(policy));
    }
}
