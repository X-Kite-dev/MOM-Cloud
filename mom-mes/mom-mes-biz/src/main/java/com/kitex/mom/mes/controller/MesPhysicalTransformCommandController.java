package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.service.MesPhysicalTransformCommandService;
import com.kitex.mom.mes.service.MesPhysicalTransformExecutionService;
import com.kitex.mom.mes.service.MesPhysicalTransformSourceHoldService;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceHoldDTO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 专用永久原命令入口，不开放通用CRUD、execute、强制放行或自动换号。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPhysicalTransform/commands")
public class MesPhysicalTransformCommandController {

    private final MesPhysicalTransformCommandService service;
    private final MesPhysicalTransformSourceHoldService sourceHolds;
    private final MesPhysicalTransformExecutionService executions;

    /** 写权限与预检查看权限分开；保存原文不是库存执行许可。 */
    @Operation(summary = "实物转换完整原命令永久登记")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/{requestId}/register", consumes = "application/json")
    public R<MesPhysicalTransformCommandDTO.Receipt> register(@PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(service.register(requestId, rawJson));
    }

    /** 本人原号恢复不依赖当前任务页或当前业务写权限，但仍核实际租户/工厂/实名。 */
    @Operation(summary = "实物转换本人原命令回执")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{requestId}")
    public R<MesPhysicalTransformCommandDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }

    /** 本人仅封存原REGISTERED；不会删除原文、释放原号或冲销库存。 */
    @Operation(summary = "实物转换未执行原命令永久封存")
    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/{requestId}/seal", consumes = "application/json")
    public R<MesPhysicalTransformCommandDTO.Receipt> seal(@PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(service.seal(requestId, rawJson));
    }

    /** 仅提交原command SHA；服务器锁内重读原工程和精确来源，不能提交客户端来源包。 */
    @Operation(summary = "实物转换本人原命令来源占用")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/{requestId}/source-hold", consumes = "application/json")
    public R<MesPhysicalTransformSourceHoldDTO.Receipt> hold(@PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(sourceHolds.acquire(requestId, rawJson));
    }

    /** 独立查本人永久事实，不复用当前任务状态，也不自动重投未知响应。 */
    @Operation(summary = "实物转换本人来源占用回执")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{requestId}/source-hold")
    public R<MesPhysicalTransformSourceHoldDTO.Receipt> holdReceipt(@PathVariable String requestId) {
        return R.ok(sourceHolds.receipt(requestId));
    }

    /** 原hold SHA和原原因显式取消；不封存command、不删除占用历史，不进行库存逆向。 */
    @Operation(summary = "实物转换本人来源占用取消")
    @PreAuthorize("isAuthenticated()")
    @PostMapping(value = "/{requestId}/source-hold/cancel", consumes = "application/json")
    public R<MesPhysicalTransformSourceHoldDTO.Receipt> cancelHold(@PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(sourceHolds.cancel(requestId, rawJson));
    }

    /** 同一原号完成精确库存、输出谱系和位置消费；不重复记生产报工产量。 */
    @Operation(summary = "实物转换正式执行")
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @PostMapping(value = "/{requestId}/apply", consumes = "application/json")
    public R<com.kitex.mom.mes.dto.MesPhysicalTransformExecutionDTO.Receipt> apply(
            @PathVariable String requestId, @RequestBody String rawJson) {
        return R.ok(executions.apply(requestId, rawJson));
    }

    /** 响应不明只按同一永久原号核账，ABSENT不自动换号重投。 */
    @Operation(summary = "实物转换正式执行回执")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{requestId}/execution")
    public R<com.kitex.mom.mes.dto.MesPhysicalTransformExecutionDTO.Receipt> executionReceipt(
            @PathVariable String requestId) {
        return R.ok(executions.receipt(requestId));
    }
}
