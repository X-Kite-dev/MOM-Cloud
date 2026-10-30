package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 复用正式写入口的解析器，解释当前仓库、物料和作业的生效规则。 */
@Service
@RequiredArgsConstructor
public class WmsWarehouseRulePreviewService {
    private final WmsWarehouseBusinessGuard operations;
    private final WmsIdentifierPolicyService identifiers;
    private final WmsReservationMovementPolicyService reservations;
    private final PermissionService permissions;

    public Preview preview(String warehouseCode, String billType, boolean inbound, String materialCode) {
        if (TenantContextHolder.getTenantId() == null || StrUtil.isBlank(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("规则试算必须具有明确的租户和工厂上下文");
        }
        if (!permissions.hasPermission("wms_wmsWarehouseMenu_view")) {
            throw new CheckedException("无仓库业务配置查看权限");
        }
        String warehouse = StrUtil.trim(warehouseCode);
        String business = StrUtil.trim(billType);
        if (StrUtil.isBlank(warehouse) || StrUtil.isBlank(business)) {
            throw new CheckedException("仓库和业务类型不能为空");
        }
        WmsWarehouseBusinessGuard.Evaluation operation = operations.evaluate(warehouse, business, inbound);
        return new Preview(warehouse, business, inbound, operation,
                identifier(business, inbound, materialCode), reservation(business, inbound));
    }

    private IdentifierResult identifier(String billType, boolean inbound, String materialCode) {
        if (!inbound) {
            return new IdentifierResult("NOT_APPLICABLE", null, null, null, null, null, "当前不是入库作业");
        }
        if (!permissions.hasPermission("wms_identifierPolicy_view")) {
            return new IdentifierResult("NO_PERMISSION", null, null, null, null, null, "无批次条码规则查看权限");
        }
        if (StrUtil.isBlank(materialCode)) {
            return new IdentifierResult("INPUT_REQUIRED", null, null, null, null, null, "输入物料编码后试算标识要求");
        }
        WmsIdentifierPolicyEntity effective = identifiers.resolveEffective(billType, materialCode.trim());
        if (effective == null) {
            return new IdentifierResult("EVALUATED", "PRODUCT_DEFAULT", null, false, false, false,
                    "物料主数据和当前工厂均未要求额外标识");
        }
        boolean configured = effective.getId() != null;
        return new IdentifierResult("EVALUATED", configured ? "FACTORY_POLICY" : "MATERIAL_MASTER",
                configured ? effective.getId().toString() : null,
                Boolean.TRUE.equals(effective.getBatchRequired()),
                Boolean.TRUE.equals(effective.getBarcodeRequired()),
                Boolean.TRUE.equals(effective.getSerialManaged()),
                configured ? "工厂规则与物料主数据取更严格要求" : "物料主数据要求");
    }

    private ReservationResult reservation(String billType, boolean inbound) {
        if (inbound || (!"produce".equals(billType) && !"sales".equals(billType))) {
            return new ReservationResult("NOT_APPLICABLE", null, null, null, "当前作业不新建生产或销售库存预留");
        }
        if (!permissions.hasPermission("wms_reservationMovementPolicy_view")) {
            return new ReservationResult("NO_PERMISSION", null, null, null, "无库存预留规则查看权限");
        }
        WmsReservationMovementPolicyService.Evaluation evaluation = reservations.preview(billType);
        return new ReservationResult("EVALUATED", evaluation.configurationSource(),
                evaluation.policyId() == null ? null : evaluation.policyId().toString(),
                evaluation.allowed(), evaluation.reason());
    }

    public record Preview(String warehouseCode, String billType, boolean inbound,
                          WmsWarehouseBusinessGuard.Evaluation operation,
                          IdentifierResult identifier, ReservationResult reservation) {
    }

    public record IdentifierResult(String status, String source, String ruleId, Boolean batchRequired,
                                   Boolean barcodeRequired, Boolean serialManaged, String reason) {
    }

    public record ReservationResult(String status, String source, String ruleId, Boolean allowed, String reason) {
    }
}
