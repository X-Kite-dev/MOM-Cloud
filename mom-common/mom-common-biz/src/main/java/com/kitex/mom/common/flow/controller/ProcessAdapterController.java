package com.kitex.mom.common.flow.controller;

import com.kitex.common.core.util.R;
import com.kitex.flow.task.dto.ProcessCompleteNotifyDTO;
import com.kitex.mom.common.flow.dto.StartFlowDataDTO;
import com.kitex.mom.common.flow.service.ProcessAdapterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程管理
 *
 * @author kitex
 * @date 2025-2-8 15:10:12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/process")
@Tag(description = "process", name = "流程管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class ProcessAdapterController {

    private final ProcessAdapterService processAdapterService;

    /**
     * 启动审批流程
     */
    @Operation(summary = "启动审批流程", description = "启动审批流程")
    @PostMapping("/startFlow")
    public R<Boolean> startFlow(@RequestBody StartFlowDataDTO param) {
        return R.ok(processAdapterService.startFlow(param));
    }

    /**
     * 接收流程审核信息-系统内置flowable
     */
    @Operation(summary = "接收流程审核信息-系统内置flowable", description = "接收流程审核信息-系统内置flowable")
    @PostMapping("/backProcessComplete")
    public R<Boolean> backProcessComplete(@RequestBody ProcessCompleteNotifyDTO params) {
        return R.ok(processAdapterService.backProcessComplete(params));
    }
}