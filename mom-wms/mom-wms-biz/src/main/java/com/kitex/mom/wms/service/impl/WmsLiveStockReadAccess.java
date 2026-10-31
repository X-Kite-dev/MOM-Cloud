package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.enums.menu.ButtonLiveStockTypeEnum;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 实时库存页面的权限和组织范围校验。仓库页面使用同步菜单生成的按钮权限。
 */
@Component
@RequiredArgsConstructor
public class WmsLiveStockReadAccess {
    private final PermissionService permissions;

    public void requireView(LiveStockMenuTypeEnum page, String warehouseCode) {
        require(ButtonLiveStockTypeEnum.VIEW, page, warehouseCode);
    }

    public void requireExport(LiveStockMenuTypeEnum page, String warehouseCode) {
        require(ButtonLiveStockTypeEnum.EXPORT, page, warehouseCode);
    }

    public void requireViewRow(WmsLiveStockEntity stock) {
        requireOrganization();
        if (stock == null || !Objects.equals(TenantContextHolder.getTenantId(), stock.getTenantId())
                || !Objects.equals(FactoryContextHolder.getFactoryCode(), stock.getFactoryCode())
                || StrUtil.isBlank(stock.getWarehouseCode())) {
            throw new CheckedException("库存不存在或无查看权限");
        }
        requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, stock.getWarehouseCode());
    }

    private void require(ButtonLiveStockTypeEnum operation, LiveStockMenuTypeEnum page, String warehouseCode) {
        requireOrganization();
        String warehouse = StrUtil.trim(warehouseCode);
        if (StrUtil.isNotBlank(warehouse) && warehouse.contains("*")) {
            throw new CheckedException("仓库编码不正确");
        }
        String base = operation.getCode();
        if (permissions.hasPermission(base)) {
            return;
        }
        if (StrUtil.isBlank(warehouse)
                || !permissions.hasPermission(base + "_" + page.getCode() + "_" + warehouse)) {
            throw new CheckedException("无该仓库库存"
                    + (operation == ButtonLiveStockTypeEnum.EXPORT ? "导出" : "查看") + "权限");
        }
    }

    private void requireOrganization() {
        if (TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库存查询必须具有租户和工厂上下文");
        }
    }
}
