package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.entity.MesChangePointOperationEntity;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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


    @Operation(summary = "变化点处理分页", description = "变化点处理分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<IPage<MesChangePointHandleEntity>> page(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    @Operation(summary = "触发变化点事件")
    @PostMapping("/trigger")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:trigger')")
    public R<MesChangePointHandleEntity> trigger(@RequestBody ChangePointTriggerCommand command) {
        return R.ok(service.trigger(command));
    }

    @Operation(summary = "变化点处理编辑", description = "变化点处理编辑")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointHandleEntity> getById(Long id) {
        return R.ok(service.getById(id));
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

    @Operation(summary = "变化点事件生命周期履历")
    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<java.util.List<MesChangePointOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }
}
