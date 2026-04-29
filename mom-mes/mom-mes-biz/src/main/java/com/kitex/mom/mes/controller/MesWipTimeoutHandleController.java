package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.service.MesWipTimeoutHandleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(description = "mesWipTimeoutHandle", name = "在制超时管控记录")
@RestController
@RequestMapping("/mesWipTimeoutHandle")
@RequiredArgsConstructor
public class MesWipTimeoutHandleController {

    private final MesWipTimeoutHandleService service;

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
        return R.ok(service.getById(id));
    }

    @Operation(summary = "处理")
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('mesWipTimeoutHandle:handle')")
    public R<Void> handle(@RequestBody MesWipTimeoutHandleEntity entity) {
        service.handle(entity);
        return R.ok();
    }
}
