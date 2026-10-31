package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 扫码库存汇总只服务于已授权的源仓作业。
 */
@Component
@RequiredArgsConstructor
public class WmsOperationStockSummaryAccess {
    private static final String MOVE_SITE = "move_site";
    private static final String ALLOCATION_OUT = "allocation_out";

    private final PermissionService permissions;

    public void require(String operation, String warehouseCode) {
        String warehouse = StrUtil.trim(warehouseCode);
        if (TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(warehouse) || warehouse.contains("*")) {
            throw new CheckedException("扫码库存汇总必须指定当前组织和源仓");
        }

        String permission = switch (operation == null ? "" : operation) {
            case MOVE_SITE -> "wms_wmsMaterialMoveSite_add";
            case ALLOCATION_OUT -> "wms_wmsAllocationBill_add";
            default -> throw new CheckedException("不支持的库存汇总作业类型");
        };
        if (!permissions.hasPermission(permission, permission + "_" + warehouse)) {
            throw new CheckedException("无该源仓作业权限");
        }
    }
}
