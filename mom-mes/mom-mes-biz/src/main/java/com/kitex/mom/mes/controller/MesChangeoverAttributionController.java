package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesChangeoverAttributionDTO;
import com.kitex.mom.mes.exception.MesChangeoverAttributionRejectedException;
import com.kitex.mom.mes.service.MesChangeoverAttributionService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 三端共用已完成换型归属入口；没有通用增删改，不扩设备物理权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesChangeoverAttribution")
@Tag(name = "已完成换型工序归属")
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show') and @pms.hasPermission('mesChangeoverAttribution:view')")
public class MesChangeoverAttributionController {
    private final MesChangeoverAttributionService service;
    private final MesActualFactoryAccessService access;
    private final PermissionService permissions;

    @GetMapping("/context")
    @Operation(summary = "归属入口的服务端实名组织和动作权限")
    public R<Map<String, Object>> context() {
        String factory = access.require("read");
        return R.ok(Map.of("tenantId", TenantContextHolder.getTenantId().toString(), "factoryCode", factory,
                "actorUserId", SecurityUtils.getUser().getId().toString(),
                "canSubmit", permissions.hasPermission("mesChangeoverAttribution:submit"),
                "canReview", permissions.hasPermission("mesChangeoverAttribution:review")));
    }

    @GetMapping("/sources")
    @Operation(summary = "当前组织真实已完成换型候选，不自动匹配")
    public R<List<Map<String, Object>>> sources(@RequestParam String arrangeId) {
        return R.ok(service.sources(arrangeId));
    }

    @GetMapping("/preview")
    @Operation(summary = "源与目标完整事实核对")
    public R<MesChangeoverAttributionDTO.Preview> preview(@RequestParam String executionId, @RequestParam String arrangeId) {
        return R.ok(service.preview(executionId, arrangeId));
    }

    @GetMapping("/cases")
    @Operation(summary = "目标完整归属及撤销历史摘要")
    public R<List<Map<String, Object>>> cases(@RequestParam String arrangeId) {
        return R.ok(service.cases(arrangeId));
    }

    @GetMapping("/detail")
    @Operation(summary = "归属原凭证完整正文")
    public R<Map<String, Object>> detail(@RequestParam String caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/operations")
    @Operation(summary = "案件完整永久操作履历")
    public R<List<Map<String, Object>>> operations(@RequestParam String caseId) {
        return R.ok(service.operations(caseId));
    }

    @GetMapping("/by-request")
    @Operation(summary = "本人原命令回执，未观察到不等于未提交")
    public R<MesChangeoverAttributionDTO.Receipt> byRequest(@RequestParam String requestId) {
        return R.ok(service.byRequest(requestId));
    }

    @PostMapping("/prepare")
    @Operation(summary = "只读规范载荷签出，不是提交或未提交证明")
    public R<MesChangeoverAttributionDTO.Receipt> prepare(@RequestParam String action, @RequestBody MesChangeoverAttributionDTO.Command command) {
        boolean allowed = "REVIEW".equals(action) ? permissions.hasPermission("mesChangeoverAttribution:review")
                : permissions.hasPermission("mesChangeoverAttribution:submit");
        if (!allowed) throw new org.springframework.security.access.AccessDeniedException("缺少对应归属动作权限");
        return R.ok(service.prepareCommand(action, command));
    }

    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') and @pms.hasPermission('mesChangeoverAttribution:view') and @pms.hasPermission('mesChangeoverAttribution:submit')")
    @Operation(summary = "按完整事实申请归属")
    public R<MesChangeoverAttributionDTO.Receipt> submit(@RequestBody MesChangeoverAttributionDTO.Command command) {
        return R.ok(service.execute("SUBMIT", command));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') and @pms.hasPermission('mesChangeoverAttribution:view') and @pms.hasPermission('mesChangeoverAttribution:submit')")
    @Operation(summary = "本人撤回待审申请")
    public R<MesChangeoverAttributionDTO.Receipt> cancel(@RequestBody MesChangeoverAttributionDTO.Command command) {
        return R.ok(service.execute("CANCEL", command));
    }

    @PostMapping("/revoke")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') and @pms.hasPermission('mesChangeoverAttribution:view') and @pms.hasPermission('mesChangeoverAttribution:submit')")
    @Operation(summary = "申请撤销精确生效归属")
    public R<MesChangeoverAttributionDTO.Receipt> revoke(@RequestBody MesChangeoverAttributionDTO.Command command) {
        return R.ok(service.execute("REVOKE", command));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') and @pms.hasPermission('mesChangeoverAttribution:view') and @pms.hasPermission('mesChangeoverAttribution:review')")
    @Operation(summary = "异人原事实核定或撤销批准")
    public R<MesChangeoverAttributionDTO.Receipt> review(@RequestBody MesChangeoverAttributionDTO.Command command) {
        return R.ok(service.execute("REVIEW", command));
    }

    /**
     * 此handler在事务代理回滚后运行，只认保存前专门领域拒绝；技术异常仍为未知。
     */
    @ExceptionHandler(MesChangeoverAttributionRejectedException.class)
    public R<MesChangeoverAttributionDTO.Receipt> rejected(MesChangeoverAttributionRejectedException exception) {
        return R.ok(exception.getReceipt());
    }
}
