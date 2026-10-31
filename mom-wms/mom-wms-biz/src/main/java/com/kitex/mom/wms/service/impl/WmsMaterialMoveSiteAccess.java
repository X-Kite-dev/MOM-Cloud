package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsMaterialMoveSiteEntity;
import com.kitex.mom.wms.enums.menu.ButtonMoveSiteTypeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 库内移动入口按当前组织和同步菜单的仓库按钮权限校验。
 */
@Component
@RequiredArgsConstructor
public class WmsMaterialMoveSiteAccess {
    private final PermissionService permissions;

    public void requireView(String warehouseCode) {
        require(ButtonMoveSiteTypeEnum.VIEW, warehouseCode);
    }

    public void requireExport(String warehouseCode) {
        require(ButtonMoveSiteTypeEnum.EXPORT, warehouseCode);
    }

    public void requireMove(String warehouseCode) {
        require(ButtonMoveSiteTypeEnum.ADD, warehouseCode);
    }

    public void requireViewRow(WmsMaterialMoveSiteEntity row) {
        requireOrganization();
        if (row == null || !Objects.equals(TenantContextHolder.getTenantId(), row.getTenantId())
                || !Objects.equals(FactoryContextHolder.getFactoryCode(), row.getFactoryCode())
                || StrUtil.isBlank(row.getWarehouseCode())) {
            throw new CheckedException("移库记录不存在或无查看权限");
        }
        requireView(row.getWarehouseCode());
    }

    private void require(ButtonMoveSiteTypeEnum operation, String warehouseCode) {
        requireOrganization();
        String warehouse = StrUtil.trim(warehouseCode);
        if (StrUtil.isNotBlank(warehouse) && warehouse.contains("*")) {
            throw new CheckedException("仓库编码不正确");
        }
        String permission = operation.getCode();
        if (permissions.hasPermission(permission)) {
            return;
        }
        if (StrUtil.isBlank(warehouse) || !permissions.hasPermission(permission + "_" + warehouse)) {
            throw new CheckedException("无该仓库库内移动" + operation.getName() + "权限");
        }
    }

    private void requireOrganization() {
        if (TenantContextHolder.getTenantId() == null
                || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("库内移动必须具有租户和工厂上下文");
        }
    }
}
