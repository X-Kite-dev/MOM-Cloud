package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesProductionNgCaptureDTO;
import com.kitex.mom.mes.service.MesProductionNgCaptureService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 明确新生产不良的实物采集；不以原普通不良申报权限默认授予库存化权限。
 */
@RestController
@RequestMapping("/mesProductionNg")
@RequiredArgsConstructor
public class MesProductionNgCaptureController {
    private final MesProductionNgCaptureService captures;
    private final MesActualFactoryAccessService access;

    @GetMapping("/context")
    @Operation(summary = "读取生产NG采集实际实名身份")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture','mesTaskReport:unqualified','mesTaskReport:show')")
    public R<java.util.Map<String, String>> context() {
        String factory = access.require("read");
        var user = SecurityUtils.getUser();
        return R.ok(java.util.Map.of("tenantId", TenantContextHolder.getTenantId().toString(), "factoryCode", factory,
                "actorId", user.getId().toString(), "account", user.getUsername()));
    }

    @PostMapping("/capture")
    @Operation(summary = "新生产不良实物隔离采集", description = "原NG生产、可选FEED耗用、隔离库存、实际入库与案件同事务")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    public R<MesProductionNgCaptureDTO.Receipt> capture(@RequestBody String raw) {
        return R.ok(captures.capture(raw));
    }

    @PostMapping("/capture-mobile")
    @Operation(summary = "移动端新生产不良实物隔离采集", description = "固定MOBILE通道，逐次核原派工移动人工权限")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    public R<MesProductionNgCaptureDTO.Receipt> captureMobile(@RequestBody String raw) {
        return R.ok(captures.captureMobile(raw));
    }

    @GetMapping("/prepare")
    @Operation(summary = "读取工站不良采集实际派工和计量单位")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    public R<MesProductionNgCaptureDTO.Preparation> prepare(@RequestParam String arrangeCode) {
        return R.ok(captures.prepare(arrangeCode, false));
    }

    @GetMapping("/prepare-mobile")
    @Operation(summary = "读取移动不良采集实际派工和计量单位")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    public R<MesProductionNgCaptureDTO.Preparation> prepareMobile(@RequestParam String arrangeCode) {
        return R.ok(captures.prepare(arrangeCode, true));
    }

    @GetMapping("/receipt")
    @Operation(summary = "查询本人NG实物采集原号", description = "ABSENT不授权换号；ISOLATED不是质量处置或PASS")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture','mesTaskReport:unqualified','mesTaskReport:show')")
    public R<MesProductionNgCaptureDTO.Receipt> receipt(@RequestParam String requestId) {
        return R.ok(captures.receipt(requestId));
    }

    @PostMapping("/cancel")
    @Operation(summary = "永久作废未执行的本人采集原号", description = "完整原文与作废原因；已隔离只返回原事实，不撤库存")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:ngCapture')")
    public R<MesProductionNgCaptureDTO.Receipt> cancel(@RequestBody String raw) {
        return R.ok(captures.cancel(raw));
    }
}
