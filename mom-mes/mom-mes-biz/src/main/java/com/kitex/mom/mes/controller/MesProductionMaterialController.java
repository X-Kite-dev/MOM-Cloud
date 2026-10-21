package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesProductionMaterialReversalDTO;
import com.kitex.mom.mes.service.MesProductionMaterialReversalService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 真实投料耗用纠正与原号查询；登记不良权限不默认具有恢复耗料权限。 */
@RestController
@RequestMapping("/mesProductionMaterial")
@RequiredArgsConstructor
public class MesProductionMaterialController {
    private final MesProductionMaterialReversalService reversals;
    private final MesActualFactoryAccessService access;

    @GetMapping("/context")
    @Operation(summary = "查询耗料账纠正实际身份")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
    public R<Map<String, String>> context() {
        String factory = access.require("read");
        var actor = SecurityUtils.getUser();
        return R.ok(Map.of("tenantId", TenantContextHolder.getTenantId().toString(),
                "factoryCode", factory, "actorId", actor.getId().toString(), "account", actor.getUsername()));
    }

    @PostMapping("/reverse")
    @Operation(summary = "全额纠正误登记不良投料耗用", description = "只恢复原耗料分配，不取消不良声明或退回实物")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse')")
    public R<MesProductionMaterialReversalDTO.Receipt> reverse(@RequestBody String raw) {
        return R.ok(reversals.reverse(raw));
    }

    @GetMapping("/reversal")
    @Operation(summary = "查询本人耗料纠正原号", description = "未查到不代表可换号，不依赖当前开工状态")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
    public R<MesProductionMaterialReversalDTO.Receipt> reversal(@RequestParam String requestId) {
        return R.ok(reversals.receipt(requestId));
    }

    @PostMapping("/reversal/resolve")
    @Operation(summary = "只读核耗料纠正完整原文", description = "识别本原号结果或同一原耗用已由另一永久原号恢复；不产生业务写入")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:materialReverse','mesTaskReport:show','mesTaskReport:unqualified')")
    public R<MesProductionMaterialReversalDTO.Resolution> resolve(@RequestBody String raw) {
        return R.ok(reversals.resolve(raw));
    }
}
