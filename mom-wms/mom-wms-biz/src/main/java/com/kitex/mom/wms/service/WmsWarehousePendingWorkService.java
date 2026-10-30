package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.menu.ButtonBillTypeEnum;
import com.kitex.mom.wms.enums.menu.ButtonInventoryTypeEnum;
import com.kitex.mom.wms.enums.menu.InOutMenuTypeEnum;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 当前仓库待处理单据。无对应单据查看权限的类别不查询、不返回数量。 */
@Service
@RequiredArgsConstructor
public class WmsWarehousePendingWorkService {
    private final PermissionService permissions;
    private final WmsInBillMapper inBills;
    private final WmsOutBillMapper outBills;
    private final WmsInsideInventoryPlanMapper inventoryPlans;

    public PendingWork query(String warehouseCode) {
        String warehouse = StrUtil.trim(warehouseCode);
        Long tenantId = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenantId == null || StrUtil.isBlank(factory)
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(warehouse) || warehouse.contains("*")) {
            throw new CheckedException("查询仓库待办必须具有明确的租户、工厂和仓库");
        }

        Long inbound = null;
        if (canView(InOutMenuTypeEnum.IN_BILL, warehouse)) {
            inbound = inBills.selectCount(new LambdaQueryWrapper<WmsInBillEntity>()
                    .eq(WmsInBillEntity::getTenantId, tenantId)
                    .eq(WmsInBillEntity::getFactoryCode, factory)
                    .eq(WmsInBillEntity::getWarehouseCode, warehouse)
                    .eq(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode()));
        }

        Long outbound = null;
        if (canView(InOutMenuTypeEnum.OUT_BILL, warehouse)) {
            outbound = outBills.selectCount(new LambdaQueryWrapper<WmsOutBillEntity>()
                    .eq(WmsOutBillEntity::getTenantId, tenantId)
                    .eq(WmsOutBillEntity::getFactoryCode, factory)
                    .eq(WmsOutBillEntity::getWarehouseCode, warehouse)
                    .eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode()));
        }

        Long inventory = null;
        if (canViewInventory(warehouse)) {
            inventory = inventoryPlans.selectCount(new LambdaQueryWrapper<WmsInsideInventoryPlanEntity>()
                    .eq(WmsInsideInventoryPlanEntity::getTenantId, tenantId)
                    .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory)
                    .eq(WmsInsideInventoryPlanEntity::getWarehouseCode, warehouse)
                    .eq(WmsInsideInventoryPlanEntity::getHandleState, "0")
                    .in(WmsInsideInventoryPlanEntity::getStatus, "0", "1"));
        }
        return new PendingWork(warehouse, LocalDateTime.now(), inbound, outbound, inventory);
    }

    private boolean canView(InOutMenuTypeEnum bill, String warehouse) {
        String scoped = ButtonBillTypeEnum.VIEW.getCode() + "_" + bill.getType() + "_" + warehouse;
        String global = bill == InOutMenuTypeEnum.IN_BILL
                ? "wms_wmsInBill_view" : "wms_wmsOutBill_view";
        return permissions.hasPermission(scoped) || permissions.hasPermission(global);
    }

    private boolean canViewInventory(String warehouse) {
        String global = ButtonInventoryTypeEnum.VIEW.getCode();
        return permissions.hasPermission(global + "_" + warehouse) || permissions.hasPermission(global);
    }

    public record PendingWork(String warehouseCode, LocalDateTime observedAt,
                              Long inboundPendingReview, Long outboundPendingReview, Long inventoryPending) {
    }
}
