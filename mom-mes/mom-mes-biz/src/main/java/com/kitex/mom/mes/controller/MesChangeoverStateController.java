package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesChangeoverStateDTO;
import com.kitex.mom.mes.service.MesChangeoverStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 物理换型原账的只读恢复入口，不复用归属审核权限或跨服务回执。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesChangeoverExecution")
@Tag(name = "工站换型原账恢复")
@PreAuthorize("@pms.hasPermission('mesChangeoverExecution:view')")
public class MesChangeoverStateController {
    private final MesChangeoverStateService service;

    @GetMapping("/state")
    @Operation(summary = "同组织设备进行中、精确原账及本地绑定只读观察")
    public R<MesChangeoverStateDTO> state(@RequestParam String deviceCode, @RequestParam String worksiteCode,
                                          @RequestParam(required = false) String executionId, @RequestParam(required = false) String requestId) {
        return R.ok(service.observe(deviceCode, worksiteCode, executionId, requestId));
    }
}
