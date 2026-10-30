package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.HasPermission;
import com.kitex.mom.wms.agent.dto.WmsInventoryHealthDTO;
import com.kitex.mom.wms.agent.service.WmsInventoryHealthQueryService;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.service.WmsWarehousePendingWorkService;
import com.kitex.mom.wms.service.WmsWarehouseDashboardPolicyService;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
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

/** 按仓库库存查看权限提供真实的仓库运营证据。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsWarehouseDashboard")
@Tag(description = "wmsWarehouseDashboard", name = "仓库运营看板")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsWarehouseDashboardController {
    private final WmsLiveStockReadAccess readAccess;
    private final WmsInventoryHealthQueryService health;
    private final WmsWarehousePendingWorkService pendingWork;
    private final WmsWarehouseDashboardPolicyService policy;

    @Operation(summary = "查询当前仓库库存健康")
    @GetMapping("/inventory-health")
    public R<WmsInventoryHealthDTO.Evidence> inventoryHealth(
            @RequestParam String warehouseCode,
            @RequestParam(required = false) Integer nearExpiryDays,
            @RequestParam(required = false) Integer slowMovingDays) {
        String warehouse = StrUtil.trim(warehouseCode);
        if (StrUtil.isBlank(warehouse)) {
            throw new CheckedException("请选择仓库");
        }
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
        WmsWarehouseDashboardPolicyService.Policy defaults = nearExpiryDays == null || slowMovingDays == null
                ? policy.effective(warehouse) : null;
        WmsInventoryHealthDTO.Query query = new WmsInventoryHealthDTO.Query()
                .setFactoryCode(FactoryContextHolder.getFactoryCode())
                .setWarehouseCode(warehouse)
                .setNearExpiryDays(nearExpiryDays == null ? defaults.nearExpiryDays() : nearExpiryDays)
                .setSlowMovingDays(slowMovingDays == null ? defaults.slowMovingDays() : slowMovingDays)
                .setLimit(10);
        return R.ok(health.query(query));
    }

    @Operation(summary = "读取当前仓库看板风险阈值")
    @GetMapping("/risk-policy")
    public R<WmsWarehouseDashboardPolicyService.Policy> riskPolicy(@RequestParam String warehouseCode) {
        return R.ok(policy.effective(warehouseCode));
    }

    @Operation(summary = "保存当前仓库看板风险阈值")
    @PostMapping("/risk-policy")
    @HasPermission("wms_wmsWarehouseMenu_add")
    @SysLog("保存仓库看板风险阈值")
    public R<WmsWarehouseDashboardPolicyService.Policy> saveRiskPolicy(
            @RequestBody WmsWarehouseDashboardPolicyService.SaveCommand command) {
        return R.ok(policy.save(command));
    }

    @Operation(summary = "恢复继承产品看板阈值")
    @PostMapping("/risk-policy/reset")
    @HasPermission("wms_wmsWarehouseMenu_add")
    @SysLog("恢复仓库看板产品默认阈值")
    public R<WmsWarehouseDashboardPolicyService.Policy> resetRiskPolicy(
            @RequestBody WmsWarehouseDashboardPolicyService.ResetCommand command) {
        return R.ok(policy.reset(command));
    }

    @Operation(summary = "查询当前仓库有查看权限的待处理单据数量")
    @GetMapping("/pending-work")
    public R<WmsWarehousePendingWorkService.PendingWork> pendingWork(@RequestParam String warehouseCode) {
        return R.ok(pendingWork.query(warehouseCode));
    }
}
