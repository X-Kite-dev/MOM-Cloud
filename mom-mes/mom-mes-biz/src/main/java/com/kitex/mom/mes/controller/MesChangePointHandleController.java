package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.kitex.mom.mes.service.impl.MesChangePointPauseCoordinator;
import com.kitex.mom.mes.service.impl.MesStartPreparationDrilldownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 变化点处理
 *
 * @author kitex
 * @since 2025-05-26
 */
@Tag(description = "mesChangePointHandle", name = "变化点处理")
@RestController
@RequestMapping("/mesChangePointHandle")
@RequiredArgsConstructor
public class MesChangePointHandleController {

    private final MesChangePointHandleService service;
    private final MesChangePointPauseCoordinator pauseCoordinator;
    private final MesStartPreparationDrilldownService preparationDrilldown;


    @Operation(summary = "变化点处理分页", description = "变化点处理分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<IPage<MesChangePointHandleEntity>> page(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query,
                                                    @RequestParam(required = false) Long preparationArrangeId) {
        if (preparationArrangeId != null) {
            // 父工单范围包含上级变化点，Mapper 的 task_code 已按等值过滤，不扩成相似工单。
            preparationDrilldown.require(preparationArrangeId, query.getArrangeCode(), query.getTaskCode());
        }
        return R.ok(service.queryPage(pageParam, query));
    }

    @Operation(summary = "触发变化点事件")
    @PostMapping("/trigger")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:trigger')")
    public R<MesChangePointHandleEntity> trigger(@RequestBody ChangePointTriggerCommand command) {
        MesChangePointHandleEntity record = service.trigger(command);
        if (Boolean.TRUE.equals(record.getAutoPauseRequired()) && !Boolean.TRUE.equals(record.getProductionPaused())
                && record.getPauseOutcome() == null) {
            pauseCoordinator.attempt(record.getId());
            record = service.getDetail(record.getId());
        }
        return R.ok(record);
    }

    @Operation(summary = "变化点处理编辑", description = "变化点处理编辑")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointHandleEntity> getById(Long id) {
        return R.ok(service.getDetail(id));
    }

    @Operation(summary = "接收变化点事件")
    @PostMapping("/accept")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> accept(@RequestBody ChangePointAcceptCommand command) {
        service.accept(command);
        return R.ok();
    }

    @Operation(summary = "处理", description = "处理")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> handle(@RequestBody ChangePointHandleCommand command) {
        service.handle(command);
        return R.ok();
    }

    @Operation(summary = "验证变化点处置")
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:verify')")
    public R<Void> verify(@RequestBody ChangePointVerifyCommand command) {
        service.verify(command);
        return R.ok();
    }

    @Operation(summary = "审批变化点恢复生产")
    @PostMapping("/release")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:release')")
    public R<Void> release(@RequestBody ChangePointProductionReleaseCommand command) {
        service.release(command);
        return R.ok();
    }

    @Operation(summary = "取消误报或无效变化点")
    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:cancel')")
    public R<Void> cancel(@RequestBody ChangePointCancelCommand command) {
        service.cancel(command);
        return R.ok();
    }

    @Operation(summary = "重试变化点首检")
    @PostMapping("/{id}/retryInspection")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> retryInspection(@PathVariable Long id) {
        service.retryInspection(id);
        return R.ok();
    }

    @Operation(summary = "重试变化点联动暂停")
    @PostMapping("/{id}/retryPause")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> retryPause(@PathVariable Long id) {
        pauseCoordinator.retry(id);
        return R.ok();
    }

    @Operation(summary = "变化点事件生命周期履历")
    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<java.util.List<MesChangePointOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

    @Operation(summary = "变化点暂停与在制损失分析")
    @GetMapping("/loss-analysis")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointLossAnalysisDTO> lossAnalysis(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return R.ok(service.lossAnalysis(startTime, endTime));
    }
}
