package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialCompensationCommandDTO;
import com.kitex.mom.mes.dto.MesMaterialReconcileActionDTO;
import com.kitex.mom.mes.dto.MesMaterialReconcileScanResultDTO;
import com.kitex.mom.mes.entity.MesMaterialReconcileCompensationEntity;
import com.kitex.mom.mes.entity.MesMaterialReconcileIssueEntity;
import com.kitex.mom.mes.service.MesMaterialReconcileCompensationService;
import com.kitex.mom.mes.service.MesMaterialReconcileIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(description = "mesMaterialReconcileIssue", name = "物料预留执行对账工作台")
@RestController
@RequestMapping("/mesMaterialReconcileIssue")
@RequiredArgsConstructor
public class MesMaterialReconcileIssueController {
    private final MesMaterialReconcileIssueService service;
    private final MesMaterialReconcileCompensationService compensationService;

    @GetMapping("/page")
    @Operation(summary = "分页查询对账异常")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:show')")
    public R<IPage<MesMaterialReconcileIssueEntity>> page(Page<MesMaterialReconcileIssueEntity> page,
                                                          MesMaterialReconcileIssueEntity query) {
        return R.ok(service.queryPage(page, query));
    }

    @PostMapping("/scan")
    @Operation(summary = "立即执行PLAN-WMS-MES物料对账")
    @SysLog("执行物料预留跨模块对账")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:scan')")
    public R<MesMaterialReconcileScanResultDTO> scan() {
        return R.ok(service.scan());
    }

    @PostMapping("/action")
    @Operation(summary = "认领、复核、忽略或重开对账异常")
    @SysLog("处置物料预留对账异常")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:handle')")
    public R<MesMaterialReconcileIssueEntity> action(@RequestBody MesMaterialReconcileActionDTO command) {
        return R.ok(service.action(command));
    }

    @GetMapping("/compensation/list")
    @Operation(summary = "查询异常的历次补偿记录")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:show')")
    public R<List<MesMaterialReconcileCompensationEntity>> compensationList(@RequestParam Long issueId) {
        return R.ok(compensationService.listByIssue(issueId));
    }

    @PostMapping("/compensation/prepare")
    @Operation(summary = "生成受控补偿方案")
    @SysLog("生成物料对账补偿方案")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:handle')")
    public R<MesMaterialReconcileCompensationEntity> prepareCompensation(
            @RequestBody MesMaterialCompensationCommandDTO command) {
        return R.ok(compensationService.prepare(command));
    }

    @PostMapping("/compensation/approve")
    @Operation(summary = "审批受控补偿方案")
    @SysLog("审批物料对账补偿方案")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:compensateApprove')")
    public R<MesMaterialReconcileCompensationEntity> approveCompensation(
            @RequestBody MesMaterialCompensationCommandDTO command) {
        return R.ok(compensationService.approve(command));
    }

    @PostMapping("/compensation/execute")
    @Operation(summary = "执行已审批的自动补偿方案")
    @SysLog("执行物料对账补偿方案")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:compensateExecute')")
    public R<MesMaterialReconcileCompensationEntity> executeCompensation(
            @RequestBody MesMaterialCompensationCommandDTO command) {
        return R.ok(compensationService.execute(command));
    }

    @PostMapping("/compensation/cancel")
    @Operation(summary = "取消尚未执行的补偿方案")
    @SysLog("取消物料对账补偿方案")
    @PreAuthorize("@pms.hasPermission('mesMaterialReconcileIssue:compensateApprove')")
    public R<MesMaterialReconcileCompensationEntity> cancelCompensation(
            @RequestBody MesMaterialCompensationCommandDTO command) {
        return R.ok(compensationService.cancel(command));
    }
}
