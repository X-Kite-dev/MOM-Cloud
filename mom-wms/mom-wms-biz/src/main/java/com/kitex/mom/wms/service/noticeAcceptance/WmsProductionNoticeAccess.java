package com.kitex.mom.wms.service.noticeAcceptance;

import com.kitex.common.core.constant.SecurityConstants;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.wms.service.WmsAllocationMobileReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

import static com.kitex.mom.wms.service.noticeAcceptance.WmsProductionNoticeCanonical.require;

/**
 * 内调标记不替代实名用户、实际工厂和MES来源业务权限。
 */
@Component
@RequiredArgsConstructor
public class WmsProductionNoticeAccess {
    private final PermissionService permissions;
    private final RemoteMdmFactoryAccessService factories;

    public record Scope(Long tenant, String factory, String operator, Long userId) {
    }

    public Scope scope(String warehouse, boolean write, boolean sourceOnly) {
        var actual = WmsAllocationMobileReceiptService.requireScope();
        var user = SecurityUtils.getUser();
        require(user.getId() != null && Objects.equals(user.getTenantId(), actual.tenantId()),
                "领料受理需要实际租户实名身份");
        var checked = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId()).setFactoryCode(
                actual.factoryCode()).setRequiredPermission(write ? "write" : "read"));
        require(checked != null && checked.isOk() && Boolean.TRUE.equals(checked.getData()),
                "没有当前实际工厂访问权限");
        var request = RequestContextHolder.getRequestAttributes();
        boolean internal = request instanceof ServletRequestAttributes attributes &&
                SecurityConstants.FROM_IN.equals(attributes.getRequest().getHeader(SecurityConstants.FROM));
        boolean sourcePermission = permissions.hasPermission("mes_mesWarehousePickingBill_edit") ||
                !write && permissions.hasPermission("mes_mesWarehousePickingBill_view");
        if (write || sourceOnly) require(internal && sourcePermission, "领料来源受理及撤回仅允许已授权MES内部调用");
        else {
            require(warehouse != null && !warehouse.isBlank() && warehouse.length() <= 100 &&
                    !warehouse.matches(".*[?*].*"), "请选择实际仓库");
            require(internal && sourcePermission ||
                            permissions.hasPermission("wms_notice_view_produce", "wms_notice_view_produce_" + warehouse),
                    "没有当前仓库领料受理查询权限");
        }
        return new Scope(actual.tenantId(), actual.factoryCode(), actual.operatorCode(), user.getId());
    }
}
