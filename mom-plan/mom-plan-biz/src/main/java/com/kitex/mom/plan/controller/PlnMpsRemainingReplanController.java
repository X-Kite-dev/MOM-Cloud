package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnMpsRemainingReplanDTO;
import com.kitex.mom.plan.service.PlnMpsRemainingReplanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MPS已执行来源的业务重排入口；不借用Agent授权，也不变更原数量。
 */
@RestController
@RequestMapping("/plnMpsRemainingReplan")
@RequiredArgsConstructor
@Tag(name = "MPS剩余未开工工序受控重排")
public class PlnMpsRemainingReplanController {
    private final PlnMpsRemainingReplanService service;

    @GetMapping("/preview")
    @PreAuthorize("@pms.hasPermission('plan_mps_view') and @pms.hasPermission('plan_resourceReplan_view')")
    @Operation(summary = "从已独立核验观察读取完整根计划工序及实时MES证据")
    public R<PlnMpsRemainingReplanDTO.Preview> preview(@RequestParam("observationId") Long observationId,
                                                       @RequestParam("lineId") Long lineId,
                                                       @RequestParam(value = "toleranceMinutes", defaultValue = "30") int toleranceMinutes) {
        return R.ok(service.preview(observationId, lineId, toleranceMinutes));
    }

    @PostMapping("/assess")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit') and @pms.hasPermission('plan_resourceReplan_assess')")
    @Operation(summary = "冻结MPS来源与显式工序范围，同事务生成三个局部候选，不发布正式排程")
    @SysLog("评估MPS剩余未开工工序重排")
    public R<PlnMpsRemainingReplanDTO.Detail> assess(@RequestBody PlnMpsRemainingReplanDTO.Assess command) {
        return R.ok(service.assess(command));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view') and @pms.hasPermission('plan_resourceReplan_view')")
    @Operation(summary = "核对原评估永久号；未观察到提交不代表已取消，须继续原号核对或续投")
    public R<?> findRequest(@PathVariable("requestId") String requestId) {
        var detail = service.findRequest(requestId);
        return R.ok(detail == null ? Map.of("state", "NOT_OBSERVED")
                : Map.of("state", "COMMITTED", "detail", detail));
    }

    @GetMapping("/history")
    @PreAuthorize("@pms.hasPermission('plan_mps_view') and @pms.hasPermission('plan_resourceReplan_view')")
    @Operation(summary = "完整查询当前实际组织下观察及精确日桶的不可变重排来源")
    public R<?> history(@RequestParam("observationId") Long observationId, @RequestParam("lineId") Long lineId) {
        return R.ok(service.history(observationId, lineId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view') and @pms.hasPermission('plan_resourceReplan_view')")
    @Operation(summary = "读取不可变MPS来源及当前权威三候选状态、逐工序差异与永久履历")
    public R<PlnMpsRemainingReplanDTO.Detail> detail(@PathVariable("id") Long id) {
        return R.ok(service.detail(id));
    }
}
