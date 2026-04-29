package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
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

    @Operation(summary = "变化点处理接收", description = "变化点处理接收")
    @PostMapping("/add")
    public R<Void> add(@RequestBody MesChangePointHandleEntity params) {
        service.save(params);
        return R.ok();
    }

    @Operation(summary = "变化点处理编辑", description = "变化点处理编辑")
    @PostMapping("/update")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:update')")
    public R<Void> update(@RequestBody MesChangePointHandleEntity mesChangePointHandle) {
        service.updateById(mesChangePointHandle);
        return R.ok();
    }

    @Operation(summary = "变化点处理编辑", description = "变化点处理编辑")
    @GetMapping("/getById")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:show')")
    public R<MesChangePointHandleEntity> getById(Long id) {
        return R.ok(service.getById(id));
    }

    @Operation(summary = "通知", description = "通知")
    @PostMapping("/notice")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:notice')")
    public R<Void> notice(@RequestBody MesChangePointHandleEntity mesChangePointHandle) {
        service.notice(mesChangePointHandle);
        return R.ok();
    }

    @Operation(summary = "处理", description = "处理")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('mesChangePointHandle:handle')")
    public R<Void> handle(@RequestBody MesChangePointHandleEntity mesChangePointHandle) {
        service.handle(mesChangePointHandle);
        return R.ok();
    }
}
