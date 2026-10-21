package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import com.kitex.mom.plan.service.PlnSchedulingEffectReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnScheduling/effects")
@Tag(name = "APS候选执行效果回评")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnSchedulingEffectReviewController {
    private final PlnSchedulingEffectReviewService service;

    @GetMapping("/page")
    @Operation(summary = "分页查询已发布候选效果")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingEffect_view')")
    public R<Page<PlnSchedulingEffectReviewEntity>> page(
            @ParameterObject SearchPage page, @ParameterObject PlnSchedulingEffectReviewDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{reviewId}")
    @Operation(summary = "查询效果回评及不可变观察历史")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingEffect_view')")
    public R<PlnSchedulingEffectReviewDTO.Detail> detail(@PathVariable Long reviewId) {
        return R.ok(service.detail(reviewId));
    }

    @PostMapping("/capture")
    @Operation(summary = "重采MES实际执行事实并回评候选效果")
    @SysLog("重评APS候选执行效果")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingEffect_capture')")
    public R<PlnSchedulingEffectReviewDTO.Detail> capture(
            @RequestBody PlnSchedulingEffectReviewDTO.CaptureCommand command) {
        return R.ok(service.capture(command));
    }
}
