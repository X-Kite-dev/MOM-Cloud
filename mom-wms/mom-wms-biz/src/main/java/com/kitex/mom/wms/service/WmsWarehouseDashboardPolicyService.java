package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.enums.menu.LiveStockMenuTypeEnum;
import com.kitex.mom.wms.mapper.WmsWarehouseDashboardPolicyMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsLiveStockReadAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 仓库看板阈值；未配置时继承产品默认值，保存时使用版本校验。 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseDashboardPolicyService {
    private static final int DEFAULT_NEAR_EXPIRY_DAYS = 30;
    private static final int DEFAULT_SLOW_MOVING_DAYS = 90;

    private final WmsWarehouseDashboardPolicyMapper policies;
    private final WmsLiveStockReadAccess readAccess;
    private final PermissionService permissions;
    private final WmsWarehouseMenuMapper menus;
    private final RemoteWarehouseService warehouses;

    public Policy effective(String warehouseCode) {
        Scope scope = scope(warehouseCode);
        WmsWarehouseDashboardPolicyMapper.Row row = policies.find(scope.tenant(), scope.factory(), scope.warehouse());
        return row == null
                ? new Policy(scope.warehouse(), DEFAULT_NEAR_EXPIRY_DAYS, DEFAULT_SLOW_MOVING_DAYS,
                0, "PRODUCT_DEFAULT")
                : new Policy(scope.warehouse(), row.nearExpiryDays(), row.slowMovingDays(),
                row.version(), "WAREHOUSE_CONFIG");
    }

    @Transactional(rollbackFor = Exception.class)
    public Policy save(SaveCommand command) {
        if (command == null) {
            throw new CheckedException("请填写看板阈值");
        }
        Scope scope = scope(command.warehouseCode());
        validateDays(command.nearExpiryDays());
        validateDays(command.slowMovingDays());
        if (command.expectedVersion() < 0) {
            throw new CheckedException("看板阈值版本不正确");
        }
        requireConfigurationAccess(scope);
        WmsWarehouseDashboardPolicyMapper.Row previous = policies.find(
                scope.tenant(), scope.factory(), scope.warehouse());
        int version = previous == null ? 0 : previous.version();
        if (version != command.expectedVersion() || version == Integer.MAX_VALUE) {
            throw new CheckedException("看板阈值已被其他人更新，请刷新后重试");
        }
        var user = SecurityUtils.getUser();
        if (user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("无法识别当前操作人");
        }
        int updated = policies.upsert(scope.tenant(), scope.factory(), scope.warehouse(),
                command.nearExpiryDays(), command.slowMovingDays(), version, user.getUsername());
        if (updated != 1) {
            throw new CheckedException("看板阈值已被其他人更新，请刷新后重试");
        }
        return new Policy(scope.warehouse(), command.nearExpiryDays(), command.slowMovingDays(),
                version + 1, "WAREHOUSE_CONFIG");
    }

    @Transactional(rollbackFor = Exception.class)
    public Policy reset(ResetCommand command) {
        if (command == null) {
            throw new CheckedException("请选择要恢复默认值的仓库");
        }
        Scope scope = scope(command.warehouseCode());
        if (command.expectedVersion() < 1) {
            throw new CheckedException("看板阈值版本不正确");
        }
        requireConfigurationAccess(scope);
        WmsWarehouseDashboardPolicyMapper.Row previous = policies.find(
                scope.tenant(), scope.factory(), scope.warehouse());
        if (previous == null || previous.version() != command.expectedVersion()
                || policies.deleteVersion(scope.tenant(), scope.factory(), scope.warehouse(),
                command.expectedVersion()) != 1) {
            throw new CheckedException("看板阈值已被其他人更新，请刷新后重试");
        }
        return new Policy(scope.warehouse(), DEFAULT_NEAR_EXPIRY_DAYS, DEFAULT_SLOW_MOVING_DAYS,
                0, "PRODUCT_DEFAULT");
    }

    private Scope scope(String warehouseCode) {
        String warehouse = StrUtil.trim(warehouseCode);
        readAccess.requireView(LiveStockMenuTypeEnum.STOCK_DETAIL, warehouse);
        if (StrUtil.isBlank(warehouse)) {
            throw new CheckedException("请选择仓库");
        }
        return new Scope(TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), warehouse);
    }

    private void validateDays(int days) {
        if (days < 1 || days > 3650) {
            throw new CheckedException("预警天数须为1至3650的整数");
        }
    }

    private void requireConfigurationAccess(Scope scope) {
        if (!permissions.hasPermission("wms_wmsWarehouseMenu_add")) {
            throw new CheckedException("无仓库配置权限");
        }
        // 与永久删除仓库共用事务锁，避免删除完成后重新生成孤立配置。
        menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
        requireActiveWarehouse(scope);
    }

    private void requireActiveWarehouse(Scope scope) {
        R<List<MdmWarehouseEntity>> response = warehouses.getMenuConfigurationWarehouses();
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new CheckedException("仓库状态读取失败，禁止保存配置");
        }
        boolean found = false;
        for (MdmWarehouseEntity warehouse : response.getData()) {
            if (warehouse == null || StrUtil.isBlank(warehouse.getWarehouseCode())
                    || !Objects.equals(scope.tenant(), warehouse.getTenantId())
                    || !Objects.equals(scope.factory(), warehouse.getFactoryCode())) {
                throw new CheckedException("仓库状态与当前组织不一致，禁止保存配置");
            }
            if (scope.warehouse().equals(warehouse.getWarehouseCode())) {
                if (found || !Boolean.TRUE.equals(warehouse.getStatus())) {
                    throw new CheckedException("仅允许配置当前工厂已启用的仓库");
                }
                found = true;
            }
        }
        if (!found) {
            throw new CheckedException("仓库不存在或无权配置");
        }
    }

    private record Scope(Long tenant, String factory, String warehouse) {
    }

    public record Policy(String warehouseCode, int nearExpiryDays, int slowMovingDays,
                         int version, String source) {
    }

    public record SaveCommand(String warehouseCode, int nearExpiryDays, int slowMovingDays,
                              int expectedVersion) {
    }

    public record ResetCommand(String warehouseCode, int expectedVersion) {
    }
}
