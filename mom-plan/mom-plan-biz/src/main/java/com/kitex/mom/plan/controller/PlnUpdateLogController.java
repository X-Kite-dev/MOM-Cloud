package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.entity.PlnUpdateLogEntity;
import com.kitex.mom.plan.service.PlnUpdateLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 修改日志表
 *
 * @author kitex
 * @date 2025-02-26 10:06:07
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnUpdateLog")
@Tag(description = "plnUpdateLog", name = "修改日志表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnUpdateLogController {

    private final PlnUpdateLogService plnUpdateLogService;

    /**
     * 查询修改日志列表
     *
     * @param time 时间
     * @return
     */
    @Operation(summary = "查询修改日志列表", description = "查询修改日志列表")
    @GetMapping("/list")
    public R<List<PlnUpdateLogEntity>> getPlnUpdateLogList(LocalDate time) {
        return R.ok(plnUpdateLogService.getPlnUpdateLogList(time));
    }
}