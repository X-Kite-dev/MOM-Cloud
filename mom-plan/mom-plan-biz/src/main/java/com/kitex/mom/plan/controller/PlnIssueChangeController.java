package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnIssueChangeDTO;
import com.kitex.mom.plan.entity.PlnIssueChangeEntity;
import com.kitex.mom.plan.entity.PlnIssueChangeOperationEntity;
import com.kitex.mom.plan.service.PlnIssueChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 已排产/已下达生产计划受控变更工作台。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnIssue/change")
@Tag(name = "生产计划受控变更", description = "无现场事实计划的异岗审批、跨模块撤回、MRP重算与失败续跑")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnIssueChangeController {

    private final PlnIssueChangeService service;

    @PostMapping("/submit")
    @SysLog("提交生产计划受控变更")
    @Operation(summary = "提交生产计划受控变更")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_edit')")
    public R<PlnIssueChangeEntity> submit(@RequestBody PlnIssueChangeDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/review")
    @SysLog("复核生产计划受控变更")
    @Operation(summary = "异岗复核并执行生产计划受控变更")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_edit')")
    public R<PlnIssueChangeEntity> review(@RequestBody PlnIssueChangeDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/retry")
    @SysLog("续跑生产计划受控变更")
    @Operation(summary = "续跑失败或部分完成的生产计划变更")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_edit')")
    public R<PlnIssueChangeEntity> retry(@RequestBody PlnIssueChangeDTO.RetryCommand command) {
        return R.ok(service.retry(command));
    }

    @PostMapping("/withdraw")
    @SysLog("撤回待复核生产计划变更")
    @Operation(summary = "申请人撤回待复核生产计划变更")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_edit')")
    public R<PlnIssueChangeEntity> withdraw(@RequestBody PlnIssueChangeDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(command));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询生产计划变更")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_view')")
    public R<Page<PlnIssueChangeEntity>> page(@ParameterObject SearchPage page,
                                              @RequestParam(required = false) Long issueId,
                                              @RequestParam(required = false) String status) {
        return R.ok(service.page(page, issueId, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询生产计划变更详情")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_view')")
    public R<PlnIssueChangeEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/operations")
    @Operation(summary = "查询生产计划变更不可变履历")
    @PreAuthorize("@pms.hasPermission('pln_plnIssue_view')")
    public R<List<PlnIssueChangeOperationEntity>> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }
}
