package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.WipTimeoutCancelCommand;
import com.kitex.mom.mes.dto.WipTimeoutHandleCommand;
import com.kitex.mom.mes.dto.WipTimeoutVerifyCommand;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.entity.MesWipTimeoutOperationEntity;
import com.kitex.mom.mes.service.MesWipTimeoutHandleService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(description = "mesWipTimeoutHandle", name = "在制超时管控记录")
@RestController
@RequestMapping("/mesWipTimeoutHandle")
@RequiredArgsConstructor
public class MesWipTimeoutHandleController {

    private final MesWipTimeoutHandleService service;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:show')")
    public R<IPage<MesWipTimeoutHandleEntity>> page(Page<MesWipTimeoutHandleEntity> pageParam, MesWipTimeoutHandleEntity query) {
        return R.ok(service.queryPage(pageParam, query));
    }

    @Operation(summary = "详情")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:show')")
    public R<MesWipTimeoutHandleEntity> getById(Long id) {
        var event = service.getOne(new LambdaQueryWrapper<MesWipTimeoutHandleEntity>().eq(MesWipTimeoutHandleEntity::getId, id)
                .eq(MesWipTimeoutHandleEntity::getFactoryCode, factoryAccess.require("read")));
        if (event == null) throw new com.kitex.common.core.exception.CheckedException("当前工厂超时事件不存在");
        return R.ok(event);
    }

    @Operation(summary = "处理")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:handle')")
    public R<Void> handle(@RequestBody WipTimeoutHandleCommand command) {
        service.handle(command);
        return R.ok();
    }

    @Operation(summary = "验证处置结果")
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:verify')")
    public R<Void> verify(@RequestBody WipTimeoutVerifyCommand command) {
        service.verify(command);
        return R.ok();
    }

    @Operation(summary = "取消误报或无效事件")
    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:cancel')")
    public R<Void> cancel(@RequestBody WipTimeoutCancelCommand command) {
        service.cancel(command);
        return R.ok();
    }

    @Operation(summary = "查询不可变操作履历")
    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:show')")
    public R<List<MesWipTimeoutOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

    @Operation(summary = "手动触发扫描")
    @PostMapping("/scanNow")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:scan')")
    public R<Void> scanNow() {
        service.scanNow();
        return R.ok();
    }
}
