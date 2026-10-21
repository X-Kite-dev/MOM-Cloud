package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnMpsCommand;
import com.kitex.mom.plan.dto.PlnMpsCapacityBalanceDTO;
import com.kitex.mom.plan.dto.PlnMpsCapacityDTO;
import com.kitex.mom.plan.entity.PlnMpsPolicyEntity;
import com.kitex.mom.plan.entity.PlnMpsVersionEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.service.impl.PlnMpsRevisionExecutor;
import com.kitex.mom.plan.service.impl.PlnMpsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnMps")
public class PlnMpsController {
    private final PlnMpsService service;
    private final PlnMpsRevisionExecutor revisionExecutor;

    @GetMapping("/policy")
    @Operation(summary = "工厂当前MPS策略")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsPolicyEntity> policy(@RequestParam String factoryCode) {
        return R.ok(service.policy(factoryCode));
    }

    @PostMapping("/policy")
    @SysLog("发布MPS工厂策略版本")
    @PreAuthorize("@pms.hasPermission('plan_mps_policy_manage')")
    public R<PlnMpsPolicyEntity> configure(@RequestBody PlnMpsCommand.Policy command) {
        return R.ok(service.configure(command));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<Page<PlnMpsVersionEntity>> page(SearchPage page,
                                             @RequestParam String factoryCode,
                                             @RequestParam(required = false) String status) {
        return R.ok(service.page(page, factoryCode, status));
    }

    @GetMapping("/demands")
    @Operation(summary = "查询MPS可编排需求")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<Page<PlnProductDemandEntity>> demands(SearchPage page,
                                                    @RequestParam String factoryCode,
                                                    @RequestParam(required = false) String productCode,
                                                    @RequestParam(required = false) String warehouseCode) {
        return R.ok(service.eligibleDemands(page, factoryCode, productCode, warehouseCode));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsCommand.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/submit")
    @SysLog("提交MPS滚动候选版本")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    public R<PlnMpsVersionEntity> submit(@RequestBody PlnMpsCommand.Submit command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/capacity-preview")
    @Operation(summary = "MPS候选RCCP产能预览")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsCapacityDTO.Preview> capacityPreview(@RequestBody PlnMpsCommand.Submit command) {
        return R.ok(service.capacityPreview(command));
    }

    @PostMapping("/capacity-balance")
    @Operation(summary = "生成MPS候选RCCP负荷平衡建议")
    @PreAuthorize("@pms.hasPermission('plan_mps_view')")
    public R<PlnMpsCapacityBalanceDTO.Result> capacityBalance(@RequestBody PlnMpsCommand.Submit command) {
        return R.ok(service.capacityBalance(command));
    }

    @PostMapping("/review")
    @SysLog("复核MPS滚动候选版本")
    @PreAuthorize("@pms.hasPermission('plan_mps_review')")
    public R<PlnMpsVersionEntity> review(@RequestBody PlnMpsCommand.Review command) {
        PlnMpsVersionEntity reviewed = service.review(command);
        return R.ok("APPROVED".equals(reviewed.getStatus())
                ? revisionExecutor.execute(reviewed.getId()) : reviewed);
    }

    @PostMapping("/retry")
    @SysLog("续跑MPS滚动版本跨模块执行")
    @PreAuthorize("@pms.hasPermission('plan_mps_review')")
    public R<PlnMpsVersionEntity> retry(@RequestBody PlnMpsCommand.Retry command) {
        PlnMpsVersionEntity approved = service.retry(command);
        return R.ok("APPROVED".equals(approved.getStatus())
                ? revisionExecutor.execute(approved.getId()) : approved);
    }

    @PostMapping("/cancel")
    @SysLog("取消MPS滚动候选版本")
    @PreAuthorize("@pms.hasPermission('plan_mps_submit')")
    public R<PlnMpsVersionEntity> cancel(@RequestBody PlnMpsCommand.Cancel command) {
        return R.ok(service.cancel(command));
    }
}
