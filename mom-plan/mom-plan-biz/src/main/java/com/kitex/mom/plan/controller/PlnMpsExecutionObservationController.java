package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnMpsExecutionDTO;
import com.kitex.mom.plan.service.PlnMpsExecutionObservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MPS执行兑现业务工作台，核验记录不授权改动生产事实。 */
@RestController
@RequestMapping("/plnMpsExecution")
@RequiredArgsConstructor
@Tag(name = "MPS执行兑现及独立核验")
public class PlnMpsExecutionObservationController {
    private final PlnMpsExecutionObservationService service;

    @GetMapping("/preview/{versionId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "MPS日桶到根计划和MES精确来源的执行兑现预览")
    public R<PlnMpsExecutionDTO.Evidence> preview(@PathVariable Long versionId,
            @RequestParam(defaultValue = "30") int toleranceMinutes) {
        return R.ok(service.preview(versionId, toleranceMinutes));
    }

    @PostMapping("/capture")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    @Operation(summary = "按永久请求号冻结MPS来源和执行观察，不修改正式生产事实")
    @SysLog("采集MPS执行兑现观察")
    public R<?> capture(@RequestBody PlnMpsExecutionDTO.Capture command) {
        return R.ok(service.capture(command));
    }

    @GetMapping("/history/{versionId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "分页查询当前工厂MPS版本的不可变观察与核验摘要")
    public R<?> history(@PathVariable Long versionId, @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.history(versionId, current, size));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "核对原采集请求；NOT_OBSERVED不代表原请求已取消，应继续原号核对")
    public R<?> findCapture(@PathVariable String requestId) {
        var record = service.findCapture(requestId);
        return R.ok(record == null ? java.util.Map.of("state", "NOT_OBSERVED")
                : java.util.Map.of("state", "COMMITTED", "record", record));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    @Operation(summary = "查询原始兑现快照、计算结论和独立核验记录")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('plan_mps_review')")
    @Operation(summary = "异人独立核验MPS执行观察，重采事实变化或超时则失效")
    @SysLog("独立核验MPS执行兑现观察")
    public R<?> review(@RequestBody PlnMpsExecutionDTO.Review command) {
        return R.ok(service.review(command));
    }
}
