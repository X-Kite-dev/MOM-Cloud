package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesTimingExceptionDetailDTO;
import com.kitex.mom.mes.entity.MesTimingExceptionEntity;
import com.kitex.mom.mes.service.MesTimingExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 超时观察和异岗处置入口，不绕过质量、设备、物料、最短等待或真实来源门。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/process-timing-exception")
public class MesTimingExceptionController {
    private final MesTimingExceptionService service;

    @Operation(summary = "超时观察分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTimingException:view')")
    public R<IPage<MesTimingExceptionEntity>> page(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "20") long size, @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String taskCode) {
        return R.ok(service.pageCases(current, size, status, taskCode));
    }

    @Operation(summary = "超时例外原证据与处置履历")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesTimingException:view')")
    public R<MesTimingExceptionDetailDTO> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "本人原动作核账")
    @GetMapping("/operation")
    @PreAuthorize("@pms.hasPermission('mesTimingException:view','mesTimingException:apply','mesTimingException:review')")
    public R<MesTimingExceptionDetailDTO> operation(@RequestParam String requestId) {
        return R.ok(service.operation(requestId));
    }

    @Operation(summary = "申请、撤回或独立审核超时例外")
    @PostMapping("/action")
    @PreAuthorize("@pms.hasPermission('mesTimingException:apply','mesTimingException:review')")
    public R<MesTimingExceptionDetailDTO> action(@RequestBody String raw) {
        return R.ok(service.action(raw));
    }
}

