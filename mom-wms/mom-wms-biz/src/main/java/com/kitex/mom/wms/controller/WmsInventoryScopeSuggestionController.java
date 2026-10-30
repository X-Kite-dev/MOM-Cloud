package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.service.WmsInventoryScopePolicyService;
import com.kitex.mom.wms.service.WmsInventoryScopeSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Suggest count-plan draft scopes without changing inventory or creating a plan. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsInsideInventoryPlan")
@Tag(name = "盘点范围建议")
public class WmsInventoryScopeSuggestionController {
    private final WmsInventoryScopeSuggestionService service;
    private final WmsInventoryScopePolicyService policies;

    @GetMapping("/scope-policy")
    @Operation(summary = "读取仓库盘点建议默认规则")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
    public R<WmsInventoryScopePolicyService.Policy> policy(@RequestParam String warehouseCode) {
        return R.ok(policies.effective(warehouseCode));
    }

    @PostMapping("/scope-policy")
    @Operation(summary = "保存仓库盘点建议默认规则")
    @HasPermission("wms_wmsWarehouseMenu_add")
    @SysLog("保存仓库盘点默认规则")
    public R<WmsInventoryScopePolicyService.Policy> savePolicy(
            @RequestBody WmsInventoryScopePolicyService.SaveCommand command) {
        return R.ok(policies.save(command));
    }

    @PostMapping("/scope-policy/reset")
    @Operation(summary = "恢复产品盘点建议默认规则")
    @HasPermission("wms_wmsWarehouseMenu_add")
    @SysLog("恢复产品盘点默认规则")
    public R<WmsInventoryScopePolicyService.Policy> resetPolicy(
            @RequestBody WmsInventoryScopePolicyService.ResetCommand command) {
        return R.ok(policies.reset(command));
    }

    @GetMapping("/suggest-scope")
    @Operation(summary = "按当前库位库存生成盘点范围建议")
    @PreAuthorize("@pms.hasPermission('wms_wmsInsideInventoryPlan_add_*', 'wms_wmsInsideInventoryPlan_add')")
    public R<WmsInventoryScopeSuggestionService.Suggestion> suggest(@RequestParam String warehouseCode,
                                                                     @RequestParam String siteCode,
                                                                     @RequestParam(required = false) String ruleCode,
                                                                     @RequestParam(required = false) Integer thresholdDays) {
        return R.ok(service.suggest(warehouseCode, siteCode, ruleCode, thresholdDays));
    }
}
