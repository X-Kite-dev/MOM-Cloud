package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.oms.dto.OmsOrderSupplyReplanDTO;
import com.kitex.mom.oms.entity.OmsOrderSupplyReplanEntity;
import com.kitex.mom.plan.service.OmsOrderSupplyReplanService;
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
@RequestMapping("/omsOrderSupplyReplan")
@Tag(description = "omsOrderSupplyReplan", name = "OMS订单变更供应级联重排")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class OmsOrderSupplyReplanController {
    private final OmsOrderSupplyReplanService service;

    @GetMapping("/page")
    @Operation(summary = "分页查询订单供应级联重排")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_view')")
    public R<Page<OmsOrderSupplyReplanEntity>> page(@ParameterObject SearchPage page,
                                                    @ParameterObject OmsOrderSupplyReplanDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询订单供应级联重排详情")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_view')")
    public R<OmsOrderSupplyReplanDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/review/{reviewId}/latest")
    @Operation(summary = "查询订单评审最近一份供应级联重排")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_view')")
    public R<OmsOrderSupplyReplanDTO.Detail> latest(@PathVariable Long reviewId) {
        return R.ok(service.latestByReview(reviewId));
    }

    @PostMapping("/submit")
    @SysLog("提交订单变更供应级联重排")
    @Operation(summary = "冻结订单、PLAN、MRP和下游供应证据并提交级联重排")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_submit')")
    public R<OmsOrderSupplyReplanDTO.Detail> submit(@RequestBody OmsOrderSupplyReplanDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/review")
    @SysLog("复核并执行订单变更供应级联重排")
    @Operation(summary = "异岗复核后撤回安全供应并完成OMS评审和MRP重算")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_review')")
    public R<OmsOrderSupplyReplanDTO.Detail> review(@RequestBody OmsOrderSupplyReplanDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/retry")
    @SysLog("续跑订单变更供应级联重排")
    @Operation(summary = "按永久回执续跑失败或部分完成的级联重排")
    @PreAuthorize("@pms.hasPermission('oms_orderSupplyReplan_review')")
    public R<OmsOrderSupplyReplanDTO.Detail> retry(@RequestBody OmsOrderSupplyReplanDTO.RetryCommand command) {
        return R.ok(service.retry(command));
    }
}
