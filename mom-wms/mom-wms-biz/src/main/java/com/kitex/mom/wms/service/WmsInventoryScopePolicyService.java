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
import com.kitex.mom.wms.mapper.WmsInventoryScopePolicyMapper;
import com.kitex.mom.wms.mapper.WmsWarehouseMenuMapper;
import com.kitex.mom.wms.service.impl.WmsInventoryWarehousePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 仓库盘点建议默认规则；操作员建单时可调整，计划保存仍以实时库存重新核算。 */
@Service
@RequiredArgsConstructor
public class WmsInventoryScopePolicyService {
    private final WmsInventoryScopePolicyMapper policies;
    private final WmsInventoryWarehousePermission inventoryPermission;
    private final PermissionService permissions;
    private final WmsWarehouseMenuMapper menus;
    private final RemoteWarehouseService warehouses;

    public Policy effective(String warehouseCode) {
        Scope scope = scope(warehouseCode);
        WmsInventoryScopePolicyMapper.Row row = policies.find(scope.tenant(), scope.factory(), scope.warehouse());
        if (row == null) {
            return new Policy(scope.warehouse(), "ALL_POSITIVE", null, 0, "PRODUCT_DEFAULT");
        }
        if (row.version() < 1) {
            throw new CheckedException("仓库盘点规则版本无效");
        }
        if (StrUtil.isBlank(row.ruleCode()) || !row.ruleCode().equals(row.ruleCode().trim())) {
            throw new CheckedException("仓库盘点规则无效");
        }
        WmsInventoryScopeSuggestionService.validateRule(row.ruleCode(), row.thresholdDays());
        if (row.inheritDefault()) {
            if (!"ALL_POSITIVE".equals(row.ruleCode()) || row.thresholdDays() != null) {
                throw new CheckedException("仓库盘点规则默认状态无效");
            }
            return new Policy(scope.warehouse(), "ALL_POSITIVE", null, row.version(), "PRODUCT_DEFAULT");
        }
        return new Policy(scope.warehouse(), row.ruleCode(), row.thresholdDays(), row.version(), "WAREHOUSE_CONFIG");
    }

    @Transactional(rollbackFor = Exception.class)
    public Policy save(SaveCommand command) {
        if (command == null) {
            throw new CheckedException("请填写仓库盘点默认规则");
        }
        Scope scope = scope(command.warehouseCode());
        if (StrUtil.isBlank(command.ruleCode()) || !command.ruleCode().equals(command.ruleCode().trim())) {
            throw new CheckedException("盘点建议规则无效");
        }
        WmsInventoryScopeSuggestionService.validateRule(command.ruleCode(), command.thresholdDays());
        if (command.expectedVersion() < 0) {
            throw new CheckedException("盘点规则版本不正确");
        }
        requireConfigurationAccess(scope);
        WmsInventoryScopePolicyMapper.Row previous = policies.find(scope.tenant(), scope.factory(), scope.warehouse());
        int version = previous == null ? 0 : previous.version();
        if (version != command.expectedVersion() || version == Integer.MAX_VALUE) {
            throw new CheckedException("盘点默认规则已更新，请刷新后重试");
        }
        var user = SecurityUtils.getUser();
        if (user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("无法识别当前操作人");
        }
        int updated = policies.upsert(scope.tenant(), scope.factory(), scope.warehouse(), command.ruleCode(),
                command.thresholdDays(), version, user.getUsername());
        if (updated != 1) {
            throw new CheckedException("盘点默认规则已更新，请刷新后重试");
        }
        return new Policy(scope.warehouse(), command.ruleCode(), command.thresholdDays(), version + 1,
                "WAREHOUSE_CONFIG");
    }

    @Transactional(rollbackFor = Exception.class)
    public Policy reset(ResetCommand command) {
        if (command == null) {
            throw new CheckedException("请选择仓库");
        }
        Scope scope = scope(command.warehouseCode());
        if (command.expectedVersion() < 1) {
            throw new CheckedException("盘点规则版本不正确");
        }
        requireConfigurationAccess(scope);
        WmsInventoryScopePolicyMapper.Row previous = policies.find(scope.tenant(), scope.factory(), scope.warehouse());
        if (previous == null || previous.inheritDefault() || previous.version() != command.expectedVersion()) {
            throw new CheckedException("盘点默认规则已更新，请刷新后重试");
        }
        var user = SecurityUtils.getUser();
        if (user == null || StrUtil.isBlank(user.getUsername())) {
            throw new CheckedException("无法识别当前操作人");
        }
        if (policies.resetVersion(scope.tenant(), scope.factory(), scope.warehouse(),
                command.expectedVersion(), user.getUsername()) != 1) {
            throw new CheckedException("盘点默认规则已更新，请刷新后重试");
        }
        return new Policy(scope.warehouse(), "ALL_POSITIVE", null, previous.version() + 1, "PRODUCT_DEFAULT");
    }

    private Scope scope(String warehouseCode) {
        String warehouse = StrUtil.trim(warehouseCode);
        if (StrUtil.isBlank(warehouse)) {
            throw new CheckedException("请选择仓库");
        }
        inventoryPermission.require("add", warehouse);
        String factory = FactoryContextHolder.getFactoryCode();
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null || StrUtil.isBlank(factory)) {
            throw new CheckedException("当前租户或工厂不存在");
        }
        return new Scope(tenant, factory, warehouse);
    }

    private void requireConfigurationAccess(Scope scope) {
        if (!permissions.hasPermission("wms_wmsWarehouseMenu_add")) {
            throw new CheckedException("无仓库配置权限");
        }
        // 与仓库永久删除共用组织事务锁，避免已删仓库重新写入规则。
        menus.lockConfiguration("WMS_WAREHOUSE_MENU:" + scope.tenant() + ":" + scope.factory());
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

    public record Policy(String warehouseCode, String ruleCode, Integer thresholdDays, int version, String source) {
    }

    public record SaveCommand(String warehouseCode, String ruleCode, Integer thresholdDays, int expectedVersion) {
    }

    public record ResetCommand(String warehouseCode, int expectedVersion) {
    }
}
