package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlanDemandChangeDTO;
import com.kitex.mom.plan.entity.PlnProductDemandChangeEntity;
import com.kitex.mom.plan.entity.PlnProductDemandChangeOperationEntity;
import com.kitex.mom.plan.service.PlnProductDemandChangeService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/planDemandChange")
@Tag(name = "已接收产品需求受控变更")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnProductDemandChangeController {
    private final PlnProductDemandChangeService service;

    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_edit')")
    public R<PlanDemandChangeDTO.Preview> preview(@RequestBody PlanDemandChangeDTO.SubmitCommand command) {
        return R.ok(service.preview(command));
    }

    @PostMapping("/submit")
    @SysLog("提交已接收产品需求受控变更")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_edit')")
    public R<PlnProductDemandChangeEntity> submit(@RequestBody PlanDemandChangeDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/review")
    @SysLog("复核已接收产品需求受控变更")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_receive')")
    public R<PlnProductDemandChangeEntity> review(@RequestBody PlanDemandChangeDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/cancel")
    @SysLog("取消已接收产品需求受控变更")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_edit')")
    public R<PlnProductDemandChangeEntity> cancel(@RequestBody PlanDemandChangeDTO.CancelCommand command) {
        return R.ok(service.cancel(command));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_view')")
    public R<Page<PlnProductDemandChangeEntity>> page(@ParameterObject SearchPage page,
                                                      @RequestParam(required = false) Long demandId,
                                                      @RequestParam(required = false) String status) {
        return R.ok(service.page(page, demandId, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_view')")
    public R<PlnProductDemandChangeEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('plan_planMesPlan_view')")
    public R<List<PlnProductDemandChangeOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }
}
