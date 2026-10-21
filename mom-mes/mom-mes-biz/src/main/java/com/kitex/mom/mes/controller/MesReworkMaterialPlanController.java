package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesReworkMaterialCommand;
import com.kitex.mom.mes.service.impl.MesReworkMaterialPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesReworkMaterialPlan")
@Tag(name = "返工执行方案", description = "双人复核返工用料、原节点执行依据和跨工单时间口径")
public class MesReworkMaterialPlanController {
    private final MesReworkMaterialPlanService service;

    @GetMapping("/{taskId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show','mesTask:reworkMaterial:propose','mesTask:reworkMaterial:review')")
    @Operation(summary = "查询返工执行方案上下文",
            description = "返回原冻结BOM候选、当前批准版、时间口径及分页复核履历")
    public R<?> context(@PathVariable Long taskId, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.context(taskId, page));
    }

    @GetMapping("/evidence/{planId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show','mesTask:reworkMaterial:propose','mesTask:reworkMaterial:review')")
    @Operation(summary = "查询返工执行方案完整证据",
            description = "返回方案原始依据、执行准备快照和不可变操作回执")
    public R<?> evidence(@PathVariable Long planId) {
        return R.ok(service.evidence(planId));
    }

    @PostMapping("/{operation}")
    @PreAuthorize("@pms.hasPermission(#operation == 'PROPOSE' || #operation == 'SUPPLEMENT' || #operation == 'WITHDRAW' ? 'mesTask:reworkMaterial:propose' : 'mesTask:reworkMaterial:review')")
    @Operation(summary = "执行返工方案编制或复核动作",
            description = "支持PROPOSE、SUPPLEMENT、APPROVE、REJECT、WITHDRAW；同请求号只允许原内容幂等重放")
    public R<?> execute(@PathVariable String operation, @RequestBody MesReworkMaterialCommand command) {
        command.setOperation(operation);
        return R.ok(service.execute(command));
    }
}
